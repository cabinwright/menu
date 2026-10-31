package app.cabinwright.menu

import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import app.cabinwright.api.contract.MenuContract

/**
 * Names and icons for menu entries, as All Menus shows them: the stock entries from the
 * launcher's own resources (so day/night variants match), custom apps from their
 * Cabinwright meta-data.
 */
internal class EntryLook(context: Context) {
    private val pm: PackageManager = context.packageManager
    private var launcher: Resources? = null

    init {
        refresh()
    }

    /** Re-reads the launcher's resources (after a day/night switch, say). */
    fun refresh() {
        launcher = orNull { pm.getResourcesForApplication(MenuContract.LAUNCHER_PACKAGE) }
    }

    fun label(e: MenuStore.Entry): String = orNull { if (e.custom) customLabel(e) else launcher?.getString(e.label) }
        ?: e.cls.substringAfterLast('.').ifEmpty { e.key }

    // getDrawable(id, theme) is API 21
    @Suppress("DEPRECATION")
    fun icon(e: MenuStore.Entry): Drawable? = orNull { if (e.custom) customIcon(e) else launcher?.getDrawable(e.icon) }

    private fun customLabel(e: MenuStore.Entry): String {
        val ai = activity(e)
        val res = meta(ai, MenuContract.META_LABEL)
        val text = if (res != 0) pm.getText(ai.packageName, res, ai.applicationInfo) else null
        return (text ?: ai.loadLabel(pm)).toString()
    }

    private fun customIcon(e: MenuStore.Entry): Drawable {
        val ai = activity(e)
        val res = meta(ai, MenuContract.META_ICON)
        val icon = if (res != 0) pm.getDrawable(ai.packageName, res, ai.applicationInfo) else null
        return icon ?: ai.loadIcon(pm)
    }

    private fun activity(e: MenuStore.Entry): ActivityInfo =
        pm.getActivityInfo(ComponentName(e.pkg, e.cls), PackageManager.GET_META_DATA)

    private fun meta(ai: ActivityInfo, key: String): Int = ai.metaData?.getInt(key, 0) ?: 0

    /** Null for an app or resource that has gone (uninstalled, or another launcher build). */
    private inline fun <T> orNull(block: () -> T?): T? = try {
        block()
    } catch (ignored: PackageManager.NameNotFoundException) {
        null
    } catch (ignored: Resources.NotFoundException) {
        null
    }
}
