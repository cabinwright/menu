package app.cabinwright.api.contract;

/**
 * Cabinwright API: how an app shows up in All Menus (with the Cabinwright launcher hook
 * installed). This class has no dependencies; copy or compile it into your app.
 *
 * <h3>Showing an app</h3>
 * On the activity to open, in AndroidManifest.xml:
 * <pre>
 *   &lt;intent-filter&gt;
 *       &lt;action android:name="android.intent.action.MAIN" /&gt;
 *       &lt;category android:name="app.cabinwright.category.ALL_MENUS" /&gt;
 *   &lt;/intent-filter&gt;
 *   &lt;meta-data android:name="app.cabinwright.icon" android:resource="@drawable/menu_icon" /&gt;
 *   &lt;meta-data android:name="app.cabinwright.icon_disabled" android:resource="@drawable/menu_icon_dis" /&gt;
 *   &lt;meta-data android:name="app.cabinwright.label" android:resource="@string/menu_label" /&gt;
 * </pre>
 * The meta-data is optional: without it the activity's own icon and label are used, and
 * the disabled icon is a greyed-out copy. The stock icons are 99x99 px at hdpi with
 * drawable-night-hdpi variants; api/tools/menu_icon.py makes all four from an SVG path.
 * The menu picks the app up the next time it rebuilds (send {@link #ACTION_RELOAD}).
 *
 * <h3>The editor</h3>
 * Edit Menu ({@link #EDITOR_PACKAGE}) owns the layout: order and hidden entries, for the stock
 * entries and custom apps alike. Without it installed, custom apps are simply added after
 * the stock ones. The launcher hook talks to it through its provider's call():
 * <ul>
 *   <li>{@link #CALL_CATALOG} from the launcher: every entry, as parallel arrays
 *       {@link #EXTRA_KEYS}, {@link #EXTRA_LABELS}, {@link #EXTRA_ICONS},
 *       {@link #EXTRA_DISABLED} (launcher resource ids, or Cabinwright ids for custom apps)
 *       and {@link #EXTRA_CUSTOM}; only accepted from the system uid</li>
 *   <li>{@link #CALL_LAYOUT} to the launcher: {@link #EXTRA_ORDER} and {@link #EXTRA_HIDDEN},
 *       arrays of entry keys</li>
 * </ul>
 * An entry's key is {@code "<package>/<activity class>"}; see {@link #key}.
 */
public final class MenuContract {
    /** Intent category that puts an activity in All Menus. */
    public static final String CATEGORY = "app.cabinwright.category.ALL_MENUS";
    /** Activity meta-data: drawable for the menu icon. */
    public static final String META_ICON = "app.cabinwright.icon";
    /** Activity meta-data: drawable for the menu icon while the entry is disabled. */
    public static final String META_ICON_DISABLED = "app.cabinwright.icon_disabled";
    /** Activity meta-data: string for the menu label. */
    public static final String META_LABEL = "app.cabinwright.label";

    /** Broadcast: rebuild All Menus (new apps, layout changes). Added by the launcher hook. */
    public static final String ACTION_RELOAD = "app.cabinwright.action.RELOAD";
    /** The head unit's own broadcast: refreshes the existing items' icons and states only. */
    public static final String ACTION_STOCK_REFRESH = "com.hkmc.system.app.allmenu.UPDATE";

    public static final String EDITOR_PACKAGE = "app.cabinwright.menu";
    /** Edit Menu's screen; All Menus' "Edit Home Icons" opens it (launcher hook). */
    public static final String EDITOR_ACTIVITY = "app.cabinwright.menu.MenuActivity";
    public static final String EDITOR_URI = "content://app.cabinwright.menu";
    public static final String CALL_CATALOG = "catalog";
    public static final String CALL_LAYOUT = "layout";
    public static final String EXTRA_KEYS = "keys";
    public static final String EXTRA_LABELS = "labels";
    public static final String EXTRA_ICONS = "icons";
    public static final String EXTRA_DISABLED = "disabled";
    public static final String EXTRA_CUSTOM = "custom";
    public static final String EXTRA_ORDER = "order";
    public static final String EXTRA_HIDDEN = "hidden";

    /** The stock Setup entry: never hidden, so settings stay reachable. */
    public static final String SETUP_KEY = "com.android.settings/com.android.settings.Settings";
    public static final String LAUNCHER_PACKAGE = "com.android.launcher3";

    private MenuContract() {
    }

    public static String key(String packageName, String className) {
        return packageName + "/" + className;
    }

    /** Entries the layout can never hide: the stock Setup and the editor itself. */
    public static boolean isProtected(String key) {
        return SETUP_KEY.equals(key) || key.startsWith(EDITOR_PACKAGE + "/");
    }
}
