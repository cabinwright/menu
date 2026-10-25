#!/bin/sh
# Checks the launcher hook without Kia's files (as CI does): compiles it against the
# stubs and the kia-menu API, and checks patch.py parses. Building the real launcher
# needs the stock APK from the car: see build.sh.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
JAR=$SDK/platforms/android-23/android.jar
JDK=${JAVA_HOME:-$(/usr/libexec/java_home -v 11)}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/stubs" "$OUT/hook"
"$JDK/bin/javac" -source 8 -target 8 -bootclasspath "$JAR" -Xlint:-options -d "$OUT/stubs" \
    $(find "$HERE/hook/stubs" -name '*.java')
"$JDK/bin/javac" -source 8 -target 8 -bootclasspath "$JAR" -Xlint:-options -cp "$OUT/stubs" \
    -d "$OUT/hook" $(find "$HERE/hook/src" "$HERE/../api/contract/src" -name '*.java')
python3 -I -c 'import ast, sys; ast.parse(open(sys.argv[1]).read())' "$HERE/hook/patch.py"
echo "hook ok"
