package io.canais;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The light look (Apple TV's): gray gradient, dark text, white cards that lift on focus. Views are built in code. */
final class Ui {
    static final int INK = 0xFF1C1C1E, DIM = 0xFF6E6E73, FAINT = 0xFF8E8E93, CARD = 0xFFDADADF, WHITE = 0xFFFFFFFF,
            GREEN = 0xFF30B856;
    static final ExecutorService IO = Executors.newFixedThreadPool(2);
    static final Handler MAIN = new Handler(Looper.getMainLooper());
    static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable background() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xFFF5F5F7, 0xFFE2E2E7});
    }

    static TextView text(Context c, String s, float sp, int color, Typeface face) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (face != null) t.setTypeface(face);
        t.setIncludeFontPadding(false);
        return t;
    }

    static TextView line(Context c, String s, float sp, int color, Typeface face) {
        TextView t = text(c, s, sp, color, face);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    static GradientDrawable round(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    static void roundCorners(View v, float radius) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        v.setClipToOutline(true);
    }

    /** How much a focused card grows. */
    static final float LIFT = 1.12f;

    /** The tvOS focus: grows and casts a deeper shadow. */
    static void lift(View v, boolean on) {
        float s = on ? LIFT : 1f;
        v.animate().scaleX(s).scaleY(s).translationZ(on ? dp(v.getContext(), 14) : 0).setDuration(160).start();
    }

    /**
     * A text tab or chip: the chosen one in bold ink; with the focus, the white pill with a shadow (tvOS). `chosen` is
     * kept in the view's "selected" state.
     */
    static TextView pill(Context c, String label, float sp) {
        TextView t = text(c, label, sp, DIM, MEDIUM);
        int h = dp(c, 18), v = dp(c, 9);
        t.setPadding(h, v, h, v);
        t.setFocusable(true);
        t.setGravity(Gravity.CENTER);
        t.setOnFocusChangeListener((view, has) -> paintPill((TextView) view));
        return t;
    }

    static void choose(TextView t, boolean chosen) {
        t.setSelected(chosen);
        paintPill(t);
    }

    private static void paintPill(TextView t) {
        boolean focus = t.hasFocus();
        t.setTextColor(focus || t.isSelected() ? INK : DIM);
        t.setTypeface(focus || t.isSelected() ? BOLD : MEDIUM);
        t.setBackground(focus ? round(WHITE, dp(t.getContext(), 40)) : null);
        t.setElevation(focus ? dp(t.getContext(), 6) : 0);
        float s = focus ? 1.06f : 1f;
        t.animate().scaleX(s).scaleY(s).setDuration(140).start();
    }

    /**
     * A card: rounded image on a gray placeholder, caption below. `logo`: fit inside a dark card (channel logos: many
     * lists use white logos, which vanish on white).
     */
    static final class Card {
        final LinearLayout root;
        final FrameLayout frame;
        final ImageView image;
        final TextView title, sub, placeholder;   // placeholder: the name, when there's no image

        Card(Context c, int w, int h, boolean logo) {
            root = new LinearLayout(c);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setClipChildren(false);
            frame = new FrameLayout(c);
            frame.setBackground(round(logo ? 0xFF2C2C2E : CARD, dp(c, 14)));
            roundCorners(frame, dp(c, 14));
            frame.setElevation(dp(c, 3));
            image = new ImageView(c);
            image.setScaleType(logo ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
            int p = logo ? dp(c, 16) : 0;
            image.setPadding(p, p, p, p);
            placeholder = text(c, "", logo ? 17 : 15, logo ? 0xCCFFFFFF : DIM, BOLD);
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setMaxLines(3);
            placeholder.setPadding(dp(c, 10), 0, dp(c, 10), 0);
            frame.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
            frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
            root.addView(frame, new LinearLayout.LayoutParams(w, h));
            title = line(c, "", 14, INK, MEDIUM);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(w, -2);
            tl.topMargin = dp(c, 10);
            root.addView(title, tl);
            sub = line(c, "", 12, FAINT, null);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(w, -2);
            sl.topMargin = dp(c, 3);
            root.addView(sub, sl);
        }

        void focus(boolean on) {
            frame.setPivotX(frame.getWidth() / 2f);
            frame.setPivotY(frame.getHeight() / 2f);
            lift(frame, on);
            ring(on);
            // The caption moves down with the grown image, so it isn't covered
            float down = on ? frame.getLayoutParams().height * (LIFT - 1) / 2 : 0;
            title.animate().translationY(down).setDuration(160).start();
            sub.animate().translationY(down).setDuration(160).start();
            title.setTypeface(on ? BOLD : MEDIUM);
        }

        /** The same, at once (a recycled card in a grid). */
        void set(boolean on) {
            float s = on ? LIFT : 1f, down = on ? frame.getLayoutParams().height * (LIFT - 1) / 2 : 0;
            frame.animate().cancel();
            title.animate().cancel();
            sub.animate().cancel();
            frame.setScaleX(s);
            frame.setScaleY(s);
            frame.setTranslationZ(on ? dp(frame.getContext(), 14) : 0);
            ring(on);
            title.setTranslationY(down);
            sub.setTranslationY(down);
            title.setTypeface(on ? BOLD : MEDIUM);
        }

        /** A thick outline around the focused card, dark with a white line inside: it shows on any picture. */
        private void ring(boolean on) {
            if (!on) {
                frame.setForeground(null);
                return;
            }
            Context c = frame.getContext();
            int dark = dp(c, 4), light = dp(c, 3);
            GradientDrawable outer = new GradientDrawable(), inner = new GradientDrawable();
            outer.setCornerRadius(dp(c, 14));
            outer.setStroke(dark, INK);
            inner.setCornerRadius(dp(c, 14) - dark);
            inner.setStroke(light, WHITE);
            android.graphics.drawable.LayerDrawable g =
                    new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {outer, inner});
            g.setLayerInset(1, dark, dark, dark, dark);
            frame.setForeground(g);
        }
    }
}
