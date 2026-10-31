package io.canais;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Favorite channels, movies and series, newest first: saved whole, so they show without asking the server. */
final class Favorites {
    static final String CATEGORY = "__favorites";
    private static final List<List<Xtream.Item>> LISTS = new ArrayList<>();   // per kind, read once

    private Favorites() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("favorites", Context.MODE_PRIVATE);
    }

    static synchronized List<Xtream.Item> list(Context c, int kind) {
        while (LISTS.size() <= kind) LISTS.add(null);
        if (LISTS.get(kind) == null) {
            List<Xtream.Item> out = new ArrayList<>();
            try {
                JSONArray a = new JSONArray(sp(c).getString("k" + kind, "[]"));
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    Xtream.Item it = new Xtream.Item(o.getString("id"), o.optString("name"));
                    it.image = o.optString("image");
                    it.ext = o.optString("ext");
                    it.meta = o.optString("meta");
                    out.add(it);
                }
            } catch (org.json.JSONException ignored) {
            }
            LISTS.set(kind, out);
        }
        return LISTS.get(kind);
    }

    static boolean contains(Context c, int kind, String id) {
        for (Xtream.Item it : list(c, kind)) if (it.id.equals(id)) return true;
        return false;
    }

    /** Adds or removes it; true when it's now a favorite. */
    static synchronized boolean toggle(Context c, int kind, Xtream.Item item) {
        List<Xtream.Item> list = list(c, kind);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(item.id)) {
                list.remove(i);
                save(c, kind, list);
                return false;
            }
        }
        list.add(0, item);
        save(c, kind, list);
        return true;
    }

    private static void save(Context c, int kind, List<Xtream.Item> list) {
        JSONArray a = new JSONArray();
        try {
            for (Xtream.Item it : list) {
                a.put(new JSONObject().put("id", it.id).put("name", it.name).put("image", it.image)
                        .put("ext", it.ext).put("meta", it.meta));
            }
        } catch (org.json.JSONException ignored) {
        }
        sp(c).edit().putString("k" + kind, a.toString()).apply();
    }
}
