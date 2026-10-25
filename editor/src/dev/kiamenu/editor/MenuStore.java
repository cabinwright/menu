package dev.kiamenu.editor;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The All Menus layout (order and hidden entries) and the latest list of entries the
 * launcher reported. Entry keys are "<package>/<activity class>", as in MenuContract.
 * The layout is also kept in {@link #BACKUP} on the internal sdcard: a NEMESIS package
 * update reinstalls the app, which wipes its own data, and the layout is restored from
 * there on first use.
 */
final class MenuStore {
    /** One All Menus entry as the launcher reported it. */
    static final class Entry {
        final String key;
        final int label;
        final int icon;
        final int disabledIcon;
        final boolean custom;

        Entry(String key, int label, int icon, int disabledIcon, boolean custom) {
            this.key = key;
            this.label = label;
            this.icon = icon;
            this.disabledIcon = disabledIcon;
            this.custom = custom;
        }

        String pkg() {
            int slash = key.indexOf('/');
            return slash < 0 ? key : key.substring(0, slash);
        }

        String cls() {
            int slash = key.indexOf('/');
            return slash < 0 ? "" : key.substring(slash + 1);
        }
    }

    static final File BACKUP = new File(Environment.getExternalStorageDirectory(), "kia-menu/layout.json");

    private final SharedPreferences prefs;

    MenuStore(Context context) {
        prefs = context.getSharedPreferences("layout", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("restored", false)) restore();
    }

    // ---- layout

    List<String> order() {
        return strings(prefs.getString("order", "[]"));
    }

    Set<String> hidden() {
        return new HashSet<String>(strings(prefs.getString("hidden", "[]")));
    }

    void save(List<String> order, Set<String> hidden) {
        prefs.edit()
                .putString("order", new JSONArray(order).toString())
                .putString("hidden", new JSONArray(new ArrayList<String>(hidden)).toString())
                .commit();
        backup();
    }

    void reset() {
        prefs.edit().remove("order").remove("hidden").commit();
        backup();
    }

    private void backup() {
        try {
            JSONObject o = new JSONObject()
                    .put("order", new JSONArray(order()))
                    .put("hidden", new JSONArray(new ArrayList<String>(hidden())));
            BACKUP.getParentFile().mkdirs();
            File tmp = new File(BACKUP.getPath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(o.toString().getBytes("UTF-8"));
            } finally {
                out.close();
            }
            tmp.renameTo(BACKUP);
        } catch (IOException | JSONException ignored) {
        }
    }

    private void restore() {
        SharedPreferences.Editor edit = prefs.edit().putBoolean("restored", true);
        try {
            byte[] b = new byte[(int) Math.min(BACKUP.length(), 1 << 20)];
            FileInputStream in = new FileInputStream(BACKUP);
            try {
                int n = 0;
                while (n < b.length) {
                    int r = in.read(b, n, b.length - n);
                    if (r < 0) break;
                    n += r;
                }
            } finally {
                in.close();
            }
            JSONObject o = new JSONObject(new String(b, "UTF-8"));
            edit.putString("order", o.getJSONArray("order").toString())
                    .putString("hidden", o.getJSONArray("hidden").toString());
        } catch (IOException | JSONException ignored) {
            // no backup yet: start from the launcher's own order
        }
        edit.commit();
    }

    // ---- what the launcher has

    void saveCatalog(String[] keys, int[] labels, int[] icons, int[] disabled, boolean[] custom) {
        JSONArray a = new JSONArray();
        try {
            for (int i = 0; i < keys.length; i++) {
                a.put(new JSONObject().put("key", keys[i]).put("label", labels[i]).put("icon", icons[i])
                        .put("disabled", disabled[i]).put("custom", custom[i]));
            }
        } catch (JSONException e) {
            return;
        }
        prefs.edit().putString("catalog", a.toString()).putLong("catalogTime", System.currentTimeMillis()).commit();
    }

    List<Entry> catalog() {
        List<Entry> out = new ArrayList<Entry>();
        try {
            JSONArray a = new JSONArray(prefs.getString("catalog", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Entry(o.getString("key"), o.getInt("label"), o.getInt("icon"),
                        o.getInt("disabled"), o.getBoolean("custom")));
            }
        } catch (JSONException e) {
            out.clear();
        }
        return out;
    }

    long catalogTime() {
        return prefs.getLong("catalogTime", 0);
    }

    /** The catalog in menu order: saved order first, then the rest as the launcher has them. */
    List<Entry> arranged() {
        List<Entry> catalog = catalog();
        Set<String> seen = new LinkedHashSet<String>();
        List<Entry> out = new ArrayList<Entry>();
        for (String k : order()) {
            for (Entry e : catalog) {
                if (e.key.equals(k) && seen.add(k)) out.add(e);
            }
        }
        for (Entry e : catalog) {
            if (seen.add(e.key)) out.add(e);
        }
        return out;
    }

    private static List<String> strings(String json) {
        List<String> out = new ArrayList<String>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (JSONException ignored) {
        }
        return out;
    }
}
