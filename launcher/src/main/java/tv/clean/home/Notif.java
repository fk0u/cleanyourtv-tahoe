package tv.clean.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.*;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Banner overlay (macOS-style, over any app) and prayer-time lookup.
 *  Not a NotificationListenerService: this stick runs low-RAM firmware (ro.config.low_ram=true),
 *  where Android refuses notification-listener access to non-system apps, so app notifications
 *  and Now Playing (same permission) are unavailable here. */
final class Notif {
    /** Epoch ms of the first prayer after `after` today (0 if none); its name goes into out[0]. */
    static long nextPrayer(SharedPreferences sp, long after, String[] out) {
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        if (!today.equals(sp.getString("prayer_date", ""))) return 0;
        for (String part : sp.getString("prayer", "").split(";")) {
            String[] kv = part.split("=");
            if (kv.length != 2) continue;
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(kv[1].substring(0, 2)));
            c.set(Calendar.MINUTE, Integer.parseInt(kv[1].substring(3, 5)));
            c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
            if (c.getTimeInMillis() > after) { if (out != null) out[0] = kv[0]; return c.getTimeInMillis(); }
        }
        return 0;
    }

    // ---- banner overlay ----

    static Drawable appIcon(Context c, String pkg) {
        try { return c.getPackageManager().getApplicationIcon(pkg); } catch (PackageManager.NameNotFoundException e) { return null; }
    }
    static String appLabel(Context c, String pkg) {
        try { PackageManager pm = c.getPackageManager(); return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); }
        catch (Exception e) { return pkg; }
    }

    static void banner(Context c, Drawable icon, String app, String title, String text) {
        if (!Settings.canDrawOverlays(c)) return; // grant: appops set tv.clean.home SYSTEM_ALERT_WINDOW allow
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        View v = card(c, icon, app, title, text);
        float d = c.getResources().getDisplayMetrics().density;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams((int) (360 * d), -2,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END; lp.x = (int) (24 * d); lp.y = (int) (40 * d);
        try { wm.addView(v, lp); } catch (Exception e) { return; }
        v.setTranslationX(400 * d); v.setAlpha(0);
        v.animate().translationX(0).alpha(1).setDuration(380).setInterpolator(new android.view.animation.DecelerateInterpolator(2)).start();
        v.postDelayed(() -> v.animate().translationX(400 * d).alpha(0).setDuration(300)
                .withEndAction(() -> { try { wm.removeView(v); } catch (Exception ignored) {} }).start(), 5500);
    }

    /** The banner card, also used by Home when overlays are not allowed. */
    static View card(Context c, Drawable icon, String app, String title, String text) {
        float d = c.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding((int) (14 * d), (int) (12 * d), (int) (16 * d), (int) (12 * d));
        row.setBackground(new GlassDrawable(22 * d, Ui.GLASS_STRONG, d));
        ImageView iv = new ImageView(c);
        if (icon != null) iv.setImageDrawable(icon); else iv.setImageResource(android.R.drawable.ic_popup_reminder);
        row.addView(iv, new LinearLayout.LayoutParams((int) (40 * d), (int) (40 * d)));
        LinearLayout col = new LinearLayout(c); col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding((int) (12 * d), 0, 0, 0);
        col.addView(line(c, app.toUpperCase(Locale.getDefault()), 11, 0x99FFFFFF));
        col.addView(line(c, title, 15, Color.WHITE));
        if (!text.isEmpty()) col.addView(line(c, text, 13, 0xCCFFFFFF));
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    static TextView line(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); t.setTextColor(color);
        t.setTypeface(Ui.font(c, 400)); t.setMaxLines(2); t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }
}
