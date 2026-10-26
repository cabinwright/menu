package app.cabinwright.api.ui

import android.content.Context
import java.lang.reflect.Proxy

/**
 * The stock split-screen panel (com.mobis.splitscreen.right) is a window drawn over the
 * right of the screen, from x=1276. Stock apps ask the framework's
 * com.hkmc.split.manager.SplitScreenManager (automotive.jar, on every app's boot
 * classpath) whether it is on, and get onChangeOnOff callbacks. It isn't in the SDK,
 * so this reaches it by reflection; if anything fails, callers assume the panel is on.
 */
class SplitScreen(context: Context) {
    fun interface OnChange {
        fun onSplitScreen(on: Boolean)
    }

    private val manager: Any? = probeHidden(null) {
        Class.forName("com.hkmc.split.manager.SplitScreenManager")
            .getMethod("getInstance", Context::class.java)
            .invoke(null, context.applicationContext)
    }
    private val listenerClass: Class<*>? = probeHidden(null) {
        Class.forName("com.hkmc.split.manager.SplitScreenManager\$SplitListener")
    }
    private var listener: Any? = null

    /** True when the panel is showing, or when it can't be asked. */
    fun isOn(): Boolean {
        val m = manager ?: return true
        return probeHidden(true) { m.javaClass.getMethod("isSplitScreenOn").invoke(m) as Boolean }
    }

    fun listen(callback: OnChange) {
        if (manager == null || listenerClass == null || listener != null) return
        listener = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { proxy, method, args ->
            when (method.name) {
                // the manager keeps listeners in a list, so answer Object's methods too
                "equals" -> proxy === args[0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "SplitScreen listener"
                else -> {
                    if (method.name == "onChangeOnOff" && args != null && args.size == 1) {
                        callback.onSplitScreen(args[0] as Boolean)
                    }
                    null
                }
            }
        }
        call("addSplitListener")
    }

    fun stop() {
        if (listener == null) return
        call("removeSplitListener")
        listener = null
    }

    private fun call(name: String) {
        probeHidden(Unit) { manager!!.javaClass.getMethod(name, listenerClass).invoke(manager, listener) }
    }

    companion object {
        /** Left edge of the panel window, in pixels. */
        const val PANEL_LEFT_PX = 1276

        /** Width of the panel's open handle at the right edge while the panel is off. */
        const val HANDLE_PX = 80
    }
}
