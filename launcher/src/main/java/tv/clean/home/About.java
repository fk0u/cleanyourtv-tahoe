package tv.clean.home;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
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
    boolean busy, fixed;
    String shownVersion; // set when this is the "What's new" pop-up after an update

    About(Home h) {
        super(h);
        home = h;
        float d = Ui.dp(h, 1);
        setBackgroundColor(0xFC080A10); // nearly opaque: the home screen behind it showed through the text
        String lang = TvViewer.get(h).getLanguage();
        Ui.crumb(this, R.drawable.ic_info, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.about_title"));

        LinearLayout col = new LinearLayout(h); col.setOrientation(LinearLayout.VERTICAL);
        TextView eb = Ui.eyebrow(h, Ui.TEXT_3); eb.setText(tech.kousozo.tahoetv.core.Brand.TAGLINE);
        TextView ti = Ui.text(h, Ui.TITLE, 700, Ui.TEXT); ti.setText(tech.kousozo.tahoetv.core.Brand.NAME + " " + version(h));
        status = Ui.text(h, Ui.BODY, 600, Ui.ACCENT); status.setText(tech.kousozo.tahoetv.core.I18n.get(lang, "update.checking"));
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
        TextView hint = Ui.text(h, Ui.CAPTION, 500, Ui.TEXT_3); hint.setText(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.about_hint"));
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

    /** After an update: show this release's notes once instead of the update check. */
    void showReleased(Updates.Release r) {
        fixed = true; busy = false; latest = r; shownVersion = r.getVersion();
        String lang = TvViewer.get(home).getLanguage();
        status.setText(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.about_updated", r.getVersion()));
        notes.setText(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.about_whats_new") + r.getVersion() + "  (" + r.getDate() + ")\n\n" + plain(r.getNotes()));
    }

    void check() {
        String lang = TvViewer.get(home).getLanguage();
        busy = true; status.setText(tech.kousozo.tahoetv.core.I18n.get(lang, "update.checking"));
        Net.bg.execute(() -> {
            try {
                Updates.Release r = Updates.latest();
                post(() -> {
                    if (fixed) return;
                    latest = r; busy = false;
                    boolean nu = Updates.newer(r.getVersion(), version(home)) && r.hasAnyAsset(Updates.LAUNCHER_ASSETS);
                    status.setText(nu ? tech.kousozo.tahoetv.core.I18n.get(lang, "tv.about_status_new", r.getVersion())
                            : tech.kousozo.tahoetv.core.I18n.get(lang, "tv.about_latest"));
                    notes.setText((nu ? tech.kousozo.tahoetv.core.I18n.get(lang, "tv.about_whats_new")
                            : tech.kousozo.tahoetv.core.I18n.get(lang, "tv.about_notes"))
                            + r.getVersion() + "  (" + r.getDate() + ")\n\n" + plain(r.getNotes()));
                });
            } catch (Exception e) { post(() -> { busy = false; status.setText(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.about_fail")); }); }
        });
    }

    void installLatest() {
        if (latest == null) return;
        if (!ensureInstallPermission(home, status)) return;
        String lang = TvViewer.get(home).getLanguage();
        busy = true;
        status.setText(tech.kousozo.tahoetv.core.I18n.get(lang, "update.preparing"));
        Updates.Release rel = latest;
        Net.bg.execute(() -> {
            try {
                kotlin.Triple<String, String, String> picked = rel.resolveDownload(Updates.LAUNCHER_ASSETS);
                File apk = new File(home.getCacheDir(), "update.apk");
                Updates.download(picked.getSecond(), apk, picked.getThird(), p -> {
                    post(() -> status.setText(tech.kousozo.tahoetv.core.I18n.get(lang, "update.downloading_pct", rel.getVersion(), String.valueOf(p))));
                    return kotlin.Unit.INSTANCE;
                });
                post(() -> status.setText(tech.kousozo.tahoetv.core.I18n.get(lang, "update.install_confirm")));
                install(home, apk);
            } catch (Exception e) { post(() -> { busy = false; status.setText(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.about_dl_fail", String.valueOf(e.getMessage()))); }); }
        });
    }

    /** Hands the APK to Android's installer; [Done] opens the confirmation screen it asks for. */
    /** Android 8+ asks per app for "install unknown apps": if it is off, open that switch (the viewer comes back and presses OK again). */
    static boolean ensureInstallPermission(Context c, TextView status) {
        if (Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls()) return true;
        String lang = TvViewer.get(c).getLanguage();
        if (status != null) { status.setVisibility(View.VISIBLE); status.setText(tech.kousozo.tahoetv.core.I18n.get(lang, "update.allow_install")); }
        // this app's page first, then the general "unknown sources" page, then security, then settings: TV firmwares differ a lot
        android.net.Uri me = android.net.Uri.parse("package:" + c.getPackageName());
        Intent[] tries = {
            new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, me),
            new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
            new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS),
            new Intent(android.provider.Settings.ACTION_SETTINGS),
        };
        for (Intent i : tries) {
            try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); break; } catch (Exception notOnThisTv) { /* try the next page */ }
        }
        return false;
    }

    static void install(Context c, File apk) throws Exception {
        ApkCheck.requireGenuineUpdate(c, apk, tech.kousozo.tahoetv.core.I18n.get(TvViewer.get(c).getLanguage(), "update.untrusted"));
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
            } else if (st != PackageInstaller.STATUS_SUCCESS) {
                String lang = TvViewer.get(c).getLanguage();
                String why = String.valueOf(i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
                tech.kousozo.tahoetv.core.DiagLog.INSTANCE.add("update", "install failed: status " + st + " " + why);
                boolean otherKey = st == PackageInstaller.STATUS_FAILURE_CONFLICT || why.contains("UPDATE_INCOMPATIBLE") || why.contains("signatures do not match");
                Toast.makeText(c, otherKey ? tech.kousozo.tahoetv.core.I18n.get(lang, "update.untrusted")
                        : tech.kousozo.tahoetv.core.I18n.get(lang, "tv.about_upd_failed", why), Toast.LENGTH_LONG).show();
            }
        }
    }

    /** Once a day: shows the Update Modal dialog automatically when a newer release exists (or server policy mandates it). */
    static void dailyCheck(Home h) {
        android.content.SharedPreferences p = h.getSharedPreferences("updates", 0);
        String now = version(h);
        String lang = TvViewer.get(h).getLanguage();
        if (!now.equals(p.getString("seen", now))) PushHelper.showInAppBanner(h, tech.kousozo.tahoetv.core.Brand.NAME,
                tech.kousozo.tahoetv.core.I18n.get(lang, "tv.about_updated_to", now),
                tech.kousozo.tahoetv.core.I18n.get(lang, "tv.about_ready_use"));
        p.edit().putString("seen", now).apply();
        ControlPlane.loadCachedConfig(h.getFilesDir());
        if (System.currentTimeMillis() - p.getLong("checked", 0) < 6 * 3600_000L && !ControlPlane.INSTANCE.getCurrentPolicy().getForceModal()) return;
        Net.bg.execute(() -> {
            try {
                Updates.Release r = Updates.latest();
                p.edit().putLong("checked", System.currentTimeMillis()).apply();
                if (r.hasAnyAsset(Updates.LAUNCHER_ASSETS) && ControlPlane.shouldShowUpdateModal(h.getFilesDir(), now, TahoeVersion.VERSION_CODE, r)) {
                    boolean mandatory = ControlPlane.isMandatory(now, TahoeVersion.VERSION_CODE, r); // incl. fix releases (3.8.0 → 3.8.1)
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

    @Override public void onClose() { if (shownVersion != null) home.prefs.edit().putString("whatsnew_seen", About.version(home)).apply(); }

    /** Glass pop-up modal shown when a new Tahoe TV update is available or mandated by the admin server. */
    static final class UpdateModal extends FrameLayout implements Sheet {
        final Home home;
        final Updates.Release release;
        final boolean mandatory;
        final TextView primaryBtn, laterBtn, statusView, progLabel;
        final View progFill;
        final ScrollView notesScroll;
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
            card.setFocusable(false); card.setFocusableInTouchMode(false);

            String lang = TvViewer.get(h).getLanguage();
            TextView badge = Ui.eyebrow(h, Ui.ACCENT);
            badge.setText(mandatory ? tech.kousozo.tahoetv.core.I18n.get(lang, "tv.about_modal_mandatory")
                    : tech.kousozo.tahoetv.core.I18n.get(lang, "tv.about_modal_badge", r.getVersion()));
            card.addView(badge);

            String titleText = !r.getModalTitle().isEmpty() ? r.getModalTitle()
                    : tech.kousozo.tahoetv.core.I18n.get(lang, "tv.about_modal_title", r.getVersion());
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
            body.setText(msgBody.isEmpty() ? tech.kousozo.tahoetv.core.I18n.get(lang, "tv.about_modal_body_default") : msgBody);
            ScrollView sv = new ScrollView(h);
            this.notesScroll = sv;
            sv.setFocusable(false); sv.setFocusableInTouchMode(false);
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
            laterBtn.setText(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.about_modal_later"));
            laterBtn.setPadding((int) (18 * d), (int) (12 * d), (int) (18 * d), (int) (12 * d));
            laterBtn.setBackground(new GlassDrawable(16 * d, 0x22FFFFFF, d));
            laterBtn.setClickable(true);
            laterBtn.setFocusable(false); laterBtn.setFocusableInTouchMode(false);
            laterBtn.setOnClickListener(v -> {
                if (!mandatory && !busy) {
                    ControlPlane.markUpdateModalDismissed(home.getFilesDir(), release.getVersion());
                    home.closeSheet();
                }
            });
            laterBtn.setVisibility(mandatory ? View.GONE : View.VISIBLE);

            primaryBtn = Ui.text(h, 15, 700, 0xFF0A0C12);
            primaryBtn.setText(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.about_modal_install"));
            primaryBtn.setPadding((int) (20 * d), (int) (12 * d), (int) (20 * d), (int) (12 * d));
            primaryBtn.setBackground(new GlassDrawable(16 * d, Ui.ACCENT, 0));
            primaryBtn.setClickable(true);
            primaryBtn.setFocusable(false); primaryBtn.setFocusableInTouchMode(false);
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
            if (!ensureInstallPermission(home, statusView)) return;
            String lang = TvViewer.get(home).getLanguage();
            busy = true;
            statusView.setVisibility(View.VISIBLE);
            statusView.setText(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.about_verifying_sha"));
            Net.bg.execute(() -> {
                try {
                    kotlin.Triple<String, String, String> picked = release.resolveDownload(Updates.LAUNCHER_ASSETS);
                    File apk = new File(home.getCacheDir(), "update.apk");
                    Updates.download(picked.getSecond(), apk, picked.getThird(), p -> {
                        post(() -> {
                            progLabel.setText(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.about_dl_pct", release.getVersion(), String.valueOf(p)));
                            View parent = (View) progFill.getParent();
                            if (parent.getWidth() > 0) {
                                progFill.getLayoutParams().width = (int) (parent.getWidth() * (p / 100f));
                                progFill.requestLayout();
                            }
                        });
                        return kotlin.Unit.INSTANCE;
                    });
                    post(() -> progLabel.setText(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.about_opening_installer")));
                    About.install(home, apk);
                } catch (Exception e) {
                    post(() -> {
                        busy = false;
                        progLabel.setText(tech.kousozo.tahoetv.core.I18n.get(lang, "update.failed", String.valueOf(e.getMessage())));
                    });
                }
            });
        }

        @Override public boolean key(KeyEvent e) {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (notesScroll != null) notesScroll.smoothScrollBy(0, -(int) Ui.dp(home, 120));
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (notesScroll != null) notesScroll.smoothScrollBy(0, (int) Ui.dp(home, 120));
                    return true;
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
