package dev.cabinwright.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import dev.cabinwright.api.contract.MenuContract;

/**
 * All Menus' "Edit Home Icons" (its options menu) opens Edit Menu instead of the head unit's own
 * edit screen, HomeSetting's AllMenuEdit, whose saved order the Edit Menu layout replaces.
 * Hooked just before the launcher's startActivity at both places it opens that screen.
 * Without Edit Menu installed the intent is left alone.
 */
public final class EditScreen {
    static final String STOCK_EDIT = "com.hkmc.system.app.homesetting.AllMenuEdit";

    private EditScreen() {
    }

    public static void redirect(Context context, Intent intent) {
        try {
            ComponentName target = intent.getComponent();
            if (target == null || !STOCK_EDIT.equals(target.getClassName())) return;
            ComponentName ours = new ComponentName(MenuContract.EDITOR_PACKAGE, MenuContract.EDITOR_ACTIVITY);
            if (context.getPackageManager().resolveActivity(new Intent().setComponent(ours), 0) == null) return;
            intent.setComponent(ours);
            Log.i(ExtApps.TAG, "edit screen: opening Edit Menu");
        } catch (RuntimeException e) {
            Log.w(ExtApps.TAG, "edit screen: left as the head unit's", e);
        }
    }
}
