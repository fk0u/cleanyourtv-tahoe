package tv.clean.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The start-up animation shown once each time the launcher process starts (TV switched on, or Tahoe restarted):
 *  the logo pops in with a soft cyan pulse, the name types in letter by letter, a light bar sweeps, then it fades away.
 *  Plain Views and property animators only (no blur, no extra layers) so it stays smooth on 1 GB TVs. */
final class Splash extends FrameLayout {
    static boolean shown;                    // once per process
    static volatile boolean active;          // keys are ignored while it plays
    private static final String NAME = "Tahoe TV";
    private final float S;
    private final TextView[] letters = new TextView[NAME.length()];
    private final View logo, ring, bar;
    private final Runnable onDone;
    private boolean ended;

    static void show(Home h, ViewGroup root, Runnable onDone) {
        if (shown) { onDone.run(); return; }
        shown = true; active = true;
        Splash s = new Splash(h, onDone);
        root.addView(s, new FrameLayout.LayoutParams(-1, -1));
        s.play(Ui.dur(h, 1000) > 0);
    }

    private Splash(Context c, Runnable onDone) {
        super(c);
        this.onDone = onDone;
        S = c.getResources().getDisplayMetrics().widthPixels / 1920f;
        setClickable(true); setFocusable(false);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF05060B, 0xFF0B0F24, 0xFF140B2B});
        setBackground(bg);

        ring = new View(c);
        GradientDrawable rg = new GradientDrawable(); rg.setShape(GradientDrawable.OVAL); rg.setColor(Color.TRANSPARENT); rg.setStroke(px(3), 0x8863D1FF);
        ring.setBackground(rg); ring.setAlpha(0f);
        addView(ring, lp(px(220), px(220), -px(70)));

        logo = new View(c);
        GradientDrawable lg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF1F3BD1, 0xFF63D1FF});
        lg.setCornerRadius(px(40)); logo.setBackground(lg);
        logo.setScaleX(0f); logo.setScaleY(0f);
        addView(logo, lp(px(160), px(160), -px(70)));

        LinearLayout word = new LinearLayout(c); word.setGravity(Gravity.CENTER);
        Typeface tf = Ui.font(c, 700);
        for (int i = 0; i < NAME.length(); i++) {
            TextView t = new TextView(c); t.setText(String.valueOf(NAME.charAt(i)).replace(' ', ' '));
            t.setTextColor(Color.WHITE); t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(84)); t.setTypeface(tf); t.setIncludeFontPadding(false);
            t.setAlpha(0f); t.setTranslationY(px(26));
            letters[i] = t; word.addView(t);
        }
        addView(word, lp(-2, -2, px(120)));

        bar = new View(c);
        GradientDrawable bg2 = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0x0063D1FF, 0xFF63D1FF, 0x0063D1FF});
        bg2.setCornerRadius(px(2)); bar.setBackground(bg2); bar.setAlpha(0f);
        addView(bar, lp(px(420), px(4), px(240)));
    }

    private FrameLayout.LayoutParams lp(int w, int h, int dy) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, Gravity.CENTER); p.topMargin = dy + px(0); return p;
    }
    private int px(float v) { return Math.round(v * S); }

    private void play(boolean motion) {
        if (!motion) { // reduced motion: the finished frame, held briefly
            logo.setScaleX(1f); logo.setScaleY(1f);
            for (TextView t : letters) { t.setAlpha(1f); t.setTranslationY(0f); }
            postDelayed(this::end, 700);
            return;
        }
        logo.animate().scaleX(1f).scaleY(1f).setDuration(650).setInterpolator(new OvershootInterpolator(1.6f)).start();
        // soft pulse ring: grows and fades, twice
        ValueAnimator pulse = ValueAnimator.ofFloat(0f, 1f); pulse.setDuration(1100); pulse.setRepeatCount(1); pulse.setStartDelay(350);
        pulse.addUpdateListener(a -> { float f = (float) a.getAnimatedValue(); ring.setScaleX(0.7f + f * 1.1f); ring.setScaleY(0.7f + f * 1.1f); ring.setAlpha(0.7f * (1f - f)); });
        pulse.start();
        for (int i = 0; i < letters.length; i++) {
            letters[i].animate().alpha(1f).translationY(0f).setStartDelay(500 + i * 70L).setDuration(380).setInterpolator(new DecelerateInterpolator(2f)).start();
        }
        bar.animate().alpha(1f).setStartDelay(1000).setDuration(250).start();
        ValueAnimator sweep = ValueAnimator.ofFloat(-1f, 1f); sweep.setDuration(900); sweep.setStartDelay(1050);
        sweep.addUpdateListener(a -> bar.setTranslationX((float) a.getAnimatedValue() * px(260)));
        sweep.start();
        postDelayed(this::end, 2500);
    }

    private void end() {
        if (ended) return; ended = true; active = false;
        animate().alpha(0f).setDuration(450).withEndAction(() -> { ViewGroup p = (ViewGroup) getParent(); if (p != null) p.removeView(this); onDone.run(); }).start();
    }
}
