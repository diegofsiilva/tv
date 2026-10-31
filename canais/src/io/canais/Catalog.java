package io.canais;

import android.content.Context;

import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Everything the list has (channels, movies and series) for the search: fetched once, kept in memory and on disk (the
 * app's cache) for 6 hours, so later searches are instant. Matching ignores accents and case, and every word typed
 * must be in the name.
 */
final class Catalog {
    private static final long FRESH_MS = 6 * 3600_000L;
    private static final List<List<Xtream.Item>> ALL = new ArrayList<>();      // per kind
    private static final List<List<String>> KEYS = new ArrayList<>();         // the names, normalized
    private static long loadedAt;

    private Catalog() {}

    static synchronized boolean ready() {
        return ALL.size() == 3 && System.currentTimeMillis() - loadedAt < FRESH_MS;
    }

    /** Drops it from memory and disk (Atualizar): the next search fetches the list again. */
    static synchronized void forget(Context c) {
        ALL.clear();
        KEYS.clear();
        loadedAt = 0;
        new File(c.getCacheDir(), "catalog.json").delete();
    }

    /** Loads it (from disk when fresh, else from the server). Call off the UI thread. */
    static void load(Context c, Xtream account) throws Exception {
        if (ready()) return;
        File f = new File(c.getCacheDir(), "catalog.json");
        List<List<Xtream.Item>> lists = null;
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < FRESH_MS) lists = read(f);
        long at = lists != null ? f.lastModified() : System.currentTimeMillis();
        if (lists == null) {
            lists = new ArrayList<>();
            for (int kind = 0; kind < 3; kind++) lists.add(account.items(kind, null));
            write(f, lists);
        }
        List<List<String>> keys = new ArrayList<>();
        for (List<Xtream.Item> list : lists) {
            List<String> k = new ArrayList<>(list.size());
            for (Xtream.Item it : list) k.add(normalize(it.name));
            keys.add(k);
        }
        synchronized (Catalog.class) {
            ALL.clear();
            ALL.addAll(lists);
            KEYS.clear();
            KEYS.addAll(keys);
            loadedAt = at;
        }
    }

    /** Up to `max` of `kind` whose name has every word of `query`; names that start with it come first. */
    static synchronized List<Xtream.Item> search(int kind, String query, int max) {
        List<Xtream.Item> first = new ArrayList<>(), rest = new ArrayList<>();
        if (kind >= ALL.size()) return first;
        String q = normalize(query);
        String[] words = q.split(" ");
        if (q.isEmpty()) return first;
        List<Xtream.Item> list = ALL.get(kind);
        List<String> keys = KEYS.get(kind);
        for (int i = 0; i < list.size() && first.size() < max; i++) {
            String name = keys.get(i);
            boolean all = true;
            for (String w : words) {
                if (!name.contains(w)) {
                    all = false;
                    break;
                }
            }
            if (!all) continue;
            if (name.startsWith(q)) first.add(list.get(i));
            else if (rest.size() < max) rest.add(list.get(i));
        }
        first.addAll(rest);
        return first.size() > max ? new ArrayList<>(first.subList(0, max)) : first;
    }

    static String normalize(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    // MARK: - Disk: [[id, name, image, ext, meta], ...] per kind

    private static List<List<Xtream.Item>> read(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) break;
                n += r;
            }
            JSONArray kinds = new JSONArray(new String(buf, 0, n, "UTF-8"));
            List<List<Xtream.Item>> out = new ArrayList<>();
            for (int k = 0; k < kinds.length(); k++) {
                JSONArray a = kinds.getJSONArray(k);
                List<Xtream.Item> list = new ArrayList<>(a.length());
                for (int i = 0; i < a.length(); i++) {
                    JSONArray o = a.getJSONArray(i);
                    Xtream.Item it = new Xtream.Item(o.getString(0), o.getString(1));
                    it.image = o.optString(2);
                    it.ext = o.optString(3);
                    it.meta = o.optString(4);
                    list.add(it);
                }
                out.add(list);
            }
            return out.size() == 3 ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(File f, List<List<Xtream.Item>> lists) {
        try {
            JSONArray kinds = new JSONArray();
            for (List<Xtream.Item> list : lists) {
                JSONArray a = new JSONArray();
                for (Xtream.Item it : list) {
                    a.put(new JSONArray().put(it.id).put(it.name).put(it.image).put(it.ext).put(it.meta));
                }
                kinds.put(a);
            }
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(kinds.toString().getBytes("UTF-8"));
            }
            tmp.renameTo(f);
        } catch (Exception ignored) {
            // no disk cache: the next search fetches again
        }
    }
}
