package app.cabinwright.menu

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.cabinwright.api.contract.MenuContract
import app.cabinwright.api.ui.CarPage
import java.util.Random
import kotlin.math.max

/**
 * Edit Menu: All Menus as an editable grid, like a phone launcher's edit mode.
 * Long-press and drag a tile to move it, tap its x to hide it; "Add apps" swaps the grid
 * for the hidden ones, tap one to put it back. Every change is saved and the launcher
 * reloads right away.
 */
class MenuActivity : CarPage() {
    private val handler = Handler()
    private val random = Random()
    private val wiggles = ArrayList<ObjectAnimator>()
    private lateinit var store: MenuStore
    private lateinit var look: EntryLook

    /** Every entry in menu order, hidden ones included. */
    private var items = mutableListOf<MenuStore.Entry>()
    private var hidden = mutableSetOf<String>()
    private var adding = false
    private lateinit var grid: TileGrid
    private lateinit var hiddenGrid: TileGrid
    private lateinit var gridScroll: ScrollView
    private lateinit var hiddenScroll: ScrollView
    private lateinit var hint: TextView
    private lateinit var addButton: TextView
    private lateinit var resetButton: TextView
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = MenuStore(this)
        look = EntryLook(this)

        val bar = headerBar("Edit Menu")
        hint = text("", 15, MUTED, false)
        hint.setPadding(dp(16), 0, 0, 0)
        bar.addView(hint, 1)
        addButton = button("") {
            adding = !adding
            render()
        }
        bar.addView(addButton)
        resetButton = button("Reset") {
            store.reset()
            adding = false
            load()
            reloadLauncher()
        }
        bar.addView(resetButton)

        empty = text("", 18, MUTED, false)
        empty.setPadding(0, dp(16), 0, 0)
        page.addView(empty)

        gridScroll = ScrollView(this)
        grid = TileGrid(this, dp(150), dp(114), dp(48))
        grid.scroller = gridScroll
        grid.onReordered = { reorder(it) }
        gridScroll.addView(grid)
        page.addView(gridScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        hiddenScroll = ScrollView(this)
        hiddenGrid = TileGrid(this, dp(150), dp(114), dp(48))
        hiddenScroll.addView(hiddenGrid)
        page.addView(hiddenScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    override fun onResume() {
        super.onResume()
        look.refresh()
        load()
        // ask the launcher to reload, which also reports its current entries to us
        val before = store.catalogTime()
        reloadLauncher()
        handler.postDelayed({ if (store.catalogTime() != before) load() }, CATALOG_WAIT_MS)
    }

    override fun onPause() {
        handler.removeCallbacksAndMessages(null)
        stopWiggles()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (adding) {
            adding = false
            render()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun load() {
        items = store.arranged()
        hidden = store.hidden()
        if (items.isEmpty()) {
            empty.text = "Waiting for the launcher to list its apps. This needs the launcher hook installed."
            empty.visibility = View.VISIBLE
        } else {
            empty.visibility = View.GONE
        }
        render()
    }

    // ---- changes

    private fun hide(e: MenuStore.Entry) {
        hidden.add(e.key)
        save()
    }

    private fun unhide(e: MenuStore.Entry) {
        hidden.remove(e.key)
        items.remove(e)
        items.add(e) // back at the end
        save()
    }

    /** After a drag: shown tiles in the grid's order, hidden ones after them. */
    private fun reorder(shownKeys: List<String>) {
        val byKey = items.associateByTo(HashMap()) { it.key }
        val next = shownKeys.mapNotNullTo(ArrayList()) { byKey.remove(it) }
        items.filterTo(next) { it.key in byKey }
        items = next
        store.save(items.map { it.key }, hidden)
        reloadLauncher()
    }

    private fun save() {
        store.save(items.map { it.key }, hidden)
        render()
        reloadLauncher()
    }

    private fun reloadLauncher() {
        sendBroadcast(Intent(MenuContract.ACTION_RELOAD))
    }

    // ---- views

    private fun render() {
        stopWiggles()
        grid.removeAllViews()
        hiddenGrid.removeAllViews()
        for (e in items) {
            if (isShown(e)) grid.addView(tile(e)) else hiddenGrid.addView(hiddenTile(e))
        }
        val n = hiddenGrid.childCount
        if (n == 0) adding = false
        showMode(n)
    }

    /** The header and which grid shows, adding or arranging, with [n] apps hidden. */
    private fun showMode(n: Int) {
        gridScroll.visibility = visibleIf(!adding)
        hiddenScroll.visibility = visibleIf(adding)
        resetButton.visibility = visibleIf(!adding)
        hint.text = if (adding) "Tap an app to add it back" else "Hold and drag to move  ·  ✕ to hide"
        addButton.text = when {
            adding -> "Done"
            n == 0 -> "Nothing hidden"
            else -> "Add apps ($n)"
        }
        val usable = adding || n > 0
        addButton.isEnabled = usable
        addButton.alpha = if (usable) 1f else 0.45f
    }

    private fun visibleIf(shown: Boolean) = if (shown) View.VISIBLE else View.GONE

    private fun isShown(e: MenuStore.Entry) = MenuContract.isProtected(e.key) || e.key !in hidden

    /** A grid cell: stock-style tile with the icon, label underneath, x badge on the corner. */
    private fun tile(e: MenuStore.Entry): View {
        val cell = cell(e, 1f)
        if (!MenuContract.isProtected(e.key)) {
            val x = badge("✕", false)
            x.setOnClickListener { hide(e) }
            cell.addView(x, cornerBadge())
        }
        cell.setOnLongClickListener {
            grid.startDrag(cell)
            true
        }
        return cell
    }

    private fun hiddenTile(e: MenuStore.Entry): View {
        val cell = cell(e, 0.55f)
        cell.addView(badge("+", true), cornerBadge())
        cell.setOnClickListener { unhide(e) }
        return cell
    }

    private fun cell(e: MenuStore.Entry, iconAlpha: Float): FrameLayout {
        val cell = FrameLayout(this)
        cell.tag = e.key
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setGravity(Gravity.CENTER_HORIZONTAL)
        column.setPadding(dp(4), dp(14), dp(4), 0)
        val square = FrameLayout(this)
        square.background = iconTile(dp(16))
        val icon = ImageView(this)
        icon.setImageDrawable(look.icon(e))
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        icon.alpha = iconAlpha
        square.addView(icon, FrameLayout.LayoutParams(dp(62), dp(62), Gravity.CENTER))
        column.addView(square, LinearLayout.LayoutParams(dp(72), dp(72)))
        val name = text(look.label(e), 17, if (iconAlpha < 1f) MUTED else TEXT, false)
        name.gravity = Gravity.CENTER_HORIZONTAL
        name.setSingleLine(true)
        name.ellipsize = TextUtils.TruncateAt.END
        name.setPadding(0, dp(5), 0, 0)
        column.addView(
            name,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        cell.addView(
            column,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        if (iconAlpha == 1f) wiggle(square)
        return cell
    }

    /** Centred on the tile's top-right corner (a centred child is offset by leftMargin). */
    private fun cornerBadge() =
        FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { leftMargin = dp(36) }

    /** The round corner badge: grey x to hide, teal + to add. */
    private fun badge(glyph: String, add: Boolean): TextView {
        val b = text(glyph, 15, if (add) BG else TEXT, true)
        b.gravity = Gravity.CENTER
        val d = rounded(if (add) ACCENT else TILE, dp(15))
        if (!add) d.setStroke(max(1, dp(1) * 2 / 3), EDGE)
        b.background = d
        return b
    }

    /** The gentle edit-mode wobble modern launchers do. */
    private fun wiggle(v: View) {
        val a = ObjectAnimator.ofFloat(v, "rotation", -1.6f, 1.6f)
        a.duration = 150L + random.nextInt(60)
        a.repeatMode = ValueAnimator.REVERSE
        a.repeatCount = ValueAnimator.INFINITE
        a.startDelay = random.nextInt(150).toLong()
        a.start()
        wiggles.add(a)
    }

    private fun stopWiggles() {
        wiggles.forEach { it.cancel() }
        wiggles.clear()
    }

    private companion object {
        const val CATALOG_WAIT_MS = 4000L
    }
}
