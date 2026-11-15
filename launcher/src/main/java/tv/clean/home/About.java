package tv.clean.home;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import tech.kousozo.tahoetv.core.ControlPlane;
import tech.kousozo.tahoetv.core.TahoeVersion;
import tech.kousozo.tahoetv.core.Updates;

/** Pusat Kontrol → Tentang: version, the latest release's changelog, OTA installer, and pop-up update modal. */
final class About extends FrameLayout implements Sheet {
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
        status.setClickable(true);
        status.setOnClickListener(v -> {
            if (busy) return;
            if (latest != null && Updates.newer(latest.getVersion(), version(home)) && latest.hasAnyAsset(Updates.LAUNCHER_ASSETS)) installLatest();
            else check();
        });
        notes = Ui.text(h, Ui.BODY, 400, Ui.TEXT_2); notes.setSingleLine(false); notes.setLineSpacing(0, 1.35f);
        scroll = new ScrollView(h); scroll.addView(notes); scroll.setFocusable(true);
        col.addView(eb); col.addView(ti);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2); sl.topMargin = (int) (8 * d);
        col.addView(status, sl);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, (int) (260 * d)); nl.topMargin = (int) (22 * d);
        col.addView(scroll, nl);
        TextView foot = Ui.text(h, Ui.CAPTION, 500, Ui.TEXT_3);
        foot.setSingleLine(false);
        foot.setText(Updates.PAGE.replace("https://", "") + "  ·  Font Outfit (OFL)  ·  ExoPlayer, NanoHTTPD, ZXing (Apache 2.0)");
        col.addView(foot);
        int widthPx = (int) Math.min(820 * d, Math.max(280 * d, h.W - 64 * d));
        int leftMarginPx = h.W < 700 * d ? (int) (24 * d) : (int) (64 * d);
        LayoutParams cl = new LayoutParams(widthPx, -2, Gravity.CENTER_VERTICAL); cl.leftMargin = leftMarginPx;
        addView(col, cl);
        TextView hint = Ui.text(h, Ui.CAPTION, 500, Ui.TEXT_3); hint.setText("OK / Ketuk status: cek atau pasang pembaruan  ·  ▲▼ gulir  ·  Back: tutup");
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
                    boolean nu = Updates.newer(r.getVersion(), version(home)) && r.hasAnyAsset(Updates.LAUNCHER_ASSETS);
                    status.setText(nu ? "Versi " + r.getVersion() + " tersedia  ·  tekan OK atau ketuk untuk pasang" : "Sudah versi terbaru");
                    notes.setText((nu ? "Yang baru di " : "Catatan rilis ") + r.getVersion() + "  (" + r.getDate() + ")\n\n" + plain(r.getNotes()));
                });
            } catch (Exception e) { post(() -> { busy = false; status.setText("Tidak bisa mengecek pembaruan. Cek internet, lalu tekan OK."); }); }
        });
    }

    void installLatest() {
        if (latest == null) return;
        busy = true;
        status.setText("Menyiapkan unduhan…");
        Updates.Release rel = latest;
        Net.bg.execute(() -> {
            try {
                kotlin.Triple<String, String, String> picked = rel.resolveDownload(Updates.LAUNCHER_ASSETS);
                File apk = new File(home.getCacheDir(), "update.apk");
                Updates.download(picked.getSecond(), apk, picked.getThird(), p -> {
                    post(() -> status.setText("Mengunduh " + rel.getVersion() + "…  " + p + "%"));
                    return kotlin.Unit.INSTANCE;
                });
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
            Intent doneIntent = new Intent(c, Done.class).setPackage(c.getPackageName());
            PendingIntent done = PendingIntent.getBroadcast(c, id, doneIntent,
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

    /** Once a day: shows the Update Modal dialog automatically when a newer release exists (or server policy mandates it). */
    static void dailyCheck(Home h) {
        android.content.SharedPreferences p = h.getSharedPreferences("updates", 0);
        String now = version(h);
        if (!now.equals(p.getString("seen", now))) PushHelper.showInAppBanner(h, "Tahoe TV", "Diperbarui ke " + now, "Aplikasi siap dipakai");
        p.edit().putString("seen", now).apply();
        ControlPlane.loadCachedConfig(h.getFilesDir());
        if (System.currentTimeMillis() - p.getLong("checked", 0) < 6 * 3600_000L && !ControlPlane.INSTANCE.getCurrentPolicy().getForceModal()) return;
        Net.bg.execute(() -> {
            try {
                Updates.Release r = Updates.latest();
                p.edit().putLong("checked", System.currentTimeMillis()).apply();
                if (r.hasAnyAsset(Updates.LAUNCHER_ASSETS) && ControlPlane.shouldShowUpdateModal(h.getFilesDir(), now, TahoeVersion.VERSION_CODE, r)) {
                    boolean mandatory = ControlPlane.INSTANCE.getCurrentPolicy().getForceModal()
                            || r.getForceModal()
                            || (ControlPlane.INSTANCE.getCurrentPolicy().getMinVersionCode() > TahoeVersion.VERSION_CODE);
                    h.runOnUiThread(() -> {
                        if (!h.isFinishing() && h.sheet == null) {
                            h.openSheet(new UpdateModal(h, r, mandatory));
                        }
                    });
                }
            } catch (Exception ignored) {}
        });
    }

    @Override public boolean key(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                if (busy) return true;
                if (latest != null && Updates.newer(latest.getVersion(), version(home)) && latest.hasAnyAsset(Updates.LAUNCHER_ASSETS)) installLatest(); else check();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: scroll.smoothScrollBy(0, (int) Ui.dp(home, 120)); return true;
            case KeyEvent.KEYCODE_DPAD_UP: scroll.smoothScrollBy(0, -(int) Ui.dp(home, 120)); return true;
            case KeyEvent.KEYCODE_BACK: return false;
        }
        return true;
    }

    @Override public void onClose() {}

    /** Glass pop-up modal shown when a new Tahoe TV update is available or mandated by the admin server. */
    static final class UpdateModal extends FrameLayout implements Sheet {
        final Home home;
        final Updates.Release release;
        final boolean mandatory;
        final TextView primaryBtn, laterBtn, statusView, progLabel;
        final View progFill;
        boolean busy;
        int selectedBtn = 0; // 0 = Pasang Sekarang, 1 = Nanti Saja

        UpdateModal(Home h, Updates.Release r, boolean mandatory) {
            super(h);
            this.home = h;
            this.release = r;
            this.mandatory = mandatory;
            float d = Ui.dp(h, 1);
            setBackgroundColor(0x99000000);

            LinearLayout card = new LinearLayout(h);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding((int) (26 * d), (int) (24 * d), (int) (26 * d), (int) (24 * d));
            card.setBackground(new GlassDrawable(28 * d, Ui.GLASS_STRONG, d));
            card.setClickable(true);

            TextView badge = Ui.eyebrow(h, Ui.ACCENT);
            badge.setText(mandatory ? "PEMBARUAN PENTING" : "PEMBARUAN TERSEDIA · v" + r.getVersion());
            card.addView(badge);

            String titleText = !r.getModalTitle().isEmpty() ? r.getModalTitle() : "Tahoe TV " + r.getVersion() + " Siap Dipasang";
            TextView title = Ui.text(h, Ui.HEADLINE, 700, Ui.TEXT);
            title.setSingleLine(false);
            title.setText(titleText);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
            tl.topMargin = (int) (8 * d);
            card.addView(title, tl);

            String msgBody = (!r.getModalMessage().isEmpty() ? r.getModalMessage() + "\n\n" : "") + plain(r.getNotes());
            TextView body = Ui.text(h, 14, 400, Ui.TEXT_2);
            body.setSingleLine(false);
            body.setLineSpacing(0, 1.3f);
            body.setText(msgBody.isEmpty() ? "Perbaikan stabilitas, keamanan, dan daftar saluran TV terbaru." : msgBody);
            ScrollView sv = new ScrollView(h);
            sv.setFocusable(true);
            sv.addView(body);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, (int) (180 * d));
            sl.topMargin = (int) (12 * d);
            card.addView(sv, sl);

            statusView = Ui.text(h, 13, 600, Ui.ACCENT);
            statusView.setVisibility(View.GONE);
            LinearLayout.LayoutParams stl = new LinearLayout.LayoutParams(-1, -2);
            stl.topMargin = (int) (12 * d);
            card.addView(statusView, stl);

            FrameLayout track = new FrameLayout(h);
            track.setBackground(new GlassDrawable(4 * d, 0x33FFFFFF, 0));
            progFill = new View(h);
            progFill.setBackgroundColor(Ui.ACCENT);
            track.addView(progFill, new FrameLayout.LayoutParams(0, -1));
            progLabel = statusView;
            LinearLayout.LayoutParams trl = new LinearLayout.LayoutParams(-1, (int) (6 * d));
            trl.topMargin = (int) (8 * d);
            card.addView(track, trl);

            LinearLayout btnRow = new LinearLayout(h);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            btnRow.setGravity(Gravity.END);
            LinearLayout.LayoutParams brl = new LinearLayout.LayoutParams(-1, -2);
            brl.topMargin = (int) (18 * d);

            laterBtn = Ui.text(h, 15, 600, Ui.TEXT);
            laterBtn.setText("Nanti Saja");
            laterBtn.setPadding((int) (18 * d), (int) (12 * d), (int) (18 * d), (int) (12 * d));
            laterBtn.setBackground(new GlassDrawable(16 * d, 0x22FFFFFF, d));
            laterBtn.setClickable(true);
            laterBtn.setOnClickListener(v -> {
                if (!mandatory && !busy) {
                    ControlPlane.markUpdateModalDismissed(home.getFilesDir(), release.getVersion());
                    home.closeSheet();
                }
            });
            laterBtn.setVisibility(mandatory ? View.GONE : View.VISIBLE);

            primaryBtn = Ui.text(h, 15, 700, 0xFF0A0C12);
            primaryBtn.setText("Pasang Sekarang");
            primaryBtn.setPadding((int) (20 * d), (int) (12 * d), (int) (20 * d), (int) (12 * d));
            primaryBtn.setBackground(new GlassDrawable(16 * d, Ui.ACCENT, 0));
            primaryBtn.setClickable(true);
            primaryBtn.setOnClickListener(v -> { if (!busy) startInstall(); });

            if (!mandatory) {
                LinearLayout.LayoutParams lpLater = new LinearLayout.LayoutParams(-2, -2);
                lpLater.rightMargin = (int) (12 * d);
                btnRow.addView(laterBtn, lpLater);
            }
            btnRow.addView(primaryBtn);
            card.addView(btnRow, brl);

            int cardW = (int) Math.min(540 * d, Math.max(300 * d, h.W - 40 * d));
            addView(card, new LayoutParams(cardW, -2, Gravity.CENTER));
            renderBtns();
        }

        void renderBtns() {
            ((GlassDrawable) primaryBtn.getBackground()).fill(selectedBtn == 0 ? 0xFFF5F5F7 : Ui.ACCENT);
            primaryBtn.setTextColor(0xFF0A0C12);
            if (!mandatory) {
                ((GlassDrawable) laterBtn.getBackground()).fill(selectedBtn == 1 ? 0xFFF5F5F7 : 0x22FFFFFF);
                laterBtn.setTextColor(selectedBtn == 1 ? 0xFF0A0C12 : Ui.TEXT);
            }
        }

        void startInstall() {
            busy = true;
            statusView.setVisibility(View.VISIBLE);
            statusView.setText("Memverifikasi checksum SHA-256…");
            Net.bg.execute(() -> {
                try {
                    kotlin.Triple<String, String, String> picked = release.resolveDownload(Updates.LAUNCHER_ASSETS);
                    File apk = new File(home.getCacheDir(), "update.apk");
                    Updates.download(picked.getSecond(), apk, picked.getThird(), p -> {
                        post(() -> {
                            progLabel.setText("Mengunduh Tahoe TV " + release.getVersion() + "…  " + p + "%");
                            View parent = (View) progFill.getParent();
                            if (parent.getWidth() > 0) {
                                progFill.getLayoutParams().width = (int) (parent.getWidth() * (p / 100f));
                                progFill.requestLayout();
                            }
                        });
                        return kotlin.Unit.INSTANCE;
                    });
                    post(() -> progLabel.setText("Membuka pemasang Android…"));
                    About.install(home, apk);
                } catch (Exception e) {
                    post(() -> {
                        busy = false;
                        progLabel.setText("Gagal: " + e.getMessage());
                    });
                }
            });
        }

        @Override public boolean key(KeyEvent e) {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (!mandatory && !busy) { selectedBtn = 1 - selectedBtn; renderBtns(); }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                    if (busy) return true;
                    if (selectedBtn == 0) startInstall();
                    else if (!mandatory) {
                        ControlPlane.markUpdateModalDismissed(home.getFilesDir(), release.getVersion());
                        home.closeSheet();
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (mandatory || busy) return true;
                    ControlPlane.markUpdateModalDismissed(home.getFilesDir(), release.getVersion());
                    return false;
            }
            return true;
        }

        @Override public void onClose() {}
    }
}
