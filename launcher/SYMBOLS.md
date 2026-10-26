# Stock launcher (Launcher_WP3): symbol map

What the obfuscated names in `Launcher_WP3` mean, so nobody has to work it out again.

**Applies to exactly:** `/system/app/Launcher_WP3.apk` md5 `768d9fda8efcd2d38dbe34e58ca37d1a`,
`Launcher_WP3.odex` md5 `12c9a96cae35fce9c11fab3918cd912a` (package `com.android.launcher3`,
versionName `4.4.2-eng.vmadmin.20260507.092712`, Kia EV6 US firmware `CVEV.USA.0000.V015.012.260507`).
Obfuscated names change between builds: check the checksums before trusting anything below.

Names are given as smali paths under `com/android/launcher3/`. "Source" is the `.source` line
baksmali prints, which survives obfuscation and is the best clue to what a class is.

## Decompiling

```sh
baksmali x -a 18 -d <framework dir> -b <BOOTCLASSPATH, bare jar names> Launcher_WP3.odex -o smali
smali a -j 1 --api 18 smali -o classes.dex
```

- **Case-sensitive filesystem required.** The tree has classes differing only in case (`g`/`G`,
  `ab`/`aB`, ...). On macOS's default APFS they overwrite each other silently. `launcher/build.sh`
  makes a case-sensitive disk image.
- `-a 18`, not 19 (Leafia: 19 mis-maps KitKat instance fields). `-j 1` for a reproducible dex.
- BOOTCLASSPATH order (from the unit): core, conscrypt, okhttp, core-junit, bouncycastle, ext,
  framework, framework2, telephony-common, voip-common, mms-common, android.policy, services,
  apache-xml, webviewchromium, automotive.
- 4 instructions come out as `#Replaced unresolvable odex instruction with a throw` /
  `throw vN` (`d/d.smali` x2, `view/b/ab.smali` x2). Each register is provably `null` there
  (`const/4 vN, 0x0` just before), so the original was a call on null: `throw null` raises the
  same NullPointerException, caught by the same handlers. Safe to reassemble as-is.
- A rebuilt, unmodified launcher (stock APK + reassembled `classes.dex`, odex removed, then
  `dexopt --preopt`) boots and runs normally (tested 2026-10-25).

## All Menus

| Name | Source / real role | Notes |
|---|---|---|
| `view/a/g` | `AllMenuEntryList.java`: the All Menus entry list | fields: `by` (List of entries), `mContext`, `mLock`. `load()Z` builds the list (picks a region list, calls its `hf()`, stores in `by`). `b(String pkg, String cls)Intent` makes the launch intent. `getCount()`, `l(int)` = get entry. **The hook goes in `load()` right after `hf()`.** |
| `view/a/Q` | entry base class (inner class of `g`: synthetic field `s` = owner) | ctors `<init>(g, Context)` and `<init>(g, Context, String pkg, String cls, int icon, int disabledIcon, int label)` (the 7-arg one calls `b(...)`). `b(String,String,int,int,int)` sets package/class/icon/disabled icon/label. Getters: **`bl()` = icon id, `bm()` = disabled icon id, `bn()` = label id**, `getPackageName()`, `getClassName()`, `isEnabled()` (`mIsEnabled`), `isVisible()` (`sB`), `gq()` = launch policy (`sC`, null by default), `a(ap)` sets it, `gp()` = `sD`. Some subclasses override `bl/bm/bn` for dynamic icons (`S`, `k`, `ag`). |
| `view/a/ap` | launch policy for an entry | `m(Context)` = may launch (default true), `b(Context)` = launch via `g.b(pkg, cls)` + `utils/a.b(Intent)`. Entries without a policy are launched by `view/bp`'s fallback (works for any activity). |
| `view/a/au` | base of the region lists | abstract `hf()List` builds the entries; `getContext()`. |
| `view/a/aB` | **default region list: used on US/Canada** (~41 entries, gated by `utils/n` feature checks and `VariantCoding.getVariantCode(0x17)`) | |
| `view/a/aH` | region list when `utils/a.Q()` (= `utils/a.n(0x14)`) | |
| `view/a/ab` | region list when `utils/n.eb()` (`Build.MODEL` is `opio`/`wp_opio`) | |
| `view/a/aL` | region list when `utils/n.ea()` (`Build.MODEL` is `wp_daudiopio`/`daudiopio`/`wp_daudiopluspio`) | |
| `view/a/T` | entry: EV (HEV2) `com.hkmc.daudio.app.hev2/.ui.IgnCheckActivity` | simplest example of an entry subclass |
| `view/a/F` | entry: EV `com.hkmc.daudio.app.ev3/.IgnCheckActivity` | |
| `view/a/v` | entry: **Setup** `com.android.settings/com.android.settings.Settings` | label `0x7f0a004a`, icon `0x7f02012a` (`hom_settings_66`) |
| `view/a/R` | entry: app market `com.mobis.market/.MarketMainActivity` ("Apps/Info") | |
| `view/a/ao` | entry: launcher's own `com.android.launcher3.view.AppsMenu` ("Apps/Info") | not on the US list |
| `view/aY` | `AllMenuView.java`: the All Menus screen | **`t(Z)` = full rebuild** (starts `aV`; called only from `Launcher.aZ(I)` and `Launcher.cz()`). **`a(aY, int, boolean)` (static) = partial refresh** of existing items (logs `fetching data with: ...`). Static accessors: `a(aY)` = loader lock, `b(aY)` = the drawn `ArrayList<Item>`, `c(aY)` = saved list, `a(aY, ArrayList)` converts it, `b(aY, ArrayList)` hands the final list to the view, `d(aY)` = the `g` entry list, `q(aY)` = Context, `e/f/g(aY)` = insertion positions for new items, `h/i/j(aY)` = view updates. Draws entries via `Item`s with resource ids (`setText(I)`, `Resources.getString/getDrawable`, `setBackgroundResource`, `setImageResource`). Registers receiver `br` for `allmenu.UPDATE`, `allmenu.GRIDVIEW_UPDATE`, `MODE_CHANGED`, ... (IntentFilter built near `.line 798`). |
| `view/br` | `AllMenuView.java`: All Menus broadcast receiver | `onReceive`: `allmenu.UPDATE` and `allmenu.GRIDVIEW_UPDATE` → `aY.a(aY, int, boolean)` (partial refresh only, **not** a rebuild); also `MEDIA_MODE_AVAILABLE`, ... |
| `view/aV` | `AllMenuView.java`: loader `AsyncTask` (`tB` = the `aY`) | `doInBackground`, under `aY`'s lock: calls `g.load()`, then for each entry with `isVisible()` (this is where entries like Warmer or Rear Climate drop out) merges it into `aY.b` by class name (existing items keep their place; new ones go at `aY.e/f/g` positions, logged `new item:`), then walks `aY.b` backwards pruning items no longer listed (`:goto_154`), then at **`:cond_261`** hands the list over (`aY.b(aY, list)`, `aY.j(aY)`). If `aY.b` is null it takes **`:cond_279`** instead: `aY.a(aY, new ArrayList)` (sets `rB`) then `aY.i(aY)` → **`aY.addItem()`**, which builds the list from scratch. **On the EV6 the list starts out null, so `addItem()` is the path actually taken.** The hook covers both (`:cond_261` and the end of `addItem()`). |
| `view/bp` | All Menus item click | launches via the entry's policy, else by `Item` package/class (with special cases, e.g. seat settings) |
| `aY.addItem()` | `AllMenuView.java`: builds the drawn list from scratch | clears `rB` (the `ArrayList<Item>`), then for each `g` entry with `isVisible()` adds `Item(index, pkg, cls, bn(), bl(), bm())` (logs `itemId: N mPackageName:...`), ends at `:cond_ad`. **The layout hook goes right before its `return-void`.** |
| `view/Item` | one drawn All Menus item | fields (unobfuscated): `mItemId`, `mPackageName`, `mClassName`, `mTitleId`, `mEnableIconId`, `mDisableIconId`. `bQ()` = title id, `J(I)`/`K(I)` set icon ids, `setTitle(I)`. Placeholder (blank) cells are Items with class name `"Empty view"`. The Android Auto/CarPlay entry has the placeholder key `PhoneProjection_PackageName/PhoneProjection_ClassName` in `g`, resolved to the real app when drawn. |
| `view/AppsMenu` | `AppsMenu.java`: an "Apps/Info" activity | listens to PACKAGE_ADDED/CHANGED/REMOVED |
| `view/ar.1` | media source names for the home widget (`DOWNLOADAPP`, `USBMUSIC`, `DIPOMUSIC` = CarPlay, `GPMMUSIC` = Android Auto, ...) | `DOWNLOADAPP` is a music source, **not** an app slot |
| `view/bM` | `AvWidgetMainView.java`: home screen media/call widget | |

**Reload:** Kia's `com.hkmc.system.app.allmenu.UPDATE` (no permission needed) only does the
partial refresh (`aY.a(aY,int,boolean)`): icons and enabled states of the items already there.
It does **not** run `g.load()` or the loader. A full rebuild needs `aY.t(true)`; the Cabinwright
patch adds the broadcast `dev.cabinwright.action.RELOAD` for that.

**Logging:** `utils/i` logs under the tag `AdvancedLauncher` (debug and info levels show in
logcat): useful markers are `start task to make menu list`, `loading menu entries...`,
`new item:`, `end task`, `onReceive=<action>`, `fetching data with: ...`.

**Saved order:** Kia's own All Menus edit screen is `com.hkmc.system.app.homesetting.AllMenuEdit`
and saves into HomeSetting's `AllMenu` table (see below). On this car it is empty (default order).

**Options menu (≡ on the All Menus/home pages):** `Launcher.aX(I)` opens HomeSetting screens
by index, all with flags `0x14008000` and extra `root=Launcher`: `0` = `EditWidgetActivity`
("Edit Home Widgets"), `1` = `AllMenuEdit` ("Edit Home Icons", plus extra `Page` = current
workspace page - 1, from `Workspace.cl(kg())`), `2` = `TypeSettingActivity` (layout), `3` =
`WebManualActivity` (extra `TYPE=1`). One `startActivity` at `.line 918` serves all four.
`aY.gG()` is a second way into `AllMenuEdit` (same extras; `startActivity` at `.line 1394`,
`aY.tR` = the `Launcher`). The hook catches both `startActivity` calls with
`EditScreen.redirect(context, intent)`, which swaps only `AllMenuEdit` for Edit Menu. Outside
the launcher, Kia's `DisplaySettings` (Setup) also opens `AllMenuEdit`; that path is untouched.

## Utilities

| Name | Source / role |
|---|---|
| `utils/i` | log helper, tag `AdvancedLauncher`: `k(String)` debug, `j(String)` info, `w(String)` warn, `e(String)` error |
| `utils/a` | misc: `b(Intent)` starts an activity; `n(int)` variant/feature check; `Q()` = `n(0x14)`; `R()` |
| `utils/n` | feature/model checks: `ea()`, `eb()` (models above), `ds()`, `dV()`, `eg()`, `c(...)` (gate entries in `aB`) |
| `utils/v` | `SplitScreenUtils.java`: wraps `com.hkmc.split.manager.SplitScreenManager` (`setFreezeSS`, `isSSUserOn`) |
| `d/d` | reads JSON from `/mnt/vcrm_log` (`e(String,String)JSONObject`) |
| `view/b/ab` | album art loader (`a(Context,String,int)Bitmap`, uses `MediaScanner.extractAlbumArt`) |

## Resources (launcher `R`)

Icons are 99x99 px WebP in `res/drawable-hdpi/` and `res/drawable-night-hdpi/`, named
`hom_<name>_66` with a `hom_<name>_dis_66` disabled version. Useful ids:

| Id | Name | Value |
|---|---|---|
| `0x7f0a0061` | `string/apps_t` | "Apps/Info" |
| `0x7f0a0062` | `string/info_t` | "Information" |
| `0x7f0a007d` | `string/connectivity_t` | "Connectivity" |
| `0x7f0a004a` | (Setup label) | "Setup" |
| `0x7f0a01f4` | `string/baidumenu` | "Baidu Apps" (China only) |
| `0x7f0200c8`/`c9` | `drawable/hom_ev_66`/`hom_ev_dis_66` | EV icon |
| `0x7f02012a` | `drawable/hom_settings_66` | Setup gear |

Icon gradient (measured from `hom_ev/settings/phone`, see `api/tools/menu_icon.py`): day `#34E0D5` →
`#5CB3DA` → `#847FDC`, night `#32A6AE` → `#3E81B1` → `#4E52B3`, top to bottom of the glyph;
disabled = flat `#5D7378` @ ~20% (day), `#5E757C` @ ~40% (night).

## HomeSetting (`com.hkmc.system.app.homesetting`)

Provider `content://com.hkmc.system.app.homesetting.provider/` with paths `common`, `all_menu`,
`home_type`, `photo_frame`, `advanced/widget` (read as uid 1000). Database
`/data/data/com.hkmc.system.app.homesetting/databases/homesetting.db`:

- `AllMenu (_id, userName TEXT, menu_items TEXT)`: saved All Menus order per user (empty here)
- `MenuIcon (_id, userName, first, second, third, fourth)`
- `Common (_id, edit_widget_available, allmenu_revision)`, `HomeType (_id, userName, home_type)`,
  `Basic_Widget`, `Advanced_Widget`, `PhotoFrame`

## Framework: split screen (`automotive.jar`)

`com.hkmc.split.manager.SplitScreenManager.getInstance(Context)`: `isSplitScreenOn()`,
`addSplitListener(SplitListener)` / `removeSplitListener`, with
`SplitScreenManager$SplitListener { onChangeButton(Z); onChangeOnOff(Z); onChangePage(II) }`.
The panel is the window `com.mobis.splitscreen.right` at x 1276-1920 (screen 1920x720), its
open handle 80 px wide at the right edge. Used from apps by reflection
(`apps/common/.../SplitScreen.java`).

## Signature checks

For `/system/app` packages, PackageManager on this firmware verifies only the signature over
`AndroidManifest.xml`. The stock APK plus an added `classes.dex` (and probably changed
resources, untested) installs and runs as `system`. Re-signing or re-encoding the manifest
would break it.
