package dev.kiamenu.api;

/**
 * kia-menu API: how an app shows up in Kia's All Menus (with the kia-menu launcher hook
 * installed). This class has no dependencies; copy or compile it into your app.
 *
 * <h3>Showing an app</h3>
 * On the activity to open, in AndroidManifest.xml:
 * <pre>
 *   &lt;intent-filter&gt;
 *       &lt;action android:name="android.intent.action.MAIN" /&gt;
 *       &lt;category android:name="dev.kiamenu.category.ALL_MENUS" /&gt;
 *   &lt;/intent-filter&gt;
 *   &lt;meta-data android:name="dev.kiamenu.icon" android:resource="@drawable/menu_icon" /&gt;
 *   &lt;meta-data android:name="dev.kiamenu.icon_disabled" android:resource="@drawable/menu_icon_dis" /&gt;
 *   &lt;meta-data android:name="dev.kiamenu.label" android:resource="@string/menu_label" /&gt;
 * </pre>
 * The meta-data is optional: without it the activity's own icon and label are used, and
 * the disabled icon is a greyed-out copy. Kia's icons are 99x99 px at hdpi with
 * drawable-night-hdpi variants; api/tools/kia_icon.py makes all four from an SVG path.
 * The menu picks the app up the next time it rebuilds (send {@link #ACTION_RELOAD}).
 *
 * <h3>The editor</h3>
 * Edit Menu ({@link #EDITOR_PACKAGE}) owns the layout: order and hidden entries, for Kia's
 * entries and custom apps alike. Without it installed, custom apps are simply added after
 * Kia's. The launcher hook talks to it through its provider's call():
 * <ul>
 *   <li>{@link #CALL_CATALOG} from the launcher: every entry, as parallel arrays
 *       {@link #EXTRA_KEYS}, {@link #EXTRA_LABELS}, {@link #EXTRA_ICONS},
 *       {@link #EXTRA_DISABLED} (launcher resource ids, or kia-menu ids for custom apps)
 *       and {@link #EXTRA_CUSTOM}; only accepted from the system uid</li>
 *   <li>{@link #CALL_LAYOUT} to the launcher: {@link #EXTRA_ORDER} and {@link #EXTRA_HIDDEN},
 *       arrays of entry keys</li>
 * </ul>
 * An entry's key is {@code "<package>/<activity class>"}; see {@link #key}.
 */
public final class MenuContract {
    /** Intent category that puts an activity in All Menus. */
    public static final String CATEGORY = "dev.kiamenu.category.ALL_MENUS";
    /** Activity meta-data: drawable for the menu icon. */
    public static final String META_ICON = "dev.kiamenu.icon";
    /** Activity meta-data: drawable for the menu icon while the entry is disabled. */
    public static final String META_ICON_DISABLED = "dev.kiamenu.icon_disabled";
    /** Activity meta-data: string for the menu label. */
    public static final String META_LABEL = "dev.kiamenu.label";

    /** Broadcast: rebuild All Menus (new apps, layout changes). Added by the launcher hook. */
    public static final String ACTION_RELOAD = "dev.kiamenu.action.RELOAD";
    /** Kia's own broadcast: refreshes the existing items' icons and states only. */
    public static final String ACTION_KIA_REFRESH = "com.hkmc.system.app.allmenu.UPDATE";

    public static final String EDITOR_PACKAGE = "dev.kiamenu.editor";
    /** Edit Menu's screen; All Menus' "Edit Home Icons" opens it (launcher hook). */
    public static final String EDITOR_ACTIVITY = "dev.kiamenu.editor.MenuActivity";
    public static final String EDITOR_URI = "content://dev.kiamenu.editor";
    public static final String CALL_CATALOG = "catalog";
    public static final String CALL_LAYOUT = "layout";
    public static final String EXTRA_KEYS = "keys";
    public static final String EXTRA_LABELS = "labels";
    public static final String EXTRA_ICONS = "icons";
    public static final String EXTRA_DISABLED = "disabled";
    public static final String EXTRA_CUSTOM = "custom";
    public static final String EXTRA_ORDER = "order";
    public static final String EXTRA_HIDDEN = "hidden";

    /** Kia's Setup entry: never hidden, so settings stay reachable. */
    public static final String SETUP_KEY = "com.android.settings/com.android.settings.Settings";
    public static final String LAUNCHER_PACKAGE = "com.android.launcher3";

    private MenuContract() {
    }

    public static String key(String packageName, String className) {
        return packageName + "/" + className;
    }

    /** Entries the layout can never hide: Kia's Setup and the editor itself. */
    public static boolean isProtected(String key) {
        return SETUP_KEY.equals(key) || key.startsWith(EDITOR_PACKAGE + "/");
    }
}
