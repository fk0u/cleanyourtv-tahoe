package tv.clean.home;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.*;
import android.widget.TextView;

/** Design tokens and small helpers for the tvOS-26-style UI. Every view takes its numbers from here. */
final class Ui {
    // Colour: text at three emphasis levels on dark glass / photos.
    static final int TEXT = 0xFFF5F5F7, TEXT_2 = 0xB3F5F5F7, TEXT_3 = 0x73F5F5F7;
    static final int GLASS = 0x5C17171A, GLASS_STRONG = 0xE6141417, GLASS_FOCUS = 0x47FFFFFF;
    static final int ACCENT = 0xFF64D2FF;

    // Type scale (sp) and weights for the Outfit variable font.
    static final float DISPLAY = 96, TITLE = 44, HEADLINE = 26, BODY = 17, CAPTION = 12.5f;

    // Radii (dp) and motion (ms).
    static final float R_SMALL = 12, R_MED = 18, R_LARGE = 28;
    static final int FAST = 180, MED = 260, SLOW = 420;

    static final SparseArray<Typeface> fonts = new SparseArray<>();

    static Typeface font(Context c, int wght) {
        Typeface t = fonts.get(wght);
        if (t == null) {
            try { t = new Typeface.Builder(c.getAssets(), "Outfit.ttf").setFontVariationSettings("'wght' " + wght).build(); }
            catch (Exception e) { t = Typeface.DEFAULT; }
            fonts.put(wght, t);
        }
        return t;
    }

    static float dp(Context c, float v) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()); }

    static TextView text(Context c, float sp, int wght, int color) {
        TextView t = new TextView(c);
        t.setTextSize(sp); t.setTypeface(font(c, wght)); t.setTextColor(color);
        t.setSingleLine(); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        t.setShadowLayer(dp(c, 10), 0, dp(c, 1), 0x66000000);
        return t;
    }

    /** Caption in small caps with tracking, the "eyebrow" above editorial titles. */
    static TextView eyebrow(Context c, int color) {
        TextView t = text(c, CAPTION, 600, color);
        t.setLetterSpacing(0.12f); t.setAllCaps(true);
        return t;
    }

    static ViewOutlineProvider round(float r) {
        return new ViewOutlineProvider() { public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); } };
    }

    static void clip(View v, float r) { v.setOutlineProvider(round(r)); v.setClipToOutline(true); }

    /** System "remove animations" setting: 0 means skip motion. */
    static float motion(Context c) {
        return Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
    }

    static android.view.animation.Interpolator spring() { return new OvershootInterpolator(1.05f); }
    static android.view.animation.Interpolator out() { return new DecelerateInterpolator(2.2f); }

    /** Text colour that reads on top of `bg`. */
    static int onColor(int bg) { return Color.luminance(bg) > 0.55f ? 0xFF111114 : TEXT; }

    /** Saturated, bright version of a colour, for glows. */
    static int vivid(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[1] = Math.min(1, hsv[1] * 1.5f + 0.15f); hsv[2] = Math.max(hsv[2], 0.85f);
        return Color.HSVToColor(hsv);
    }

    /** Average colour of a drawable (sampled at 16×9), for glows and transitions. */
    static int average(Drawable d) {
        Bitmap bm = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888);
        Rect old = d.copyBounds();
        d.setBounds(0, 0, 16, 9); d.draw(new Canvas(bm)); d.setBounds(old);
        int[] px = new int[16 * 9]; bm.getPixels(px, 0, 16, 0, 0, 16, 9);
        long r = 0, g = 0, b = 0; int n = 0;
        for (int p : px) { if (Color.alpha(p) < 128) continue; r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++; }
        bm.recycle();
        return n == 0 ? 0xFF2C2C2E : Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
    }
}
