#!/bin/sh
# Downloads the pinned tools that build-app.sh and lint.sh use (Kotlin, R8, Android 4.4's
# android.jar, ktlint, detekt, shellcheck), once, into
# ${KIAMENU_TOOLS:-~/.cache/kia-menu-tools}, and checks them against fixed sha256s.
# Prints the tools directory.
set -e
DIR=${KIAMENU_TOOLS:-$HOME/.cache/kia-menu-tools}
KOTLIN=2.0.21
KOTLIN_SHA=0352c0a45bd22f80f6b26e485cd04da8047baa5de54865281fb9f89a4a7bcf2a
R8=8.5.35
R8_SHA=4733945987ee0a840fafc34080b135259e01678412e07212b23f706334290294
# Android 4.4 (API 19), the head unit's own: compiling against it turns any newer call
# into a build error (sha1 2ff20d89... in Google's repository2-1.xml)
PLATFORM_SHA=5efc3a3a682c1d49128daddb6716c433edf16e63349f32959b6207524ac04039
KTLINT=1.3.1
KTLINT_SHA=a9f923be58fbd32670a17f0b729b1df804af882fa57402165741cb26e5440ca1
DETEKT=1.23.7
DETEKT_SHA=84beded283012cb2b38bcaef4996452fcd6069d2e9ca74b50eaa79e0ad21897e
SHELLCHECK=0.10.0
case "$(uname -s).$(uname -m)" in
    Darwin.x86_64) SC_OS=darwin.x86_64 SC_SHA=ef27684f23279d112d8ad84e0823642e43f838993bbb8c0963db9b58a90464c2 ;;
    Linux.x86_64) SC_OS=linux.x86_64 SC_SHA=6c881ab0698e4e6ea235245f22832860544f17ba386442fe7e9d629f8cbedf87 ;;
    *) SC_OS= ;;
esac
mkdir -p "$DIR"

sha() {
    if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1
}

# fetch <url> <file> <sha256>
fetch() {
    [ -f "$DIR/$2" ] && [ "$(sha "$DIR/$2")" = "$3" ] && return
    curl -sfL -o "$DIR/$2.part" "$1"
    if [ "$(sha "$DIR/$2.part")" != "$3" ]; then
        rm -f "$DIR/$2.part"
        echo "fetch-tools: $2 doesn't match its pinned checksum" >&2
        exit 1
    fi
    mv "$DIR/$2.part" "$DIR/$2"
}

fetch "https://dl.google.com/dl/android/maven2/com/android/tools/r8/$R8/r8-$R8.jar" "r8-$R8.jar" $R8_SHA
if [ ! -x "$DIR/kotlinc-$KOTLIN/bin/kotlinc" ]; then
    fetch "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN/kotlin-compiler-$KOTLIN.zip" \
        "kotlin-compiler-$KOTLIN.zip" $KOTLIN_SHA
    rm -rf "$DIR/kotlinc-$KOTLIN" "$DIR/kotlinc"
    (cd "$DIR" && unzip -q "kotlin-compiler-$KOTLIN.zip" && mv kotlinc "kotlinc-$KOTLIN" && rm "kotlin-compiler-$KOTLIN.zip")
fi
if [ ! -f "$DIR/android-19.jar" ]; then
    fetch https://dl.google.com/android/repository/android-19_r04.zip android-19.zip $PLATFORM_SHA
    (cd "$DIR" && unzip -q -o -j android-19.zip '*/android.jar' && mv android.jar android-19.jar && rm android-19.zip)
fi
fetch "https://github.com/pinterest/ktlint/releases/download/$KTLINT/ktlint" "ktlint-$KTLINT.jar" $KTLINT_SHA
fetch "https://github.com/detekt/detekt/releases/download/v$DETEKT/detekt-cli-$DETEKT-all.jar" \
    "detekt-cli-$DETEKT-all.jar" $DETEKT_SHA
if [ -n "$SC_OS" ] && [ ! -x "$DIR/shellcheck-$SHELLCHECK" ]; then
    fetch "https://github.com/koalaman/shellcheck/releases/download/v$SHELLCHECK/shellcheck-v$SHELLCHECK.$SC_OS.tar.xz" \
        shellcheck.tar.xz "$SC_SHA"
    (cd "$DIR" && tar xJf shellcheck.tar.xz && mv "shellcheck-v$SHELLCHECK/shellcheck" "shellcheck-$SHELLCHECK" \
        && rm -rf shellcheck.tar.xz "shellcheck-v$SHELLCHECK")
fi
ln -sf "r8-$R8.jar" "$DIR/r8.jar"
ln -sf "ktlint-$KTLINT.jar" "$DIR/ktlint.jar"
ln -sf "detekt-cli-$DETEKT-all.jar" "$DIR/detekt.jar"
[ -x "$DIR/shellcheck-$SHELLCHECK" ] && ln -sf "shellcheck-$SHELLCHECK" "$DIR/shellcheck"
ln -sfn "kotlinc-$KOTLIN" "$DIR/kotlinc"
echo "$DIR"
