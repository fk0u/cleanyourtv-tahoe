package tv.clean.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Phone-only setup: find the TV on Wi-Fi → connect over network ADB → debloat → personalise → install the bundled
 *  launcher, make it Home, pair this phone → optionally switch debugging off. Also "manage" and "restore" for a TV
 *  that already runs Tahoe. No computer needed; the TV only needs USB debugging on and one "Allow" press. */
public class SetupActivity extends Activity {
    final Handler ui = new Handler(Looper.getMainLooper());
    LinearLayout col;
    Tv tv;
    String host;
    Set<String> installed = new HashSet<>();
    final List<CheckBox> debloatBoxes = new ArrayList<>();
    final List<CheckBox> dockBoxes = new ArrayList<>();
    final List<String> dockOrder = new ArrayList<>();
    EditText name, city, avatar, gemini, youtube, tmdb;
    double lat = Double.NaN, lon;
    Switch adguard, disableStock;
    TextView logView;

    SharedPreferences prefs() { return getSharedPreferences("setup", MODE_PRIVATE); }
    float dp(float v) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF07080D);
        col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) dp(22);
        col.setPadding(pad, (int) dp(40), pad, pad);
        sv.addView(col);
        setContentView(sv);
        stepScan();
    }

    @Override protected void onDestroy() { if (tv != null) new Thread(tv::close).start(); super.onDestroy(); }

    // ---------------------------------------------------------------- 1. find the TV

    void stepScan() {
        page("Langkah 1 dari 5", "Cari TV", "HP dan TV harus di Wi-Fi yang sama.");
        card("Di TV, nyalakan debugging sekali saja:\n1. Setelan → Preferensi Perangkat → Tentang → tekan \"Build\" 7×\n2. Kembali → Opsi developer → USB debugging: AKTIF\n   (dan Network/ADB debugging bila ada)");
        TextView status = text("Mencari TV di jaringan…", 15, Typeface.BOLD, 0xFF64D2FF);
        col.addView(status);
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list);
        String[] me = Scan.localAddress(this);
        if (me == null) status.setText("HP belum tersambung ke Wi-Fi.");
        else Scan.run(this, f -> ui.post(() -> {
            Button bt = button(f.label() + "\n" + f.status(), v -> {
                if (!f.adb) { toast("Nyalakan USB debugging di TV dulu, lalu pindai ulang"); return; }
                connect(f.ip);
            });
            bt.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            bt.setPadding((int) dp(18), 0, (int) dp(18), 0);
            bt.getLayoutParams().height = (int) dp(68);
            list.addView(bt);
        }), () -> ui.post(() -> status.setText(list.getChildCount() == 0
                ? "Tidak ada TV ditemukan. Pastikan debugging aktif, atau ketik IP-nya."
                : "Pilih TV kamu:")));
        EditText ip = field("…atau ketik IP TV, mis. 192.168.1.50", InputType.TYPE_CLASS_PHONE);
        ip.setText(prefs().getString("last_host", ""));
        col.addView(ip);
        col.addView(button("Sambungkan", v -> { String h = ip.getText().toString().trim(); if (!h.isEmpty()) connect(h); }));
        col.addView(button("Pindai ulang", v -> stepScan()));
    }

    // ---------------------------------------------------------------- 2. connect (TV shows "Allow USB debugging?")

    void connect(String h) {
        host = h;
        prefs().edit().putString("last_host", h).apply();
        page("Langkah 2 dari 5", "Izinkan di TV", "Di layar TV muncul \"Izinkan USB debugging?\".\nCentang \"Selalu izinkan dari komputer ini\", lalu pilih OK.");
        ProgressBar pb = new ProgressBar(this); col.addView(pb);
        TextView st = text("Menghubungkan ke " + h + "…", 15, Typeface.NORMAL, 0xB3F5F5F7); col.addView(st);
        new Thread(() -> {
            try {
                if (tv != null) tv.close();
                tv = Tv.connect(this, h);
                installed = tv.packages();
                String version = tv.installedVersion();
                List<String> apps = tv.tvApps();
                ui.post(() -> stepInfo(version, apps));
            } catch (Exception e) {
                ui.post(() -> { st.setText("Gagal: " + e.getMessage() + "\n\nCek: debugging aktif, TV menyala, dan prompt di TV sudah diizinkan."); pb.setVisibility(View.GONE);
                    col.addView(button("Coba lagi", v -> connect(h))); col.addView(button("Kembali", v -> stepScan())); });
            }
        }).start();
    }

    // ---------------------------------------------------------------- 3. TV info + debloat

    List<String> tvApps = new ArrayList<>();

    void stepInfo(String version, List<String> apps) {
        tvApps = apps;
        page("Langkah 3 dari 5", tv.model(), "Android " + tv.props.get("ro.build.version.release") + " (SDK " + tv.sdk() + ")" + (tv.lowRam() ? " · RAM rendah" : "")
                + (version != null ? "\nTahoe " + version + " sudah terpasang" : ""));
        if (tv.sdk() < 26) { card("Launcher Tahoe butuh Android 8.0 (SDK 26) ke atas. TV ini hanya bisa di-debloat."); }
        if (version != null) col.addView(button("Pulihkan TV ke bawaan…", v -> confirmRestore()));
        text2("Matikan aplikasi yang tidak perlu (bisa dikembalikan kapan saja):");
        debloatBoxes.clear();
        Set<String> already;
        try { already = tv.disabled(); } catch (Exception e) { already = new HashSet<>(); }
        int shown = 0;
        for (String[] c : Tv.CATALOG) {
            if (!installed.contains(c[0]) || Tv.protectedPkg(c[0])) continue;
            CheckBox cb = check(c[1] + (already.contains(c[0]) ? "  (sudah mati)" : "") + "\n" + c[0], c[2].equals("1") && !already.contains(c[0]));
            cb.setTag(c[0]); cb.setEnabled(!already.contains(c[0]));
            debloatBoxes.add(cb); col.addView(cb); shown++;
        }
        if (shown == 0) card("Tidak ada aplikasi bawaan dari daftar kami di TV ini.");
        col.addView(button("Lanjut", v -> stepPersonal()));
    }

    // ---------------------------------------------------------------- 4. personalise

    void stepPersonal() {
        SharedPreferences p = prefs();
        page("Langkah 4 dari 5", "Personalisasi", "Semua bisa diubah lagi nanti dari app ini.");
        name = field("Nama (untuk sapaan)", InputType.TYPE_CLASS_TEXT); name.setText(p.getString("name", "")); col.addView(name);
        city = field("Kota (cuaca & jadwal shalat)", InputType.TYPE_CLASS_TEXT); city.setText(p.getString("city", "")); col.addView(city);
        TextView where = text(p.contains("lat") ? "📍 " + p.getString("place", "") : "", 13, Typeface.NORMAL, 0xB3F5F5F7);
        if (p.contains("lat")) { lat = Double.longBitsToDouble(p.getLong("lat", 0)); lon = Double.longBitsToDouble(p.getLong("lon", 0)); }
        col.addView(where);
        col.addView(button("Cari kota", v -> geocode(city.getText().toString().trim(), where)));
        avatar = field("URL foto profil (opsional)", InputType.TYPE_TEXT_VARIATION_URI); avatar.setText(p.getString("avatar", "")); col.addView(avatar);

        text2("Dock (urut sesuai urutan dicentang, maks 7):");
        dockBoxes.clear(); dockOrder.clear();
        String saved = p.getString("dock", "com.google.android.youtube.tv,com.netflix.ninja,com.spotify.tv.android");
        for (String s : saved.split(",")) if (tvApps.contains(s)) dockOrder.add(s);
        for (String pkg : tvApps) {
            CheckBox cb = check(pkg, dockOrder.contains(pkg)); cb.setTag(pkg);
            cb.setOnCheckedChangeListener((v, on) -> {
                if (on && !dockOrder.contains(pkg)) { if (dockOrder.size() >= 7) { v.setChecked(false); toast("Maksimal 7"); return; } dockOrder.add(pkg); }
                if (!on) dockOrder.remove(pkg);
            });
            dockBoxes.add(cb); col.addView(cb);
        }

        text2("API key (opsional, disimpan di HP ini dan TV saja):");
        gemini = field("Gemini (asisten)", KEY); gemini.setText(p.getString("gemini", "")); col.addView(gemini);
        youtube = field("YouTube Data API (hero trending)", KEY); youtube.setText(p.getString("youtube", "")); col.addView(youtube);
        tmdb = field("TMDB (hero Netflix)", KEY); tmdb.setText(p.getString("tmdb", "")); col.addView(tmdb);
        adguard = toggle("DNS pemblokir iklan (AdGuard)", p.getBoolean("adguard", false));
        disableStock = toggle("Matikan launcher bawaan bila perlu", true);
        col.addView(adguard); col.addView(disableStock);
        col.addView(button("Pasang ke TV", v -> stepApply()));
    }

    void geocode(String q, TextView where) {
        if (q.isEmpty()) return;
        where.setText("Mencari…");
        new Thread(() -> {
            try {
                JSONObject o = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=id&name=" + URLEncoder.encode(q, "UTF-8")));
                JSONObject r = o.getJSONArray("results").getJSONObject(0);
                lat = r.getDouble("latitude"); lon = r.getDouble("longitude");
                String place = r.getString("name") + ", " + r.optString("admin1", "") + ", " + r.optString("country", "");
                prefs().edit().putLong("lat", Double.doubleToLongBits(lat)).putLong("lon", Double.doubleToLongBits(lon)).putString("place", place).apply();
                String cityName = r.getString("name");
                ui.post(() -> { where.setText("📍 " + place); city.setText(cityName); });
            } catch (Exception e) { ui.post(() -> where.setText("Kota tidak ditemukan")); }
        }).start();
    }

    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(10000);
        try (java.util.Scanner s = new java.util.Scanner(c.getInputStream(), "UTF-8").useDelimiter("\\A")) { return s.hasNext() ? s.next() : ""; }
    }

    // ---------------------------------------------------------------- 5. apply

    void stepApply() {
        List<String> disable = new ArrayList<>();
        for (CheckBox cb : debloatBoxes) if (cb.isChecked() && cb.isEnabled()) disable.add((String) cb.getTag());
        String n = name.getText().toString().trim(), c = city.getText().toString().trim(), a = avatar.getText().toString().trim();
        String g = gemini.getText().toString().trim(), y = youtube.getText().toString().trim(), t = tmdb.getText().toString().trim();
        boolean ad = adguard.isChecked(), stock = disableStock.isChecked();
        prefs().edit().putString("name", n).putString("city", c).putString("avatar", a).putString("dock", android.text.TextUtils.join(",", dockOrder))
                .putString("gemini", g).putString("youtube", y).putString("tmdb", t).putBoolean("adguard", ad).apply();

        page("Langkah 5 dari 5", "Memasang…", "Biarkan HP tetap terbuka dan di Wi-Fi yang sama.");
        logView = text("", 13, Typeface.NORMAL, 0xFFE5E5EA); logView.setTypeface(Typeface.MONOSPACE);
        col.addView(logView);
        new Thread(() -> {
            try {
                String serial = tv.props.get("ro.serialno");
                Set<String> record = new HashSet<>(prefs().getStringSet("disabled_" + serial, new HashSet<>()));
                for (String pkg : disable) {
                    if (Tv.protectedPkg(pkg)) continue;
                    String r = tv.sh("pm disable-user --user 0 " + pkg);
                    log((r.contains("disabled") ? "✓ dimatikan " : "✗ ") + pkg);
                    if (r.contains("disabled")) record.add(pkg);
                }
                prefs().edit().putStringSet("disabled_" + serial, record).apply();

                if (tv.sdk() >= 26) {
                    tv.installLauncher(this, this::log);
                    log("✓ Tahoe TV terpasang");
                    String home = tv.makeHome(this::log, stock);
                    if (stock && home.equals(Tv.PKG)) record.addAll(Arrays.asList(Tv.STOCK_LAUNCHERS));
                    prefs().edit().putStringSet("disabled_" + serial, record).apply();
                    log(home.equals(Tv.PKG) ? "✓ Tahoe jadi Home" : "! Home masih " + home + " (cek manual)");

                    Map<String, String> cfg = new LinkedHashMap<>();
                    if (!n.isEmpty()) cfg.put("user_name", n);
                    if (!c.isEmpty()) cfg.put("city", c);
                    if (!Double.isNaN(lat)) { cfg.put("latitude", String.valueOf(lat)); cfg.put("longitude", String.valueOf(lon)); }
                    if (!a.isEmpty()) cfg.put("avatar_url", a);
                    if (!dockOrder.isEmpty()) cfg.put("dock", android.text.TextUtils.join(",", dockOrder));
                    if (!g.isEmpty()) cfg.put("gemini_key", g);
                    if (!y.isEmpty()) cfg.put("youtube_key", y);
                    if (!t.isEmpty()) cfg.put("tmdb_key", t);
                    JSONObject res = new JSONObject(tv.configure(cfg, true));
                    log("✓ Personalisasi tersimpan (" + res.optInt("updated") + " setelan)");
                    String token = res.optString("token");
                    if (!token.isEmpty()) {
                        Sync.prefs(this).edit().putString("host", host).putString("token", token).apply();
                        if (Sync.inst != null) Sync.inst.connect();
                        log("✓ HP ini tersambung ke TV (notifikasi & musik)");
                    }
                }
                if (ad) {
                    tv.sh("settings put global private_dns_specifier dns.adguard-dns.com");
                    tv.sh("settings put global private_dns_mode hostname");
                    log("✓ DNS AdGuard aktif");
                }
                ui.post(this::stepDone);
            } catch (Exception e) {
                log("✗ Berhenti: " + e.getMessage());
                ui.post(() -> col.addView(button("Kembali", v -> stepInfo(null, tvApps))));
            }
        }).start();
    }

    void log(String s) { ui.post(() -> logView.append(s + "\n")); }

    // ---------------------------------------------------------------- 6. done

    void stepDone() {
        TextView l = logView;
        page("Selesai", "Tahoe TV siap", "Lihat TV-mu. Tekan OK di layar \"Siapa yang menonton?\".");
        col.addView(l);
        card("Demi keamanan, matikan debugging setelah selesai. Untuk mengubah setelan lagi, nyalakan USB debugging lalu buka app ini.");
        col.addView(button("Matikan debugging di TV", v -> new Thread(() -> {
            try { tv.sh("settings put global adb_enabled 0"); } catch (Exception ignored) {} // connection drops right after
            ui.post(() -> toast("Debugging dimatikan"));
        }).start()));
        col.addView(button("Ke halaman utama", v -> finish()));
    }

    // ---------------------------------------------------------------- restore

    void confirmRestore() {
        new AlertDialog.Builder(this).setTitle("Pulihkan TV?")
                .setMessage("Aplikasi yang dimatikan lewat HP ini dinyalakan lagi, launcher bawaan diaktifkan, dan Tahoe TV di-uninstall.")
                .setPositiveButton("Pulihkan", (d, w) -> restore()).setNegativeButton("Batal", null).show();
    }

    void restore() {
        page("Pemulihan", "Memulihkan TV…", "");
        logView = text("", 13, Typeface.NORMAL, 0xFFE5E5EA); logView.setTypeface(Typeface.MONOSPACE); col.addView(logView);
        new Thread(() -> {
            try {
                String serial = tv.props.get("ro.serialno");
                Set<String> record = new HashSet<>(prefs().getStringSet("disabled_" + serial, new HashSet<>()));
                record.addAll(Arrays.asList(Tv.STOCK_LAUNCHERS));
                for (String pkg : record) if (installed.contains(pkg)) { tv.sh("pm enable " + pkg); log("✓ dinyalakan " + pkg); }
                tv.sh("settings put global private_dns_mode opportunistic");
                tv.sh("settings delete secure screensaver_components");
                log(tv.sh("pm uninstall " + Tv.PKG).trim() + " (uninstall Tahoe)");
                tv.sh("input keyevent KEYCODE_HOME");
                prefs().edit().remove("disabled_" + serial).apply();
                log("✓ Selesai");
            } catch (Exception e) { log("✗ " + e.getMessage()); }
            ui.post(() -> col.addView(button("Ke halaman utama", v -> finish())));
        }).start();
    }

    // ---------------------------------------------------------------- tiny UI kit (same look as MainActivity)

    void page(String eyebrow, String title, String sub) {
        col.removeAllViews();
        TextView e = text(eyebrow.toUpperCase(Locale.ROOT), 12, Typeface.BOLD, 0x73F5F5F7); e.setLetterSpacing(0.1f);
        col.addView(e);
        TextView t = text(title, 28, Typeface.BOLD, 0xFFF5F5F7); t.setPadding(0, (int) dp(4), 0, (int) dp(6)); col.addView(t);
        if (!sub.isEmpty()) { TextView s = text(sub, 15, Typeface.NORMAL, 0xB3F5F5F7); s.setPadding(0, 0, 0, (int) dp(16)); col.addView(s); }
    }

    void card(String s) {
        TextView t = text(s, 14, Typeface.NORMAL, 0xE6F5F5F7);
        t.setBackground(glass(18, 0x1FFFFFFF)); t.setPadding((int) dp(16), (int) dp(14), (int) dp(16), (int) dp(14)); t.setLineSpacing(0, 1.25f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = (int) dp(14); t.setLayoutParams(lp);
        col.addView(t);
    }

    void text2(String s) { TextView t = text(s, 15, Typeface.BOLD, 0xFFF5F5F7); t.setPadding(0, (int) dp(18), 0, (int) dp(8)); col.addView(t); }

    TextView text(String s, float sp, int style, int color) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTypeface(Typeface.create("sans-serif", style)); t.setTextColor(color);
        return t;
    }

    GradientDrawable glass(float r, int fill) { GradientDrawable g = new GradientDrawable(); g.setCornerRadius(dp(r)); g.setColor(fill); g.setStroke((int) dp(1), 0x2EFFFFFF); return g; }

    static final int KEY = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS; // no IME learning of secrets
    EditText field(String hint, int type) {
        EditText e = new EditText(this); e.setHint(hint); e.setInputType(type); e.setTextColor(0xFFF5F5F7); e.setHintTextColor(0x73F5F5F7);
        e.setBackground(glass(16, 0x59000000)); e.setPadding((int) dp(16), (int) dp(14), (int) dp(16), (int) dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = (int) dp(10); e.setLayoutParams(lp);
        return e;
    }

    Button button(String s, View.OnClickListener l) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextColor(0xFFF5F5F7); b.setTextSize(15);
        b.setBackground(glass(18, 0x1FFFFFFF)); b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, (int) dp(52)); lp.topMargin = (int) dp(8); b.setLayoutParams(lp);
        return b;
    }

    CheckBox check(String s, boolean on) {
        CheckBox c = new CheckBox(this); c.setText(s); c.setTextColor(0xFFF5F5F7); c.setTextSize(14); c.setChecked(on);
        c.setPadding((int) dp(6), (int) dp(8), 0, (int) dp(8));
        return c;
    }

    Switch toggle(String s, boolean on) {
        Switch w = new Switch(this); w.setText(s); w.setTextColor(0xFFF5F5F7); w.setTextSize(15); w.setChecked(on);
        w.setPadding(0, (int) dp(14), 0, (int) dp(6));
        return w;
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
