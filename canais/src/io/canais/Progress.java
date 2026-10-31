package io.canais;

import android.content.Context;
import android.content.SharedPreferences;

/** Where each movie and episode stopped, and each series' last episode and season: to continue from there. */
final class Progress {
    private Progress() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("progress", Context.MODE_PRIVATE);
    }

    private static String key(int kind, String id) {
        return (kind == Xtream.MOVIES ? "m" : "e") + id;
    }

    static void save(Context c, int kind, String id, long position, long duration, String series) {
        SharedPreferences.Editor e = sp(c).edit().putLong(key(kind, id), position).putLong(key(kind, id) + "d", duration);
        if (series != null) e.putString("last" + series, id);
        e.apply();
    }

    /** Where to start: 0 when it was barely started or already finished. */
    static long resume(Context c, int kind, String id) {
        long at = sp(c).getLong(key(kind, id), 0), d = sp(c).getLong(key(kind, id) + "d", 0);
        return at < 60_000 || finished(at, d) ? 0 : at;
    }

    static boolean finished(Context c, int kind, String id) {
        return finished(sp(c).getLong(key(kind, id), 0), sp(c).getLong(key(kind, id) + "d", 0));
    }

    private static boolean finished(long at, long d) {
        return d > 0 && at > d - Math.max(120_000, d / 20);   // the credits count as the end
    }

    static String resumeText(Context c, int kind, String id) {
        long at = resume(c, kind, id);
        return at == 0 ? "" : time(at);
    }

    /** For an episode card: "Assistido", "Faltam 23 min" or nothing. */
    static String label(Context c, int kind, String id) {
        if (finished(c, kind, id)) return "Assistido";
        long at = resume(c, kind, id), d = sp(c).getLong(key(kind, id) + "d", 0);
        return at == 0 || d == 0 ? "" : "Faltam " + Math.max(1, (d - at) / 60_000) + " min";
    }

    static String lastEpisode(Context c, String series) {
        return sp(c).getString("last" + series, "");
    }

    static boolean started(Context c, String series) {
        return !lastEpisode(c, series).isEmpty();
    }

    static int lastSeason(Context c, String series, int count) {
        return Math.max(0, Math.min(count - 1, sp(c).getInt("season" + series, 0)));
    }

    static void setLastSeason(Context c, String series, int season) {
        sp(c).edit().putInt("season" + series, season).apply();
    }

    static String time(long ms) {
        long s = ms / 1000, h = s / 3600, m = s / 60 % 60;
        return h > 0 ? String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s % 60)
                : String.format(java.util.Locale.US, "%d:%02d", m, s % 60);
    }
}
