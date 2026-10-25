package dev.kiamenu.editor;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import dev.kiamenu.api.MenuContract;

import java.util.ArrayList;
import java.util.List;

/**
 * MenuContract.EDITOR_URI, used by the launcher hook through call():
 *   "layout"  -> String[] order, String[] hidden
 *   "catalog" <- the launcher's entries (keys, labels, icons, disabled, custom); accepted
 *                only from the system uid, which the launcher runs as
 */
public class MenuProvider extends ContentProvider {
    private static final String TAG = "EV6Menu";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        MenuStore store = new MenuStore(getContext());
        if (MenuContract.CALL_LAYOUT.equals(method)) {
            List<String> hidden = new ArrayList<String>(store.hidden());
            Bundle b = new Bundle();
            b.putStringArray(MenuContract.EXTRA_ORDER, store.order().toArray(new String[0]));
            b.putStringArray(MenuContract.EXTRA_HIDDEN, hidden.toArray(new String[0]));
            return b;
        }
        if (MenuContract.CALL_CATALOG.equals(method)) {
            if (Binder.getCallingUid() != Process.SYSTEM_UID) {
                Log.w(TAG, "catalog from uid " + Binder.getCallingUid() + " ignored");
                return null;
            }
            if (extras != null) {
                String[] keys = extras.getStringArray(MenuContract.EXTRA_KEYS);
                int[] labels = extras.getIntArray(MenuContract.EXTRA_LABELS);
                int[] icons = extras.getIntArray(MenuContract.EXTRA_ICONS);
                int[] disabled = extras.getIntArray(MenuContract.EXTRA_DISABLED);
                boolean[] custom = extras.getBooleanArray(MenuContract.EXTRA_CUSTOM);
                if (keys != null && labels != null && icons != null && disabled != null && custom != null
                        && labels.length == keys.length && icons.length == keys.length
                        && disabled.length == keys.length && custom.length == keys.length) {
                    store.saveCatalog(keys, labels, icons, disabled, custom);
                }
            }
            return null;
        }
        return null;
    }

    // not a table provider
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
