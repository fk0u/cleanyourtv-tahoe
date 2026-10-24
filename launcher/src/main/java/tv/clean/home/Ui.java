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

    // Type scale: Apple HIG tvOS sizes (px at 1080p) halved to dp on this 2x display:
    // Title1 76 → 38, Title3 48 → 24, Body 29 → 15, Caption 23 → 12. DISPLAY is the lock/sheet clock.
    static final float DISPLAY = 96, TITLE = 38, HEADLINE = 24, BODY = 15, CAPTION = 12;

    // tvOS safe area at 1080p: 60 px top/bottom, 80 px sides (→ 30 / 40 dp). Content never goes past it.
    static final float SAFE_TOP = 30, SAFE_SIDE = 56;

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

    /** Lucide icon (vector drawable), tinted. */
    static android.widget.ImageView icon(Context c, int res, float sizeDp, int color) {
        android.widget.ImageView v = new android.widget.ImageView(c);
        v.setImageResource(res);
        v.setImageTintList(android.content.res.ColorStateList.valueOf(color));
        v.setLayoutParams(new android.widget.LinearLayout.LayoutParams((int) dp(c, sizeDp), (int) dp(c, sizeDp)));
        return v;
    }

    /** tvOS breadcrumb pill ("‹ [icon] Cuaca") for the top-left of full-screen sheets, and a clock pill for the top-right. */
    static android.widget.FrameLayout.LayoutParams crumb(android.widget.FrameLayout parent, int iconRes, String title) {
        Context c = parent.getContext();
        android.widget.LinearLayout pill = new android.widget.LinearLayout(c);
        pill.setGravity(android.view.Gravity.CENTER_VERTICAL);
        pill.setBackground(new GlassDrawable(dp(c, 18), GLASS, dp(c, 1)));
        pill.setPadding((int) dp(c, 8), (int) dp(c, 6), (int) dp(c, 14), (int) dp(c, 6));
        pill.addView(icon(c, R.drawable.ic_chevron_left, 16, TEXT_2));
        android.widget.ImageView i = icon(c, iconRes, 16, TEXT);
        ((android.widget.LinearLayout.LayoutParams) i.getLayoutParams()).leftMargin = (int) dp(c, 6);
        pill.addView(i);
        TextView t = text(c, 14, 500, TEXT); t.setText(title); t.setPadding((int) dp(c, 8), 0, 0, 0);
        pill.addView(t);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(-2, -2);
        lp.leftMargin = (int) dp(c, SAFE_SIDE - 16); lp.topMargin = (int) dp(c, SAFE_TOP);
        parent.addView(pill, lp);
        TextView clock = text(c, 18, 500, TEXT);
        clock.setText(new java.text.SimpleDateFormat("HH.mm", java.util.Locale.getDefault()).format(new java.util.Date()));
        clock.setBackground(new GlassDrawable(dp(c, 20), GLASS, dp(c, 1)));
        clock.setPadding((int) dp(c, 14), (int) dp(c, 7), (int) dp(c, 14), (int) dp(c, 7));
        android.widget.FrameLayout.LayoutParams cl = new android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.END);
        cl.rightMargin = (int) dp(c, SAFE_SIDE - 16); cl.topMargin = (int) dp(c, SAFE_TOP);
        parent.addView(clock, cl);
        return lp;
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
