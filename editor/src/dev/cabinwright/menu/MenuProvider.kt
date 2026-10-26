package dev.cabinwright.menu

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log
import dev.cabinwright.api.contract.MenuContract

/**
 * MenuContract.EDITOR_URI, used by the launcher hook through call():
 *   "layout"  -> String[] order, String[] hidden
 *   "catalog" <- the launcher's entries (keys, labels, icons, disabled, custom); accepted
 *                only from the system uid, which the launcher runs as
 */
class MenuProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val store = MenuStore(context!!)
        when (method) {
            MenuContract.CALL_LAYOUT -> return Bundle().apply {
                putStringArray(MenuContract.EXTRA_ORDER, store.order().toTypedArray())
                putStringArray(MenuContract.EXTRA_HIDDEN, store.hidden().toTypedArray())
            }
            MenuContract.CALL_CATALOG -> if (Binder.getCallingUid() == Process.SYSTEM_UID) {
                extras?.let { saveCatalog(store, it) }
            } else {
                Log.w(TAG, "catalog from uid ${Binder.getCallingUid()} ignored")
            }
        }
        return null
    }

    /** Saves the launcher's catalog if every array is there and they all match in length. */
    private fun saveCatalog(store: MenuStore, extras: Bundle) {
        val keys = extras.getStringArray(MenuContract.EXTRA_KEYS) ?: return
        val labels = extras.getIntArray(MenuContract.EXTRA_LABELS) ?: return
        val icons = extras.getIntArray(MenuContract.EXTRA_ICONS) ?: return
        val disabled = extras.getIntArray(MenuContract.EXTRA_DISABLED) ?: return
        val custom = extras.getBooleanArray(MenuContract.EXTRA_CUSTOM) ?: return
        if (listOf(labels.size, icons.size, disabled.size, custom.size).all { it == keys.size }) {
            store.saveCatalog(keys, labels, icons, disabled, custom)
        }
    }

    // not a table provider
    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        args: Array<String>?,
        sort: String?,
    ): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?): Int = 0

    private companion object {
        const val TAG = "EV6Menu"
    }
}
