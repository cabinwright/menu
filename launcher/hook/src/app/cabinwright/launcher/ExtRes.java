package app.cabinwright.launcher;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * Lets the launcher draw custom apps with their own icons and labels.
 *
 * All Menus code only knows launcher resource ids. Custom apps get ids in a range
 * no real resource uses (package id 0x7e; the launcher is 0x7f, the framework 0x01), and
 * the patch routes the launcher's resource lookups through here: ids in that range come
 * from the custom app, everything else goes straight to the launcher's own Resources.
 * Drawables are loaded on every call so day/night switches pick the right variant.
 */
public final class ExtRes {
    static final int BASE = 0x7e000000;
    static final int LABEL = 0;
    static final int ICON = 1;
    static final int ICON_DISABLED = 2;

    private static final Map<Integer, ExtApp> APPS = new HashMap<Integer, ExtApp>();

    private ExtRes() {
    }

    /** Stable per activity, so ids saved by the launcher (menu order) survive reboots. */
    static int slot(String pkg, String cls) {
        return BASE | (((pkg + "/" + cls).hashCode() & 0xFFFFF) << 4);
    }

    static int id(int slot, int kind) {
        return slot | kind;
    }

    static void register(ExtApp app) {
        synchronized (APPS) {
            APPS.put(app.slot, app);
        }
    }

    static boolean isCustom(int id) {
        return ours(id);
    }

    private static boolean ours(int id) {
        return (id & 0xFF000000) == BASE;
    }

    private static ExtApp app(int id) {
        synchronized (APPS) {
            return APPS.get(id & ~0xF);
        }
    }

    // ---- replacements for the launcher's call sites (same arguments, receiver first)

    public static String getString(Resources res, int id) {
        if (!ours(id)) return res.getString(id);
        ExtApp a = app(id);
        return a == null ? "" : a.label().toString();
    }

    public static String getString(Context context, int id) {
        if (!ours(id)) return context.getString(id);
        return getString(context.getResources(), id);
    }

    public static CharSequence getText(Resources res, int id) {
        if (!ours(id)) return res.getText(id);
        ExtApp a = app(id);
        return a == null ? "" : a.label();
    }

    public static Drawable getDrawable(Resources res, int id) {
        if (!ours(id)) return res.getDrawable(id);
        ExtApp a = app(id);
        if (a == null) return null;
        return (id & 0xF) == ICON_DISABLED ? a.disabledIcon() : a.icon();
    }

    public static void setText(TextView view, int id) {
        if (!ours(id)) {
            view.setText(id);
            return;
        }
        view.setText(getText(view.getResources(), id));
    }

    public static void setImageResource(ImageView view, int id) {
        if (!ours(id)) {
            view.setImageResource(id);
            return;
        }
        view.setImageDrawable(getDrawable(view.getResources(), id));
    }

    public static void setBackgroundResource(View view, int id) {
        if (!ours(id)) {
            view.setBackgroundResource(id);
            return;
        }
        view.setBackground(getDrawable(view.getResources(), id));
    }
}
