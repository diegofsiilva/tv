package io.canais;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** The IPTV account (Xtream Codes API): login, live channels, movies, series and their stream addresses. */
final class Xtream {
    static final int LIVE = 0, MOVIES = 1, SERIES = 2;

    /** A category, channel, movie, series or episode. `image`: logo, poster or still; `ext`: the file's container. */
    static final class Item {
        final String id, name;
        String image = "", ext = "", meta = "", plot = "";

        Item(String id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** A movie's or series' page: the backdrop, the details and, for a series, its seasons and episodes. */
    static final class Details {
        String backdrop = "", meta = "", plot = "", ext = "";
        final List<String> seasons = new ArrayList<>();
        final List<List<Item>> episodes = new ArrayList<>();
    }

    final String server, user, pass;

    Xtream(String server, String user, String pass) {
        server = server.trim();
        if (!server.startsWith("http://") && !server.startsWith("https://")) server = "http://" + server;
        while (server.endsWith("/")) server = server.substring(0, server.length() - 1);
        this.server = server;
        this.user = user.trim();
        this.pass = pass.trim();
    }

    // MARK: - Saved account

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("account", Context.MODE_PRIVATE);
    }

    static Xtream saved(Context c) {
        SharedPreferences p = prefs(c);
        String s = p.getString("server", null);
        return s == null ? null : new Xtream(s, p.getString("user", ""), p.getString("pass", ""));
    }

    void save(Context c) {
        prefs(c).edit().putString("server", server).putString("user", user).putString("pass", pass).apply();
    }

    // MARK: - API (call off the UI thread)

    /** True when the server accepts the user and password. */
    boolean login() throws IOException {
        JSONObject info = object(api("")).optJSONObject("user_info");
        return info != null && info.optInt("auth") == 1;
    }

    List<Item> categories(int kind) throws IOException {
        String action = kind == LIVE ? "get_live_categories" : kind == MOVIES ? "get_vod_categories" : "get_series_categories";
        JSONArray a = array(api(action));
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) out.add(new Item(o.optString("category_id"), o.optString("category_name").trim()));
        }
        return out;
    }

    /** A category's channels, movies or series; with a null category, all of them (the search catalog). */
    List<Item> items(int kind, String categoryId) throws IOException {
        String action = kind == LIVE ? "get_live_streams" : kind == MOVIES ? "get_vod_streams" : "get_series";
        JSONArray a = array(api(action) + (categoryId == null ? "" : "&category_id=" + enc(categoryId)));
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Item it = new Item(o.optString(kind == SERIES ? "series_id" : "stream_id"), o.optString("name").trim());
            it.image = o.optString(kind == SERIES ? "cover" : "stream_icon");
            it.ext = o.optString("container_extension");
            if (kind == SERIES) it.meta = meta(year(o.optString("releaseDate")), o.optString("rating"), o.optString("genre"));
            if (kind == MOVIES) it.meta = meta("", o.optString("rating"), "");
            out.add(it);
        }
        return out;
    }

    Details movie(String id) throws IOException {
        JSONObject o = object(api("get_vod_info") + "&vod_id=" + enc(id));
        JSONObject info = o.optJSONObject("info"), data = o.optJSONObject("movie_data");
        Details d = new Details();
        if (info != null) {
            d.backdrop = first(info.opt("backdrop_path"));
            if (d.backdrop.isEmpty()) d.backdrop = info.optString("movie_image");
            d.plot = info.optString("plot", info.optString("description")).trim();
            d.meta = meta(year(info.optString("releasedate")), info.optString("rating"),
                    join(info.optString("genre"), info.optString("duration")));
        }
        if (data != null) d.ext = data.optString("container_extension");
        return d;
    }

    Details series(String id) throws IOException {
        JSONObject o = object(api("get_series_info") + "&series_id=" + enc(id));
        JSONObject info = o.optJSONObject("info");
        Details d = new Details();
        if (info != null) {
            d.backdrop = first(info.opt("backdrop_path"));
            if (d.backdrop.isEmpty()) d.backdrop = info.optString("cover");
            d.plot = info.optString("plot").trim();
            d.meta = meta(year(info.optString("releaseDate")), info.optString("rating"), info.optString("genre"));
        }
        // "episodes" is an object keyed by season number (some servers send an array of arrays).
        Object eps = o.opt("episodes");
        List<String> keys = new ArrayList<>();
        List<JSONArray> lists = new ArrayList<>();
        if (eps instanceof JSONObject) {
            JSONArray names = ((JSONObject) eps).names();
            for (int i = 0; names != null && i < names.length(); i++) keys.add(names.optString(i));
            java.util.Collections.sort(keys, (a, b) -> Integer.compare(num(a), num(b)));
            for (String k : keys) lists.add(((JSONObject) eps).optJSONArray(k));
        } else if (eps instanceof JSONArray) {
            for (int i = 0; i < ((JSONArray) eps).length(); i++) {
                keys.add(String.valueOf(i + 1));
                lists.add(((JSONArray) eps).optJSONArray(i));
            }
        }
        for (int s = 0; s < keys.size(); s++) {
            JSONArray a = lists.get(s);
            if (a == null) continue;
            List<Item> season = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject e = a.optJSONObject(i);
                if (e == null) continue;
                Item it = new Item(e.optString("id"), e.optString("title").trim());
                it.ext = e.optString("container_extension");
                JSONObject ei = e.optJSONObject("info");
                if (ei != null) {
                    it.image = ei.optString("movie_image");
                    it.plot = ei.optString("plot").trim();
                    it.meta = ei.optString("duration");
                }
                season.add(it);
            }
            d.seasons.add("Temporada " + keys.get(s));
            d.episodes.add(season);
        }
        return d;
    }

    /** The stream's address. Live: HLS (m3u8) or MPEG-TS (ts); movies and episodes: their own file. */
    String stream(int kind, String id, String ext) {
        String path = kind == LIVE ? "/live/" : kind == MOVIES ? "/movie/" : "/series/";
        if (ext == null || ext.isEmpty()) ext = kind == LIVE ? "ts" : "mp4";
        return server + path + enc(user) + "/" + enc(pass) + "/" + id + "." + ext;
    }

    // MARK: - Helpers

    private String api(String action) {
        String u = server + "/player_api.php?username=" + enc(user) + "&password=" + enc(pass);
        return action.isEmpty() ? u : u + "&action=" + action;
    }

    private static JSONObject object(String url) throws IOException {
        try {
            return new JSONObject(get(url));
        } catch (org.json.JSONException e) {
            throw new IOException("unexpected answer from the server");
        }
    }

    private static JSONArray array(String url) throws IOException {
        String body = get(url).trim();
        if (!body.startsWith("[")) return new JSONArray();   // some servers answer {} for an empty category
        try {
            return new JSONArray(body);
        } catch (org.json.JSONException e) {
            throw new IOException("unexpected answer from the server");
        }
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("User-Agent", "Canais/1.0");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static String first(Object o) {
        if (o instanceof JSONArray) return ((JSONArray) o).optString(0, "");
        return o instanceof String ? (String) o : "";
    }

    private static String year(String date) {
        return date != null && date.length() >= 4 && Character.isDigit(date.charAt(0)) ? date.substring(0, 4) : "";
    }

    /** "2021 · ★ 7,8 · Drama": the parts that exist. */
    private static String meta(String year, String rating, String extra) {
        String r = "";
        try {
            double v = Double.parseDouble(rating);
            if (v > 0) r = String.format(java.util.Locale.forLanguageTag("pt-BR"), "★ %.1f", v > 10 ? v / 10 : v);
        } catch (Exception ignored) {
        }
        return join(join(year, r), extra);
    }

    private static String join(String a, String b) {
        a = a == null ? "" : a.trim();
        b = b == null ? "" : b.trim();
        return a.isEmpty() ? b : b.isEmpty() ? a : a + " · " + b;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 999;
        }
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }
}
