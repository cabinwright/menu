# kia-menu

Custom apps in Kia's **All Menus**, and an editor to hide and reorder every entry, Kia's
own included. Written for the 2023 Kia EV6 head unit (Gen5W, `wp_daudioplus_cvev_us`,
firmware `CVEV.USA.0000.V015.012.260507`) with [NEMESIS](https://github.com/xwtk/hmg_nemesis)
installed.

| Part | What it is |
|---|---|
| `api/contract` | `MenuContract`: the one class an app needs to show up in All Menus. No dependencies. |
| `api/ui` | Optional: `KiaPage` (a screen in Kia's look: colours, Mobis font, day/night, keeps clear of the split-screen panel) and `SplitScreen`. |
| `api/tools` | `build-app.sh` (builds an app without Gradle) and `kia_icon.py` (Kia-style gradient icons from an SVG path). |
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

Send `dev.kiamenu.action.RELOAD` to make All Menus pick it up. See `MenuContract` for the rest.
[ev6-fixes](https://github.com/hazptr/ev6-fixes) is a complete example.

## Building

Needs a JDK (11), Python 3, and the Android SDK with `build-tools;29.0.2` and
`platforms;android-23` (`ANDROID_HOME`, or `~/Library/Android/sdk`).

```sh
./build.sh                 # dist/kia.menu.zip
launcher/check-hook.sh     # compiles the hook without Kia's files
```

Builds are signed with the debug key unless `KIAMENU_KEYSTORE` / `KIAMENU_KEYSTORE_PASS` are
set. CI builds every push; pushing a tag `v<version>` (matching `package/NEMESIS.data`)
publishes a release.

## Ground rules

No manufacturer files live in this repo: only our code, patch recipes, and checksums of the
stock files they apply to. Every `/system` change is checked against the stock checksum, backed
up first, and has an exact restore.
