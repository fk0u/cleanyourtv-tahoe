package tv.clean.home;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.UiModeManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import java.util.Locale;
import tech.kousozo.tahoetv.core.ControlPlane;
import tech.kousozo.tahoetv.core.TahoeVersion;

/** Connects Tahoe TV (TV & Phone) to the Control Plane for telemetry, SSE/background push, and in-app banners. */
final class PushHelper {
    private static final String CHANNEL_ID = "tahoe_push";
    private static final int JOB_ID = 4401;

    static boolean isTv(Context c) {
        UiModeManager um = (UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
        if (um != null && um.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) return true;
        return c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    static ControlPlane.DeviceProfile profile(Context c) {
        String id = ControlPlane.deviceId(c.getFilesDir());
        String platform = isTv(c) ? "android_tv" : "android_phone";
        String arch = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "";
        tech.kousozo.tahoetv.core.Viewer v = TvViewer.get(c);
        return new ControlPlane.DeviceProfile(
                id,
                "tv_home",
                platform,
                Build.VERSION.RELEASE != null ? Build.VERSION.RELEASE : "",
                (Build.MANUFACTURER + " " + Build.MODEL).trim(),
                TahoeVersion.VERSION,
                TahoeVersion.VERSION_CODE,
                arch,
                v.getCountry(),
                v.getLanguage().isEmpty() ? Locale.getDefault().getLanguage() : v.getLanguage()
        );
    }

    static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Info & Pembaruan Tahoe TV", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Notifikasi pembaruan dan siaran penting dari Tahoe TV");
        nm.createNotificationChannel(ch);
    }

    static void scheduleBackgroundPoll(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(c, PushJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(30 * 60_000L)
                    .setPersisted(true)
                    .build();
            js.schedule(info);
        } catch (Exception ignored) {}
    }

    static void showSystemNotification(Context c, ControlPlane.PushNotification n) {
        ensureChannel(c);
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Intent tapIntent = !n.getActionUrl().isEmpty() && (n.getActionUrl().startsWith("https://") || n.getActionUrl().startsWith("http://"))
                ? new Intent(Intent.ACTION_VIEW, Uri.parse(n.getActionUrl()))
                : new Intent(c, Home.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, (int) (n.getId() & 0x7fff), tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new Notification.Builder(c, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_bell)
                .setContentTitle(n.getTitle())
                .setContentText(n.getBody())
                .setStyle(new Notification.BigTextStyle().bigText(n.getBody()))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        nm.notify((int) (n.getId() % 100000), notif);
    }

    static void showInAppBanner(Home h, String app, String title, String text) {
        if (h.isFinishing() || h.root == null) return;
        // System overlay if permitted, plus guaranteed in-app glass banner inside root (works on phones & restricted TVs)
        Notif.banner(h, null, app, title, text);
        if (android.provider.Settings.canDrawOverlays(h)) return;
        View card = Notif.card(h, null, app, title, text);
        float d = Ui.dp(h, 1);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) Math.min(360 * d, Math.max(260 * d, h.W * 0.88f)), -2, Gravity.TOP | Gravity.END);
        lp.topMargin = (int) (24 * d);
        lp.rightMargin = (int) (20 * d);
        h.root.addView(card, lp);
        card.setTranslationX(400 * d);
        card.setAlpha(0f);
        card.animate().translationX(0).alpha(1f).setDuration(350).start();
        card.setOnClickListener(v -> h.root.removeView(card));
        card.postDelayed(() -> card.animate().translationX(400 * d).alpha(0f).setDuration(280)
                .withEndAction(() -> h.root.removeView(card)).start(), 6000);
    }
}
