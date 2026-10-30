package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.Map;

/**
 * The minimal layout's app marks: each brand's official symbol alone (Netflix's N, YouTube's play button, Prime
 * Video's smile...), white (or tinted), from Simple Icons (res/drawable/mark_*.xml). Each is sized to the same optical weight,
 * rendered once at its focused size and then only drawn scaled (no work per frame).
 */
final class Marks {
    private static final Map<String, Integer> RES = new HashMap<>();
    private static final Map<String, Bitmap> CACHE = new HashMap<>();
    private static final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final RectF dst = new RectF();
    private static int inkNow = 0xFFFFFFFF;

    static {
        RES.put("netflix", R.drawable.mark_netflix);
        RES.put("youtube", R.drawable.mark_youtube);
        RES.put("prime", R.drawable.mark_prime);
        RES.put("max", R.drawable.mark_max);
        RES.put("spotify", R.drawable.mark_spotify);
        RES.put("appletv", R.drawable.mark_appletv);
    }

    private Marks() {}

    /**
     * Draws `key`'s mark centered at (cx, cy) for a slot `size` px tall (`largest`: the slot at full focus, the size it's
     * rendered at), in `ink`; false when the app has no mark.
     */
    static boolean draw(Context c, Canvas canvas, String key, float cx, float cy, float size, float largest, int alpha,
                        int ink) {
        Integer res = RES.get(key);
        if (res == null) return false;
        Bitmap b = CACHE.get(key);
        if (b == null || b.getHeight() > largest * 1.01f + 1 || Math.max(b.getWidth(), b.getHeight()) < largest * 0.3f) {
            b = render(c, res, largest);
            if (b == null) return false;
            CACHE.put(key, b);
        }
        float scale = size / largest;
        float w = b.getWidth() * scale, h = b.getHeight() * scale;
        dst.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        if (ink != inkNow) {
            inkNow = ink;
            paint.setColorFilter(ink == 0xFFFFFFFF ? null : new PorterDuffColorFilter(ink, PorterDuff.Mode.SRC_IN));
        }
        paint.setAlpha(alpha);
        canvas.drawBitmap(b, null, dst, paint);
        return true;
    }

    /** Same visual weight for every mark (√(w·h) = 80% of the slot), never taller than it nor wider than twice it. */
    private static Bitmap render(Context c, int res, float slot) {
        Drawable d = c.getDrawable(res);
        if (d == null) return null;
        float iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        float s = slot * 0.8f / (float) Math.sqrt(iw * ih);
        s = Math.min(s, Math.min(slot / ih, slot * 2 / iw));
        int w = Math.max(1, Math.round(iw * s)), h = Math.max(1, Math.round(ih * s));
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, w, h);
        d.draw(new Canvas(b));
        return b;
    }
}
