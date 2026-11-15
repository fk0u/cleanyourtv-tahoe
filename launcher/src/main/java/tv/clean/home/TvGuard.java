package tv.clean.home;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import tech.kousozo.tahoetv.core.ControlPlane;
import tech.kousozo.tahoetv.core.I18n;
import tech.kousozo.tahoetv.core.Region;

/**
 * Runtime device-role guard ensuring Tahoe TV Launcher runs strictly on Android TV / Google TV
 * devices and blocks accidental sideload usage on phones or tablets.
 */
final class TvGuard {
    private TvGuard() {}

    static boolean isAndroidTv(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                || pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)) {
            return true;
        }
        UiModeManager ui = (UiModeManager) ctx.getSystemService(Context.UI_MODE_SERVICE);
        return ui != null && ui.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
    }

    /** Returns true and renders an access-blocked screen when launched outside Android TV. */
    static boolean blockIfNotTv(Activity a) {
        if (isAndroidTv(a)) return false;
        String lang = Region.suggest(java.util.Locale.getDefault()).getFirst();

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#0B0B0F"));
        int pad = (int) Ui.dp(a, 28);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int cPad = (int) Ui.dp(a, 24);
        card.setPadding(cPad, cPad, cPad, cPad);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#161822"));
        cardBg.setCornerRadius(Ui.dp(a, 20));
        cardBg.setStroke((int) Ui.dp(a, 1), Color.parseColor("#2A2E42"));
        card.setBackground(cardBg);

        TextView title = Ui.text(a, 20, 700, Color.WHITE);
        title.setSingleLine(false);
        title.setText(I18n.get(lang, "guard.tv_only_title"));
        title.setGravity(Gravity.CENTER);
        card.addView(title);

        TextView body = Ui.text(a, 14, 400, Color.parseColor("#C2C6D6"));
        body.setSingleLine(false);
        body.setText(I18n.get(lang, "guard.tv_only_body"));
        body.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = (int) Ui.dp(a, 12);
        card.addView(body, bp);

        TextView openBtn = actionButton(a, I18n.get(lang, "guard.open_downloads"), Color.parseColor("#4DA3FF"), Color.parseColor("#0B0B0F"));
        openBtn.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(ControlPlane.INSTANCE.getBaseUrl()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { a.startActivity(i); } catch (Throwable ignored) {}
            a.finishAndRemoveTask();
        });
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-1, -2);
        op.topMargin = (int) Ui.dp(a, 20);
        card.addView(openBtn, op);

        TextView closeBtn = actionButton(a, I18n.get(lang, "guard.close"), Color.parseColor("#242838"), Color.WHITE);
        closeBtn.setOnClickListener(v -> a.finishAndRemoveTask());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.topMargin = (int) Ui.dp(a, 10);
        card.addView(closeBtn, cp);

        root.addView(card, new LinearLayout.LayoutParams(-1, -2));
        a.setContentView(root);
        return true;
    }

    private static TextView actionButton(Activity a, String label, int bgColor, int fgColor) {
        TextView btn = Ui.text(a, 15, 600, fgColor);
        btn.setText(label);
        btn.setGravity(Gravity.CENTER);
        btn.setFocusable(true);
        btn.setClickable(true);
        int h = (int) Ui.dp(a, 18);
        int v = (int) Ui.dp(a, 13);
        btn.setPadding(h, v, h, v);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(Ui.dp(a, 12));
        btn.setBackground(bg);
        return btn;
    }
}
