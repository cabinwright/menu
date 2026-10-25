#!/bin/sh
# Builds the patched Kia launcher: the stock Launcher_WP3.apk, untouched (Kia's signed
# manifest and resources), plus a classes.dex with the custom-apps hook. Only the
# manifest is signature-checked for /system apps on this firmware, as with Leafia's
# patched apps. Kia's files are inputs only and never stored here.
#
# usage: launcher/build.sh <stock dir> [out apk]
#   <stock dir> holds Launcher_WP3.apk, Launcher_WP3.odex and framework/ from the car
#   (adb pull /system/app/Launcher_WP3.apk, .odex, and /system/framework)
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
STOCK=$(cd "$1" && pwd)
OUT=${2:-$HERE/build/Launcher_WP3.apk}
case "$OUT" in /*) ;; *) OUT=$(pwd)/$OUT ;; esac
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT=$SDK/build-tools/${BUILD_TOOLS:-29.0.2}
JAR=$SDK/platforms/android-23/android.jar
JDK=${JAVA_HOME:-$(/usr/libexec/java_home -v 11)}
JAVA=$JDK/bin/java

md5of() { if command -v md5 >/dev/null; then md5 -q "$1"; else md5sum "$1" | cut -d' ' -f1; fi; }

# only the exact stock launcher this hook was written against (firmware 260507)
SUM_APK=768d9fda8efcd2d38dbe34e58ca37d1a
SUM_ODEX=12c9a96cae35fce9c11fab3918cd912a
[ "$(md5of "$STOCK/Launcher_WP3.apk")" = $SUM_APK ] || { echo "not the stock Launcher_WP3.apk" >&2; exit 1; }
[ "$(md5of "$STOCK/Launcher_WP3.odex")" = $SUM_ODEX ] || { echo "not the stock Launcher_WP3.odex" >&2; exit 1; }

# smali 2.5.2, checked
TOOLS=$HERE/tools
mkdir -p "$TOOLS"
fetch() {
    [ -f "$TOOLS/$1" ] || curl -sSL -o "$TOOLS/$1" "https://bitbucket.org/JesusFreke/smali/downloads/$1"
    echo "$2  $TOOLS/$1" | shasum -a 256 -c -s 2>/dev/null || { echo "$1 checksum mismatch" >&2; rm -f "$TOOLS/$1"; exit 1; }
}
fetch baksmali-2.5.2.jar d3116248cce4f82ec5a31eb7f95ee75daff42ddf6eed0ab573973dc53fbad2e5
fetch smali-2.5.2.jar 9544299578b16f771d8aa8eaefe0d3718ca03478c16f3c356f2fcf1366bfb116

# the smali tree has classes that differ only in case (g, G): work on a case-sensitive
# volume (a disk image on macOS; Linux file systems already are)
WORK=$(mktemp -d)
if [ "$(uname)" = Darwin ]; then
    trap 'hdiutil detach -quiet "$WORK/cs" 2>/dev/null; rm -rf "$WORK"' EXIT
    hdiutil create -quiet -size 400m -fs "Case-sensitive APFS" -volname kiamenu "$WORK/cs.dmg"
    hdiutil attach -quiet -nobrowse -mountpoint "$WORK/cs" "$WORK/cs.dmg"
else
    trap 'rm -rf "$WORK"' EXIT
    mkdir "$WORK/cs"
fi
CS=$WORK/cs

BCP=core.jar:conscrypt.jar:okhttp.jar:core-junit.jar:bouncycastle.jar:ext.jar:framework.jar:framework2.jar:telephony-common.jar:voip-common.jar:mms-common.jar:android.policy.jar:services.jar:apache-xml.jar:webviewchromium.jar:automotive.jar
# -a 18: -a 19 mis-maps KitKat instance fields (Leafia's notes)
"$JAVA" -jar "$TOOLS/baksmali-2.5.2.jar" x -a 18 -d "$STOCK/framework" -b "$BCP" \
    "$STOCK/Launcher_WP3.odex" -o "$CS/smali"

# the hook: compiled against stubs of the two launcher classes it uses
mkdir -p "$CS/stubs" "$CS/hook" "$CS/hookdex"
"$JDK/bin/javac" -source 8 -target 8 -bootclasspath "$JAR" -Xlint:-options -d "$CS/stubs" \
    $(find "$HERE/hook/stubs" -name '*.java')
"$JDK/bin/javac" -source 8 -target 8 -bootclasspath "$JAR" -Xlint:-options -cp "$CS/stubs" \
    -d "$CS/hook" $(find "$HERE/hook/src" "$HERE/../api/contract/src" -name '*.java')
"$BT/d8" --release --min-api 19 --lib "$JAR" --classpath "$CS/stubs" --output "$CS/hookdex" \
    $(find "$CS/hook" -name '*.class')
"$JAVA" -jar "$TOOLS/baksmali-2.5.2.jar" d "$CS/hookdex/classes.dex" -o "$CS/hooksmali"
cp -R "$CS/hooksmali/dev" "$CS/smali/"

python3 -I "$HERE/hook/patch.py" "$CS/smali"
# -j 1: more threads write a different (equivalent) dex each run
"$JAVA" -jar "$TOOLS/smali-2.5.2.jar" a -j 1 --api 18 "$CS/smali" -o "$CS/classes.dex"

mkdir -p "$(dirname "$OUT")"
python3 -I - "$STOCK/Launcher_WP3.apk" "$CS/classes.dex" "$OUT" <<'EOF'
import shutil, sys, zipfile
src, dex, out = sys.argv[1:]
shutil.copyfile(src, out)
with zipfile.ZipFile(out, "a", compression=zipfile.ZIP_DEFLATED) as z:
    assert "classes.dex" not in z.namelist()
    z.write(dex, "classes.dex")
with zipfile.ZipFile(out) as z:
    assert z.testzip() is None
EOF
echo "$OUT ($(md5of "$OUT"))"
