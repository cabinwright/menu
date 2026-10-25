#!/bin/sh
# Builds the kia.menu NEMESIS package (Edit Menu). Install over ADB:
#   adb push dist/kia.menu.zip /data/local/tmp/
#   adb shell "nemesis install [--update] /data/local/tmp/kia.menu.zip"
# The launcher hook is built and installed separately (launcher/build.sh,
# launcher/install-launcher.sh): it replaces a /system app.
set -e
ROOT=$(cd "$(dirname "$0")" && pwd)
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
cp -R "$ROOT/package/." "$STAGE/"
mkdir -p "$STAGE/app"
sh "$ROOT/api/tools/build-app.sh" --ui "$ROOT/editor" "$STAGE/app/kia-menu-editor.apk" >/dev/null
mkdir -p "$ROOT/dist" && rm -f "$ROOT/dist/kia.menu.zip"
(cd "$STAGE" && zip -qrX "$ROOT/dist/kia.menu.zip" .)
echo "$ROOT/dist/kia.menu.zip"
