package tv.clean.home;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

/** App tile: rounded banner that lifts on focus (scale + elevation shadow) with a specular
 *  shine sweeping across once, like tvOS. The label lives outside (dock tooltip / grid caption). */
final class Tile extends FrameLayout {
    final ImageView image;
    final Shine shine;
    final View dot; // "running" indicator under dock tiles
    final float w, h, radius;
    final Home.App app;
    float scale = 1f;

    Tile(Context c, Home.App app, float w, float h) {
        super(c);
        this.app = app; this.w = w; this.h = h; this.radius = Ui.dp(c, Ui.R_SMALL);
        setClipChildren(false); setClipToPadding(false);
        image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImageDrawable(app.banner);
        image.setBackgroundColor(0xFF2C2C2E);
        Ui.clip(image, radius);
        addView(image, new LayoutParams((int) w, (int) h));
        shine = new Shine(c);
        Ui.clip(shine, radius);
        addView(shine, new LayoutParams((int) w, (int) h));
        dot = new View(c);
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL); d.setColor(0xCCFFFFFF);
        dot.setBackground(d); dot.setAlpha(0);
        int ds = (int) Ui.dp(c, 5);
        LayoutParams dl = new LayoutParams(ds, ds); dl.leftMargin = (int) (w - ds) / 2; dl.topMargin = (int) (h + Ui.dp(c, 9));
        addView(dot, dl);
        setPivotX(w / 2); setPivotY(h);
    }

    /** Scale relative to the bottom edge (dock magnification grows upward). */
    void lift(float s, boolean focused, int ms) {
        scale = s;
        animate().scaleX(s).scaleY(s).translationZ(focused ? Ui.dp(getContext(), 18) : 0)
                .setDuration(ms).setInterpolator(Ui.spring()).start();
        if (focused && ms > 0) shine.sweep();
    }

    void running(boolean on) { dot.animate().alpha(on ? 1 : 0).setDuration(Ui.FAST).start(); }

    /** A diagonal band of light that crosses the tile once. */
    static final class Shine extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float t = -1;
        Shine(Context c) { super(c); setWillNotDraw(false); }

        void sweep() {
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0, 1);
            a.setDuration(650); a.setInterpolator(Ui.out());
            a.addUpdateListener(v -> { t = (float) v.getAnimatedValue(); invalidate(); });
            a.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator x) { t = -1; invalidate(); }
            });
            a.start();
        }

        @Override protected void onDraw(Canvas c) {
            if (t < 0) return;
            float w = getWidth(), x = -w * 0.6f + t * w * 2.2f;
            p.setShader(new LinearGradient(x, 0, x + w * 0.35f, getHeight(), new int[]{0x00FFFFFF, 0x40FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, getHeight(), p);
        }
    }

    static Drawable iconBanner(Context c, Drawable icon, String label) {
        Bitmap bm = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bm);
        int avg = icon == null ? 0xFF2C2C2E : Ui.average(icon);
        cv.drawColor(blend(avg, 0xFF1C1C1E, 0.65f));
        if (icon != null) { icon.setBounds(124, 38, 196, 110); icon.draw(cv); }
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(0xE6FFFFFF); tp.setTextSize(22); tp.setTypeface(Ui.font(c, 500)); tp.setTextAlign(Paint.Align.CENTER);
        String l = label.length() > 22 ? label.substring(0, 21) + "…" : label;
        cv.drawText(l, 160, 146, tp);
        return new android.graphics.drawable.BitmapDrawable(c.getResources(), bm);
    }

    static int blend(int a, int b, float t) {
        return Color.rgb((int) (Color.red(a) * (1 - t) + Color.red(b) * t), (int) (Color.green(a) * (1 - t) + Color.green(b) * t),
                (int) (Color.blue(a) * (1 - t) + Color.blue(b) * t));
    }
}
