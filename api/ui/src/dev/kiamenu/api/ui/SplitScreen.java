package dev.kiamenu.api.ui;

import android.content.Context;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Kia's split-screen panel (com.mobis.splitscreen.right) is a window drawn over the
 * right of the screen, from x=1276. Stock apps ask the framework's
 * com.hkmc.split.manager.SplitScreenManager (automotive.jar, on every app's boot
 * classpath) whether it is on, and get onChangeOnOff callbacks. It isn't in the SDK,
 * so this reaches it by reflection; if anything fails, callers assume the panel is on.
 */
public final class SplitScreen {
    /** Left edge of the panel window, in pixels. */
    public static final int PANEL_LEFT_PX = 1276;
    /** Width of the panel's open handle at the right edge while the panel is off. */
    public static final int HANDLE_PX = 80;

    public interface OnChange {
        void onSplitScreen(boolean on);
    }

    private final Object manager;
    private final Class<?> listenerClass;
    private Object listener;

    public SplitScreen(Context context) {
        Object m = null;
        Class<?> l = null;
        try {
            Class<?> c = Class.forName("com.hkmc.split.manager.SplitScreenManager");
            m = c.getMethod("getInstance", Context.class).invoke(null, context.getApplicationContext());
            l = Class.forName("com.hkmc.split.manager.SplitScreenManager$SplitListener");
        } catch (Throwable t) {
            m = null;
        }
        manager = m;
        listenerClass = l;
    }

    /** True when the panel is showing, or when it can't be asked. */
    public boolean isOn() {
        if (manager == null) return true;
        try {
            return (Boolean) manager.getClass().getMethod("isSplitScreenOn").invoke(manager);
        } catch (Throwable t) {
            return true;
        }
    }

    public void listen(final OnChange callback) {
        if (manager == null || listenerClass == null || listener != null) return;
        listener = Proxy.newProxyInstance(listenerClass.getClassLoader(), new Class<?>[] { listenerClass },
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        // the manager keeps listeners in a list, so answer Object's methods too
                        if ("equals".equals(name)) return proxy == args[0];
                        if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                        if ("toString".equals(name)) return "SplitScreen listener";
                        if ("onChangeOnOff".equals(name) && args != null && args.length == 1) {
                            callback.onSplitScreen((Boolean) args[0]);
                        }
                        return null;
                    }
                });
        call("addSplitListener");
    }

    public void stop() {
        if (listener == null) return;
        call("removeSplitListener");
        listener = null;
    }

    private void call(String name) {
        try {
            manager.getClass().getMethod(name, listenerClass).invoke(manager, listener);
        } catch (Throwable ignored) {
        }
    }
}
