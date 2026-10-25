package dev.kiamenu.launcher;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.Drawable;
import android.os.Bundle;

import dev.kiamenu.api.MenuContract;

/** One custom app's All Menus entry: where its label and icons come from. */
final class ExtApp {
    final int slot;
    final String pkg;
    final String cls;
    private final Context context;
    private final ActivityInfo info;

    ExtApp(Context context, ActivityInfo info) {
        this.context = context;
        this.info = info;
        this.pkg = info.packageName;
        this.cls = info.name;
        this.slot = ExtRes.slot(pkg, cls);
    }

    CharSequence label() {
        PackageManager pm = context.getPackageManager();
        int res = meta(MenuContract.META_LABEL);
        if (res != 0) {
            CharSequence s = pm.getText(pkg, res, info.applicationInfo);
            if (s != null) return s;
        }
        return info.loadLabel(pm);
    }

    Drawable icon() {
        PackageManager pm = context.getPackageManager();
        int res = meta(MenuContract.META_ICON);
        if (res != 0) {
            Drawable d = pm.getDrawable(pkg, res, info.applicationInfo);
            if (d != null) return d;
        }
        return info.loadIcon(pm);
    }

    /** The app's own disabled icon, or its icon greyed out like Kia's _dis_ icons. */
    Drawable disabledIcon() {
        int res = meta(MenuContract.META_ICON_DISABLED);
        if (res != 0) {
            Drawable d = context.getPackageManager().getDrawable(pkg, res, info.applicationInfo);
            if (d != null) return d;
        }
        Drawable d = icon().mutate();
        ColorMatrix grey = new ColorMatrix();
        grey.setSaturation(0);
        d.setColorFilter(new ColorMatrixColorFilter(grey));
        d.setAlpha(70);
        return d;
    }

    private int meta(String key) {
        Bundle m = info.metaData;
        return m == null ? 0 : m.getInt(key, 0);
    }
}
