#!/bin/sh
# Builds a kia-menu app (API 19, plain Java, no Gradle) with the Android SDK tools.
# An app folder has AndroidManifest.xml, src/, res/ and glyph.svgpath; the glyph becomes
# its Kia-style All Menus icons (@drawable/menu_icon and menu_icon_dis, day and night).
# The kia-menu API is compiled in: api/contract always, api/ui with --ui.
# usage: build-app.sh [--ui] <app dir> <output apk>
# Signs with the Android debug key unless KIAMENU_KEYSTORE is set (release builds):
#   KIAMENU_KEYSTORE=<.jks> KIAMENU_KEYSTORE_PASS=<password> [KIAMENU_KEY_ALIAS=kia-menu]
# NEMESIS reinstalls an app on update, so a change of key between builds doesn't matter.
set -e
TOOLS=$(cd "$(dirname "$0")" && pwd)
API=$TOOLS/..
SOURCES="$API/contract/src"
if [ "$1" = --ui ]; then
    SOURCES="$SOURCES $API/ui/src"
    shift
fi
APP=$(cd "$1" && pwd)
OUT=$2
case "$OUT" in /*) ;; *) OUT=$(pwd)/$OUT ;; esac
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT=$SDK/build-tools/${BUILD_TOOLS:-29.0.2}
JAR=$SDK/platforms/android-23/android.jar   # compile against 23, use only API 19 calls
JDK=${JAVA_HOME:-$(/usr/libexec/java_home -v 11)}
BUILD=$(mktemp -d)
trap 'rm -rf "$BUILD"' EXIT
mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/res"

[ -d "$APP/res" ] && cp -R "$APP/res/." "$BUILD/res/"
python3 -I "$TOOLS/kia_icon.py" "$APP/glyph.svgpath" "$BUILD/res" menu_icon

"$JDK/bin/javac" -source 8 -target 8 -bootclasspath "$JAR" -Xlint:-options \
    -d "$BUILD/classes" $(find "$APP/src" $SOURCES -name '*.java')
"$BT/d8" --release --min-api 19 --lib "$JAR" --output "$BUILD/dex" $(find "$BUILD/classes" -name '*.class')

"$BT/aapt" package -f -M "$APP/AndroidManifest.xml" -S "$BUILD/res" -I "$JAR" -F "$BUILD/unsigned.apk"
(cd "$BUILD/dex" && "$BT/aapt" add "$BUILD/unsigned.apk" classes.dex >/dev/null)
"$BT/zipalign" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
mkdir -p "$(dirname "$OUT")"
if [ -n "$KIAMENU_KEYSTORE" ]; then
    KS=$KIAMENU_KEYSTORE ALIAS=${KIAMENU_KEY_ALIAS:-kia-menu}
    export KIAMENU_KEYSTORE_PASS
    PASS=env:KIAMENU_KEYSTORE_PASS
else
    KS=$HOME/.android/debug.keystore ALIAS=androiddebugkey PASS=pass:android
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "$PASS" --key-pass "$PASS" --ks-key-alias "$ALIAS" \
    --out "$OUT" "$BUILD/aligned.apk"
echo "$OUT"
