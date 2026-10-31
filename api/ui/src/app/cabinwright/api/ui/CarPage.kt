package app.cabinwright.api.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Base for Cabinwright app screens: a page in the head unit's own look (colours and the Mobis font
 * sampled from the stock Setup screen) that keeps clear of the stock split-screen panel (left
 * of it while it's on, full width with room for its handle while it's off), plus small
 * view helpers. Subclasses add their views to [page].
 */
// BG, TILE...: theme colours, named like the constants they are for a page's lifetime
@Suppress("ktlint:standard:property-naming")
abstract class CarPage : Activity() {
    // The stock colours, measured from the stock Setup screen (CVEV.USA 260507) in each mode.
    // The car switches day/night through the standard UI night mode (headlamps or the
    // Display setting), which recreates the activity, so these are picked in onCreate.
    /** Page background. */
    @JvmField protected var BG = 0

    /** Panels, buttons and menus. */
    @JvmField protected var TILE = 0

    /** The thin edge around panels and buttons. */
    @JvmField protected var EDGE = 0

    /** The raised square behind an icon, and its lighter edge. */
    @JvmField protected var ICON_TILE = 0

    @JvmField protected var ICON_TILE_EDGE = 0

    /** The shadow line under the title bar. */
    @JvmField protected var DIVIDER = 0

    @JvmField protected var TEXT = 0

    @JvmField protected var MUTED = 0

    protected lateinit var page: LinearLayout
    private lateinit var split: SplitScreen

    private fun pickColors() {
        if (isNight(this)) {
            BG = 0xFF121214.toInt()
            TILE = 0xFF161618.toInt()
            EDGE = 0xFF313136.toInt()
            ICON_TILE = 0xFF1F1F24.toInt()
            ICON_TILE_EDGE = 0xFF3D3D44.toInt()
            DIVIDER = 0xFF0E0E0F.toInt()
            TEXT = 0xFFB6C1C8.toInt()
            MUTED = 0xFF79838A.toInt()
        } else {
            BG = 0xFFF0F0F0.toInt()
            TILE = 0xFFF0F0F0.toInt()
            EDGE = 0xFFCBCBCB.toInt()
            ICON_TILE = 0xFFF0F0F2.toInt()
            ICON_TILE_EDGE = 0xFFDDDDE0.toInt()
            DIVIDER = 0xFFCACACA.toInt()
            TEXT = 0xFF62797F.toInt()
            MUTED = 0xFF8D9DA2.toInt()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickColors()
        val screen = FrameLayout(this)
        screen.setBackgroundColor(BG)
        page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(dp(24), dp(6), dp(24), dp(8))
        split = SplitScreen(this)
        screen.addView(page, FrameLayout.LayoutParams(pageWidth(split.isOn()), FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(screen)
    }

    override fun onResume() {
        super.onResume()
        fitToSplitScreen(split.isOn())
        split.listen { on -> runOnUiThread { fitToSplitScreen(on) } }
    }

    override fun onPause() {
        split.stop()
        super.onPause()
    }

    /** Adds the page title row; returns the small text on its right, for the caller to fill. */
    protected fun header(title: String): TextView {
        val right = text("", 15, MUTED, false)
        headerBar(title).addView(right)
        return right
    }

    /**
     * Adds a slim title row (the head unit's status bar already sits above every app, so this stays
     * small): the title on the left, then whatever the caller adds, pushed to the right.
     */
    protected fun headerBar(title: String): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setGravity(Gravity.CENTER_VERTICAL)
        row.addView(text(title, 19, TEXT, false))
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        page.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(BAR_DP)))
        return row
    }

    /** A stock button or panel: flat dark fill with a thin lighter edge. */
    protected fun surface(radius: Int): Drawable =
        rounded(TILE, radius).apply { setStroke(max(1, dp(1) * 2 / 3), EDGE) }

    /** The raised square the launcher puts behind menu icons. */
    protected fun iconTile(radius: Int): Drawable =
        rounded(ICON_TILE, radius).apply { setStroke(max(1, dp(1) * 2 / 3), ICON_TILE_EDGE) }

    /** A stock-style button: text in a [surface]. */
    protected fun button(label: String, click: View.OnClickListener): TextView {
        val b = text(label, 16, TEXT, false)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(14), 0, dp(14), 0)
        b.background = surface(dp(4))
        b.setOnClickListener(click)
        b.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(BAR_DP - 6))
            .apply { leftMargin = dp(8) }
        return b
    }

    private fun pageWidth(splitOn: Boolean): Int {
        val screenWidth = resources.displayMetrics.widthPixels
        return if (splitOn) min(SplitScreen.PANEL_LEFT_PX, screenWidth) else FrameLayout.LayoutParams.MATCH_PARENT
    }

    private fun fitToSplitScreen(on: Boolean) {
        val lp = page.layoutParams as FrameLayout.LayoutParams
        val width = pageWidth(on)
        if (lp.width != width) {
            lp.width = width
            page.layoutParams = lp
        }
        // with the panel off, its open handle sits over the right edge
        val right = if (on) dp(24) else SplitScreen.HANDLE_PX + dp(12)
        page.setPadding(page.paddingLeft, page.paddingTop, right, page.paddingBottom)
    }

    protected fun text(s: String, sp: Int, color: Int, bold: Boolean): TextView {
        val t = TextView(this)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
        t.setTextColor(color)
        t.typeface = font(bold)
        return t
    }

    protected fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        /** The head unit's highlight teal (an active button's glyph), the same day and night. */
        const val ACCENT = 0xFF17BEB0.toInt()

        /** The head unit's day icon gradient, top to bottom (measured from the stock icons). */
        const val ICON_START = 0xFF34E0D5.toInt()
        const val ACCENT_END = 0xFF847FDC.toInt()
        const val GOOD = 0xFF2ECC71.toInt()
        const val WARN = 0xFFF1B336.toInt()
        const val BAD = 0xFFEB5757.toInt()
        const val IDLE = 0xFF6E7884.toInt()

        /** Height of the title row and its buttons. */
        const val BAR_DP = 36

        private var regular: Typeface? = null
        private var bold: Typeface? = null

        /** True when the car is showing its night (dark) theme. */
        @JvmStatic
        fun isNight(context: Context): Boolean {
            val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return mode != Configuration.UI_MODE_NIGHT_NO // the head unit's default is night
        }

        /** The head unit's UI font (Mobis), falling back to the system one. */
        @JvmStatic
        fun font(isBold: Boolean): Typeface {
            if (regular == null) {
                regular = load("/system/fonts/Mobis-Regular.ttf", Typeface.DEFAULT)
                bold = load("/system/fonts/Mobis-Bold.ttf", Typeface.DEFAULT_BOLD)
            }
            return (if (isBold) bold else regular)!!
        }

        private fun load(path: String, fallback: Typeface): Typeface = try {
            if (File(path).isFile) Typeface.createFromFile(path) else fallback
        } catch (ignored: RuntimeException) {
            // createFromFile's "native typeface cannot be made"
            fallback
        }

        @JvmStatic
        fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
            setColor(color)
            setCornerRadius(radius.toFloat())
        }
    }
}
