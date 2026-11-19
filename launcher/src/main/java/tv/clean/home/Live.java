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
    LivePreview preview;
    FrameLayout root;
    /** Channel detail sheet over the guide (#181), or null. */
    View sheet;
    /** Text typed in the guide's search field (#181). */
    String query = "";
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
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.NIGHT);
        LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding((int) dp(Ui.SAFE_SIDE), (int) dp(Ui.SAFE_TOP), (int) dp(Ui.SAFE_SIDE), (int) dp(Ui.SAFE_TOP));
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this); titles.setOrientation(LinearLayout.VERTICAL);
        title = Ui.text(this, Ui.TITLE, 700, Ui.TEXT); title.setText(I18n.get(lang, "tv.guide_title"));
        sub = Ui.text(this, Ui.BODY, 400, Ui.TEXT_2); sub.setText(I18n.get(lang, "tv.guide_loading"));
        titles.addView(title); titles.addView(sub);
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        // search (#181): the TV's own keyboard opens on OK; the list filters as you type
        android.widget.EditText search = new android.widget.EditText(this);
        search.setSingleLine(); search.setHint(I18n.get(lang, "home.search_hint"));
        search.setTextColor(Ui.TEXT); search.setHintTextColor(Ui.TEXT_3);
        search.setTextSize(Ui.CAPTION * Ui.textScale(this)); search.setTypeface(Ui.font(this, 500));
        search.setPadding((int) dp(16), (int) dp(8), (int) dp(16), (int) dp(8));
        search.setBackground(new GlassDrawable(dp(16), Ui.GLASS, dp(1)));
        search.setOnFocusChangeListener((v, f) -> v.setBackground(f ? Ui.focusRing(this, dp(16)) : new GlassDrawable(dp(16), Ui.GLASS, dp(1))));
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence a, int b, int c2, int d2) {}
            @Override public void onTextChanged(CharSequence a, int b, int c2, int d2) {}
            @Override public void afterTextChanged(android.text.Editable ed) { query = ed.toString().trim(); filterCat(false); }
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams((int) dp(240), -2); sl.setMarginEnd((int) dp(12));
        head.addView(search, sl);
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
        // live preview (#181): the focused channel plays quietly here once focus rests
        FrameLayout pv = new FrameLayout(this);
        pv.setBackground(new GlassDrawable(dp(Ui.R_MED), 0x33000000, dp(1)));
        Ui.clip(pv, dp(Ui.R_MED));
        android.view.TextureView tex = new android.view.TextureView(this);
        pv.addView(tex, new FrameLayout.LayoutParams(-1, -1));
        TextView badge = Ui.eyebrow(this, Ui.TEXT);
        badge.setText(I18n.get(lang, "tv.live_preview"));
        badge.setPadding((int) dp(10), (int) dp(5), (int) dp(10), (int) dp(5));
        badge.setBackground(new GlassDrawable(dp(12), 0xCC000000, dp(1)));
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START); bl.setMarginStart((int) dp(10)); bl.topMargin = (int) dp(10);
        pv.addView(badge, bl);
        preview = new LivePreview(this, tex, badge);
        if (LivePreview.enabled(this)) { LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, (int) dp(186)); pl.bottomMargin = (int) dp(14); d.addView(pv, pl); }
        d.addView(dCat); d.addView(dName, lp(4)); d.addView(dNow, lp(18)); d.addView(dDesc, lp(6)); d.addView(dNext, lp(18));
        View spacer = new View(this); d.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1)); d.addView(dHint);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams((int) dp(380), -1); dl.setMarginStart((int) dp(20));
        cols.addView(d, dl);

        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, 0, 1); cl.topMargin = (int) dp(18);
        page.addView(cols, cl);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        Ui.direction(root);
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
                      java.util.function.Consumer<Epg> guide, Runnable failed) { fetch(c, false, list, guide, failed); }

    /** [listOnly] = just the channel list (no guide, no stream probing, no popularity lookup): Home uses it so the hero can preview
     *  a channel before the Live TV screen was ever opened, without weighing on a 1 GB stick. */
    static void fetch(android.content.Context c, boolean listOnly, java.util.function.Consumer<List<LocalTv.Entry>> list,
                      java.util.function.Consumer<Epg> guide, Runnable failed) {
        Handler main = new Handler(Looper.getMainLooper());
        SourceHealth.init(new java.io.File(c.getCacheDir(), "source-health.tsv"), new java.io.File(c.getFilesDir(), "source-banned.txt")); // bans: durable
        Sources.initCache(c.getCacheDir());
        tech.kousozo.tahoetv.core.MediaScraper.initCache(c.getCacheDir());
        Sources.scheduleDailyRefresh(() -> cachedAt = 0);
        boolean fresh = cached != null && System.currentTimeMillis() - cachedAt < 6 * 3600_000L;
        if (fresh) { if (!listOnly) probe(c, cached); list.accept(cached); if (listOnly) return; if (epg != null) { guide.accept(epg); return; } }
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
                    List<tech.kousozo.tahoetv.core.M3u.Channel> rawAll = Sources.loadChannels(extraPlaylist, vw.getCountries(), vw.getChannelLanguages(), priorityRaw -> {
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
                    if (!listOnly) {
                        probe(c, l);
                        List<String> chNames = new ArrayList<>(l.size());
                        for (LocalTv.Entry entry : l) chNames.add(entry.getName());
                        PopularityScraper.prefetchAsync(vw.getCountries(), chNames, () -> {
                            Viewer latestVw = TvViewer.get(c);
                            List<LocalTv.Entry> resorted = latestVw.arrange(l);
                            main.post(() -> { cached = resorted; cachedAt = System.currentTimeMillis(); list.accept(resorted); });
                            return kotlin.Unit.INSTANCE;
                        });
                    }
                    main.post(() -> { cached = l; cachedAt = System.currentTimeMillis(); list.accept(l); });
                }
                if (listOnly) return;
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

    void filterCat() { filterCat(true); }

    void filterCat(boolean moveFocus) {
        list = new ArrayList<>();
        String q = query.toLowerCase(java.util.Locale.ROOT);
        for (LocalTv.Entry e : all) if ((cat == null || LocalTv.INSTANCE.inCategory(e, cat)) && (q.isEmpty() || e.getName().toLowerCase(java.util.Locale.ROOT).contains(q))) list.add(e);
        buildCats(); buildRows();
        if (!moveFocus) return; // typing in the search field keeps focus there
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
            t.setOnFocusChangeListener((v, f) -> v.setBackground(f ? Ui.focusRing(this, dp(Ui.R_SMALL)) : null));
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
                v.setBackground(f ? Ui.focusRing(this, dp(Ui.R_SMALL)) : null);
                if (f) { detail(e); preview.focus(e.getChannel()); }
            });
            t.setOnClickListener(v -> watch(e));
            t.setOnLongClickListener(v -> { channelDetail(e); return true; });
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

    /** Channel details (#41): long-press OK or the Info key on a guide row. Watch, and add/remove favourite
     *  (the same "fav" set Live TV's favourites shelf reads). */
    void channelDetail(LocalTv.Entry e) {
        String lang = TvViewer.get(this).getLanguage();
        detail(e);
        closeSheet();
        if (preview != null) preview.stop();
        SharedPreferences live = getSharedPreferences("live", MODE_PRIVATE);
        java.util.Set<String> fav = new java.util.HashSet<>(live.getStringSet("fav", new java.util.HashSet<>()));
        String url = e.getChannel().getUrl();
        boolean isFav = fav.contains(url);

        FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(0xB3050508);
        scrim.setClickable(true); scrim.setOnClickListener(v -> closeSheet());
        LinearLayout p = new LinearLayout(this); p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding((int) dp(28), (int) dp(28), (int) dp(28), (int) dp(28));
        p.setBackground(new GlassDrawable(dp(Ui.R_LARGE), Ui.GLASS_STRONG, dp(1)));
        p.setClickable(true);
        // big logo on white, like the phone's channel page
        FrameLayout logoBox = new FrameLayout(this);
        logoBox.setBackground(new GlassDrawable(dp(Ui.R_MED), 0xFFFFFFFF, dp(1)));
        android.widget.ImageView logo = new android.widget.ImageView(this); logo.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        TextView fb = Ui.text(this, Ui.HEADLINE, 700, 0xFF111114); fb.setShadowLayer(0, 0, 0, 0); fb.setGravity(Gravity.CENTER);
        logoBox.addView(fb, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(-1, -1); ll.setMargins((int) dp(12), (int) dp(10), (int) dp(12), (int) dp(10));
        logoBox.addView(logo, ll);
        Ui.bindLogo(e.getChannel().getLogo(), logo, fb, e.getName());
        p.addView(logoBox, new LinearLayout.LayoutParams((int) dp(132), (int) dp(84)));
        TextView cat = Ui.eyebrow(this, Ui.TEXT_2); cat.setText(dCat.getText()); p.addView(cat, lp(18));
        TextView name = Ui.text(this, Ui.TITLE, 700, Ui.TEXT); name.setText(e.getName()); p.addView(name, lp(4));
        TextView now = Ui.text(this, Ui.HEADLINE, 600, Ui.ACCENT); now.setSingleLine(false); now.setMaxLines(2); now.setText(dNow.getText()); p.addView(now, lp(16));
        if (dDesc.getText().length() > 0) { TextView ds = Ui.text(this, Ui.BODY, 400, Ui.TEXT_2); ds.setSingleLine(false); ds.setMaxLines(3); ds.setText(dDesc.getText()); p.addView(ds, lp(6)); }
        if (dNext.getText().length() > 0) { TextView nx = Ui.text(this, Ui.BODY, 400, Ui.TEXT); nx.setSingleLine(false); nx.setMaxLines(5); nx.setLineSpacing(dp(4), 1); nx.setText(dNext.getText()); p.addView(nx, lp(18)); }
        View grow = new View(this); p.addView(grow, new LinearLayout.LayoutParams(0, 0, 1));
        // large buttons, one row, each with a clear focus ring
        LinearLayout acts = new LinearLayout(this); acts.setGravity(Gravity.CENTER_VERTICAL);
        TextView watchB = sheetButton(I18n.get(lang, "home.watch"), true, () -> { closeSheet(); watch(e); });
        acts.addView(watchB);
        // the main action keeps its label; the others are round icons, and the focused one names itself below the row
        TextView actHint = Ui.text(this, Ui.BODY, 500, Ui.TEXT_2);
        acts.addView(sheetIcon(R.drawable.ic_record, I18n.get(lang, "tv.live_record_btn"), actHint, () -> {
            closeSheet(); if (preview != null) preview.stop();
            startActivity(new Intent(this, LiveTv.class).putExtra("url", url).putExtra("record", true)); }));
        acts.addView(sheetIcon(isFav ? R.drawable.ic_heart_filled : R.drawable.ic_heart, I18n.get(lang, isFav ? "tv.fav_remove" : "tv.fav_add"), actHint, () -> {
            if (isFav) fav.remove(url); else fav.add(url);
            live.edit().putStringSet("fav", fav).apply();
            closeSheet(); }));
        View.OnFocusChangeListener watchFocus = watchB.getOnFocusChangeListener();
        watchB.setOnFocusChangeListener((v, f) -> { if (watchFocus != null) watchFocus.onFocusChange(v, f); if (f) actHint.setText(""); });
        p.addView(acts, lp(20));
        p.addView(actHint, lp(10));
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams((int) dp(500), -1, Gravity.END);
        pl.setMargins((int) dp(24), (int) dp(24), (int) dp(24), (int) dp(24));
        scrim.addView(p, pl);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        sheet = scrim;
        // slide in from the end edge like a sheet (drawer curve), or just appear with reduced motion
        float m = Math.min(1f, Ui.motion(this));
        scrim.setAlpha(0f); p.setTranslationX(dp(40));
        scrim.animate().alpha(1f).setDuration((long) (tech.kousozo.tahoetv.core.Brand.MOTION_MED * m)).start();
        p.animate().translationX(0).setDuration((long) (tech.kousozo.tahoetv.core.Brand.MOTION_SHEET * m)).setInterpolator(Ui.drawer()).start();
        watchB.requestFocus();
    }

    /** A big rounded button for the detail sheet: white when it is the main action, glass otherwise. */
    TextView sheetButton(String label, boolean primary, Runnable onClick) {
        TextView b = Ui.text(this, Ui.BODY, 600, primary ? 0xFF111114 : Ui.TEXT);
        b.setShadowLayer(0, 0, 0, 0); b.setText(label); b.setGravity(Gravity.CENTER);
        b.setPadding((int) dp(22), (int) dp(14), (int) dp(22), (int) dp(14));
        b.setFocusable(true); b.setFocusableInTouchMode(true);
        android.graphics.drawable.GradientDrawable base = new android.graphics.drawable.GradientDrawable();
        base.setCornerRadius(dp(26)); base.setColor(primary ? 0xFFF7F8FA : 0x24FFFFFF);
        b.setBackground(base);
        b.setOnFocusChangeListener((v, f) -> {
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setCornerRadius(dp(26)); g.setColor(primary ? 0xFFF7F8FA : (f ? 0x47FFFFFF : 0x24FFFFFF));
            if (f) g.setStroke((int) dp(tech.kousozo.tahoetv.core.Brand.FOCUS_RING_DP), Ui.highContrast(this) ? tech.kousozo.tahoetv.core.Brand.HC_FOCUS : tech.kousozo.tahoetv.core.Brand.CYAN);
            v.setBackground(g);
            v.animate().scaleX(f ? 1.04f : 1f).scaleY(f ? 1.04f : 1f).setDuration(Ui.dur(this, Ui.FAST)).start();
        });
        b.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2); l.setMarginEnd((int) dp(12));
        b.setLayoutParams(l);
        return b;
    }

    /** A round glass icon button for the detail sheet: 60 dp, ring + slight growth on focus, and its name shown in [hint] while focused. */
    android.widget.ImageView sheetIcon(int res, String label, TextView hint, Runnable onClick) {
        android.widget.ImageView b = new android.widget.ImageView(this);
        b.setImageResource(res); b.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        int pad = (int) dp(17); b.setPadding(pad, pad, pad, pad);
        b.setContentDescription(label);
        b.setFocusable(true); b.setFocusableInTouchMode(true);
        android.graphics.drawable.GradientDrawable base = new android.graphics.drawable.GradientDrawable();
        base.setShape(android.graphics.drawable.GradientDrawable.OVAL); base.setColor(0x24FFFFFF);
        b.setBackground(base);
        b.setOnFocusChangeListener((v, f) -> {
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL); g.setColor(f ? 0x47FFFFFF : 0x24FFFFFF);
            if (f) g.setStroke((int) dp(tech.kousozo.tahoetv.core.Brand.FOCUS_RING_DP), Ui.highContrast(this) ? tech.kousozo.tahoetv.core.Brand.HC_FOCUS : tech.kousozo.tahoetv.core.Brand.CYAN);
            v.setBackground(g);
            v.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).setDuration(Ui.dur(this, Ui.FAST)).start();
            if (f) hint.setText(label);
        });
        b.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams((int) dp(60), (int) dp(60)); l.setMarginEnd((int) dp(12));
        b.setLayoutParams(l);
        return b;
    }

    void closeSheet() {
        if (sheet == null) return;
        View s = sheet; sheet = null;
        s.animate().alpha(0f).setDuration(Ui.dur(this, 200)).withEndAction(() -> root.removeView(s)).start();
        View f = rows.getFocusedChild(); if (f != null) f.requestFocus();
    }

    // ------------------------------------------------------------------ watch in BitTV

    /** Watch in Tahoe's own Live TV player, starting at this channel. */
    void watch(LocalTv.Entry e) {
        if (preview != null) preview.stop(); // one player at a time on small TV sticks
        startActivity(new Intent(this, LiveTv.class).putExtra("url", e.getChannel().getUrl()));
    }

    @Override protected void onPause() { super.onPause(); if (preview != null) preview.stop(); }
    @Override protected void onDestroy() { if (preview != null) preview.release(); super.onDestroy(); }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = Home.gamepadToRemote(e.getKeyCode());
        if (k != e.getKeyCode()) e = new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(), k, e.getRepeatCount(), e.getMetaState(), e.getDeviceId(), e.getScanCode(), e.getFlags(), e.getSource());
        return super.dispatchKeyEvent(e);
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU && e.getRepeatCount() == 0) { cycleSort(); return true; }
        if ((code == KeyEvent.KEYCODE_INFO || code == KeyEvent.KEYCODE_PROG_RED) && e.getRepeatCount() == 0) {
            View f = getCurrentFocus();
            if (f != null && f.getTag() instanceof LocalTv.Entry) { channelDetail((LocalTv.Entry) f.getTag()); return true; }
        }
        if (code == KeyEvent.KEYCODE_BACK) { if (sheet != null) closeSheet(); else finish(); return true; }
        return super.onKeyDown(code, e);
    }
}
