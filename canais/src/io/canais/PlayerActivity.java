package io.canais;

import android.app.Activity;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.List;

/**
 * Full screen, with the TV's own player (MediaPlayer: the TV's video chip decodes it). Live: ↑/↓ change the channel.
 * Movies and episodes: ←/→ jump 15 s back or 30 s forward, OK pauses; where it stopped is saved. ↓ or Menu opens the
 * options: picture format (Original, Fill, Stretch, 16:9, 4:3), audio language and subtitles, the last two kept for
 * the next video. Episodes: ↑ goes to the next one (Channel ↑/↓ to the next or previous), and in the last 30 s the
 * next episode's card shows up (OK plays it now, Back stays). OK (live) or Info shows the panel with what's really
 * arriving: resolution, frame rate, codec and speed. Back returns.
 */
public class PlayerActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "Canais";
    private static final String[] LIVE_FORMATS = {"m3u8", "ts"};   // the second one when the first doesn't play
    private static final String[] ASPECTS = {"Original", "Tela cheia", "Esticar", "16:9", "4:3"};

    private Xtream account;
    private List<Xtream.Item> list;
    private int kind, index, format;
    private String series;
    private MediaPlayer player;
    private boolean prepared;
    private SurfaceView surface;
    private SurfaceHolder holder;
    private FrameLayout root;
    private LinearLayout panel;
    private TextView name, info, clock;
    private ProgressBar bar;
    private int videoW, videoH, aspect;
    private LinearLayout nextCard;
    private TextView nextTitle, nextWhen;
    private boolean nextDismissed;
    private String codec = "", fps = "", speed = "";
    private long rxBytes, rxAt;
    private LinearLayout options;
    private TextView optAspect, optAudio, optSubs, subtitle;
    // Indexes into getTrackInfo(); subs[0] is -1, "off".
    private final List<Integer> audios = new java.util.ArrayList<>(), subs = new java.util.ArrayList<>();
    private final List<String> audioNames = new java.util.ArrayList<>(), subNames = new java.util.ArrayList<>();
    private int audioAt, subAt;
    private final Runnable hidePanel = () -> panel.setVisibility(View.GONE);
    private final Runnable hideOptions = () -> options.setVisibility(View.GONE);
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            measure();
            updateClock();
            saveProgress();
            checkNext();
            Ui.MAIN.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        account = Xtream.saved(this);
        list = HomeActivity.playing;
        kind = getIntent().getIntExtra("kind", Xtream.LIVE);
        index = getIntent().getIntExtra("index", 0);
        series = getIntent().getStringExtra("series");
        if (account == null || list.isEmpty()) {
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        aspect = kind == Xtream.LIVE ? 0 : getSharedPreferences("player", MODE_PRIVATE).getInt("aspect", 0);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        surface.getHolder().addCallback(this);

        int pad = Ui.dp(this, 22);
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(pad, pad * 3 / 4, pad, pad * 3 / 4);
        panel.setBackground(Ui.round(0xCC1C1C1E, Ui.dp(this, 18)));
        name = Ui.line(this, "", 22, Ui.WHITE, Ui.BOLD);
        panel.addView(name);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.WHITE));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x55FFFFFF));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, Ui.dp(this, 6));
        bl.topMargin = Ui.dp(this, 12);
        panel.addView(bar, bl);
        clock = Ui.line(this, "", 13, 0xCCFFFFFF, Ui.MEDIUM);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = Ui.dp(this, 6);
        panel.addView(clock, cl);
        info = Ui.line(this, "", 13, 0x99FFFFFF, null);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(-1, -2);
        il.topMargin = Ui.dp(this, 8);
        panel.addView(info, il);
        boolean live = kind == Xtream.LIVE;
        bar.setVisibility(live ? View.GONE : View.VISIBLE);
        clock.setVisibility(live ? View.GONE : View.VISIBLE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.setMargins(Ui.dp(this, 48), 0, Ui.dp(this, 48), Ui.dp(this, 36));
        root.addView(panel, lp);

        // Subtitles, drawn here: the TV's player hands over the text (timed text), not the picture.
        subtitle = Ui.text(this, "", 26, Ui.WHITE, Ui.MEDIUM);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setShadowLayer(Ui.dp(this, 4), 0, Ui.dp(this, 1), Color.BLACK);
        subtitle.setPadding(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 6));
        subtitle.setBackground(Ui.round(0x66000000, Ui.dp(this, 8)));
        subtitle.setVisibility(View.GONE);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sl.setMargins(Ui.dp(this, 96), 0, Ui.dp(this, 96), Ui.dp(this, 56));
        root.addView(subtitle, sl);

        // The options (movies and episodes), at the right: ←/→ or OK change the value.
        options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(pad, pad, pad, pad);
        options.setBackground(Ui.round(0xE01C1C1E, Ui.dp(this, 18)));
        options.addView(Ui.line(this, "Opções", 20, Ui.WHITE, Ui.BOLD));
        optAspect = option(dir -> nextAspect(dir));
        optAudio = option(this::nextAudio);
        optSubs = option(this::nextSubs);
        TextView hint = Ui.line(this, "←/→ ou OK para mudar · Voltar para fechar", 12, 0x99FFFFFF, null);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-2, -2);
        hl.topMargin = Ui.dp(this, 14);
        options.addView(hint, hl);
        options.setVisibility(View.GONE);
        FrameLayout.LayoutParams ol = new FrameLayout.LayoutParams(Ui.dp(this, 420), -2, Gravity.CENTER_VERTICAL | Gravity.END);
        ol.setMargins(0, 0, Ui.dp(this, 48), 0);
        root.addView(options, ol);

        // The next episode's card (series), at the bottom right.
        nextCard = new LinearLayout(this);
        nextCard.setOrientation(LinearLayout.VERTICAL);
        nextCard.setPadding(pad, pad * 3 / 4, pad, pad * 3 / 4);
        nextCard.setBackground(Ui.round(Ui.WHITE, Ui.dp(this, 18)));
        nextCard.setElevation(Ui.dp(this, 10));
        nextCard.addView(Ui.line(this, "Próximo episódio", 13, Ui.DIM, Ui.MEDIUM));
        nextTitle = Ui.line(this, "", 18, Ui.INK, Ui.BOLD);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(Ui.dp(this, 300), -2);
        nl.topMargin = Ui.dp(this, 6);
        nextCard.addView(nextTitle, nl);
        TextView go = Ui.text(this, "▶  OK para assistir agora", 14, Ui.WHITE, Ui.BOLD);
        go.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        go.setBackground(Ui.round(Ui.INK, Ui.dp(this, 40)));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-2, -2);
        gl.topMargin = Ui.dp(this, 12);
        nextCard.addView(go, gl);
        nextWhen = Ui.line(this, "", 12, Ui.FAINT, null);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-2, -2);
        wl.topMargin = Ui.dp(this, 8);
        nextCard.addView(nextWhen, wl);
        nextCard.setVisibility(View.GONE);
        FrameLayout.LayoutParams ncl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        ncl.setMargins(0, 0, Ui.dp(this, 48), Ui.dp(this, 36));
        root.addView(nextCard, ncl);
        setContentView(root);
    }

    // MARK: - Surface and lifecycle

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        holder = h;
        start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int w, int hgt) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        holder = null;
        saveProgress();
        release();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ui.MAIN.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Ui.MAIN.removeCallbacks(tick);
        saveProgress();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Ui.MAIN.removeCallbacks(hidePanel);
        Ui.MAIN.removeCallbacks(hideOptions);
        release();
    }

    // MARK: - Playback

    private void start() {
        release();
        if (holder == null) return;
        final Xtream.Item it = list.get(index);
        videoW = videoH = 0;
        codec = fps = "";
        prepared = false;
        nextDismissed = false;
        nextCard.setVisibility(View.GONE);
        subtitle.setVisibility(View.GONE);
        options.setVisibility(View.GONE);
        showPanel("Abrindo…", false);
        String ext = kind == Xtream.LIVE ? LIVE_FORMATS[format] : it.ext;
        final long resumeAt = kind == Xtream.LIVE ? 0 : Progress.resume(this, kind, it.id);
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build());
            mp.setDisplay(holder);
            mp.setScreenOnWhilePlaying(true);
            mp.setDataSource(this, Uri.parse(account.stream(kind, it.id, ext)));
            mp.setOnPreparedListener(p -> {
                if (p != player) return;
                prepared = true;
                readTracks(p);
                if (resumeAt > 0) p.seekTo((int) resumeAt);
                p.start();
                showPanel(describe(), true);
            });
            mp.setOnVideoSizeChangedListener((p, w, h) -> {
                if (p != player || w == 0 || h == 0) return;
                videoW = w;
                videoH = h;
                fit();
                info.setText(describe());
                Log.i(TAG, "video " + w + "x" + h + " " + codec + " " + fps + " fps");
            });
            mp.setOnTimedTextListener((p, t) -> {
                if (p != player) return;
                String s = t == null || t.getText() == null ? "" : t.getText().trim();
                subtitle.setText(s);
                subtitle.setVisibility(s.isEmpty() || subAt == 0 ? View.GONE : View.VISIBLE);
            });
            mp.setOnErrorListener((p, what, extra) -> {
                Log.w(TAG, "error " + what + "/" + extra + " (" + ext + ")");
                if (p == player) failed();
                return true;
            });
            mp.setOnCompletionListener(p -> {
                if (p != player) return;
                if (kind == Xtream.LIVE) failed();   // a live channel only "ends" when the stream drops
                else finishedItem();
            });
            mp.prepareAsync();
        } catch (Exception e) {
            failed();
        }
    }

    /** Live: the other format, then a message. Movies and episodes: a message. ↑/↓ still move on. */
    private void failed() {
        if (kind == Xtream.LIVE && format + 1 < LIVE_FORMATS.length) {
            format++;
            start();
            return;
        }
        release();
        showPanel(kind == Xtream.LIVE ? "Este canal não abriu. ↑/↓ para outro canal." : "Não abriu. Voltar para tentar outro.", false);
    }

    /** The end of a movie (back to its page) or an episode (the next one). */
    private void finishedItem() {
        saveProgress();
        if (hasNext() && !nextDismissed) {
            index++;
            start();
        } else {
            finish();
        }
    }

    private boolean hasNext() {
        return kind == Xtream.SERIES && index + 1 < list.size();
    }

    /** In the last 30 s of an episode (the credits), the next one's card with the countdown. */
    private void checkNext() {
        if (!hasNext() || nextDismissed || player == null || !prepared) return;
        long left;
        try {
            long d = player.getDuration();
            if (d < 120_000) return;
            left = d - player.getCurrentPosition();
        } catch (IllegalStateException e) {
            return;
        }
        if (left > 30_000) {
            nextCard.setVisibility(View.GONE);
            return;
        }
        Xtream.Item next = list.get(index + 1);
        nextTitle.setText((index + 2) + ". " + (next.name.isEmpty() ? "Episódio " + (index + 2) : next.name));
        nextWhen.setText("Começa em " + Math.max(1, left / 1000) + " s · Voltar para continuar aqui");
        if (nextCard.getVisibility() != View.VISIBLE) {
            nextCard.setVisibility(View.VISIBLE);
            nextCard.setAlpha(0f);
            nextCard.animate().alpha(1f).setDuration(250).start();
            hidePanel.run();
        }
    }

    private void release() {
        if (player == null) return;
        MediaPlayer p = player;
        player = null;
        prepared = false;
        try {
            p.reset();
        } catch (Exception ignored) {
        }
        p.release();
    }

    /**
     * The codec and the frame rate, from the stream's own description (when the TV's player gives it), and the audio
     * and subtitle tracks, with the languages chosen before.
     */
    private void readTracks(MediaPlayer p) {
        audios.clear();
        audioNames.clear();
        subs.clear();
        subNames.clear();
        subs.add(-1);
        subNames.add("Desligadas");
        audioAt = subAt = 0;
        try {
            MediaPlayer.TrackInfo[] tracks = p.getTrackInfo();
            int selected = p.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO);
            for (int i = 0; i < tracks.length; i++) {
                MediaPlayer.TrackInfo t = tracks[i];
                int type = t.getTrackType();
                Log.i(TAG, "track " + i + " type " + type + " " + t.getLanguage());
                if (type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                    if (i == selected) audioAt = audios.size();
                    audios.add(i);
                    audioNames.add(language(t.getLanguage(), "Faixa " + audios.size()));
                } else if (type == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_TIMEDTEXT) {
                    subs.add(i);
                    subNames.add(language(t.getLanguage(), "Legenda " + (subs.size() - 1)));
                }
            }
            android.content.SharedPreferences prefs = getSharedPreferences("player", MODE_PRIVATE);
            int a = audioNames.indexOf(prefs.getString("audio", ""));
            if (a >= 0 && a != audioAt) pickAudio(a, false);
            int s = subNames.indexOf(prefs.getString("subs", ""));
            if (s > 0) pickSubs(s, false);
            for (MediaPlayer.TrackInfo t : tracks) {
                if (t.getTrackType() != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO) continue;
                MediaFormat f = t.getFormat();
                if (f == null) continue;
                Log.i(TAG, "track " + f);
                String mime = f.containsKey(MediaFormat.KEY_MIME) ? f.getString(MediaFormat.KEY_MIME) : "";
                codec = mime.contains("avc") ? "H.264" : mime.contains("hevc") ? "H.265" : mime.contains("mpeg2") ? "MPEG-2"
                        : mime.replace("video/", "").toUpperCase(java.util.Locale.US);
                if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    try {
                        fps = String.valueOf(f.getInteger(MediaFormat.KEY_FRAME_RATE));
                    } catch (ClassCastException e) {
                        fps = String.valueOf(Math.round(f.getFloat(MediaFormat.KEY_FRAME_RATE)));
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "track info: " + e);
        }
    }

    private void saveProgress() {
        if (kind == Xtream.LIVE || player == null || !prepared) return;
        try {
            long d = player.getDuration(), at = player.getCurrentPosition();
            if (d > 0 && at > 0) Progress.save(this, kind, list.get(index).id, at, d, series);
        } catch (IllegalStateException ignored) {
        }
    }

    /**
     * Sizes the video for the picture format: Original keeps its proportions inside the screen, Fill covers the
     * screen (cropping the edges), Stretch fills it as it is, 16:9 and 4:3 force those proportions.
     */
    private void fit() {
        int sw = root.getWidth(), sh = root.getHeight();
        if (sw == 0 || sh == 0 || videoW == 0) return;
        float screen = (float) sw / sh, ratio = aspect == 3 ? 16f / 9 : aspect == 4 ? 4f / 3 : (float) videoW / videoH;
        int w, h;
        if (aspect == 2) {
            w = sw;
            h = sh;
        } else if ((ratio > screen) != (aspect == 1)) {
            w = sw;
            h = Math.round(sw / ratio);
        } else {
            h = sh;
            w = Math.round(sh * ratio);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        lp.width = w;
        lp.height = h;
        surface.setLayoutParams(lp);
    }

    private void nextAspect(int dir) {
        aspect = (aspect + dir + ASPECTS.length) % ASPECTS.length;
        getSharedPreferences("player", MODE_PRIVATE).edit().putInt("aspect", aspect).apply();
        fit();
        refreshOptions();
    }

    private void nextAudio(int dir) {
        if (audios.size() < 2) return;
        pickAudio((audioAt + dir + audios.size()) % audios.size(), true);
        refreshOptions();
    }

    private void nextSubs(int dir) {
        if (subs.size() < 2) return;
        pickSubs((subAt + dir + subs.size()) % subs.size(), true);
        refreshOptions();
    }

    /** `remember`: the language goes on to the next videos (the person chose it, not the saved one). */
    private void pickAudio(int at, boolean remember) {
        if (player == null || !prepared) return;
        try {
            player.selectTrack(audios.get(at));
            audioAt = at;
            if (remember) getSharedPreferences("player", MODE_PRIVATE).edit().putString("audio", audioNames.get(at)).apply();
        } catch (Exception e) {
            Log.w(TAG, "audio track: " + e);
        }
    }

    private void pickSubs(int at, boolean remember) {
        if (player == null || !prepared) return;
        try {
            if (subAt > 0) player.deselectTrack(subs.get(subAt));
            if (at > 0) player.selectTrack(subs.get(at));
            subAt = at;
            subtitle.setVisibility(View.GONE);
            if (remember) getSharedPreferences("player", MODE_PRIVATE).edit().putString("subs", subNames.get(at)).apply();
        } catch (Exception e) {
            Log.w(TAG, "subtitle track: " + e);
        }
    }

    /** "por" → "Português"; "und" or nothing → the fallback. */
    private static String language(String code, String fallback) {
        if (code == null || code.isEmpty() || code.equals("und")) return fallback;
        java.util.Locale pt = java.util.Locale.forLanguageTag("pt-BR");
        String name = new java.util.Locale(code).getDisplayLanguage(pt);
        if (name.isEmpty() || name.equalsIgnoreCase(code)) return code.toUpperCase(java.util.Locale.US);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** A row of the options: ←/→ change it back and forth, OK forward. */
    private TextView option(java.util.function.IntConsumer change) {
        TextView t = Ui.line(this, "", 17, Ui.WHITE, Ui.MEDIUM);
        int h = Ui.dp(this, 16), v = Ui.dp(this, 11);
        t.setPadding(h, v, h, v);
        t.setFocusable(true);
        t.setOnFocusChangeListener((view, has) -> {
            t.setTextColor(has ? Ui.INK : Ui.WHITE);
            t.setTypeface(has ? Ui.BOLD : Ui.MEDIUM);
            t.setBackground(has ? Ui.round(Ui.WHITE, Ui.dp(this, 12)) : null);
        });
        t.setOnKeyListener((view, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) change.accept(-1);
            else if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_CENTER
                    || code == KeyEvent.KEYCODE_ENTER) change.accept(1);
            else return false;
            Ui.MAIN.removeCallbacks(hideOptions);
            Ui.MAIN.postDelayed(hideOptions, 10_000);
            return true;
        });
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2);
        l.topMargin = Ui.dp(this, 8);
        options.addView(t, l);
        return t;
    }

    private void refreshOptions() {
        optAspect.setText("Formato da tela:  ‹ " + ASPECTS[aspect] + " ›");
        optAudio.setText("Áudio:  " + (audios.isEmpty() ? "—" : audios.size() == 1 ? audioNames.get(0)
                : "‹ " + audioNames.get(audioAt) + " ›"));
        optSubs.setText("Legendas:  " + (subs.size() < 2 ? "nenhuma neste vídeo" : "‹ " + subNames.get(subAt) + " ›"));
    }

    private void showOptions() {
        refreshOptions();
        hidePanel.run();
        options.setVisibility(View.VISIBLE);
        optAspect.requestFocus();
        Ui.MAIN.removeCallbacks(hideOptions);
        Ui.MAIN.postDelayed(hideOptions, 10_000);
    }

    private void step(int dir) {
        int next = index + dir;
        if (kind == Xtream.LIVE) next = (next + list.size()) % list.size();
        if (next < 0 || next >= list.size()) return;
        saveProgress();
        index = next;
        format = 0;
        start();
    }

    private void seek(int seconds) {
        if (player == null || !prepared) return;
        try {
            long d = player.getDuration();
            long to = Math.max(0, Math.min(d > 0 ? d - 1000 : Long.MAX_VALUE, player.getCurrentPosition() + seconds * 1000L));
            player.seekTo((int) to);
        } catch (IllegalStateException ignored) {
        }
        showPanel(describe(), true);
        updateClock();
    }

    private void togglePause() {
        if (player == null || !prepared) return;
        if (player.isPlaying()) {
            player.pause();
            showPanel(describe(), false);
        } else {
            player.start();
            showPanel(describe(), true);
        }
        updateClock();
    }

    // MARK: - Panel

    private void showPanel(String text, boolean autoHide) {
        Xtream.Item it = list.get(index);
        String prefix = kind == Xtream.LIVE ? (index + 1) + "  " : kind == Xtream.SERIES ? (index + 1) + ". " : "";
        name.setText(prefix + it.name);
        info.setText(text);
        updateClock();
        panel.setVisibility(View.VISIBLE);
        Ui.MAIN.removeCallbacks(hidePanel);
        if (autoHide) Ui.MAIN.postDelayed(hidePanel, 5000);
    }

    private void updateClock() {
        if (kind == Xtream.LIVE || player == null || !prepared || panel.getVisibility() != View.VISIBLE) return;
        try {
            long d = player.getDuration(), at = player.getCurrentPosition();
            if (d <= 0) return;
            bar.setProgress((int) (at * 1000 / d));
            clock.setText((player.isPlaying() ? "" : "Pausado · ") + Progress.time(at) + " / " + Progress.time(d)
                    + " · faltam " + Progress.time(d - at));
        } catch (IllegalStateException ignored) {
        }
    }

    /** "1920×1080 Full HD · 30 fps · H.264 · 9,8 Mbps": what's really arriving. */
    private String describe() {
        StringBuilder sb = new StringBuilder();
        if (videoW > 0) sb.append(videoW).append('×').append(videoH).append(' ').append(quality(videoH));
        else sb.append("Resolução: aguardando");
        if (!fps.isEmpty()) sb.append(" · ").append(fps).append(" fps");
        if (!codec.isEmpty()) sb.append(" · ").append(codec);
        if (!speed.isEmpty()) sb.append(" · ").append(speed);
        if (kind != Xtream.LIVE) sb.append(" · Formato: ").append(ASPECTS[aspect]);
        return sb.toString();
    }

    private static String quality(int h) {
        if (h >= 2000) return "4K";
        if (h >= 1000) return "Full HD";
        if (h >= 700) return "HD";
        return "SD";
    }

    /** The TV's download speed: the TV's own player fetches the stream, so it's counted for the whole TV. */
    private void measure() {
        long now = SystemClock.elapsedRealtime(), rx = TrafficStats.getTotalRxBytes();
        if (rxAt != 0 && rx >= rxBytes && now - rxAt >= 900) {
            double mbps = (rx - rxBytes) * 8.0 / ((now - rxAt) / 1000.0) / 1e6;
            speed = String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.1f Mbps", mbps);
            if (panel.getVisibility() == View.VISIBLE && player != null && prepared) info.setText(describe());
        }
        rxBytes = rx;
        rxAt = now;
    }

    // MARK: - Remote

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean live = kind == Xtream.LIVE;
        if (nextCard.getVisibility() == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                step(1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                nextDismissed = true;   // stays on this episode; at its end, back to the series' page
                nextCard.setVisibility(View.GONE);
                return true;
            }
        }
        if (options.getVisibility() == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) {
                hideOptions.run();
                return true;
            }
            // ↑/↓ move between the rows (the focus moves after this, when nothing took the key).
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return false;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (kind == Xtream.MOVIES) showOptions();
                else step(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (live) step(-1);
                else showOptions();
                return true;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (kind == Xtream.MOVIES) return super.onKeyDown(keyCode, event);
                step(keyCode == KeyEvent.KEYCODE_CHANNEL_UP ? 1 : -1);
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (live) return super.onKeyDown(keyCode, event);
                showOptions();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (live) return super.onKeyDown(keyCode, event);
                seek(-15);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (live) return super.onKeyDown(keyCode, event);
                seek(30);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                togglePause();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (!live) {
                    togglePause();
                    return true;
                }
                // Live: OK shows or hides the panel, like Info.
            case KeyEvent.KEYCODE_INFO:
                if (panel.getVisibility() == View.VISIBLE) hidePanel.run();
                else showPanel(player == null ? info.getText().toString() : describe(), true);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }
}
