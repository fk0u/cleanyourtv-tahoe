package tv.clean.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shown while a channel loads (instead of a black screen): the channel's logo tile inside a rotating cyan ring with a
 *  soft pulse, its name, and a status line. Same setText/setVisibility as the TextView it replaces. */
final class Loader extends LinearLayout {
    final TextView name, status, logoText;
    final ImageView logo;
    final Ring ring;
    ValueAnimator spin;

    Loader(Context c) {
        super(c);
        setOrientation(VERTICAL); setGravity(Gravity.CENTER_HORIZONTAL);
        float d = Ui.dp(c, 1);

        FrameLayout stage = new FrameLayout(c);
        ring = new Ring(c);
        stage.addView(ring, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout tile = new FrameLayout(c);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xF5FFFFFF); bg.setCornerRadius(18 * d);
        tile.setBackground(bg); Ui.clip(tile, 18 * d);
        logoText = Ui.text(c, Ui.HEADLINE, 800, 0xFF111114); logoText.setShadowLayer(0, 0, 0, 0); logoText.setGravity(Gravity.CENTER);
        logo = new ImageView(c); logo.setScaleType(ImageView.ScaleType.FIT_CENTER); logo.setPadding((int) (12 * d), (int) (12 * d), (int) (12 * d), (int) (12 * d));
        tile.addView(logoText, new FrameLayout.LayoutParams(-1, -1)); tile.addView(logo, new FrameLayout.LayoutParams(-1, -1));
        stage.addView(tile, new FrameLayout.LayoutParams((int) (120 * d), (int) (80 * d), Gravity.CENTER));
        addView(stage, new LayoutParams((int) (200 * d), (int) (200 * d)));

        name = Ui.text(c, Ui.HEADLINE, 700, Ui.TEXT);
        LayoutParams nl = new LayoutParams(-2, -2); nl.topMargin = (int) (6 * d);
        addView(name, nl);
        status = Ui.text(c, Ui.BODY, 500, Ui.TEXT_2);
        LayoutParams sl = new LayoutParams(-2, -2); sl.topMargin = (int) (6 * d);
        addView(status, sl);
    }

    /** Logo + name of the channel being loaded. */
    void channel(String logoUrl, String channelName) {
        Logos.into(logoUrl, logo, logoText, channelName);
        name.setText(channelName);
    }

    void setText(CharSequence s) { status.setText(s); }

    @Override public void setVisibility(int v) {
        boolean show = v == VISIBLE, was = getVisibility() == VISIBLE && getAlpha() > 0;
        if (show && !was) { super.setVisibility(VISIBLE); setAlpha(0); animate().alpha(1).setDuration(Ui.MED).start(); start(); }
        else if (!show && was) animate().alpha(0).setDuration(Ui.MED).withEndAction(() -> { super.setVisibility(v); stop(); }).start();
        else if (!show) { super.setVisibility(v); stop(); }
    }

    void start() {
        if (spin != null || Ui.motion(getContext()) == 0) return; // "remove animations": static ring
        spin = ValueAnimator.ofFloat(0, 1); spin.setDuration(1200); spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        spin.addUpdateListener(a -> { ring.t = (float) a.getAnimatedValue(); ring.invalidate(); });
        spin.start();
    }

    void stop() { if (spin != null) { spin.cancel(); spin = null; } }

    /** Rotating cyan arc with a fading tail, over a faint pulsing halo. */
    static final class Ring extends View {
        float t;
        final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG), halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF box = new RectF();

        Ring(Context c) {
            super(c);
            arc.setStyle(Paint.Style.STROKE); arc.setStrokeCap(Paint.Cap.ROUND); arc.setStrokeWidth(Ui.dp(c, 4));
            halo.setStyle(Paint.Style.STROKE); halo.setStrokeWidth(Ui.dp(c, 2));
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            float in = Ui.dp(getContext(), 12);
            box.set(in, in, w - in, h - in);
            arc.setShader(new SweepGradient(w / 2f, h / 2f, new int[]{0x0064D2FF, Ui.ACCENT, Brand_CYAN_LIGHT}, new float[]{0f, 0.7f, 1f}));
        }

        static final int Brand_CYAN_LIGHT = tech.kousozo.tahoetv.core.Brand.CYAN_LIGHT;

        @Override protected void onDraw(Canvas c) {
            float pulse = (float) (0.5 + 0.5 * Math.sin(t * 2 * Math.PI));
            halo.setColor((Ui.ACCENT & 0x00FFFFFF) | ((int) (40 + 50 * pulse) << 24));
            float grow = Ui.dp(getContext(), 6) * pulse;
            c.drawOval(box.left - grow, box.top - grow, box.right + grow, box.bottom + grow, halo);
            c.save();
            c.rotate(t * 360, getWidth() / 2f, getHeight() / 2f);
            c.drawArc(box, 0, 300, false, arc);
            c.restore();
        }
    }
}
