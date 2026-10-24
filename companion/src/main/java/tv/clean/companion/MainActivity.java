package tv.clean.companion;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONObject;

/** Pair with the TV (auto-discovered over mDNS, or typed IP) using the 6-digit code from the TV, grant notification
 *  access, choose what to sync, and open the web remote/gamepad. */
public class MainActivity extends Activity {
    static MainActivity live;
    TextView status;
    EditText host, code;
    Switch notif, media;
    NsdManager.DiscoveryListener discovery;

    float dp(float v) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        live = this;
        SharedPreferences p = Sync.prefs(this);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF07080D);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) dp(22);
        col.setPadding(pad, (int) dp(48), pad, pad);
        sv.addView(col);

        col.addView(text("Tahoe Companion", 28, Typeface.BOLD, 0xFFF5F5F7));
        TextView sub = text("Notifikasi & musik dari HP muncul di TV. Kontrol musik dari Pusat Kontrol TV.", 15, Typeface.NORMAL, 0xB3F5F5F7);
        sub.setPadding(0, (int) dp(6), 0, (int) dp(22));
        col.addView(sub);
        status = text("", 15, Typeface.BOLD, 0xFF64D2FF);
        status.setPadding(0, 0, 0, (int) dp(18));
        col.addView(status);

        host = field("Alamat TV (otomatis terdeteksi)", InputType.TYPE_CLASS_TEXT);
        host.setText(p.getString("host", ""));
        code = field("Kode 6 digit dari TV (Pusat Kontrol → HP)", InputType.TYPE_CLASS_NUMBER);
        col.addView(host); col.addView(code);
        col.addView(button("Sambungkan ke TV", v -> pair()));
        col.addView(button("Izinkan akses notifikasi", v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))));
        notif = toggle("Teruskan notifikasi", "notif");
        media = toggle("Tampilkan musik yang diputar", "media");
        col.addView(notif); col.addView(media);
        col.addView(button("Buka remote & gamepad (web)", v -> {
            String h = host.getText().toString().trim();
            if (!h.isEmpty()) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://" + h + ":8765/")));
        }));
        setContentView(sv);
        discover();
        refresh();
    }

    @Override protected void onResume() { super.onResume(); refresh(); }
    @Override protected void onDestroy() {
        try { ((NsdManager) getSystemService(NSD_SERVICE)).stopServiceDiscovery(discovery); } catch (Exception ignored) {}
        live = null; super.onDestroy();
    }

    static void refresh() {
        MainActivity a = live;
        if (a == null) return;
        a.runOnUiThread(() -> {
            String granted = Settings.Secure.getString(a.getContentResolver(), "enabled_notification_listeners");
            boolean ok = granted != null && granted.contains(new ComponentName(a, Sync.class).flattenToString());
            a.status.setText(!ok ? "Akses notifikasi belum diizinkan" : Sync.inst == null ? "Menunggu layanan…" : Sync.inst.status);
        });
    }

    /** Find "Tahoe TV" on the LAN (the launcher registers _tahoetv._tcp). */
    void discover() {
        NsdManager nsd = (NsdManager) getSystemService(NSD_SERVICE);
        discovery = new NsdManager.DiscoveryListener() {
            public void onStartDiscoveryFailed(String t, int e) {}
            public void onStopDiscoveryFailed(String t, int e) {}
            public void onDiscoveryStarted(String t) {}
            public void onDiscoveryStopped(String t) {}
            public void onServiceLost(NsdServiceInfo s) {}
            public void onServiceFound(NsdServiceInfo s) {
                nsd.resolveService(s, new NsdManager.ResolveListener() {
                    public void onResolveFailed(NsdServiceInfo s, int e) {}
                    public void onServiceResolved(NsdServiceInfo s) {
                        String ip = s.getHost().getHostAddress();
                        runOnUiThread(() -> { if (host.getText().toString().trim().isEmpty()) host.setText(ip); });
                    }
                });
            }
        };
        try { nsd.discoverServices("_tahoetv._tcp", NsdManager.PROTOCOL_DNS_SD, discovery); } catch (Exception ignored) {}
    }

    void pair() {
        String h = host.getText().toString().trim(), c = code.getText().toString().trim();
        if (h.isEmpty() || c.length() != 6) { toast("Isi alamat TV dan kode 6 digit"); return; }
        new Thread(() -> {
            try {
                HttpURLConnection con = (HttpURLConnection) new URL("http://" + h + ":8765/pair").openConnection();
                con.setConnectTimeout(5000); con.setRequestMethod("POST"); con.setDoOutput(true);
                con.setRequestProperty("Content-Type", "application/json");
                try (OutputStream o = con.getOutputStream()) { o.write(new JSONObject().put("code", c).toString().getBytes("UTF-8")); }
                InputStream in = con.getResponseCode() >= 400 ? con.getErrorStream() : con.getInputStream();
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(in))) { String l; while ((l = r.readLine()) != null) sb.append(l); }
                JSONObject res = new JSONObject(sb.toString());
                if (!res.has("token")) { runOnUiThread(() -> toast(res.optString("error", "Gagal"))); return; }
                Sync.prefs(this).edit().putString("host", h).putString("token", res.getString("token")).apply();
                runOnUiThread(() -> { toast("Tersambung"); code.setText(""); if (Sync.inst != null) Sync.inst.connect(); refresh(); });
            } catch (Exception e) { runOnUiThread(() -> toast("Tidak bisa menghubungi TV: " + e.getMessage())); }
        }).start();
    }

    // ---- tiny UI kit ----

    TextView text(String s, float sp, int style, int color) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTypeface(Typeface.create("sans-serif", style)); t.setTextColor(color);
        return t;
    }

    GradientDrawable glass(float r, int fill) {
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(dp(r)); g.setColor(fill); g.setStroke((int) dp(1), 0x2EFFFFFF); return g;
    }

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

    Switch toggle(String s, String key) {
        Switch w = new Switch(this); w.setText(s); w.setTextColor(0xFFF5F5F7); w.setTextSize(15);
        w.setChecked(Sync.prefs(this).getBoolean(key, true));
        w.setOnCheckedChangeListener((v, on) -> { Sync.prefs(this).edit().putBoolean(key, on).apply(); if (Sync.inst != null) Sync.inst.sendMedia(); });
        w.setPadding(0, (int) dp(14), 0, (int) dp(6));
        return w;
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
