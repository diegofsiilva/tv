package io.canais;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Search: the box at the top, the results below in three rows (channels, movies, series). Results follow the typing
 * after a short pause; OK on the keyboard jumps to the first result.
 */
public class SearchActivity extends Activity {
    private static final int MAX = 30;
    private static final String[] TITLES = {"Canais", "Filmes", "Séries"};

    private Xtream account;
    private EditText box;
    private TextView status;
    private final LinearLayout[] rows = new LinearLayout[3];
    private final TextView[] titles = new TextView[3];
    private final HorizontalScrollView[] scrolls = new HorizontalScrollView[3];
    private final List<List<Xtream.Item>> results = new ArrayList<>();
    private final Runnable run = this::search;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        account = Xtream.saved(this);
        if (account == null) {
            finish();
            return;
        }
        int side = Ui.dp(this, 48);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(Ui.background());
        scroll.setClipChildren(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        col.setPadding(side, Ui.dp(this, 30), side, Ui.dp(this, 40));
        scroll.addView(col);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Ui.text(this, "Buscar", 34, Ui.INK, Ui.BOLD), new LinearLayout.LayoutParams(0, -2, 1));
        status = Ui.text(this, "", 13, Ui.FAINT, null);
        top.addView(status);
        col.addView(top);

        box = new EditText(this);
        box.setHint("Nome do canal, filme ou série");
        box.setSingleLine(true);
        box.setTextSize(20);
        box.setTextColor(Ui.INK);
        box.setHintTextColor(Ui.FAINT);
        box.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 12), p, Ui.dp(this, 12));
        box.setBackground(Ui.round(Ui.WHITE, Ui.dp(this, 40)));
        box.setElevation(Ui.dp(this, 4));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(Ui.dp(this, 620), -2);
        bl.topMargin = Ui.dp(this, 18);
        col.addView(box, bl);
        box.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                Ui.MAIN.removeCallbacks(run);
                Ui.MAIN.postDelayed(run, 350);
            }
        });
        box.setOnEditorActionListener((v, action, e) -> {
            Ui.MAIN.removeCallbacks(run);
            search();
            hideKeyboard();
            focusFirst();
            return true;
        });

        for (int k = 0; k < 3; k++) {
            titles[k] = Ui.text(this, TITLES[k], 18, Ui.INK, Ui.BOLD);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
            tl.topMargin = Ui.dp(this, 26);
            col.addView(titles[k], tl);
            scrolls[k] = new HorizontalScrollView(this);
            scrolls[k].setHorizontalScrollBarEnabled(false);
            scrolls[k].setClipChildren(false);
            scrolls[k].setClipToPadding(false);
            scrolls[k].setPadding(0, Ui.dp(this, 14), side, Ui.dp(this, 4));
            rows[k] = new LinearLayout(this);
            rows[k].setClipChildren(false);
            scrolls[k].addView(rows[k]);
            col.addView(scrolls[k], new LinearLayout.LayoutParams(-1, -2));
            titles[k].setVisibility(View.GONE);
            scrolls[k].setVisibility(View.GONE);
            results.add(new ArrayList<>());
        }
        setContentView(scroll);
        box.requestFocus();
        loadCatalog();
    }

    private void loadCatalog() {
        if (Catalog.ready()) return;
        status.setText("Carregando o catálogo… (só demora na primeira vez)");
        final Context c = getApplicationContext();
        Ui.IO.execute(() -> {
            String error = null;
            try {
                Catalog.load(c, account);
            } catch (Exception e) {
                error = "Não consegui carregar o catálogo. Voltar e tentar de novo.";
            }
            final String msg = error;
            Ui.MAIN.post(() -> {
                if (isFinishing()) return;
                status.setText(msg == null ? "" : msg);
                if (msg == null) search();
            });
        });
    }

    private void search() {
        String q = box.getText().toString();
        if (!Catalog.ready()) return;
        int total = 0;
        for (int k = 0; k < 3; k++) {
            List<Xtream.Item> got = Catalog.search(k, q, MAX);
            results.set(k, got);
            fill(k, got);
            total += got.size();
        }
        status.setText(Catalog.normalize(q).isEmpty() ? "" : total == 0 ? "Nada encontrado" : "");
    }

    private void fill(int kind, List<Xtream.Item> list) {
        LinearLayout row = rows[kind];
        row.removeAllViews();
        boolean live = kind == Xtream.LIVE;
        int w = Ui.dp(this, live ? 150 : 104), h = live ? w * 9 / 16 : w * 3 / 2, gap = Ui.dp(this, 18);
        for (int i = 0; i < list.size(); i++) {
            final int index = i;
            Xtream.Item it = list.get(i);
            Ui.Card card = new Ui.Card(this, w, h, live);
            card.title.setText(Favorites.contains(this, kind, it.id) ? "♥ " + it.name : it.name);
            card.sub.setText(it.meta);
            card.sub.setVisibility(live || it.meta.isEmpty() ? View.GONE : View.VISIBLE);
            card.placeholder.setText(it.name);
            Images.load(card.image, it.image, w, h, card.placeholder);
            card.root.setFocusable(true);
            card.root.setOnFocusChangeListener((v, has) -> card.focus(has));
            card.root.setOnClickListener(v -> open(kind, index));
            card.root.setOnLongClickListener(v -> {
                boolean added = Favorites.toggle(this, kind, it);
                card.title.setText(added ? "♥ " + it.name : it.name);
                android.widget.Toast.makeText(this, added ? "♥  Adicionado aos favoritos" : "Removido dos favoritos",
                        android.widget.Toast.LENGTH_SHORT).show();
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) lp.leftMargin = gap;
            row.addView(card.root, lp);
        }
        int vis = list.isEmpty() ? View.GONE : View.VISIBLE;
        titles[kind].setText(TITLES[kind] + "  ·  " + list.size() + (list.size() == MAX ? "+" : ""));
        titles[kind].setVisibility(vis);
        scrolls[kind].setVisibility(vis);
        scrolls[kind].scrollTo(0, 0);
    }

    private void open(int kind, int index) {
        List<Xtream.Item> list = results.get(kind);
        if (index >= list.size()) return;
        if (kind == Xtream.LIVE) {
            HomeActivity.playing = new ArrayList<>(list);
            startActivity(new Intent(this, PlayerActivity.class).putExtra("kind", Xtream.LIVE).putExtra("index", index));
        } else {
            DetailActivity.item = list.get(index);
            startActivity(new Intent(this, DetailActivity.class).putExtra("kind", kind));
        }
    }

    private void focusFirst() {
        for (LinearLayout row : rows) {
            if (row.getChildCount() > 0 && row.isShown()) {
                row.getChildAt(0).requestFocus();
                return;
            }
        }
    }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(box.getWindowToken(), 0);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // ↓ from the box goes to the results (the keyboard closes first, if open).
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && box.hasFocus()) {
            hideKeyboard();
            focusFirst();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
