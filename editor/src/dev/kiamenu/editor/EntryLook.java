package dev.kiamenu.editor;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;

import dev.kiamenu.api.MenuContract;

/**
 * Names and icons for menu entries, as All Menus shows them: Kia's entries from the
 * launcher's own resources (so day/night variants match), custom apps from their
 * kia-menu meta-data.
 */
final class EntryLook {
    private final PackageManager pm;
    private Resources launcher;

    EntryLook(Context context) {
        pm = context.getPackageManager();
        refresh();
    }

    /** Re-reads the launcher's resources (after a day/night switch, say). */
    void refresh() {
        try {
            launcher = pm.getResourcesForApplication(MenuContract.LAUNCHER_PACKAGE);
        } catch (PackageManager.NameNotFoundException e) {
            launcher = null;
        }
    }

    String label(MenuStore.Entry e) {
        try {
            if (e.custom) {
                ActivityInfo ai = activity(e);
                int res = meta(ai, MenuContract.META_LABEL);
                if (res != 0) {
                    CharSequence s = pm.getText(ai.packageName, res, ai.applicationInfo);
                    if (s != null) return s.toString();
                }
                return ai.loadLabel(pm).toString();
            }
            if (launcher != null) return launcher.getString(e.label);
        } catch (Exception ignored) {
        }
        String cls = e.cls();
        return cls.isEmpty() ? e.key : cls.substring(cls.lastIndexOf('.') + 1);
    }

    Drawable icon(MenuStore.Entry e) {
        try {
            if (e.custom) {
                ActivityInfo ai = activity(e);
                int res = meta(ai, MenuContract.META_ICON);
                if (res != 0) {
                    Drawable d = pm.getDrawable(ai.packageName, res, ai.applicationInfo);
                    if (d != null) return d;
                }
                return ai.loadIcon(pm);
            }
            if (launcher != null) return launcher.getDrawable(e.icon);
        } catch (Exception ignored) {
        }
        return null;
    }

    private ActivityInfo activity(MenuStore.Entry e) throws PackageManager.NameNotFoundException {
        return pm.getActivityInfo(new ComponentName(e.pkg(), e.cls()), PackageManager.GET_META_DATA);
    }

    private static int meta(ActivityInfo ai, String key) {
        return ai.metaData == null ? 0 : ai.metaData.getInt(key, 0);
    }
}
