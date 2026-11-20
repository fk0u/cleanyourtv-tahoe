package tv.clean.home;

import android.content.Context;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import tech.kousozo.tahoetv.core.LocalTv;

/** "On now" shelf above the dock while Live TV is focused: a row of channel cards, light Views only, not focusable
 *  (the dock stays the single focus system). Fades in and out with the hero preview. */
final class LiveStrip extends LinearLayout {
    private boolean shown;

    LiveStrip(Context c) {
        super(c);
        setOrientation(HORIZONTAL);
        setAlpha(0f);
        setVisibility(GONE);
        setClipChildren(false);
    }

    /** Shows up to [max] channels; rebuilds only when the list changed. */
    void show(List<LocalTv.Entry> entries, int max, float cardW, float cardH, float gap) {
        if (entries == null || entries.isEmpty()) { hide(); return; }
        if (getChildCount() == 0 || getTag() != entries) {
            removeAllViews();
            setTag(entries);
            Context c = getContext();
            for (int i = 0; i < Math.min(max, entries.size()); i++) {
                LocalTv.Entry e = entries.get(i);
                FrameLayout card = new FrameLayout(c);
                card.setBackground(new GlassDrawable(Ui.dp(c, 16), Ui.GLASS, Ui.dp(c, 1)));
                TextView fb = Ui.text(c, 18, 700, Ui.TEXT_2); fb.setShadowLayer(0, 0, 0, 0); fb.setGravity(Gravity.CENTER);
                card.addView(fb, new FrameLayout.LayoutParams(-1, -1));
                ImageView logo = new ImageView(c); logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(-1, -1);
                ll.setMargins((int) Ui.dp(c, 14), (int) Ui.dp(c, 10), (int) Ui.dp(c, 14), (int) Ui.dp(c, 30));
                card.addView(logo, ll);
                Ui.bindLogo(e.getChannel().getLogo(), logo, fb, e.getName());
                TextView name = Ui.text(c, 12, 600, Ui.TEXT); name.setGravity(Gravity.CENTER);
                name.setText(e.getName()); name.setShadowLayer(0, 0, 0, 0);
                card.addView(name, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
                name.setPadding(8, 0, 8, (int) Ui.dp(c, 8));
                LayoutParams lp = new LayoutParams((int) cardW, (int) cardH);
                if (i > 0) lp.setMarginStart((int) gap);
                addView(card, lp);
            }
        }
        if (shown) return;
        shown = true;
        setVisibility(VISIBLE);
        animate().cancel();
        setTranslationY(Ui.dp(getContext(), 12));
        animate().alpha(1f).translationY(0).setDuration(Ui.dur(getContext(), Ui.MED)).setInterpolator(Ui.out()).start();
    }

    void hide() {
        if (!shown) return;
        shown = false;
        animate().cancel();
        animate().alpha(0f).setDuration(Ui.dur(getContext(), Ui.FAST)).withEndAction(() -> { if (!shown) setVisibility(GONE); }).start();
    }
}
