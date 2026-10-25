package dev.kiamenu.api.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

/**
 * Base for kia-menu app screens: a page in Kia's own look (colours and the Mobis font
 * sampled from the stock Setup screen) that keeps clear of Kia's split-screen panel (left
 * of it while it's on, full width with room for its handle while it's off), plus small
 * view helpers. Subclasses add their views to {@link #page}.
 */
public abstract class KiaPage extends Activity {
    // Kia's colours, measured from the stock Setup screen (CVEV.USA 260507) in each mode.
    // The car switches day/night through the standard UI night mode (headlamps or the
    // Display setting), which recreates the activity, so these are picked in onCreate.
    /** Page background. */
    protected int BG;
    /** Panels, buttons and menus. */
    protected int TILE;
    /** The thin edge around panels and buttons. */
    protected int EDGE;
    /** The raised square behind an icon, and its lighter edge. */
    protected int ICON_TILE;
    protected int ICON_TILE_EDGE;
    /** The shadow line under the title bar. */
    protected int DIVIDER;
    protected int TEXT;
    protected int MUTED;
    /** Kia's highlight teal (an active button's glyph), the same day and night. */
    public static final int ACCENT = 0xFF17BEB0;
    /** Kia's day icon gradient, top to bottom (measured from the stock icons). */
    public static final int ICON_START = Color.rgb(0x34, 0xE0, 0xD5);
    public static final int ACCENT_END = Color.rgb(0x84, 0x7F, 0xDC);
    public static final int GOOD = Color.rgb(46, 204, 113);
    public static final int WARN = Color.rgb(241, 179, 54);
    public static final int BAD = Color.rgb(235, 87, 87);
    public static final int IDLE = Color.rgb(110, 120, 132);

    /** True when the car is showing its night (dark) theme. */
    public static boolean isNight(Context context) {
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode != Configuration.UI_MODE_NIGHT_NO; // Kia's default is night
    }

    private void pickColors() {
        if (isNight(this)) {
            BG = 0xFF121214;
            TILE = 0xFF161618;
            EDGE = 0xFF313136;
            ICON_TILE = 0xFF1F1F24;
            ICON_TILE_EDGE = 0xFF3D3D44;
            DIVIDER = 0xFF0E0E0F;
            TEXT = 0xFFB6C1C8;
            MUTED = 0xFF79838A;
        } else {
            BG = 0xFFF0F0F0;
            TILE = 0xFFF0F0F0;
            EDGE = 0xFFCBCBCB;
            ICON_TILE = 0xFFF0F0F2;
            ICON_TILE_EDGE = 0xFFDDDDE0;
            DIVIDER = 0xFFCACACA;
            TEXT = 0xFF62797F;
            MUTED = 0xFF8D9DA2;
        }
    }

    private static Typeface regular;
    private static Typeface bold;

    protected LinearLayout page;
    private SplitScreen split;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pickColors();
        FrameLayout screen = new FrameLayout(this);
        screen.setBackgroundColor(BG);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(24), dp(6), dp(24), dp(8));
        split = new SplitScreen(this);
        screen.addView(page, new FrameLayout.LayoutParams(pageWidth(split.isOn()), FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(screen);
    }

    @Override
    protected void onResume() {
        super.onResume();
        fitToSplitScreen(split.isOn());
        split.listen(new SplitScreen.OnChange() {
            @Override
            public void onSplitScreen(final boolean on) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        fitToSplitScreen(on);
                    }
                });
            }
        });
    }

    @Override
    protected void onPause() {
        split.stop();
        super.onPause();
    }

    /** Adds the page title row; returns the small text on its right, for the caller to fill. */
    protected TextView header(String title) {
        LinearLayout header = headerBar(title);
        TextView right = text("", 15, MUTED, false);
        header.addView(right);
        return right;
    }

    /**
     * Adds a slim title row (Kia's status bar already sits above every app, so this stays
     * small): the title on the left, then whatever the caller adds, pushed to the right.
     */
    protected LinearLayout headerBar(String title) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(title, 19, TEXT, false));
        View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams(0, 1, 1));
        page.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(BAR_DP)));
        return row;
    }

    /** Height of the title row and its buttons. */
    protected static final int BAR_DP = 36;

    /** A Kia button or panel: flat dark fill with a thin lighter edge. */
    protected Drawable surface(int radius) {
        GradientDrawable d = rounded(TILE, radius);
        d.setStroke(Math.max(1, dp(1) * 2 / 3), EDGE);
        return d;
    }

    /** The raised square Kia puts behind menu icons. */
    protected Drawable iconTile(int radius) {
        GradientDrawable d = rounded(ICON_TILE, radius);
        d.setStroke(Math.max(1, dp(1) * 2 / 3), ICON_TILE_EDGE);
        return d;
    }

    /** A Kia-style button: text in a {@link #surface}. */
    protected TextView button(String label, View.OnClickListener click) {
        TextView b = text(label, 16, TEXT, false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setBackground(surface(dp(4)));
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(BAR_DP - 6));
        lp.leftMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private int pageWidth(boolean splitOn) {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        return splitOn ? Math.min(SplitScreen.PANEL_LEFT_PX, screenWidth) : FrameLayout.LayoutParams.MATCH_PARENT;
    }

    private void fitToSplitScreen(boolean on) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) page.getLayoutParams();
        int width = pageWidth(on);
        if (lp.width != width) {
            lp.width = width;
            page.setLayoutParams(lp);
        }
        // with the panel off, its open handle sits over the right edge
        int right = on ? dp(24) : SplitScreen.HANDLE_PX + dp(12);
        page.setPadding(page.getPaddingLeft(), page.getPaddingTop(), right, page.getPaddingBottom());
    }

    protected TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(font(bold));
        return t;
    }

    /** Kia's UI font (Mobis), falling back to the system one. */
    public static Typeface font(boolean isBold) {
        if (regular == null) {
            regular = load("/system/fonts/Mobis-Regular.ttf", Typeface.DEFAULT);
            bold = load("/system/fonts/Mobis-Bold.ttf", Typeface.DEFAULT_BOLD);
        }
        return isBold ? bold : regular;
    }

    private static Typeface load(String path, Typeface fallback) {
        try {
            return new File(path).isFile() ? Typeface.createFromFile(path) : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    protected static GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    protected int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
