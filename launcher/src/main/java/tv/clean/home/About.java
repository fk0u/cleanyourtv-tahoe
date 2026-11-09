package tv.clean.home;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import tech.kousozo.tahoetv.core.Updates;

/** Pusat Kontrol → Tentang: version, the latest release's changelog, and OTA (download the release APK and hand it to
 *  Android's installer, which shows one confirmation screen). Also the once-a-day background check. */
final class About extends FrameLayout implements Sheet {
    static final String APK = "tahoe-launcher.apk";
    final Home home;
    final TextView status, notes;
    final ScrollView scroll;
    Updates.Release latest;
    boolean busy;

    About(Home h) {
        super(h);
        home = h;
        float d = Ui.dp(h, 1);
        setBackgroundColor(0xF2080A10);
        Ui.crumb(this, R.drawable.ic_info, "Tentang & pembaruan");

        LinearLayout col = new LinearLayout(h); col.setOrientation(LinearLayout.VERTICAL);
        TextView eb = Ui.eyebrow(h, Ui.TEXT_3); eb.setText(tech.kousozo.tahoetv.core.Brand.TAGLINE);
        TextView ti = Ui.text(h, Ui.TITLE, 700, Ui.TEXT); ti.setText(tech.kousozo.tahoetv.core.Brand.NAME + " " + version(h));
        status = Ui.text(h, Ui.BODY, 600, Ui.ACCENT); status.setText("Mengecek pembaruan…");
        notes = Ui.text(h, Ui.BODY, 400, Ui.TEXT_2); notes.setSingleLine(false); notes.setLineSpacing(0, 1.35f);
        scroll = new ScrollView(h); scroll.addView(notes); scroll.setFocusable(false);
        col.addView(eb); col.addView(ti);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2); sl.topMargin = (int) (8 * d);
        col.addView(status, sl);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, (int) (300 * d)); nl.topMargin = (int) (22 * d);
        col.addView(scroll, nl);
        TextView foot = Ui.text(h, Ui.CAPTION, 500, Ui.TEXT_3);
        foot.setText(Updates.PAGE.replace("https://", "") + "  ·  Font Outfit (OFL)  ·  ExoPlayer, NanoHTTPD, ZXing (Apache 2.0)");
        col.addView(foot);
        LayoutParams cl = new LayoutParams((int) (820 * d), -2, Gravity.CENTER_VERTICAL); cl.leftMargin = (int) (64 * d);
        addView(col, cl);
        TextView hint = Ui.text(h, Ui.CAPTION, 500, Ui.TEXT_3); hint.setText("OK: cek / pasang pembaruan  ·  ▲▼ gulir catatan  ·  Back: tutup");
        LayoutParams hl = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); hl.bottomMargin = (int) (18 * d);
        addView(hint, hl);
        check();
    }

    static String version(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    /** GitHub release notes are Markdown; show them as plain text. */
    static String plain(String md) {
        return md.replaceAll("(?m)^#+\\s*", "").replace("**", "").replaceAll("(?m)^\\s*[-*]\\s+", "•  ").trim();
    }

    void check() {
        busy = true; status.setText("Mengecek pembaruan…");
        Net.bg.execute(() -> {
            try {
                Updates.Release r = Updates.latest();
                post(() -> {
                    latest = r; busy = false;
                    boolean nu = Updates.newer(r.getVersion(), version(home)) && r.getAssets().containsKey(APK);
                    status.setText(nu ? "Versi " + r.getVersion() + " tersedia  ·  tekan OK untuk memasang" : "Sudah versi terbaru");
                    notes.setText((nu ? "Yang baru di " : "Catatan rilis ") + r.getVersion() + "  (" + r.getDate() + ")\n\n" + plain(r.getNotes()));
                });
            } catch (Exception e) { post(() -> { busy = false; status.setText("Tidak bisa mengecek pembaruan. Cek internet, lalu tekan OK."); }); }
        });
    }

    void installLatest() {
        busy = true;
        File apk = new File(home.getCacheDir(), "update.apk");
        String url = latest.getAssets().get(APK);
        Net.bg.execute(() -> {
            try {
                Updates.download(url, apk, p -> { post(() -> status.setText("Mengunduh " + latest.getVersion() + "…  " + p + "%")); return kotlin.Unit.INSTANCE; });
                post(() -> status.setText("Memasang… konfirmasi di layar berikutnya"));
                install(home, apk);
            } catch (Exception e) { post(() -> { busy = false; status.setText("Gagal mengunduh: " + e.getMessage() + ". Tekan OK untuk coba lagi."); }); }
        });
    }

    /** Hands the APK to Android's installer; [Done] opens the confirmation screen it asks for. */
    static void install(Context c, File apk) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        int id = pi.createSession(new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL));
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream o = s.openWrite("tahoe", 0, apk.length()); InputStream i = new FileInputStream(apk)) {
                byte[] b = new byte[64 * 1024]; int n;
                while ((n = i.read(b)) > 0) o.write(b, 0, n);
                s.fsync(o);
            }
            PendingIntent done = PendingIntent.getBroadcast(c, id, new Intent(c, Done.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            s.commit(done.getIntentSender());
        }
    }

    public static class Done extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (st != PackageInstaller.STATUS_SUCCESS)
                Toast.makeText(c, "Pembaruan gagal: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), Toast.LENGTH_LONG).show();
        }
    }

    /** Once a day: a banner when a newer release exists (once per version), and "updated" after an update. */
    static void dailyCheck(Home h) {
        android.content.SharedPreferences p = h.getSharedPreferences("updates", 0);
        String now = version(h);
        if (!now.equals(p.getString("seen", now))) Notif.banner(h, null, "Tahoe TV", "Diperbarui ke " + now, "Lihat yang baru di Pusat Kontrol → Tentang");
        p.edit().putString("seen", now).apply();
        if (System.currentTimeMillis() - p.getLong("checked", 0) < 20 * 3600_000L) return;
        Net.bg.execute(() -> {
            try {
                Updates.Release r = Updates.latest();
                p.edit().putLong("checked", System.currentTimeMillis()).apply();
                if (Updates.newer(r.getVersion(), now) && !r.getVersion().equals(p.getString("announced", ""))) {
                    p.edit().putString("announced", r.getVersion()).apply();
                    h.runOnUiThread(() -> Notif.banner(h, null, "Tahoe TV", "Pembaruan " + r.getVersion() + " tersedia", "Pasang dari Pusat Kontrol → Tentang"));
                }
            } catch (Exception ignored) {}
        });
    }

    @Override public boolean key(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                if (busy) return true;
                if (latest != null && Updates.newer(latest.getVersion(), version(home)) && latest.getAssets().containsKey(APK)) installLatest(); else check();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: scroll.smoothScrollBy(0, (int) Ui.dp(home, 120)); return true;
            case KeyEvent.KEYCODE_DPAD_UP: scroll.smoothScrollBy(0, -(int) Ui.dp(home, 120)); return true;
            case KeyEvent.KEYCODE_BACK: return false;
        }
        return true;
    }

    @Override public void onClose() {}
}
