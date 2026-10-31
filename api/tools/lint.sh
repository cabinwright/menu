#!/bin/sh
# Lints a Cabinwright repo (this one, store, tweaks...) with the pinned tools
# from fetch-tools.sh and the shared settings in api/tools/lint/:
#   - ktlint: formatting of the .kt/.kts files (--fix rewrites them)
#   - detekt: static analysis of the same files
#   - ShellCheck: every .sh; on-car scripts (#!/system/bin/sh, or no #! as when sourced)
#     are checked as ksh, the nearest dialect to Android 4.4's mksh
#   - javac: any remaining Java, -Xlint:all -Werror against Android 4.4's android.jar
#     (with the Cabinwright API, and the repo's Kotlin, on the classpath)
# Files are the repo's tracked and new ones (git ls-files), so build output, dist/ and
# ignored or nested checkouts (CI's menu/) are left out; outside git, a find that
# skips dist/ and build/. Runs every check, then fails if any did.
# usage: lint.sh [--fix] [repo dir]      (the repo defaults to this menu checkout)
set -e
TOOLS=$(cd "$(dirname "$0")" && pwd)
MENU_ROOT=$(cd "$TOOLS/../.." && pwd)
CONF=$TOOLS/lint
FIX=
if [ "$1" = --fix ]; then
    FIX=1
    shift
fi
REPO=$(cd "${1:-$MENU_ROOT}" && pwd)
JDK=${JAVA_HOME:-$(/usr/libexec/java_home -v 11)}
JAVA=$JDK/bin/java
FT=$(sh "$TOOLS/fetch-tools.sh")
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
cd "$REPO"

# list <extension>: the repo's files with it, one per line, relative to the repo
list() {
    if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
        git ls-files --cached --others --exclude-standard -- "*.$1"
    else
        find . \( -name .git -o -name dist -o -name build \) -prune -o -type f -name "*.$1" -print
    fi | grep -Ev '(^|/)(dist|build)/' | while read -r f; do
        # tracked files deleted in the working tree are still listed by git
        if [ -f "$f" ]; then echo "${f#./}"; fi
    done
}
list kt >"$WORK/kt.txt"
list kts >>"$WORK/kt.txt"
list sh >"$WORK/sh.txt"
list java >"$WORK/java.txt"

FAILED=
# check <name> <command...>: runs one check, noting a failure
check() {
    name=$1
    shift
    echo "lint: $name"
    "$@" || FAILED="$FAILED $name"
}

run_ktlint() {
    if [ -n "$FIX" ]; then set -- --format; fi
    xargs "$JAVA" -jar "$FT/ktlint.jar" --relative --editorconfig="$CONF/editorconfig" "$@" <"$WORK/kt.txt"
}

run_detekt() {
    "$JAVA" -jar "$FT/detekt.jar" --config "$CONF/detekt.yml" --build-upon-default-config \
        --input "$(paste -sd, "$WORK/kt.txt")"
}

run_shellcheck() {
    sc=$FT/shellcheck
    [ -x "$sc" ] || sc=$(command -v shellcheck) || {
        echo "lint: no shellcheck for this platform (fetch-tools.sh pins darwin and linux x86_64)" >&2
        return 1
    }
    status=0
    while read -r f; do
        case $(sed -n 1p "$f") in
            '#!/system/bin/sh'*) set -- --shell=ksh ;; # on the car: mksh
            '#!'*) set -- ;;                         # the #! says which shell
            *) set -- --shell=ksh ;;                 # sourced by an on-car script
        esac
        "$sc" --rcfile "$CONF/shellcheckrc" "$@" "$f" || status=1
    done <"$WORK/sh.txt"
    return $status
}

run_javac() {
    jar=$FT/android-19.jar
    stdlib=$FT/kotlinc/lib/kotlin-stdlib.jar
    # Kotlin the Java may use: the repo's own, and Cabinwright's API when linting another repo
    cp "$WORK/kt.txt" "$WORK/kt-all.txt"
    : >"$WORK/java-ref.txt"
    if [ "$REPO" != "$MENU_ROOT" ]; then
        find "$MENU_ROOT/api" -name '*.kt' >>"$WORK/kt-all.txt"
        find "$MENU_ROOT/api/contract/src" -name '*.java' >"$WORK/java-ref.txt"
    fi
    mkdir -p "$WORK/kt-classes" "$WORK/java-classes"
    if grep -q '\.kt$' "$WORK/kt-all.txt"; then
        grep '\.kt$' "$WORK/kt-all.txt" >"$WORK/kt-src.txt"
        cat "$WORK/java.txt" "$WORK/java-ref.txt" >>"$WORK/kt-src.txt"
        JAVA_HOME=$JDK "$FT/kotlinc/bin/kotlinc" -jvm-target 1.8 -no-jdk -no-reflect -nowarn \
            -classpath "$jar" -d "$WORK/kt-classes" "@$WORK/kt-src.txt" || return 1
    fi
    "$JDK/bin/javac" -source 8 -target 8 -bootclasspath "$jar" -Xlint:all -Werror -proc:none -implicit:class \
        -sourcepath "$MENU_ROOT/api/contract/src" -classpath "$WORK/kt-classes:$stdlib" \
        -d "$WORK/java-classes" "@$WORK/java.txt"
}

if [ -s "$WORK/kt.txt" ]; then
    check ktlint run_ktlint
    check detekt run_detekt
fi
if [ -s "$WORK/sh.txt" ]; then check shellcheck run_shellcheck; fi
if [ -s "$WORK/java.txt" ]; then check javac run_javac; fi
if [ -n "$FAILED" ]; then
    echo "lint: failed:$FAILED" >&2
    exit 1
fi
echo "lint: clean"
