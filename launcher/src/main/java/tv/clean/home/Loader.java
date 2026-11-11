package tv.clean.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shown while a channel loads, in the style of Apple TV / Netflix: a dim full-screen scrim, the channel's logo tile and
 *  name, and a tvOS-style spinner (12 ticks fading around a circle). It says nothing about sources or retries: the text
 *  line stays hidden and is only used for real problems (setText with a message shows it, an empty string hides it). */
final class Loader extends LinearLayout {
    final TextView name, status, logoText;
    final ImageView logo;
    final Ticks ticks;
    final FrameLayout tile;
    ValueAnimator spin;

    Loader(Context c) {
        super(c);
        setOrientation(VERTICAL); setGravity(Gravity.CENTER);
        setBackgroundColor(0xCC07080D); // dim the picture behind (black on first tune, last frame on reconnect)
        float d = Ui.dp(c, 1);

        tile = new FrameLayout(c);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xF5FFFFFF); bg.setCornerRadius(18 * d);
        tile.setBackground(bg); Ui.clip(tile, 18 * d);
        logoText = Ui.text(c, Ui.HEADLINE, 800, 0xFF111114); logoText.setShadowLayer(0, 0, 0, 0); logoText.setGravity(Gravity.CENTER);
        logo = new ImageView(c); logo.setScaleType(ImageView.ScaleType.FIT_CENTER); logo.setPadding((int) (12 * d), (int) (12 * d), (int) (12 * d), (int) (12 * d));
        tile.addView(logoText, new FrameLayout.LayoutParams(-1, -1)); tile.addView(logo, new FrameLayout.LayoutParams(-1, -1));
        addView(tile, new LayoutParams((int) (132 * d), (int) (88 * d)));

        name = Ui.text(c, Ui.HEADLINE, 700, Ui.TEXT);
        LayoutParams nl = new LayoutParams(-2, -2); nl.topMargin = (int) (16 * d);
        addView(name, nl);

        ticks = new Ticks(c);
        LayoutParams tl = new LayoutParams((int) (48 * d), (int) (48 * d)); tl.topMargin = (int) (22 * d);
        addView(ticks, tl);

        status = Ui.text(c, Ui.BODY, 500, Ui.TEXT_2);
        LayoutParams sl = new LayoutParams(-2, -2); sl.topMargin = (int) (14 * d);
        addView(status, sl);
        status.setVisibility(GONE);
    }

    /** Logo + name of the channel being loaded. */
    void channel(String logoUrl, String channelName) {
        Logos.into(logoUrl, logo, logoText, channelName);
        name.setText(channelName);
    }

    /** A problem to tell the person (e.g. the list could not be loaded). Empty = no text, just the spinner. */
    void setText(CharSequence s) {
        boolean has = s != null && s.length() > 0;
        status.setText(has ? s : ""); status.setVisibility(has ? VISIBLE : GONE);
    }

    @Override public void setVisibility(int v) {
        boolean show = v == VISIBLE, was = getVisibility() == VISIBLE && getAlpha() > 0;
        if (show && !was) { super.setVisibility(VISIBLE); setAlpha(0); animate().alpha(1).setDuration(Ui.MED).start(); start(); }
        else if (!show && was) animate().alpha(0).setDuration(Ui.MED).withEndAction(() -> { super.setVisibility(v); stop(); }).start();
        else if (!show) { super.setVisibility(v); stop(); }
    }

    void start() {
        if (spin != null || Ui.motion(getContext()) == 0) return; // "remove animations": a still spinner
        spin = ValueAnimator.ofFloat(0, 12); spin.setDuration(1000); spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        spin.addUpdateListener(a -> { ticks.phase = (int) ((float) a.getAnimatedValue()) % 12; ticks.invalidate(); });
        spin.start();
    }

    void stop() { if (spin != null) { spin.cancel(); spin = null; } }

    /** 12 rounded ticks around a circle; the brightest moves clockwise and the others fade behind it. */
    static final class Ticks extends View {
        int phase;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Ticks(Context c) { super(c); p.setColor(0xFFFFFFFF); p.setStrokeCap(Paint.Cap.ROUND); }

        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy);
            p.setStrokeWidth(r * 0.14f);
            for (int i = 0; i < 12; i++) {
                int behind = ((phase - i) % 12 + 12) % 12; // 0 = brightest
                p.setAlpha((int) (255 * (0.2f + 0.8f * (1f - behind / 11f))));
                c.save(); c.rotate(i * 30f, cx, cy);
                c.drawLine(cx, cy - r * 0.95f, cx, cy - r * 0.6f, p);
                c.restore();
            }
        }
    }
}
