package dev.kiamenu.editor;

import android.animation.LayoutTransition;
import android.content.Context;
import android.view.DragEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

import java.util.ArrayList;
import java.util.List;

/**
 * Tiles in fixed-size cells, as many columns as fit, reordered by dragging: while a tile
 * is dragged it moves to the cell under the finger and the others slide over (animated
 * layout changes). Each child's tag is its entry key.
 */
final class TileGrid extends ViewGroup implements View.OnDragListener {
    interface Listener {
        /** A drag finished; keys are the tiles in their new order. */
        void onReordered(List<String> keys);
    }

    private final int cellWidth;
    private final int cellHeight;
    private final int edge;
    private int columns = 1;
    private int columnWidth = 1;
    private View dragging;
    private Listener listener;
    private ScrollView scroller;

    TileGrid(Context context, int cellWidth, int cellHeight, int autoScrollEdge) {
        super(context);
        this.cellWidth = cellWidth;
        this.cellHeight = cellHeight;
        this.edge = autoScrollEdge;
        LayoutTransition t = new LayoutTransition();
        // other tiles slide into place; the moved tile itself just jumps (it's invisible)
        t.disableTransitionType(LayoutTransition.APPEARING);
        t.disableTransitionType(LayoutTransition.DISAPPEARING);
        t.enableTransitionType(LayoutTransition.CHANGING);
        t.setDuration(160);
        setLayoutTransition(t);
        setOnDragListener(this);
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** The scroll view around this grid, scrolled while dragging near its edges. */
    void setScroller(ScrollView s) {
        scroller = s;
    }

    void startDrag(View tile) {
        dragging = tile;
        tile.startDrag(null, new View.DragShadowBuilder(tile), tile, 0);
        tile.setVisibility(INVISIBLE);
    }

    List<String> keys() {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < getChildCount(); i++) out.add((String) getChildAt(i).getTag());
        return out;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        columns = Math.max(1, width / cellWidth);
        columnWidth = Math.max(1, width / columns);
        int rows = (getChildCount() + columns - 1) / columns;
        int cw = MeasureSpec.makeMeasureSpec(columnWidth, MeasureSpec.EXACTLY);
        int ch = MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(cw, ch);
        setMeasuredDimension(width, rows * cellHeight);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            int x = (i % columns) * columnWidth;
            int y = (i / columns) * cellHeight;
            getChildAt(i).layout(x, y, x + columnWidth, y + cellHeight);
        }
    }

    private int indexAt(float x, float y) {
        int col = Math.min(columns - 1, Math.max(0, (int) (x / columnWidth)));
        int row = Math.max(0, (int) (y / cellHeight));
        return Math.min(getChildCount() - 1, row * columns + col);
    }

    @Override
    public boolean onDrag(View v, DragEvent event) {
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return dragging != null;
            case DragEvent.ACTION_DRAG_LOCATION:
                if (dragging != null) {
                    int to = indexAt(event.getX(), event.getY());
                    if (to >= 0 && to != indexOfChild(dragging)) {
                        removeView(dragging);
                        addView(dragging, to);
                    }
                    autoScroll(event.getY());
                }
                return true;
            case DragEvent.ACTION_DROP:
            case DragEvent.ACTION_DRAG_ENDED:
                finishDrag();
                return true;
            default:
                return true;
        }
    }

    private void autoScroll(float y) {
        if (scroller == null) return;
        float inView = y - scroller.getScrollY();
        if (inView < edge) scroller.smoothScrollBy(0, -cellHeight / 4);
        else if (inView > scroller.getHeight() - edge) scroller.smoothScrollBy(0, cellHeight / 4);
    }

    private void finishDrag() {
        if (dragging == null) return;
        dragging.setVisibility(VISIBLE);
        dragging = null;
        if (listener != null) listener.onReordered(keys());
    }
}
