package app.cabinwright.launcher;

import app.cabinwright.api.contract.MenuContract;

import java.lang.reflect.Field;

/**
 * Reads one of the launcher's drawn All Menus items (com.android.launcher3.view.Item).
 * Its field names survived obfuscation, so they're read by name: mPackageName,
 * mClassName, mTitleId, mEnableIconId, mDisableIconId.
 */
final class MenuItem {
    /** Class name of the launcher's blank placeholder cells. */
    private static final String BLANK = "Empty view";

    private static Field fPackage, fClass, fTitle, fIcon, fDisabled;

    final Object item;
    final String key;
    final String cls;
    final int label;
    final int icon;
    final int disabledIcon;

    private MenuItem(Object item, String pkg, String cls, int label, int icon, int disabledIcon) {
        this.item = item;
        this.cls = cls;
        this.key = MenuContract.key(pkg, cls);
        this.label = label;
        this.icon = icon;
        this.disabledIcon = disabledIcon;
    }

    boolean blank() {
        return BLANK.equals(cls);
    }

    /** null if it isn't an Item. */
    static MenuItem of(Object o) throws Exception {
        if (o == null || !o.getClass().getName().equals("com.android.launcher3.view.Item")) return null;
        if (fPackage == null) {
            Class<?> c = o.getClass();
            fPackage = field(c, "mPackageName");
            fClass = field(c, "mClassName");
            fTitle = field(c, "mTitleId");
            fIcon = field(c, "mEnableIconId");
            fDisabled = field(c, "mDisableIconId");
        }
        return new MenuItem(o, (String) fPackage.get(o), (String) fClass.get(o),
                fTitle.getInt(o), fIcon.getInt(o), fDisabled.getInt(o));
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
