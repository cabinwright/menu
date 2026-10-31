#!/bin/sh
# Builds the cabinwright.menu NEMESIS package (Edit Menu). Install over ADB:
#   adb push dist/cabinwright.menu.zip /data/local/tmp/
#   adb shell "nemesis install [--update] /data/local/tmp/cabinwright.menu.zip"
# The launcher hook is built and installed separately (launcher/build.sh,
# launcher/install-launcher.sh): it replaces a /system app.
set -e
ROOT=$(cd "$(dirname "$0")" && pwd)
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
cp -R "$ROOT/package/." "$STAGE/"
mkdir -p "$ROOT/dist"
# R8's name map, for reading the editor's stack traces (retrace)
CABINWRIGHT_MAP=$ROOT/dist/cabinwright.menu.map \
    sh "$ROOT/sdk/api/tools/build-app.sh" --ui "$ROOT/editor" "$STAGE/edit-menu.apk" >/dev/null
rm -f "$ROOT/dist/cabinwright.menu.zip"
(cd "$STAGE" && zip -qrX "$ROOT/dist/cabinwright.menu.zip" .)
echo "$ROOT/dist/cabinwright.menu.zip"
