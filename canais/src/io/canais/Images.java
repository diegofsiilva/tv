package io.canais;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Posters, logos and backdrops: fetched in the background, scaled down to the size on screen, kept in memory (a
 * small LRU: the TV has 2 GB) and on disk (the app's cache, which Android trims by itself).
 */
final class Images {
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);

    private Images() {}

    /** Shows `url` in `view` at about `w`×`h` pixels; a recycled view that moved on to another url ignores the old one. */
    static void load(ImageView view, String url, int w, int h) {
        load(view, url, w, h, null);
    }

    /** The same; `stand-in` (the name on the card) shows until the image arrives, and stays when it never does. */
    static void load(ImageView view, String url, int w, int h, View standIn) {
        view.setTag(url);
        if (standIn != null) standIn.setVisibility(View.VISIBLE);
        if (url == null || url.isEmpty() || !url.startsWith("http")) {
            view.setImageDrawable(null);
            return;
        }
        final String key = url + "@" + w;
        Bitmap hit = MEMORY.get(key);
        if (hit != null) {
            view.setImageBitmap(hit);
            if (standIn != null) standIn.setVisibility(View.GONE);
            return;
        }
        view.setImageDrawable(null);
        final Context c = view.getContext().getApplicationContext();
        POOL.execute(() -> {
            Bitmap b = fetch(c, url, w, h);
            if (b == null) return;
            MEMORY.put(key, b);
            Ui.MAIN.post(() -> {
                if (url.equals(view.getTag())) {
                    view.setImageBitmap(b);
                    if (standIn != null) standIn.setVisibility(View.GONE);
                    view.setAlpha(0f);
                    view.animate().alpha(1f).setDuration(180).start();
                }
            });
        });
    }

    private static Bitmap fetch(Context c, String url, int w, int h) {
        File dir = new File(c.getCacheDir(), "img");
        dir.mkdirs();
        File f = new File(dir, Integer.toHexString(url.hashCode()) + "-" + url.length());
        try {
            if (!f.exists()) {
                HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
                con.setConnectTimeout(8000);
                con.setReadTimeout(15000);
                con.setInstanceFollowRedirects(true);
                File tmp = new File(dir, f.getName() + ".tmp");
                try (InputStream in = con.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                } finally {
                    con.disconnect();
                }
                if (!tmp.renameTo(f)) return null;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            if (o.outWidth <= 0) {
                f.delete();
                return null;
            }
            int sample = 1;
            while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getPath(), d);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }
}
