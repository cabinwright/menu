#!/bin/sh
# Checks the launcher hook without the manufacturer's files (as CI does): compiles it against the
# stubs and the Cabinwright API, and checks patch.py parses. Building the real launcher
# needs the stock APK from the car: see build.sh.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
JAR=$(sh "$HERE/../sdk/api/tools/fetch-tools.sh")/android-19.jar   # the car's API level
JDK=${JAVA_HOME:-$(/usr/libexec/java_home -v 11)}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/stubs" "$OUT/hook"
find "$HERE/hook/stubs" -name '*.java' >"$OUT/stubs.txt"
find "$HERE/hook/src" "$HERE/../sdk/api/contract/src" -name '*.java' >"$OUT/hook.txt"
"$JDK/bin/javac" -source 8 -target 8 -bootclasspath "$JAR" -Xlint:-options -d "$OUT/stubs" "@$OUT/stubs.txt"
"$JDK/bin/javac" -source 8 -target 8 -bootclasspath "$JAR" -Xlint:-options -cp "$OUT/stubs" \
    -d "$OUT/hook" "@$OUT/hook.txt"
python3 -I -c 'import ast, sys; ast.parse(open(sys.argv[1]).read())' "$HERE/hook/patch.py"
echo "hook ok"
