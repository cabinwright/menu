package dev.kiamenu.editor

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * The All Menus layout (order and hidden entries) and the latest list of entries the
 * launcher reported. Entry keys are "<package>/<activity class>", as in MenuContract.
 * The layout is also kept in [BACKUP] on the internal sdcard: a NEMESIS package
 * update reinstalls the app, which wipes its own data, and the layout is restored from
 * there on first use.
 */
internal class MenuStore(context: Context) {
    /** One All Menus entry as the launcher reported it. */
    class Entry(val key: String, val label: Int, val icon: Int, val disabledIcon: Int, val custom: Boolean) {
        val pkg: String get() = key.substringBefore('/')
        val cls: String get() = if ('/' in key) key.substringAfter('/') else ""
    }

    private val prefs: SharedPreferences = context.getSharedPreferences("layout", Context.MODE_PRIVATE)

    init {
        if (!prefs.getBoolean("restored", false)) restore()
    }

    // ---- layout

    fun order(): List<String> = strings(prefs.getString("order", "[]"))

    fun hidden(): MutableSet<String> = strings(prefs.getString("hidden", "[]")).toHashSet()

    fun save(order: List<String>, hidden: Set<String>) {
        prefs.edit()
            .putString("order", JSONArray(order).toString())
            .putString("hidden", JSONArray(hidden.toList()).toString())
            .commit()
        backup()
    }

    fun reset() {
        prefs.edit().remove("order").remove("hidden").commit()
        backup()
    }

    private fun backup() {
        try {
            val o = JSONObject()
                .put("order", JSONArray(order()))
                .put("hidden", JSONArray(hidden().toList()))
            BACKUP.parentFile?.mkdirs()
            val tmp = File(BACKUP.path + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(BACKUP)
        } catch (ignored: IOException) {
        } catch (ignored: JSONException) {
        }
    }

    private fun restore() {
        val edit = prefs.edit().putBoolean("restored", true)
        try {
            if (BACKUP.length() > 1 shl 20) throw IOException("backup too big")
            val o = JSONObject(BACKUP.readText())
            edit.putString("order", o.getJSONArray("order").toString())
                .putString("hidden", o.getJSONArray("hidden").toString())
        } catch (ignored: IOException) {
            // no backup yet: start from the launcher's own order
        } catch (ignored: JSONException) {
        }
        edit.commit()
    }

    // ---- what the launcher has

    fun saveCatalog(keys: Array<String>, labels: IntArray, icons: IntArray, disabled: IntArray, custom: BooleanArray) {
        // put() only throws for a null name or a NaN, neither of which can happen here
        val a = JSONArray()
        for (i in keys.indices) {
            a.put(
                JSONObject().put("key", keys[i]).put("label", labels[i]).put("icon", icons[i])
                    .put("disabled", disabled[i]).put("custom", custom[i]),
            )
        }
        prefs.edit().putString("catalog", a.toString()).putLong("catalogTime", System.currentTimeMillis()).commit()
    }

    fun catalog(): List<Entry> = try {
        val a = JSONArray(prefs.getString("catalog", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Entry(o.getString("key"), o.getInt("label"), o.getInt("icon"), o.getInt("disabled"), o.getBoolean("custom"))
        }
    } catch (ignored: JSONException) {
        // damaged: the launcher sends a fresh one on its next reload
        emptyList()
    }

    fun catalogTime(): Long = prefs.getLong("catalogTime", 0)

    /** The catalog in menu order: saved order first, then the rest as the launcher has them. */
    fun arranged(): MutableList<Entry> {
        val catalog = catalog()
        val seen = LinkedHashSet<String>()
        val out = ArrayList<Entry>()
        for (k in order()) {
            for (e in catalog) if (e.key == k && seen.add(k)) out.add(e)
        }
        for (e in catalog) if (seen.add(e.key)) out.add(e)
        return out
    }

    companion object {
        val BACKUP = File(Environment.getExternalStorageDirectory(), "kia-menu/layout.json")

        private fun strings(json: String?): List<String> = try {
            val a = JSONArray(json)
            (0 until a.length()).map { a.getString(it) }
        } catch (ignored: JSONException) {
            emptyList()
        }
    }
}
