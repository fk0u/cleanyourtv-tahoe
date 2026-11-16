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
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tech.kousozo.tahoetv.core.Epg;
import tech.kousozo.tahoetv.core.I18n;
import tech.kousozo.tahoetv.core.OnboardingText;
import tech.kousozo.tahoetv.core.PopularityScraper;
import tech.kousozo.tahoetv.core.Region;
import tech.kousozo.tahoetv.core.SourceHealth;
import tech.kousozo.tahoetv.core.LocalTv;
import tech.kousozo.tahoetv.core.Sources;
import tech.kousozo.tahoetv.core.Viewer;
import tech.kousozo.tahoetv.core.Web;

/** "Jadwal TV": what's on now and next on channels from the viewer's configured regions, local TV first when applicable.
 *  Choosing a channel plays it in Tahoe's built-in Live TV player. */
public class Live extends Activity {
    static final String TV_APP = "com.live_streaming_tv.online_tv"; // BitTV
    static List<LocalTv.Entry> cached; static Epg epg; static long cachedAt;   // survive re-opening; refreshed every 6 h

    final Handler ui = new Handler(Looper.getMainLooper());
    DateTimeFormatter hm = DateTimeFormatter.ofPattern("HH.mm").withZone(ZoneId.systemDefault());
    TextView title, sub, sortBtn, dName, dCat, dNow, dDesc, dNext, dHint;
    LinearLayout cats, rows;
    List<LocalTv.Entry> all = new ArrayList<>(), list = new ArrayList<>();
    LocalTv.Category cat;   // null = all
    boolean subLoaded = false;
    SharedPreferences prefs;

    float dp(float v) { return Ui.dp(this, v); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (TvGuard.blockIfNotTv(this)) return;
        prefs = getSharedPreferences("live", MODE_PRIVATE);
        Viewer vw = TvViewer.get(this);
        String lang = vw.getLanguage();
        hm = DateTimeFormatter.ofPattern("HH.mm").withZone(vw.getZone());
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.NIGHT);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding((int) dp(Ui.SAFE_SIDE), (int) dp(Ui.SAFE_TOP), (int) dp(Ui.SAFE_SIDE), (int) dp(Ui.SAFE_TOP));
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this); titles.setOrientation(LinearLayout.VERTICAL);
        title = Ui.text(this, Ui.TITLE, 700, Ui.TEXT); title.setText(I18n.get(lang, "tv.guide_title"));
        sub = Ui.text(this, Ui.BODY, 400, Ui.TEXT_2); sub.setText(I18n.get(lang, "tv.guide_loading"));
        titles.addView(title); titles.addView(sub);
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        sortBtn = Ui.text(this, Ui.CAPTION, 600, Ui.TEXT);
        sortBtn.setPadding((int) dp(14), (int) dp(8), (int) dp(14), (int) dp(8));
        sortBtn.setBackground(new GlassDrawable(dp(16), Ui.GLASS, dp(1)));
        sortBtn.setFocusable(true); sortBtn.setFocusableInTouchMode(true);
        sortBtn.setOnFocusChangeListener((v, f) -> v.setBackground(new GlassDrawable(dp(16), f ? Ui.GLASS_FOCUS : Ui.GLASS, dp(1))));
        sortBtn.setOnClickListener(v -> cycleSort());
        updateSortBtn();
        head.addView(sortBtn);
        page.addView(head);

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
        dHint = Ui.text(this, Ui.CAPTION, 500, Ui.TEXT_3); dHint.setText(I18n.get(lang, "tv.guide_ok_hint"));
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
        String lang = TvViewer.get(this).getLanguage();
        fetch(this, l -> {
                    subLoaded = true;
                    sub.setText(subtitle(Cfg.p(this).getString("province", "")));
                    ready(l);
                },
                e -> { buildRows(); View f = getCurrentFocus(); if (f != null && f.getTag() instanceof LocalTv.Entry) detail((LocalTv.Entry) f.getTag()); },
                () -> sub.setText(I18n.get(lang, "tv.guide_load_failed")));
    }

    void updateSortBtn() {
        if (sortBtn == null) return;
        Viewer vw = TvViewer.get(this);
        String lang = vw.getLanguage();
        sortBtn.setText(I18n.get(lang, "sort.label") + ": " + vw.getSort().label(lang) + "  ⟳");
    }

    void cycleSort() {
        Viewer vw = TvViewer.get(this);
        LocalTv.SortMode[] modes = LocalTv.SortMode.values();
        int nextIdx = (vw.getSort().ordinal() + 1) % modes.length;
        TvViewer.setSortMode(this, modes[nextIdx]);
        updateSortBtn();
        Viewer updated = TvViewer.get(this);
        if (!all.isEmpty()) {
            all = updated.arrange(all);
            cached = all;
            filterCat();
        }
    }

    /** Channel list + guide, cached for 6 h and shared with the Live TV player. Callbacks run on the main thread. */
    static void fetch(android.content.Context c, java.util.function.Consumer<List<LocalTv.Entry>> list,
                      java.util.function.Consumer<Epg> guide, Runnable failed) {
        Handler main = new Handler(Looper.getMainLooper());
        SourceHealth.init(new java.io.File(c.getCacheDir(), "source-health.tsv"), new java.io.File(c.getFilesDir(), "source-banned.txt")); // bans: durable
        Sources.initCache(c.getCacheDir());
        tech.kousozo.tahoetv.core.MediaScraper.initCache(c.getCacheDir());
        Sources.scheduleDailyRefresh(() -> cachedAt = 0);
        boolean fresh = cached != null && System.currentTimeMillis() - cachedAt < 6 * 3600_000L;
        if (fresh) { probe(c, cached); list.accept(cached); if (epg != null) { guide.accept(epg); return; } }
        Net.bg.execute(() -> {
            try {
                SharedPreferences cfg = Cfg.p(c);
                Viewer vw = TvViewer.get(c);
                List<LocalTv.Entry> currentChannels = cached;
                if (!fresh) {
                    String province = vw.getDone()
                            ? (vw.getNearRegion() == null ? "" : vw.getNearRegion())
                            : cfg.getString("province", "");
                    if (province.isEmpty() && !vw.getDone()) { // learn it once before onboarding from the configured city
                        Web.Place p = Web.INSTANCE.geocode(Cfg.s(c, "city", R.string.city), vw.getLanguage());
                        if (p != null && !p.getRegion().isEmpty()) { province = p.getRegion(); cfg.edit().putString("province", province).apply(); }
                    }
                    final String nearProvince = province;
                    final String extraPlaylist = cfg.getString("playlist_url", "");
                    // Priority load: render the viewer's primary country first while secondary countries finish loading
                    List<tech.kousozo.tahoetv.core.M3u.Channel> rawAll = Sources.loadCountriesPriority(extraPlaylist, vw.getCountries(), priorityRaw -> {
                        if (priorityRaw.isEmpty()) return kotlin.Unit.INSTANCE;
                        List<LocalTv.Entry> first = vw.arrange(LocalTv.INSTANCE.arrange(
                                priorityRaw, nearProvince, vw.getCountries(), vw.getSort(), vw.getCity(), vw.getCountry()));
                        if (!first.isEmpty()) {
                            main.post(() -> { cached = first; cachedAt = System.currentTimeMillis(); list.accept(first); });
                        }
                        return kotlin.Unit.INSTANCE;
                    });
                    List<LocalTv.Entry> l = vw.arrange(LocalTv.INSTANCE.arrange(
                            rawAll, nearProvince, vw.getCountries(), vw.getSort(), vw.getCity(), vw.getCountry()));
                    currentChannels = l;
                    probe(c, l);
                    List<String> chNames = new ArrayList<>(l.size());
                    for (LocalTv.Entry entry : l) chNames.add(entry.getName());
                    PopularityScraper.prefetchAsync(vw.getCountries(), chNames, () -> {
                        Viewer latestVw = TvViewer.get(c);
                        List<LocalTv.Entry> resorted = latestVw.arrange(l);
                        main.post(() -> { cached = resorted; cachedAt = System.currentTimeMillis(); list.accept(resorted); });
                        return kotlin.Unit.INSTANCE;
                    });
                    main.post(() -> { cached = l; cachedAt = System.currentTimeMillis(); list.accept(l); });
                }
                String guideUrl = cfg.getString("epg_url", "");
                List<String> guides = new ArrayList<>();
                for (String cc : vw.getCountries()) for (String g : Region.guides(cc)) if (!guides.contains(g)) guides.add(g);
                java.util.Set<String> channelNames = new java.util.HashSet<>();
                if (currentChannels != null) for (LocalTv.Entry entry : currentChannels) {
                    channelNames.add(entry.getName());
                    if (entry.getChannel().getTvgId() != null) channelNames.add(entry.getChannel().getTvgId());
                }
                Epg e = guideUrl.isEmpty() ? Epg.Companion.loadPriority(guides, channelNames) : Epg.Companion.loadPriority(java.util.Collections.singletonList(guideUrl), channelNames);
                main.post(() -> { epg = e; guide.accept(e); });
            } catch (Exception ex) { main.post(failed); }
        });
    }

    /** Check every channel's streams in the background, the last-watched channel and its neighbours first. */
    static void probe(android.content.Context c, List<LocalTv.Entry> l) {
        String last = c.getSharedPreferences("live", 0).getString("last", null);
        int at = 0;
        for (int k = 0; k < l.size(); k++) if (l.get(k).getChannel().getUrl().equals(last)) { at = k; break; }
        List<LocalTv.Entry> first = new java.util.ArrayList<>();
        for (int d = 0; d < l.size(); d++) first.add(l.get((at + (d % 2 == 0 ? d / 2 : l.size() - (d + 1) / 2)) % l.size()));
        SourceHealth.probeAll(SourceHealth.streamsOf(first));
    }

    String subtitle(String province) {
        Viewer vw = TvViewer.get(this);
        String lang = vw.getLanguage();
        String place = (!vw.getDone() || vw.getNearRegion() != null) && !province.isEmpty() ? province : "";
        if (!place.isEmpty()) return I18n.get(lang, "tv.guide_sub_place", place);
        List<String> names = new ArrayList<>();
        for (String cc : vw.getCountries()) names.add(Region.name(cc, lang));
        return I18n.get(lang, "tv.guide_sub_country", String.join(", ", names));
    }

    void ready(List<LocalTv.Entry> l) {
        all = Kids.channels(this, l); // kids profile: kids' and documentary channels only
        filterCat();
        if (!subLoaded) { subLoaded = true; sub.setText(subtitle(Cfg.p(this).getString("province", ""))); }
    }

    void selectCategory(LocalTv.Category next) {
        cat = next;
        filterCat();
    }

    void filterCat() {
        list = new ArrayList<>();
        for (LocalTv.Entry e : all) if (cat == null || LocalTv.INSTANCE.inCategory(e, cat)) list.add(e);
        buildCats(); buildRows();
        if (rows.getChildCount() > 0) rows.getChildAt(0).requestFocus();
        else if (cats.getChildCount() > 0) cats.getChildAt(0).requestFocus();
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
        String lang = TvViewer.get(this).getLanguage();
        List<Map.Entry<LocalTv.Category, String>> items = new ArrayList<>();
        items.add(new AbstractMap.SimpleEntry<>(null, I18n.get(lang, "tv.guide_all")));
        for (LocalTv.Category c : LocalTv.Category.values()) {
            for (LocalTv.Entry e : all) {
                if (LocalTv.INSTANCE.inCategory(e, c)) {
                    items.add(new AbstractMap.SimpleEntry<>(c, OnboardingText.INSTANCE.category(lang, c)));
                    break;
                }
            }
        }
        for (Map.Entry<LocalTv.Category, String> item : items) {
            final LocalTv.Category target = item.getKey();
            TextView t = row(item.getValue(), Ui.BODY, 500);
            t.setTextColor(target == cat ? Ui.ACCENT : Ui.TEXT);
            t.setOnFocusChangeListener((v, f) -> v.setBackground(f ? new GlassDrawable(dp(Ui.R_SMALL), Ui.GLASS_FOCUS, dp(1)) : null));
            t.setOnClickListener(v -> selectCategory(target));
            cats.addView(t);
        }
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
        List<Epg.Programme> s = epg == null ? null : epg.schedule(e.getName(), now, e.getChannel().getTvgId());
        return s != null && !s.isEmpty() && !s.get(0).getStart().isAfter(now) ? s.get(0) : null;
    }

    void detail(LocalTv.Entry e) {
        Instant now = Instant.now();
        String lang = TvViewer.get(this).getLanguage();
        dCat.setText(OnboardingText.INSTANCE.category(lang, e.getCategory())
                + (e.getNear() ? "  ·  " + I18n.get(lang, "ch.near_badge") : "")
                + (e.getPartTime() ? "  ·  " + I18n.get(lang, "tv.part_time_badge") : ""));
        dName.setText(e.getName());
        List<Epg.Programme> s = epg == null ? new ArrayList<>() : epg.schedule(e.getName(), now, e.getChannel().getTvgId());
        Epg.Programme p = onAir(e, now);
        dNow.setText(p != null ? p.getTitle() + "   " + hm.format(p.getStart()) + "–" + hm.format(p.getStop())
                : epg == null ? I18n.get(lang, "tv.guide_loading_sched") : I18n.get(lang, "tv.guide_no_sched"));
        dDesc.setText(p != null && p.getDesc() != null ? p.getDesc() : "");
        StringBuilder next = new StringBuilder();
        for (Epg.Programme q : s) {
            if (q == p) continue;
            if (next.length() == 0) next.append(I18n.get(lang, "tv.guide_next_lbl")).append('\n');
            next.append(hm.format(q.getStart())).append("   ").append(q.getTitle()).append('\n');
            if (next.length() > 400) break;
        }
        dNext.setText(next.toString().trim());
    }

    // ------------------------------------------------------------------ watch in BitTV

    /** Watch in Tahoe's own Live TV player, starting at this channel. */
    void watch(LocalTv.Entry e) {
        startActivity(new Intent(this, LiveTv.class).putExtra("url", e.getChannel().getUrl()));
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = Home.gamepadToRemote(e.getKeyCode());
        if (k != e.getKeyCode()) e = new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(), k, e.getRepeatCount(), e.getMetaState(), e.getDeviceId(), e.getScanCode(), e.getFlags(), e.getSource());
        return super.dispatchKeyEvent(e);
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU && e.getRepeatCount() == 0) { cycleSort(); return true; }
        if (code == KeyEvent.KEYCODE_BACK) { finish(); return true; }
        return super.onKeyDown(code, e);
    }
}
