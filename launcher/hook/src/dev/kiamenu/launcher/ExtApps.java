package dev.kiamenu.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.android.launcher3.view.a.Q;
import com.android.launcher3.view.a.g;

import dev.kiamenu.api.MenuContract;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The launcher hook's two entry points (see SYMBOLS.md for the launcher classes):
 *
 * {@link #addTo} - from AllMenuEntryList.load(), right after Kia's region list is built:
 * appends an entry for every installed app that opts in (see MenuContract).
 *
 * {@link #arrange} - from the All Menus loader (AllMenuView's task), once it has merged
 * the entries into the Item list it draws, inside its lock: reports that list to Edit
 * Menu and applies Edit Menu's layout to it (hidden items out, listed ones in order,
 * the rest after them as Kia had them, blank "Empty view" cells last).
 *
 * Both run on the launcher's loader thread. Nothing here may throw into the launcher: on
 * any problem the menu is left as Kia built it.
 */
public final class ExtApps {
    static final String TAG = "KiaMenu";

    private ExtApps() {
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static void addTo(List entries, g owner) {
        if (entries == null || owner == null) return;
        try {
            Context context = context(owner);
            PackageManager pm = context.getPackageManager();
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(MenuContract.CATEGORY);
            List<ExtApp> apps = new ArrayList<ExtApp>();
            for (ResolveInfo ri : pm.queryIntentActivities(query, PackageManager.GET_META_DATA)) {
                ActivityInfo ai = ri.activityInfo;
                if (ai != null && ai.enabled && ai.exported) apps.add(new ExtApp(context, ai));
            }
            sortByLabel(apps);
            for (ExtApp a : apps) {
                ExtRes.register(a);
                entries.add(new Q(owner, context, a.pkg, a.cls,
                        ExtRes.id(a.slot, ExtRes.ICON), ExtRes.id(a.slot, ExtRes.ICON_DISABLED),
                        ExtRes.id(a.slot, ExtRes.LABEL)));
            }
            Log.i(TAG, "added " + apps.size() + " custom apps");
        } catch (Throwable t) {
            Log.w(TAG, "custom apps not added", t);
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static void arrange(ArrayList items, Context context) {
        if (items == null || context == null) {
            Log.w(TAG, "arrange: no item list or context");
            return;
        }
        try {
            List<MenuItem> real = new ArrayList<MenuItem>();
            List<Object> blanks = new ArrayList<Object>();
            for (Object o : items) {
                MenuItem m = MenuItem.of(o);
                if (m == null || m.blank()) blanks.add(o);
                else real.add(m);
            }
            report(context, real);
            Bundle layout = layout(context);
            if (layout == null) {
                Log.i(TAG, "menu: " + real.size() + " items, no layout from Edit Menu");
                return;
            }

            Set<String> hidden = new HashSet<String>();
            String[] h = layout.getStringArray(MenuContract.EXTRA_HIDDEN);
            if (h != null) Collections.addAll(hidden, h);
            Map<String, MenuItem> byKey = new HashMap<String, MenuItem>();
            List<MenuItem> kept = new ArrayList<MenuItem>();
            for (MenuItem m : real) {
                if (hidden.contains(m.key) && !MenuContract.isProtected(m.key)) continue;
                kept.add(m);
                if (!byKey.containsKey(m.key)) byKey.put(m.key, m);
            }
            List<Object> result = new ArrayList<Object>(items.size());
            String[] order = layout.getStringArray(MenuContract.EXTRA_ORDER);
            if (order != null) {
                for (String k : order) {
                    MenuItem m = byKey.remove(k);
                    if (m != null) result.add(m.item);
                }
            }
            for (MenuItem m : kept) {
                if (!result.contains(m.item)) result.add(m.item);
            }
            result.addAll(blanks);
            items.clear();
            items.addAll(result);
            Log.i(TAG, "menu: " + kept.size() + " shown, " + (real.size() - kept.size()) + " hidden");
        } catch (Throwable t) {
            Log.w(TAG, "menu layout not applied", t);
        }
    }

    /** Tells Edit Menu what's in the menu, so it can show Kia's entries too. */
    private static void report(Context context, List<MenuItem> items) {
        int n = items.size();
        String[] keys = new String[n];
        int[] labels = new int[n], icons = new int[n], disabled = new int[n];
        boolean[] custom = new boolean[n];
        for (int i = 0; i < n; i++) {
            MenuItem m = items.get(i);
            keys[i] = m.key;
            labels[i] = m.label;
            icons[i] = m.icon;
            disabled[i] = m.disabledIcon;
            custom[i] = ExtRes.isCustom(m.icon);
        }
        Bundle b = new Bundle();
        b.putStringArray(MenuContract.EXTRA_KEYS, keys);
        b.putIntArray(MenuContract.EXTRA_LABELS, labels);
        b.putIntArray(MenuContract.EXTRA_ICONS, icons);
        b.putIntArray(MenuContract.EXTRA_DISABLED, disabled);
        b.putBooleanArray(MenuContract.EXTRA_CUSTOM, custom);
        try {
            context.getContentResolver().call(Uri.parse(MenuContract.EDITOR_URI), MenuContract.CALL_CATALOG, null, b);
        } catch (Throwable t) {
            Log.w(TAG, "catalog not delivered to Edit Menu: " + t);
        }
    }

    private static Bundle layout(Context context) {
        try {
            return context.getContentResolver().call(Uri.parse(MenuContract.EDITOR_URI), MenuContract.CALL_LAYOUT, null, null);
        } catch (Throwable t) {
            Log.w(TAG, "no layout from Edit Menu: " + t);
            return null;
        }
    }

    private static Context context(g owner) throws Exception {
        Field f = g.class.getDeclaredField("mContext");
        f.setAccessible(true);
        return (Context) f.get(owner);
    }

    private static void sortByLabel(List<ExtApp> apps) {
        final Map<ExtApp, String> labels = new HashMap<ExtApp, String>();
        for (ExtApp a : apps) labels.put(a, a.label().toString());
        Collections.sort(apps, new Comparator<ExtApp>() {
            @Override
            public int compare(ExtApp x, ExtApp y) {
                return labels.get(x).compareToIgnoreCase(labels.get(y));
            }
        });
    }
}
