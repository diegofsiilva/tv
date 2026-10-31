package io.canais;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A movie's or series' page: the backdrop fading into the light background, the title, the details and the plot,
 * Watch (or Resume); for a series, the seasons as chips and their episodes as a row of cards.
 */
public class DetailActivity extends Activity {
    static Xtream.Item item;    // handed over by HomeActivity

    private Xtream account;
    private int kind;
    private Xtream.Details details;
    private TextView meta, plot, watch, favorite, status;
    private ImageView backdrop;
    private LinearLayout seasons, episodes, lower;
    private HorizontalScrollView episodeScroll;
    private int season;
    private final List<Ui.Card> episodeCards = new ArrayList<>();

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        account = Xtream.saved(this);
        kind = getIntent().getIntExtra("kind", Xtream.MOVIES);
        if (account == null || item == null) {
            finish();
            return;
        }
        int side = Ui.dp(this, 48);
        FrameLayout root = new FrameLayout(this);
        root.setBackground(Ui.background());

        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        View fadeLeft = new View(this);
        fadeLeft.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xFFF5F5F7, 0xEBF5F5F7, 0x66F5F5F7, 0x00F5F5F7, 0x00F5F5F7}));
        root.addView(fadeLeft, new FrameLayout.LayoutParams(-1, -1));
        View fadeBottom = new View(this);
        fadeBottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {0xFFE2E2E7, kind == Xtream.SERIES ? 0xF2E4E4E9 : 0x99E4E4E9, 0x00E4E4E9}));
        // Only the lower part: the photo stays vivid at the top right; under a series' episodes it's almost solid.
        root.addView(fadeBottom, new FrameLayout.LayoutParams(-1, getResources().getDisplayMetrics().heightPixels
                * (kind == Xtream.SERIES ? 3 : 2) / 5, android.view.Gravity.BOTTOM));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(side, Ui.dp(this, 56), side, Ui.dp(this, 28));
        col.setClipChildren(false);
        col.setClipToPadding(false);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        TextView title = Ui.text(this, item.name, 36, Ui.INK, Ui.BOLD);
        title.setMaxLines(2);
        col.addView(title, new LinearLayout.LayoutParams(Ui.dp(this, 560), -2));
        meta = Ui.line(this, item.meta, 14, Ui.DIM, Ui.MEDIUM);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(Ui.dp(this, 560), -2);
        ml.topMargin = Ui.dp(this, 10);
        col.addView(meta, ml);
        plot = Ui.text(this, "", 15, Ui.INK, null);
        plot.setMaxLines(4);
        plot.setEllipsize(android.text.TextUtils.TruncateAt.END);
        plot.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(Ui.dp(this, 520), -2);
        pl.topMargin = Ui.dp(this, 16);
        col.addView(plot, pl);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setClipChildren(false);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.topMargin = Ui.dp(this, 22);
        bl.leftMargin = -Ui.dp(this, 4);
        col.addView(buttons, bl);
        watch = button(kind == Xtream.MOVIES ? "▶  Assistir" : "▶  Assistir episódio 1");
        watch.setVisibility(View.INVISIBLE);
        buttons.addView(watch);
        favorite = button("");
        favorite.setVisibility(View.INVISIBLE);
        favorite.setOnClickListener(v -> {
            Favorites.toggle(this, kind, item);
            updateFavorite();
        });
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-2, -2);
        fl.leftMargin = Ui.dp(this, 14);
        buttons.addView(favorite, fl);
        status = Ui.text(this, "Carregando…", 13, Ui.FAINT, null);
        status.setPadding(Ui.dp(this, 16), 0, 0, 0);
        status.setGravity(Gravity.CENTER_VERTICAL);
        buttons.addView(status, new LinearLayout.LayoutParams(-2, -1));

        // Series: seasons and episodes, at the bottom.
        lower = new LinearLayout(this);
        lower.setOrientation(LinearLayout.VERTICAL);
        lower.setClipChildren(false);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, 0, 1);
        col.addView(lower, ll);
        View spacer = new View(this);
        lower.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1));
        HorizontalScrollView seasonScroll = new HorizontalScrollView(this);
        seasonScroll.setHorizontalScrollBarEnabled(false);
        seasonScroll.setClipChildren(false);
        seasonScroll.setClipToPadding(false);
        seasonScroll.setPadding(-Ui.dp(this, 18), Ui.dp(this, 4), 0, Ui.dp(this, 4));
        seasons = new LinearLayout(this);
        seasons.setClipChildren(false);
        seasonScroll.addView(seasons);
        lower.addView(seasonScroll);
        episodeScroll = new HorizontalScrollView(this);
        episodeScroll.setHorizontalScrollBarEnabled(false);
        episodeScroll.setClipChildren(false);
        episodeScroll.setClipToPadding(false);
        episodeScroll.setPadding(0, Ui.dp(this, 14), Ui.dp(this, 48), Ui.dp(this, 6));
        episodes = new LinearLayout(this);
        episodes.setClipChildren(false);
        episodeScroll.addView(episodes);
        lower.addView(episodeScroll);
        lower.setVisibility(View.INVISIBLE);
        setContentView(root);

        Images.load(backdrop, item.image, 960, 540);
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (details == null) return;
        updateWatch();   // back from the player: Resume where it stopped
        paintEpisodes();
    }

    private TextView button(String label) {
        TextView b = Ui.pill(this, label, 16);
        b.setBackground(Ui.round(0x14000000, Ui.dp(this, 40)));
        b.setOnFocusChangeListener((v, has) -> {
            b.setBackground(Ui.round(has ? Ui.WHITE : 0x14000000, Ui.dp(this, 40)));
            b.setElevation(has ? Ui.dp(this, 6) : 0);
            float s = has ? 1.06f : 1f;
            b.animate().scaleX(s).scaleY(s).setDuration(140).start();
        });
        b.setTextColor(Ui.INK);
        b.setTypeface(Ui.BOLD);
        return b;
    }

    private void load() {
        Ui.IO.execute(() -> {
            Xtream.Details d;
            try {
                d = kind == Xtream.MOVIES ? account.movie(item.id) : account.series(item.id);
            } catch (Exception e) {
                d = null;
            }
            final Xtream.Details got = d;
            Ui.MAIN.post(() -> {
                if (isFinishing()) return;
                if (got == null) {
                    status.setText("Não consegui abrir · Voltar para tentar outro");
                    if (kind == Xtream.MOVIES) {
                        details = new Xtream.Details();   // the movie can still play without its page
                        show();
                    }
                    return;
                }
                details = got;
                show();
            });
        });
    }

    private void show() {
        status.setText("");
        if (!details.meta.isEmpty()) meta.setText(details.meta);
        plot.setText(details.plot.isEmpty() ? item.plot : details.plot);
        if (!details.backdrop.isEmpty()) Images.load(backdrop, details.backdrop, 960, 540);
        watch.setVisibility(View.VISIBLE);
        watch.setOnClickListener(v -> play());
        favorite.setVisibility(View.VISIBLE);
        updateFavorite();
        if (kind == Xtream.SERIES) {
            if (details.seasons.isEmpty()) {
                watch.setVisibility(View.INVISIBLE);
                status.setText("Esta série ainda não tem episódios");
                return;
            }
            lower.setVisibility(View.VISIBLE);
            for (int i = 0; i < details.seasons.size(); i++) {
                final int index = i;
                TextView t = Ui.pill(this, details.seasons.get(i), 14);
                t.setOnFocusChangeListener((v, has) -> {
                    Ui.choose(t, index == season);
                    if (has && index != season) showSeason(index);
                });
                seasons.addView(t);
            }
            season = Progress.lastSeason(this, item.id, details.seasons.size());
            showSeason(season);
        }
        updateWatch();
        watch.requestFocus();
    }

    private void showSeason(int index) {
        season = index;
        for (int i = 0; i < seasons.getChildCount(); i++) Ui.choose((TextView) seasons.getChildAt(i), i == index);
        episodes.removeAllViews();
        episodeCards.clear();
        List<Xtream.Item> list = details.episodes.get(index);
        int w = Ui.dp(this, 200), h = w * 9 / 16, gap = Ui.dp(this, 20);
        for (int i = 0; i < list.size(); i++) {
            final int e = i;
            Xtream.Item ep = list.get(i);
            Ui.Card card = new Ui.Card(this, w, h, false);
            card.title.setText((i + 1) + ". " + (ep.name.isEmpty() ? "Episódio " + (i + 1) : ep.name));
            Images.load(card.image, ep.image.isEmpty() ? details.backdrop : ep.image, w, h);
            card.root.setFocusable(true);
            card.root.setOnFocusChangeListener((v, has) -> card.focus(has));
            card.root.setOnClickListener(v -> playEpisode(e));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) lp.leftMargin = gap;
            episodes.addView(card.root, lp);
            episodeCards.add(card);
        }
        paintEpisodes();
        episodeScroll.scrollTo(0, 0);
    }

    /** Watched episodes: a green ring and "Assistido"; started ones: the minutes left. */
    private void paintEpisodes() {
        if (kind != Xtream.SERIES || details == null || details.episodes.isEmpty()) return;
        List<Xtream.Item> list = details.episodes.get(season);
        for (int i = 0; i < episodeCards.size() && i < list.size(); i++) {
            Ui.Card card = episodeCards.get(i);
            Xtream.Item ep = list.get(i);
            boolean seen = Progress.finished(this, Xtream.SERIES, ep.id);
            String left = Progress.label(this, Xtream.SERIES, ep.id);
            card.sub.setText(left.isEmpty() ? ep.meta : left);
            card.sub.setTextColor(seen ? Ui.GREEN : Ui.FAINT);
            card.sub.setVisibility(card.sub.getText().length() == 0 ? View.GONE : View.VISIBLE);
            if (seen) {
                GradientDrawable ring = new GradientDrawable();
                ring.setCornerRadius(Ui.dp(this, 14));
                ring.setStroke(Ui.dp(this, 3), Ui.GREEN);
                card.frame.setForeground(ring);
            } else {
                card.frame.setForeground(null);
            }
        }
    }

    private void updateFavorite() {
        favorite.setText(Favorites.contains(this, kind, item.id) ? "♥  Nos favoritos" : "♡  Favoritar");
    }

    /** "Assistir", or "Continuar de 12:34" / "Continuar T1:E3" where it stopped. */
    private void updateWatch() {
        if (kind == Xtream.MOVIES) {
            String at = Progress.resumeText(this, Xtream.MOVIES, item.id);
            watch.setText(at.isEmpty() ? "▶  Assistir" : "▶  Continuar de " + at);
            return;
        }
        int[] next = nextEpisode();
        watch.setText("▶  " + (next[0] == 0 && next[1] == 0 && !Progress.started(this, item.id)
                ? "Assistir episódio 1" : "Continuar T" + (next[0] + 1) + ":E" + (next[1] + 1)));
    }

    /** The episode to continue: the last one watched (or the next one when it was finished). */
    private int[] nextEpisode() {
        String last = Progress.lastEpisode(this, item.id);
        for (int s = 0; s < details.episodes.size(); s++) {
            List<Xtream.Item> list = details.episodes.get(s);
            for (int e = 0; e < list.size(); e++) {
                if (!list.get(e).id.equals(last)) continue;
                if (Progress.finished(this, Xtream.SERIES, last)) {
                    if (e + 1 < list.size()) return new int[] {s, e + 1};
                    if (s + 1 < details.episodes.size()) return new int[] {s + 1, 0};
                }
                return new int[] {s, e};
            }
        }
        return new int[] {0, 0};
    }

    private void play() {
        if (kind == Xtream.MOVIES) {
            Xtream.Item it = item;
            if (it.ext.isEmpty()) it.ext = details.ext;
            HomeActivity.playing = new ArrayList<>();
            HomeActivity.playing.add(it);
            startActivity(new Intent(this, PlayerActivity.class).putExtra("kind", Xtream.MOVIES).putExtra("index", 0));
            return;
        }
        int[] next = nextEpisode();
        season = next[0];
        playEpisode(next[1]);
    }

    private void playEpisode(int index) {
        HomeActivity.playing = new ArrayList<>(details.episodes.get(season));
        Progress.setLastSeason(this, item.id, season);
        startActivity(new Intent(this, PlayerActivity.class)
                .putExtra("kind", Xtream.SERIES).putExtra("index", index).putExtra("series", item.id));
    }
}
