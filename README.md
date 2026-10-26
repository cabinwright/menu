# kia-menu

Custom apps in Kia's **All Menus**, and an editor to hide and reorder every entry, Kia's
own included. Written for the 2023 Kia EV6 head unit (Gen5W, `wp_daudioplus_cvev_us`,
firmware `CVEV.USA.0000.V015.012.260507`) with [NEMESIS](https://github.com/xwtk/hmg_nemesis)
installed.

| Part | What it is |
|---|---|
| `api/contract` | `MenuContract`: the one class an app needs to show up in All Menus. No dependencies. |
| `api/ui` | Optional: `KiaPage` (a screen in Kia's look: colours, Mobis font, day/night, keeps clear of the split-screen panel), `SplitScreen`, and `KiaAudio` (makes the app the car's audio source, so its sound reaches the speakers with normal knob volume). |
| `api/tools` | `build-app.sh` (builds a Kotlin or Java app without Gradle, shrunk by R8), `lint.sh` (ktlint, detekt, ShellCheck and an API 19 compile check), `fetch-tools.sh` (the pinned tools both use) and `kia_icon.py` (Kia-style gradient icons from an SVG path). |
| `launcher/` | The launcher hook: Kia's stock `Launcher_WP3.apk` plus a `classes.dex` that adds custom apps, applies the layout, and opens Edit Menu from ≡ → Edit Home Icons. |
| `editor/` | **Edit Menu**: a launcher-style grid. Drag to reorder, ✕ to hide, "Add apps" to bring them back. NEMESIS package `kia.menu`. |

`launcher/SYMBOLS.md` maps the obfuscated launcher code this relies on.

## Install

1. **Edit Menu**: download `kia.menu-<version>.zip` from
   [Releases](../../releases) and install it with NEMESIS (USB stick or
   `nemesis install --update <zip>` over ADB).
2. **Launcher hook** (once per firmware): it replaces a `/system` app, so it is built from
   *your own car's* stock launcher and never distributed. Pull the stock files, build, install:

   ```sh
   adb pull /system/app/Launcher_WP3.apk stock/
   adb pull /system/app/Launcher_WP3.odex stock/
   adb pull /system/framework stock/framework
   launcher/build.sh stock            # refuses anything but the exact stock files
   adb push launcher/build/Launcher_WP3.apk launcher/install-launcher.sh /data/local/tmp/
   adb shell "nemesis root -c 'sh /data/local/tmp/install-launcher.sh /data/local/tmp/Launcher_WP3.apk'"
   ```

   `install-launcher.sh` checks the stock checksums and backs the stock files up to
   `/data/nemesis/backup/ev6.launcher` before touching `/system`. Reboot to load it.
   To undo, run `launcher/restore-launcher.sh` from a USB stick (NEMESIS.data `type=execute`).

Without Edit Menu installed the hook just adds custom apps after Kia's entries.

## Adding your own app

```xml
<activity android:name=".Main">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="dev.kiamenu.category.ALL_MENUS" />
    </intent-filter>
    <meta-data android:name="dev.kiamenu.icon" android:resource="@drawable/menu_icon" />
    <meta-data android:name="dev.kiamenu.icon_disabled" android:resource="@drawable/menu_icon_dis" />
</activity>
```

Put the app in a folder with `AndroidManifest.xml`, `src/`, `res/` and `glyph.svgpath` (an
SVG path in a 24x24 box, which becomes the icons) and build it:

```sh
api/tools/build-app.sh --ui <app dir> out.apk   # --ui also compiles in api/ui
```

`src/` can be Kotlin, Java or both. The app is compiled against Android 4.4's `android.jar`
(API 19, the head unit's), so a newer call is a build error, then shrunk and optimized by R8.
Anything reached only by reflection needs a keep rule in `<app dir>/proguard-rules.pro`;
`KIAMENU_MAP=<file>` keeps R8's name map for reading stack traces. In Kotlin, avoid function
references (`::foo`): they pull in Kotlin's reflection classes.

Send `dev.kiamenu.action.RELOAD` to make All Menus pick it up. See `MenuContract` for the rest.
[ev6-fixes](https://github.com/hazptr/ev6-fixes) is a complete example.

## Building

Needs a JDK (11), Python 3, curl, and the Android SDK's `build-tools;29.0.2` (`ANDROID_HOME`,
or `~/Library/Android/sdk`). Everything else (Kotlin, R8, Android 4.4's `android.jar`, the lint
tools) is downloaded once by `api/tools/fetch-tools.sh`, pinned and checked by sha256, into
`~/.cache/kia-menu-tools` (or `KIAMENU_TOOLS`).

```sh
./build.sh                 # dist/kia.menu.zip, and dist/kia-menu-editor.map (R8's name map)
launcher/check-hook.sh     # compiles the hook without Kia's files
api/tools/lint.sh          # lint everything; --fix reformats the Kotlin
```

Kotlin everywhere except `MenuContract` (copied into any app, no dependencies, and compiled
into the launcher hook) and the launcher hook itself, which stay Java.

### Lint

`api/tools/lint.sh [--fix] [repo dir]` runs, on the repo's tracked and new files (so not
`dist/`, build output, or a nested checkout):

- **ktlint** on `.kt`/`.kts` (`--fix` rewrites them), settings in `api/tools/lint/editorconfig`;
- **detekt** on the same files with `api/tools/lint/detekt.yml` on top of its defaults. A
  deliberately ignored exception is named `ignored`; `probeHidden` in `api/ui` is the one place
  that falls back on any failure, for reflection into Kia's hidden APIs;
- **ShellCheck** on `.sh`, with `api/tools/lint/shellcheckrc`. Build scripts are POSIX `sh`;
  on-car scripts (`#!/system/bin/sh`, or sourced with no `#!`) are checked as ksh, nearest to
  Android 4.4's mksh (`local` and `print` are fine; toolbox has no sed, awk, head, tail or tr,
  which ShellCheck can't know);
- **javac** `-Xlint:all -Werror` against Android 4.4's `android.jar` for any remaining Java,
  with the kia-menu API and the repo's Kotlin on the classpath.

It fails if any check does. Other kia-menu-based repos use it from their kia-menu checkout:
`kia-menu/api/tools/lint.sh <repo>`.

Builds are signed with the debug key unless `KIAMENU_KEYSTORE` / `KIAMENU_KEYSTORE_PASS` are
set. CI lints and builds every push; pushing a tag `v<version>` (matching
`package/NEMESIS.data`) publishes a release with the R8 map alongside the zip.

## Ground rules

No manufacturer files live in this repo: only our code, patch recipes, and checksums of the
stock files they apply to. Every `/system` change is checked against the stock checksum, backed
up first, and has an exact restore.
