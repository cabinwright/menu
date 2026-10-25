package dev.kiamenu.editor;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import dev.kiamenu.api.MenuContract;
import dev.kiamenu.api.ui.KiaPage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Edit Menu: Kia's All Menus as an editable grid, like a phone launcher's edit mode.
 * Long-press and drag a tile to move it, tap its x to hide it; "Add apps" swaps the grid
 * for the hidden ones, tap one to put it back. Every change is saved and the launcher
 * reloads right away.
 */
public class MenuActivity extends KiaPage {
    private static final long CATALOG_WAIT_MS = 4000;

    private final Handler handler = new Handler();
    private final Random random = new Random();
    private final List<ObjectAnimator> wiggles = new ArrayList<ObjectAnimator>();
    private MenuStore store;
    private EntryLook look;
    /** Every entry in menu order, hidden ones included. */
    private List<MenuStore.Entry> items = new ArrayList<MenuStore.Entry>();
    private Set<String> hidden;
    private boolean adding;
    private TileGrid grid;
    private TileGrid hiddenGrid;
    private ScrollView gridScroll;
    private ScrollView hiddenScroll;
    private TextView hint;
    private TextView addButton;
    private TextView resetButton;
    private TextView empty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new MenuStore(this);
        look = new EntryLook(this);

        LinearLayout bar = headerBar("Edit Menu");
        hint = text("", 15, MUTED, false);
        hint.setPadding(dp(16), 0, 0, 0);
        bar.addView(hint, 1);
        addButton = button("", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                adding = !adding;
                render();
            }
        });
        bar.addView(addButton);
        resetButton = button("Reset", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                store.reset();
                adding = false;
                load();
                reloadLauncher();
            }
        });
        bar.addView(resetButton);

        empty = text("", 18, MUTED, false);
        empty.setPadding(0, dp(16), 0, 0);
        page.addView(empty);

        gridScroll = new ScrollView(this);
        grid = new TileGrid(this, dp(150), dp(114), dp(48));
        grid.setScroller(gridScroll);
        grid.setListener(new TileGrid.Listener() {
            @Override
            public void onReordered(List<String> keys) {
                reorder(keys);
            }
        });
        gridScroll.addView(grid);
        page.addView(gridScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        hiddenScroll = new ScrollView(this);
        hiddenGrid = new TileGrid(this, dp(150), dp(114), dp(48));
        hiddenScroll.addView(hiddenGrid);
        page.addView(hiddenScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
    }

    @Override
    protected void onResume() {
        super.onResume();
        look.refresh();
        load();
        // ask the launcher to reload, which also reports its current entries to us
        final long before = store.catalogTime();
        reloadLauncher();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (store.catalogTime() != before) load();
            }
        }, CATALOG_WAIT_MS);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacksAndMessages(null);
        stopWiggles();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (adding) {
            adding = false;
            render();
            return;
        }
        super.onBackPressed();
    }

    private void load() {
        items = store.arranged();
        hidden = store.hidden();
        if (items.isEmpty()) {
            empty.setText("Waiting for the launcher to list its apps. This needs the kia-menu launcher hook installed.");
            empty.setVisibility(View.VISIBLE);
        } else {
            empty.setVisibility(View.GONE);
        }
        render();
    }

    // ---- changes

    private void hide(MenuStore.Entry e) {
        hidden.add(e.key);
        save();
    }

    private void unhide(MenuStore.Entry e) {
        hidden.remove(e.key);
        items.remove(e);
        items.add(e); // back at the end
        save();
    }

    /** After a drag: shown tiles in the grid's order, hidden ones after them. */
    private void reorder(List<String> shownKeys) {
        Map<String, MenuStore.Entry> byKey = new HashMap<String, MenuStore.Entry>();
        for (MenuStore.Entry e : items) byKey.put(e.key, e);
        List<MenuStore.Entry> next = new ArrayList<MenuStore.Entry>();
        for (String k : shownKeys) {
            MenuStore.Entry e = byKey.remove(k);
            if (e != null) next.add(e);
        }
        for (MenuStore.Entry e : items) {
            if (byKey.containsKey(e.key)) next.add(e);
        }
        items = next;
        store.save(keys(items), hidden);
        reloadLauncher();
    }

    private void save() {
        store.save(keys(items), hidden);
        render();
        reloadLauncher();
    }

    private static List<String> keys(List<MenuStore.Entry> entries) {
        List<String> out = new ArrayList<String>();
        for (MenuStore.Entry e : entries) out.add(e.key);
        return out;
    }

    private void reloadLauncher() {
        sendBroadcast(new Intent(MenuContract.ACTION_RELOAD));
    }

    // ---- views

    private void render() {
        stopWiggles();
        grid.removeAllViews();
        hiddenGrid.removeAllViews();
        for (MenuStore.Entry e : items) {
            if (isShown(e)) grid.addView(tile(e));
            else hiddenGrid.addView(hiddenTile(e));
        }
        int n = hiddenGrid.getChildCount();
        if (adding && n == 0) adding = false;
        gridScroll.setVisibility(adding ? View.GONE : View.VISIBLE);
        hiddenScroll.setVisibility(adding ? View.VISIBLE : View.GONE);
        hint.setText(adding ? "Tap an app to add it back" : "Hold and drag to move  ·  ✕ to hide");
        addButton.setText(adding ? "Done" : n == 0 ? "Nothing hidden" : "Add apps (" + n + ")");
        addButton.setEnabled(adding || n > 0);
        addButton.setAlpha(adding || n > 0 ? 1f : 0.45f);
        resetButton.setVisibility(adding ? View.GONE : View.VISIBLE);
    }

    private boolean isShown(MenuStore.Entry e) {
        return MenuContract.isProtected(e.key) || !hidden.contains(e.key);
    }

    /** A grid cell: Kia-style tile with the icon, label underneath, x badge on the corner. */
    private View tile(final MenuStore.Entry e) {
        final FrameLayout cell = cell(e, 1f);
        if (!MenuContract.isProtected(e.key)) {
            TextView x = badge("✕", false);
            x.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    hide(e);
                }
            });
            cell.addView(x, cornerBadge());
        }
        cell.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                grid.startDrag(cell);
                return true;
            }
        });
        return cell;
    }

    private View hiddenTile(final MenuStore.Entry e) {
        FrameLayout cell = cell(e, 0.55f);
        cell.addView(badge("+", true), cornerBadge());
        cell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                unhide(e);
            }
        });
        return cell;
    }

    private FrameLayout cell(MenuStore.Entry e, float iconAlpha) {
        FrameLayout cell = new FrameLayout(this);
        cell.setTag(e.key);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(dp(4), dp(14), dp(4), 0);
        FrameLayout square = new FrameLayout(this);
        square.setBackground(iconTile(dp(16)));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(look.icon(e));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setAlpha(iconAlpha);
        square.addView(icon, new FrameLayout.LayoutParams(dp(62), dp(62), Gravity.CENTER));
        column.addView(square, new LinearLayout.LayoutParams(dp(72), dp(72)));
        TextView name = text(look.label(e), 17, iconAlpha < 1f ? MUTED : TEXT, false);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(0, dp(5), 0, 0);
        column.addView(name, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        cell.addView(column, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        if (iconAlpha == 1f) wiggle(square);
        return cell;
    }

    /** Centred on the tile's top-right corner (a centred child is offset by leftMargin). */
    private FrameLayout.LayoutParams cornerBadge() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(30), dp(30),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.leftMargin = dp(36);
        return lp;
    }

    /** The round corner badge: grey x to hide, teal + to add. */
    private TextView badge(String glyph, boolean add) {
        TextView b = text(glyph, 15, add ? BG : TEXT, true);
        b.setGravity(Gravity.CENTER);
        GradientDrawable d = rounded(add ? ACCENT : TILE, dp(15));
        if (!add) d.setStroke(Math.max(1, dp(1) * 2 / 3), EDGE);
        b.setBackground(d);
        return b;
    }

    /** The gentle edit-mode wobble modern launchers do. */
    private void wiggle(View v) {
        ObjectAnimator a = ObjectAnimator.ofFloat(v, "rotation", -1.6f, 1.6f);
        a.setDuration(150 + random.nextInt(60));
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setStartDelay(random.nextInt(150));
        a.start();
        wiggles.add(a);
    }

    private void stopWiggles() {
        for (ObjectAnimator a : wiggles) a.cancel();
        wiggles.clear();
    }
}
