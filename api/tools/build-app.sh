#!/bin/sh
# Builds a Cabinwright app (API 19, Java and/or Kotlin, no Gradle) with the Android SDK tools,
# shrunk and optimized by R8. Kotlin and R8 come from fetch-tools.sh (pinned, checksummed).
# An app folder has AndroidManifest.xml, src/, res/ and glyph.svgpath; the glyph becomes
# its stock-style All Menus icons (@drawable/menu_icon and menu_icon_dis, day and night).
# The Cabinwright API is compiled in: api/contract always, api/ui with --ui, and the
# CarBroker client (api/broker) with --broker.
# R8 keeps the manifest's components (aapt's rules) and api/tools/app-rules.pro; an app adds
# its own keep rules in <app dir>/proguard-rules.pro (anything reached by reflection).
# CABINWRIGHT_MAP=<file> keeps R8's name map, for reading stack traces (retrace).
# usage: build-app.sh [--ui] [--broker] <app dir> <output apk>
# Signs with the Android debug key unless CABINWRIGHT_KEYSTORE is set (release builds):
#   CABINWRIGHT_KEYSTORE=<.jks> CABINWRIGHT_KEYSTORE_PASS=<password> [CABINWRIGHT_KEY_ALIAS=<alias>]
# (the alias can be left out when the keystore holds one key)
# NEMESIS reinstalls an app on update, so a change of key between builds doesn't matter.
set -e
TOOLS=$(cd "$(dirname "$0")" && pwd)
API=$TOOLS/..
UI=
BROKER=
while :; do
    case $1 in
        --ui) UI=$API/ui/src ;;
        --broker) BROKER=$API/broker/src ;;
        *) break ;;
    esac
    shift
done
APP=$(cd "$1" && pwd)
OUT=$2
case "$OUT" in /*) ;; *) OUT=$(pwd)/$OUT ;; esac
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT=$SDK/build-tools/${BUILD_TOOLS:-29.0.2}
JDK=${JAVA_HOME:-$(/usr/libexec/java_home -v 11)}
FT=$(sh "$TOOLS/fetch-tools.sh")
JAR=$FT/android-19.jar   # the head unit's API level: anything newer won't compile
BUILD=$(mktemp -d)
trap 'rm -rf "$BUILD"' EXIT
mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/res"

[ -d "$APP/res" ] && cp -R "$APP/res/." "$BUILD/res/"
python3 -I "$TOOLS/menu_icon.py" "$APP/glyph.svgpath" "$BUILD/res" menu_icon

# resources first: aapt also writes keep rules for the manifest's components
"$BT/aapt" package -f -M "$APP/AndroidManifest.xml" -S "$BUILD/res" -I "$JAR" -F "$BUILD/unsigned.apk" \
    -G "$BUILD/aapt-rules.pro"

# source lists (one per line) for the compilers' @argfiles
find "$APP/src" "$API/contract/src" ${UI:+"$UI"} ${BROKER:+"$BROKER"} -name '*.java' >"$BUILD/java.txt"
find "$APP/src" "$API/contract/src" ${UI:+"$UI"} ${BROKER:+"$BROKER"} -name '*.kt' >"$BUILD/kt.txt"
STDLIB=$FT/kotlinc/lib/kotlin-stdlib.jar
if [ -s "$BUILD/kt.txt" ]; then
    # Kotlin sees the Java sources (both ways round); no JDK, only Android's classes
    JAVA_HOME=$JDK "$FT/kotlinc/bin/kotlinc" -jvm-target 1.8 -no-jdk -no-reflect -Werror \
        -Xno-param-assertions -Xno-call-assertions -Xno-receiver-assertions \
        -classpath "$JAR" -d "$BUILD/classes" "@$BUILD/kt.txt" "@$BUILD/java.txt"
fi
"$JDK/bin/javac" -source 8 -target 8 -bootclasspath "$JAR" -Xlint:-options \
    -classpath "$BUILD/classes:$STDLIB" -d "$BUILD/classes" "@$BUILD/java.txt"
(cd "$BUILD/classes" && "$JDK/bin/jar" cfM ../classes.jar .)

# R8's arguments: keep rules, then the program (the app's classes, and Kotlin's stdlib if used)
set -- --pg-conf "$BUILD/aapt-rules.pro" --pg-conf "$TOOLS/app-rules.pro"
if [ -f "$APP/proguard-rules.pro" ]; then set -- "$@" --pg-conf "$APP/proguard-rules.pro"; fi
set -- "$@" "$BUILD/classes.jar"
if [ -s "$BUILD/kt.txt" ]; then set -- "$@" "$STDLIB"; fi
"$JDK/bin/java" -cp "$FT/r8.jar" com.android.tools.r8.R8 --release --min-api 19 --lib "$JAR" \
    --pg-map-output "${CABINWRIGHT_MAP:-$BUILD/r8.map}" --output "$BUILD/dex" "$@"

(cd "$BUILD/dex" && "$BT/aapt" add "$BUILD/unsigned.apk" classes.dex >/dev/null)
"$BT/zipalign" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
mkdir -p "$(dirname "$OUT")"
if [ -n "$CABINWRIGHT_KEYSTORE" ]; then
    KS=$CABINWRIGHT_KEYSTORE ALIAS=$CABINWRIGHT_KEY_ALIAS
    export CABINWRIGHT_KEYSTORE_PASS
    PASS=env:CABINWRIGHT_KEYSTORE_PASS
else
    KS=$HOME/.android/debug.keystore ALIAS=androiddebugkey PASS=pass:android
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "$PASS" --key-pass "$PASS" ${ALIAS:+--ks-key-alias "$ALIAS"} \
    --out "$OUT" "$BUILD/aligned.apk"
echo "$OUT"
