package app.cabinwright.menu

import android.animation.LayoutTransition
import android.content.Context
import android.view.DragEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import kotlin.math.max
import kotlin.math.min

/**
 * Tiles in fixed-size cells, as many columns as fit, reordered by dragging: while a tile
 * is dragged it moves to the cell under the finger and the others slide over (animated
 * layout changes). Each child's tag is its entry key.
 */
internal class TileGrid(
    context: Context,
    private val cellWidth: Int,
    private val cellHeight: Int,
    private val edge: Int,
) : ViewGroup(context),
    View.OnDragListener {
    /** Called when a drag finishes, with the tiles' keys in their new order. */
    var onReordered: ((List<String>) -> Unit)? = null

    /** The scroll view around this grid, scrolled while dragging near its edges. */
    var scroller: ScrollView? = null

    private var columns = 1
    private var columnWidth = 1
    private var dragging: View? = null

    init {
        layoutTransition = LayoutTransition().apply {
            // other tiles slide into place; the moved tile itself just jumps (it's invisible)
            disableTransitionType(LayoutTransition.APPEARING)
            disableTransitionType(LayoutTransition.DISAPPEARING)
            enableTransitionType(LayoutTransition.CHANGING)
            setDuration(160)
        }
        setOnDragListener(this)
    }

    fun startDrag(tile: View) {
        dragging = tile
        @Suppress("DEPRECATION") // startDragAndDrop is API 24
        tile.startDrag(null, View.DragShadowBuilder(tile), tile, 0)
        tile.visibility = INVISIBLE
    }

    fun keys(): List<String> = (0 until childCount).map { getChildAt(it).tag as String }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val width = MeasureSpec.getSize(widthSpec)
        columns = max(1, width / cellWidth)
        columnWidth = max(1, width / columns)
        val rows = (childCount + columns - 1) / columns
        val cw = MeasureSpec.makeMeasureSpec(columnWidth, MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(cw, ch)
        setMeasuredDimension(width, rows * cellHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val x = (i % columns) * columnWidth
            val y = (i / columns) * cellHeight
            getChildAt(i).layout(x, y, x + columnWidth, y + cellHeight)
        }
    }

    private fun indexAt(x: Float, y: Float): Int {
        val col = min(columns - 1, max(0, (x / columnWidth).toInt()))
        val row = max(0, (y / cellHeight).toInt())
        return min(childCount - 1, row * columns + col)
    }

    override fun onDrag(v: View, event: DragEvent): Boolean {
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> return dragging != null
            DragEvent.ACTION_DRAG_LOCATION -> dragging?.let { d ->
                val to = indexAt(event.x, event.y)
                if (to >= 0 && to != indexOfChild(d)) {
                    removeView(d)
                    addView(d, to)
                }
                autoScroll(event.y)
            }
            DragEvent.ACTION_DROP, DragEvent.ACTION_DRAG_ENDED -> finishDrag()
        }
        return true
    }

    private fun autoScroll(y: Float) {
        val s = scroller ?: return
        val inView = y - s.scrollY
        if (inView < edge) {
            s.smoothScrollBy(0, -cellHeight / 4)
        } else if (inView > s.height - edge) {
            s.smoothScrollBy(0, cellHeight / 4)
        }
    }

    private fun finishDrag() {
        val d = dragging ?: return
        d.visibility = VISIBLE
        dragging = null
        onReordered?.invoke(keys())
    }
}
