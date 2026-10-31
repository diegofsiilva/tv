package io.canais;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The home: the title, the tabs (Ao vivo, Filmes, Séries, and Buscar, which opens the search), the selected tab's
 * categories as chips and a grid of channels or posters. Moving through the chips loads that category after a short
 * pause; OK on a channel plays it, on a movie or series opens its page; a long OK adds it to (or removes it from)
 * the favorites, the first chip. Atualizar fetches everything again; Seções (or a long OK on a chip) hides
 * categories. Menu changes the account.
 */
public class HomeActivity extends Activity {
    private static final String[] TABS = {"Ao vivo", "Filmes", "Séries"};

    /** The list handed to the player (too big for an Intent). */
    static List<Xtream.Item> playing = new ArrayList<>();

    private Xtream account;
    private int tab = Xtream.LIVE;
    private final TextView[] tabViews = new TextView[3];
    private LinearLayout chips;
    private HorizontalScrollView chipScroll;
    private GridView grid;
    private TextView status;
    private final List<Xtream.Item> cats = new ArrayList<>(), items = new ArrayList<>();
    private final Map<Integer, List<Xtream.Item>> catCache = new HashMap<>();
    private final Map<String, List<Xtream.Item>> itemCache = new HashMap<>();
    private int shownCat = -1, wantedCat = -1, loadToken;
    private Ui.Card focused;
    private final Runnable loadWanted = this::loadItems;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        account = Xtream.saved(this);
        if (account == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        int side = Ui.dp(this, 48);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.background());
        root.setPadding(0, Ui.dp(this, 30), 0, 0);
        root.setClipChildren(false);
        root.setClipToPadding(false);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(side, 0, side, 0);
        top.addView(Ui.text(this, "Canais", 34, Ui.INK, Ui.BOLD), new LinearLayout.LayoutParams(0, -2, 1));
        status = Ui.text(this, "", 13, Ui.FAINT, null);
        top.addView(status);
        root.addView(top);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(side - Ui.dp(this, 18), Ui.dp(this, 14), side, 0);
        tabs.setClipChildren(false);
        tabs.setClipToPadding(false);
        for (int i = 0; i < 3; i++) {
            final int index = i;
            TextView t = Ui.pill(this, TABS[i], 17);
            t.setOnFocusChangeListener((v, has) -> {
                Ui.choose(t, index == tab);
                if (has && index != tab) selectTab(index);
            });
            t.setOnClickListener(v -> chipFocus());
            tabViews[i] = t;
            tabs.addView(t);
        }
        // Search: a tab that opens its own screen (focusing it changes nothing below).
        TextView search = Ui.pill(this, "Buscar", 17);
        search.setOnClickListener(v -> startActivity(new Intent(this, SearchActivity.class)));
        tabs.addView(search);
        TextView refresh = Ui.pill(this, "Atualizar", 17);
        refresh.setOnClickListener(v -> refresh());
        tabs.addView(refresh);
        TextView sections = Ui.pill(this, "Seções", 17);
        sections.setOnClickListener(v -> chooseSections());
        tabs.addView(sections);
        // Down from the top row goes to the first chip (the favorites), not the one that happens to be below.
        for (int i = 0; i < tabs.getChildCount(); i++) {
            tabs.getChildAt(i).setOnKeyListener((v, keyCode, e) -> {
                if (keyCode != KeyEvent.KEYCODE_DPAD_DOWN || chips.getChildCount() == 0) return false;
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    chipScroll.scrollTo(0, 0);
                    chips.getChildAt(0).requestFocus();
                }
                return true;
            });
        }
        root.addView(tabs);

        chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setClipChildren(false);
        chipScroll.setClipToPadding(false);
        chipScroll.setPadding(side - Ui.dp(this, 18), Ui.dp(this, 4), side, Ui.dp(this, 4));
        chips = new LinearLayout(this);
        chips.setClipChildren(false);
        chipScroll.addView(chips);
        root.addView(chipScroll);

        grid = new GridView(this);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0));
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setStretchMode(GridView.NO_STRETCH);
        grid.setPadding(side, Ui.dp(this, 18), side, Ui.dp(this, 30));
        grid.setVerticalScrollBarEnabled(false);
        grid.setAdapter(adapter);
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                focusCard(view);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                focusCard(null);
            }
        });
        grid.setOnFocusChangeListener((v, has) -> focusCard(has ? grid.getSelectedView() : null));
        grid.setOnItemClickListener((p, v, position, id) -> open(position));
        grid.setOnItemLongClickListener((p, v, position, id) -> {
            toggleFavorite(position);
            return true;
        });
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        layoutGrid();
        for (int i = 0; i < 3; i++) Ui.choose(tabViews[i], i == tab);
        loadCategories();
        tabViews[tab].requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (grid == null) return;
        // Back from a page where the favorite may have changed.
        if (showingFavorites()) showItems(shownCat, cats.get(shownCat), Favorites.list(this, tab));
        else adapter.notifyDataSetChanged();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            startActivity(new Intent(this, LoginActivity.class).putExtra("change", true));
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // MARK: - Tabs and categories

    private void selectTab(int index) {
        tab = index;
        for (int i = 0; i < 3; i++) Ui.choose(tabViews[i], i == tab);
        layoutGrid();
        shownCat = wantedCat = -1;
        items.clear();
        adapter.notifyDataSetChanged();
        loadCategories();
    }

    /** Channels: 5 logos across; movies and series: 7 posters. */
    private void layoutGrid() {
        boolean live = tab == Xtream.LIVE;
        int cols = live ? 5 : 7, gap = Ui.dp(this, live ? 24 : 20);
        int avail = getResources().getDisplayMetrics().widthPixels - 2 * Ui.dp(this, 48);
        int w = (avail - gap * (cols - 1)) / cols;
        grid.setNumColumns(cols);
        grid.setColumnWidth(w);
        grid.setHorizontalSpacing(gap);
        grid.setVerticalSpacing(Ui.dp(this, 22));
        cardW = w;
        cardH = live ? w * 9 / 16 : w * 3 / 2;
    }

    private int cardW, cardH;

    private void loadCategories() {
        final int kind = tab;
        List<Xtream.Item> cached = catCache.get(kind);
        if (cached != null) {
            showCategories(cached);
            return;
        }
        status.setText("Carregando…");
        Ui.IO.execute(() -> {
            List<Xtream.Item> got;
            try {
                got = account.categories(kind);
            } catch (Exception e) {
                got = null;
            }
            final List<Xtream.Item> list = got;
            Ui.MAIN.post(() -> {
                if (isFinishing() || kind != tab) return;
                if (list == null) {
                    status.setText("Sem resposta do servidor · Menu: trocar a conta");
                    return;
                }
                catCache.put(kind, list);
                showCategories(list);
            });
        });
    }

    /** Drops what's kept (categories, channels and the search's catalog) and loads the tab from the server. */
    private void refresh() {
        Ui.MAIN.removeCallbacks(loadWanted);
        catCache.clear();
        itemCache.clear();
        Ui.IO.execute(() -> Catalog.forget(getApplicationContext()));
        loadToken++;
        shownCat = wantedCat = -1;
        cats.clear();
        chips.removeAllViews();
        items.clear();
        adapter.notifyDataSetChanged();
        loadCategories();
    }

    /**
     * Which tab's sections: reaching Seções moves the focus across the tabs, which changes the tab, so it asks
     * instead of taking the current one.
     */
    private void chooseSections() {
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("Seções")
                .setItems(TABS, (d, which) -> sections(which))
                .show();
    }

    /** That tab's categories (fetched first if needed), with a check on the ones shown; unchecking hides it. */
    private void sections(final int kind) {
        final List<Xtream.Item> all = catCache.get(kind);
        if (all == null) {
            status.setText("Carregando…");
            Ui.IO.execute(() -> {
                List<Xtream.Item> got;
                try {
                    got = account.categories(kind);
                } catch (Exception e) {
                    got = null;
                }
                final List<Xtream.Item> list = got;
                Ui.MAIN.post(() -> {
                    if (isFinishing()) return;
                    status.setText("");
                    if (list == null) {
                        status.setText("Sem resposta do servidor");
                        return;
                    }
                    catCache.put(kind, list);
                    sections(kind);
                });
            });
            return;
        }
        if (all.isEmpty()) {
            android.widget.Toast.makeText(this, TABS[kind] + " não tem categorias", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final java.util.Set<String> hidden = Hidden.get(this, kind);
        String[] names = new String[all.size()];
        boolean[] shown = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) {
            names[i] = all.get(i).name;
            shown[i] = !hidden.contains(all.get(i).id);
        }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("Seções de " + TABS[kind])
                .setMultiChoiceItems(names, shown, (d, which, on) -> {
                    if (on) hidden.remove(all.get(which).id);
                    else hidden.add(all.get(which).id);
                })
                .setPositiveButton("Pronto", null)
                .setOnDismissListener(d -> {
                    Hidden.set(this, kind, hidden);
                    if (kind == tab && !isFinishing()) showCategories(all);
                })
                .show();
    }

    private void hideCategory(int index) {
        if (index <= 0 || index >= cats.size()) return;   // the favorites always stay
        Xtream.Item cat = cats.get(index);
        Hidden.hide(this, tab, cat.id);
        android.widget.Toast.makeText(this, cat.name + " oculta · Seções traz de volta", android.widget.Toast.LENGTH_SHORT).show();
        List<Xtream.Item> all = catCache.get(tab);
        if (all != null) showCategories(all);
        chipFocus();
    }

    private void showCategories(List<Xtream.Item> list) {
        java.util.Set<String> hidden = Hidden.get(this, tab);
        cats.clear();
        cats.add(new Xtream.Item(Favorites.CATEGORY, "♥  Favoritos"));
        for (Xtream.Item c : list) if (!hidden.contains(c.id)) cats.add(c);
        shownCat = -1;
        chips.removeAllViews();
        for (int i = 0; i < cats.size(); i++) {
            final int index = i;
            TextView t = Ui.pill(this, cats.get(i).name, 14);
            t.setOnFocusChangeListener((v, has) -> {
                Ui.choose(t, index == wantedCat);
                if (has && index != wantedCat) {
                    chooseChip(index);
                    Ui.MAIN.removeCallbacks(loadWanted);
                    Ui.MAIN.postDelayed(loadWanted, 400);
                }
            });
            t.setOnClickListener(v -> {
                Ui.MAIN.removeCallbacks(loadWanted);
                loadItems();
                grid.requestFocus();
            });
            t.setOnLongClickListener(v -> {
                hideCategory(index);
                return true;
            });
            chips.addView(t);
        }
        chipScroll.scrollTo(0, 0);
        int off = list.size() - (cats.size() - 1);
        status.setText((cats.size() - 1) + " categorias" + (off > 0 ? " · " + off + " ocultas" : ""));
        // Favorites first when there are any; otherwise the server's first category.
        int first = Favorites.list(this, tab).isEmpty() && cats.size() > 1 ? 1 : 0;
        chooseChip(first);
        loadItems();
    }

    private void chooseChip(int index) {
        wantedCat = index;
        for (int i = 0; i < chips.getChildCount(); i++) Ui.choose((TextView) chips.getChildAt(i), i == index);
    }

    private void chipFocus() {
        int i = Math.max(0, wantedCat);
        if (i < chips.getChildCount()) chips.getChildAt(i).requestFocus();
    }

    private void loadItems() {
        final int index = wantedCat, kind = tab;
        if (index < 0 || index >= cats.size() || index == shownCat) return;
        final Xtream.Item cat = cats.get(index);
        final String key = kind + ":" + cat.id;
        final int token = ++loadToken;
        if (cat.id.equals(Favorites.CATEGORY)) {
            showItems(index, cat, Favorites.list(this, kind));
            return;
        }
        List<Xtream.Item> cached = itemCache.get(key);
        if (cached != null) {
            showItems(index, cat, cached);
            return;
        }
        status.setText("Carregando " + cat.name + "…");
        Ui.IO.execute(() -> {
            List<Xtream.Item> got;
            try {
                got = account.items(kind, cat.id);
            } catch (Exception e) {
                got = null;
            }
            final List<Xtream.Item> list = got;
            Ui.MAIN.post(() -> {
                if (isFinishing() || token != loadToken || kind != tab) return;   // the person already moved on
                if (list == null) {
                    status.setText("Não consegui carregar " + cat.name);
                    return;
                }
                itemCache.put(key, list);
                showItems(index, cat, list);
            });
        });
    }

    private void showItems(int index, Xtream.Item cat, List<Xtream.Item> list) {
        shownCat = index;
        items.clear();
        items.addAll(list);
        adapter.notifyDataSetChanged();
        grid.setSelection(0);
        String what = tab == Xtream.LIVE ? " canais" : tab == Xtream.MOVIES ? " filmes" : " séries";
        status.setText(list.isEmpty() && cat.id.equals(Favorites.CATEGORY)
                ? "Segure OK num cartaz ou canal para adicionar aos favoritos"
                : cat.name.replace("♥  ", "") + " · " + list.size() + what);
    }

    // MARK: - Grid

    private void focusCard(View v) {
        Ui.Card c = v == null || !grid.hasFocus() ? null : (Ui.Card) v.getTag();
        if (c == focused) return;
        if (focused != null) focused.focus(false);
        focused = c;
        if (c != null) c.focus(true);
    }

    private boolean showingFavorites() {
        return shownCat >= 0 && shownCat < cats.size() && cats.get(shownCat).id.equals(Favorites.CATEGORY);
    }

    private void toggleFavorite(int position) {
        if (position < 0 || position >= items.size()) return;
        Xtream.Item it = items.get(position);
        boolean added = Favorites.toggle(this, tab, it);
        android.widget.Toast.makeText(this, added ? "♥  Adicionado aos favoritos" : "Removido dos favoritos",
                android.widget.Toast.LENGTH_SHORT).show();
        if (showingFavorites()) {
            showItems(shownCat, cats.get(shownCat), Favorites.list(this, tab));
            grid.setSelection(Math.min(position, items.size() - 1));
        } else {
            adapter.notifyDataSetChanged();
        }
    }

    private void open(int position) {
        if (position < 0 || position >= items.size()) return;
        Xtream.Item it = items.get(position);
        if (tab == Xtream.LIVE) {
            playing = new ArrayList<>(items);
            startActivity(new Intent(this, PlayerActivity.class)
                    .putExtra("kind", Xtream.LIVE).putExtra("index", position));
        } else {
            DetailActivity.item = it;
            startActivity(new Intent(this, DetailActivity.class).putExtra("kind", tab));
        }
    }

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return tab == Xtream.LIVE ? 0 : 1;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            boolean live = tab == Xtream.LIVE;
            Ui.Card card = convert == null ? null : (Ui.Card) convert.getTag();
            if (card == null || card.frame.getLayoutParams().width != cardW) {
                card = new Ui.Card(HomeActivity.this, cardW, cardH, live);
                card.root.setTag(card);
            }
            Xtream.Item it = items.get(position);
            card.title.setText(Favorites.contains(HomeActivity.this, tab, it.id) ? "♥ " + it.name : it.name);
            card.sub.setText(it.meta);
            card.sub.setVisibility(live || it.meta.isEmpty() ? View.GONE : View.VISIBLE);
            card.placeholder.setText(it.name);
            Images.load(card.image, it.image, cardW, cardH, card.placeholder);
            boolean on = grid.hasFocus() && position == grid.getSelectedItemPosition();
            card.frame.setScaleX(on ? 1.08f : 1f);
            card.frame.setScaleY(on ? 1.08f : 1f);
            if (on) focused = card;
            return card.root;
        }
    };
}
