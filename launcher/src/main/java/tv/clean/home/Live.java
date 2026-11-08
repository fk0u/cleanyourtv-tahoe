package tv.clean.home;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import tech.kousozo.tahoetv.core.Epg;
import tech.kousozo.tahoetv.core.LocalTv;
import tech.kousozo.tahoetv.core.Sources;
import tech.kousozo.tahoetv.core.Web;

/** "Jadwal TV": what's on now and next on Indonesian channels, local TV first ("Dekat kamu" by province,
 *  national, TVRI regional, local). Watching happens in BitTV: choosing a channel opens it, so BitTV works like
 *  Tahoe's built-in TV app. Channels: free Indonesian TV from iptv-org + dhanytv (no paid/DRM channels);
 *  guide: dhanytv + epg.pw (both replaceable via Config). */
public class Live extends Activity {
    static final String TV_APP = "com.live_streaming_tv.online_tv"; // BitTV
    static List<LocalTv.Entry> cached; static Epg epg; static long cachedAt;   // survive re-opening; refreshed every 6 h

    final Handler ui = new Handler(Looper.getMainLooper());
    final DateTimeFormatter hm = DateTimeFormatter.ofPattern("HH.mm").withZone(ZoneId.systemDefault());
    TextView title, sub, dName, dCat, dNow, dDesc, dNext, dHint;
    LinearLayout cats, rows;
    List<LocalTv.Entry> all = new ArrayList<>(), list = new ArrayList<>();
    LocalTv.Category cat;   // null = semua
    SharedPreferences prefs;

    float dp(float v) { return Ui.dp(this, v); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("live", MODE_PRIVATE);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF07080D);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding((int) dp(Ui.SAFE_SIDE), (int) dp(Ui.SAFE_TOP), (int) dp(Ui.SAFE_SIDE), (int) dp(Ui.SAFE_TOP));
        title = Ui.text(this, Ui.TITLE, 700, Ui.TEXT); title.setText("Jadwal TV");
        sub = Ui.text(this, Ui.BODY, 400, Ui.TEXT_2); sub.setText("Memuat saluran…");
        page.addView(title); page.addView(sub);

        LinearLayout cols = new LinearLayout(this);
        cats = new LinearLayout(this); cats.setOrientation(LinearLayout.VERTICAL);
        rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
        ScrollView cs = new ScrollView(this); cs.addView(cats); cs.setVerticalScrollBarEnabled(false);
        ScrollView rs = new ScrollView(this); rs.addView(rows); rs.setVerticalScrollBarEnabled(false);
        cols.addView(cs, new LinearLayout.LayoutParams((int) dp(170), -1));
        cols.addView(rs, new LinearLayout.LayoutParams(0, -1, 1));

        // detail panel for the focused channel
        LinearLayout d = new LinearLayout(this); d.setOrientation(LinearLayout.VERTICAL);
        d.setBackground(new GlassDrawable(dp(Ui.R_LARGE), Ui.GLASS, dp(1)));
        d.setPadding((int) dp(24), (int) dp(20), (int) dp(24), (int) dp(20));
        dCat = Ui.eyebrow(this, Ui.TEXT_2); dName = Ui.text(this, Ui.TITLE, 700, Ui.TEXT);
        dNow = Ui.text(this, Ui.HEADLINE, 600, Ui.ACCENT);
        dDesc = Ui.text(this, Ui.BODY, 400, Ui.TEXT_2); dDesc.setSingleLine(false); dDesc.setMaxLines(4);
        dNext = Ui.text(this, Ui.BODY, 400, Ui.TEXT); dNext.setSingleLine(false); dNext.setMaxLines(6); dNext.setLineSpacing(dp(4), 1);
        dHint = Ui.text(this, Ui.CAPTION, 500, Ui.TEXT_3); dHint.setText("OK  ·  tonton di BitTV");
        d.addView(dCat); d.addView(dName, lp(4)); d.addView(dNow, lp(18)); d.addView(dDesc, lp(6)); d.addView(dNext, lp(18));
        View spacer = new View(this); d.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1)); d.addView(dHint);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams((int) dp(380), -1); dl.leftMargin = (int) dp(20);
        cols.addView(d, dl);

        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, 0, 1); cl.topMargin = (int) dp(18);
        page.addView(cols, cl);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        load();
    }

    LinearLayout.LayoutParams lp(float top) { LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-1, -2); l.topMargin = (int) dp(top); return l; }

    // ------------------------------------------------------------------ data

    void load() {
        if (cached != null && System.currentTimeMillis() - cachedAt < 6 * 3600_000L) { ready(cached); return; }
        Net.bg.execute(() -> {
            try {
                SharedPreferences cfg = Cfg.p(this);
                String province = cfg.getString("province", "");
                if (province.isEmpty()) { // learn it once from the configured city ("Samarinda" → "Kalimantan Timur")
                    Web.Place p = Web.INSTANCE.geocode(Cfg.s(this, "city", R.string.city));
                    if (p != null && !p.getRegion().isEmpty()) { province = p.getRegion(); cfg.edit().putString("province", province).apply(); }
                }
                // blank = built-in sources (iptv-org + dhanytv free TV); a custom playlist is filtered the same way
                List<LocalTv.Entry> l = LocalTv.INSTANCE.arrange(Sources.INSTANCE.load(cfg.getString("playlist_url", "")), province);
                String finalProvince = province;
                ui.post(() -> { cached = l; cachedAt = System.currentTimeMillis(); sub.setText(subtitle(finalProvince)); ready(l); });
                String guideUrl = cfg.getString("epg_url", "");
                Epg e = guideUrl.isEmpty() ? Epg.Companion.load(Sources.INSTANCE.getEPG()) : Epg.Companion.load(guideUrl);
                ui.post(() -> { epg = e; buildRows(); View f = getCurrentFocus(); if (f != null && f.getTag() instanceof LocalTv.Entry) detail((LocalTv.Entry) f.getTag()); });
            } catch (Exception e) {
                ui.post(() -> sub.setText("Daftar saluran tidak bisa dimuat. Cek internet, lalu buka lagi."));
            }
        });
    }

    String subtitle(String province) {
        boolean bittv = getPackageManager().getLaunchIntentForPackage(TV_APP) != null || getPackageManager().getLeanbackLaunchIntentForPackage(TV_APP) != null;
        return (province.isEmpty() ? "TV Indonesia" : "TV lokal " + province) + (bittv ? "  ·  ditonton lewat BitTV" : "  ·  pasang BitTV untuk menonton");
    }

    void ready(List<LocalTv.Entry> l) {
        all = l; list = l;
        if (sub.getText().toString().startsWith("Memuat")) sub.setText(subtitle(Cfg.p(this).getString("province", "")));
        buildCats(); buildRows();
        if (rows.getChildCount() > 0) rows.getChildAt(0).requestFocus();
    }

    // ------------------------------------------------------------------ lists

    TextView row(String text, float size, int wght) {
        TextView t = Ui.text(this, size, wght, Ui.TEXT);
        t.setText(text);
        t.setPadding((int) dp(14), (int) dp(10), (int) dp(14), (int) dp(10));
        t.setFocusable(true); t.setFocusableInTouchMode(true); t.setShadowLayer(0, 0, 0, 0);
        return t;
    }

    void buildCats() {
        cats.removeAllViews();
        addCat(null, "Semua");
        for (LocalTv.Category c : LocalTv.Category.values())
            for (LocalTv.Entry e : all) if (LocalTv.INSTANCE.inCategory(e, c)) { addCat(c, c.getLabel()); break; }
    }

    void addCat(LocalTv.Category c, String label) {
        TextView t = row(label, Ui.BODY, 500);
        t.setTextColor(c == cat ? Ui.ACCENT : Ui.TEXT);
        t.setOnFocusChangeListener((v, f) -> v.setBackground(f ? new GlassDrawable(dp(Ui.R_SMALL), Ui.GLASS_FOCUS, dp(1)) : null));
        t.setOnClickListener(v -> {
            cat = c; list = new ArrayList<>();
            for (LocalTv.Entry e : all) if (c == null || LocalTv.INSTANCE.inCategory(e, c)) list.add(e);
            buildCats(); buildRows();
            if (rows.getChildCount() > 0) rows.getChildAt(0).requestFocus();
        });
        cats.addView(t);
    }

    void buildRows() {
        rows.removeAllViews();
        Instant now = Instant.now();
        for (LocalTv.Entry e : list) {
            Epg.Programme p = onAir(e, now);
            TextView t = row(e.getName() + (e.getNear() ? "  •" : "") + (p != null ? "\n" + p.getTitle() : ""), Ui.BODY, 500);
            t.setSingleLine(false); t.setMaxLines(2); t.setTag(e);
            t.setOnFocusChangeListener((v, f) -> {
                v.setBackground(f ? new GlassDrawable(dp(Ui.R_SMALL), Ui.GLASS_FOCUS, dp(1)) : null);
                if (f) detail(e);
            });
            t.setOnClickListener(v -> watch(e));
            rows.addView(t);
        }
    }

    Epg.Programme onAir(LocalTv.Entry e, Instant now) {
        List<Epg.Programme> s = epg == null ? null : epg.schedule(e.getName(), now);
        return s != null && !s.isEmpty() && !s.get(0).getStart().isAfter(now) ? s.get(0) : null;
    }

    void detail(LocalTv.Entry e) {
        Instant now = Instant.now();
        dCat.setText(e.getCategory().getLabel() + (e.getNear() ? "  ·  dekat kamu" : "") + (e.getPartTime() ? "  ·  tidak 24 jam" : ""));
        dName.setText(e.getName());
        List<Epg.Programme> s = epg == null ? new ArrayList<>() : epg.schedule(e.getName(), now);
        Epg.Programme p = onAir(e, now);
        dNow.setText(p != null ? p.getTitle() + "   " + hm.format(p.getStart()) + "–" + hm.format(p.getStop()) : epg == null ? "Memuat jadwal…" : "Jadwal acara tidak tersedia");
        dDesc.setText(p != null && p.getDesc() != null ? p.getDesc() : "");
        StringBuilder next = new StringBuilder();
        for (Epg.Programme q : s) {
            if (q == p) continue;
            if (next.length() == 0) next.append("Berikutnya\n");
            next.append(hm.format(q.getStart())).append("   ").append(q.getTitle()).append('\n');
            if (next.length() > 400) break;
        }
        dNext.setText(next.toString().trim());
    }

    // ------------------------------------------------------------------ watch in BitTV

    void watch(LocalTv.Entry e) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(TV_APP);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(TV_APP);
        if (i == null) { Toast.makeText(this, "BitTV belum terpasang di TV ini", Toast.LENGTH_LONG).show(); return; }
        Toast.makeText(this, "Buka " + e.getName() + " di BitTV", Toast.LENGTH_SHORT).show();
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) { finish(); return true; }
        return super.onKeyDown(code, e);
    }
}
