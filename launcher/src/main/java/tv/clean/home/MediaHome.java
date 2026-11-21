package tv.clean.home;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import tech.kousozo.tahoetv.core.Epg;
import tech.kousozo.tahoetv.core.I18n;
import tech.kousozo.tahoetv.core.LocalTv;
import tech.kousozo.tahoetv.core.M3u;
import tech.kousozo.tahoetv.core.OnboardingText;

/** The TV Home from the Figma design (frame "TV / Home"), laid out on its 1920x1080 grid and scaled to the screen:
 *  top bar with a tab pill, hero copy for the focused channel, and a "Lanjutkan menonton" row of channel cards.
 *  Plain Views and native D-pad focus; the live preview plays in the Hero behind it. */
final class MediaHome extends FrameLayout {
    private final Home home;
    private final float S;
    private final String lang;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final TextView greeting, eyebrow, title, desc, clock;
    private final View liveBadge, hint;
    private final LinearLayout cards;
    private final HorizontalScrollView scroller;
    private final List<LocalTv.Entry> entries = new ArrayList<>();
    private final List<View> cardViews = new ArrayList<>();
    private TextView watchBtn;
    private int shown = -1;
    private final java.util.Map<String, Runnable> snapBinders = new java.util.HashMap<>();

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        LivePreview.snapListener = key -> { Runnable r = snapBinders.get(key); if (r != null) r.run(); };
    }

    MediaHome(Home home, int width) {
        super(home);
        this.home = home;
        this.S = width / 1920f;
        this.lang = TvViewer.get(home).getLanguage();
        Context c = home;
        setClipChildren(false); setClipToPadding(false);

        // scrim: exactly the two gradients of the design
        View scrim = new View(c);
        scrim.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                grad(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x8008080D, 0x0008080D, 0x1A08080D, 0xFF08080D}, new float[]{0f, 0.18f, 0.5f, 0.75f}),
                grad(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xF208080D, 0xB308080D, 0x0008080D}, new float[]{0f, 0.38f, 0.7f})}));
        addView(scrim, new LayoutParams(-1, -1));

        // ---- top bar (x112 y56 w1696 h64)
        LinearLayout bar = new LinearLayout(c); bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout brand = new LinearLayout(c); brand.setGravity(Gravity.CENTER_VERTICAL);
        View logo = new View(c);
        GradientDrawable lg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF1F3BD1, 0xFF63D1FF});
        lg.setCornerRadius(px(16)); logo.setBackground(lg);
        brand.addView(logo, new LinearLayout.LayoutParams(px(60), px(60)));
        greeting = tv("", 36, 700, Color.WHITE); greeting.setPadding(px(16), 0, 0, 0); greeting.setSingleLine(true);
        greeting.setMinWidth(px(250));
        brand.addView(greeting);
        bar.addView(brand);
        bar.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1));
        LinearLayout nav = new LinearLayout(c); nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(px(8), px(8), px(8), px(8));
        nav.setBackground(pill(0x14FFFFFF, 0x1FFFFFFF, px(1)));
        addTab(nav, I18n.get(lang, "tv.mh_tab_home"), true, null);
        addTab(nav, I18n.get(lang, "tv.mh_tab_media"), false, () -> home.start(new Intent(home, LiveTv.class)));
        addTab(nav, I18n.get(lang, "tv.mh_tab_schedule"), false, () -> home.start(new Intent(home, Live.class)));
        addTab(nav, I18n.get(lang, "tv.mh_tab_rec"), false, () -> home.openSheet(new Rekaman(home)));
        bar.addView(nav);
        bar.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1));
        LinearLayout right = new LinearLayout(c); right.setGravity(Gravity.CENTER_VERTICAL);
        chip(right, R.drawable.ic_search_cari, null, () -> home.openSheet(new Cari(home)));
        clock = tv("", 46, 700, Color.WHITE); clock.setPadding(px(22), 0, 0, 0); clock.setIncludeFontPadding(false); clock.setLetterSpacing(0f);
        right.addView(clock);
        bar.addView(right);
        LayoutParams bl = new LayoutParams(px(1696), px(92)); bl.leftMargin = px(112); bl.topMargin = px(48);
        addView(bar, bl);

        // ---- hero copy (x112 y250)
        LinearLayout copy = new LinearLayout(c); copy.setOrientation(LinearLayout.VERTICAL); copy.setClipChildren(false);
        LinearLayout line1 = new LinearLayout(c); line1.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout live = new LinearLayout(c); live.setGravity(Gravity.CENTER_VERTICAL);
        live.setPadding(px(12), px(6), px(15), px(6)); live.setBackground(pill(0xFFFF453A, 0, 0));
        View dot = new View(c); GradientDrawable dd = new GradientDrawable(); dd.setShape(GradientDrawable.OVAL); dd.setColor(Color.WHITE); dot.setBackground(dd);
        live.addView(dot, new LinearLayout.LayoutParams(px(9), px(9)));
        TextView lt = tv(I18n.get(lang, "tv.mh_live"), 16.5f, 600, Color.WHITE); lt.setLetterSpacing(0.12f); lt.setPadding(px(9), 0, 0, 0);
        live.addView(lt);
        liveBadge = live;
        line1.addView(live);
        eyebrow = tv("", 16, 600, 0xCCFFFFFF); eyebrow.setLetterSpacing(0.1f); eyebrow.setPadding(px(12), 0, 0, 0);
        line1.addView(eyebrow);
        copy.addView(line1);
        title = tv("", 96, 700, Color.WHITE); title.setLetterSpacing(-0.02f); title.setIncludeFontPadding(false);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2); tl.topMargin = px(22);
        copy.addView(title, tl);
        desc = tv("", 24, 400, 0xBFFFFFFF); desc.setSingleLine(false); desc.setMaxLines(2); desc.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(px(860), -2); dl.topMargin = px(20);
        copy.addView(desc, dl);
        LinearLayout btns = new LinearLayout(c); btns.setClipChildren(false);
        watchBtn = button(I18n.get(lang, "home.watch"), true, R.drawable.ic_play, this::watchCurrent);
        btns.addView(watchBtn);
        TextView detail = button(I18n.get(lang, "tv.mh_details"), false, 0, () -> home.start(new Intent(home, Live.class)));
        LinearLayout.LayoutParams dbl = new LinearLayout.LayoutParams(-2, -2); dbl.setMarginStart(px(16));
        btns.addView(detail, dbl);
        LinearLayout.LayoutParams bpl = new LinearLayout.LayoutParams(-2, -2); bpl.topMargin = px(20);
        copy.addView(btns, bpl);
        LayoutParams cl = new LayoutParams(px(1500), -2); cl.leftMargin = px(112); cl.topMargin = px(262);
        addView(copy, cl);

        // ---- row (x112 y700)
        LinearLayout rows = new LinearLayout(c); rows.setOrientation(LinearLayout.VERTICAL); rows.setClipChildren(false);
        TextView rh = tv(I18n.get(lang, "tv.mh_continue"), 30, 600, Color.WHITE);
        rows.addView(rh);
        scroller = new HorizontalScrollView(c);
        scroller.setHorizontalScrollBarEnabled(false); scroller.setClipChildren(false); scroller.setClipToPadding(false);
        scroller.setPadding(px(10), px(40), px(10), px(40)); // room for the focus glow
        cards = new LinearLayout(c); cards.setClipChildren(false);
        scroller.addView(cards);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(px(1720), -2); sl.topMargin = -px(18); sl.setMarginStart(-px(10));
        rows.addView(scroller, sl);
        LayoutParams rl = new LayoutParams(px(1800), -2); rl.leftMargin = px(112); rl.topMargin = px(672);
        addView(rows, rl);

        hint = tv(I18n.get(lang, "tv.mh_hint"), 19, 500, 0x73FFFFFF);
        LayoutParams hl = new LayoutParams(-2, -2, Gravity.TOP | Gravity.END); hl.rightMargin = px(112); hl.topMargin = px(655);
        addView(hint, hl);
        hint.setAlpha(0f);

        tickClock();
        startGreeting();
    }

    // ------------------------------------------------------------------ data

    /** Rebuilds the card row from the channel list: recently watched first, then the main channels. */
    void refresh() {
        List<LocalTv.Entry> all = Live.cached;
        if (all == null || all.isEmpty()) return;
        List<LocalTv.Entry> next = new ArrayList<>();
        String rc = home.getSharedPreferences("live", Context.MODE_PRIVATE).getString("recent", "");
        if (!rc.isEmpty()) for (String u : rc.split("\n")) for (LocalTv.Entry e : all) if (e.getChannel().getUrl().equals(u) && !next.contains(e)) { next.add(e); break; }
        for (LocalTv.Entry e : all) { if (next.size() >= 6) break; if (!next.contains(e)) next.add(e); }
        if (next.size() > 6) next.subList(6, next.size()).clear();
        boolean same = next.equals(entries);
        if (same && !cardViews.isEmpty()) { refreshChips(); return; }
        boolean hadFocus = cards.hasFocus();
        entries.clear(); entries.addAll(next);
        cards.removeAllViews(); cardViews.clear(); snapBinders.clear();
        Instant now = Instant.now();
        for (int i = 0; i < entries.size(); i++) {
            View card = card(entries.get(i), now, i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) lp.setMarginStart(px(24));
            cards.addView(card, lp); cardViews.add(card);
        }
        refreshChips();
        shown = -1;
        if (hadFocus || home.mediaOn()) focusFirst();
        else showChannel(0, false);
    }

    /** Back to the card that was showing (after the launchpad or a sheet closed), else the first one. */
    void focusLast() { if (shown >= 0 && shown < cardViews.size()) cardViews.get(shown).requestFocus(); else focusFirst(); }

    void focusFirst() { if (!cardViews.isEmpty()) cardViews.get(0).requestFocus(); }

    void refreshChips() {
    }

    private Epg.Programme onAir(LocalTv.Entry e, Instant now) {
        if (Live.epg == null) return null;
        List<Epg.Programme> s = Live.epg.schedule(e.getName(), now, e.getChannel().getTvgId());
        return s != null && !s.isEmpty() && !s.get(0).getStart().isAfter(now) ? s.get(0) : null;
    }

    // ------------------------------------------------------------------ views

    private View card(LocalTv.Entry e, Instant now, int index) {
        Context c = home;
        Epg.Programme p = onAir(e, now);
        LinearLayout col = new LinearLayout(c); col.setOrientation(LinearLayout.VERTICAL);
        col.setFocusable(true); col.setClickable(true); col.setClipChildren(false);
        col.setAlpha(0.72f);
        FrameLayout thumb = new FrameLayout(c);
        int bg = Tile.blend(0xFF1B2440, 0xFF000000 | (e.getName().hashCode() & 0x00FFFFFF), 0.35f);
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{bg, Tile.blend(bg, 0xFF0A0B10, 0.7f)});
        gd.setCornerRadius(px(20));
        thumb.setBackground(gd);
        Ui.clip(thumb, px(20));
        ImageView snap = new ImageView(c); snap.setScaleType(ImageView.ScaleType.CENTER_CROP); // a real frame of the channel, when one was captured
        thumb.addView(snap, new LayoutParams(-1, -1));
        ImageView logo = new ImageView(c); logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        TextView fb = tv("", 40, 700, 0x66FFFFFF); fb.setGravity(Gravity.CENTER);
        thumb.addView(fb, new LayoutParams(-1, -1));
        LayoutParams ll = new LayoutParams(px(150), px(90), Gravity.CENTER_HORIZONTAL); ll.topMargin = px(26);
        thumb.addView(logo, ll);
        Ui.bindLogo(e.getChannel().getLogo(), logo, fb, e.getName());
        final String key = e.getChannel().getUrl();
        Runnable bindSnap = () -> {
            java.io.File f = LivePreview.snapshot(home, key);
            android.graphics.Bitmap bm = f == null ? null : android.graphics.BitmapFactory.decodeFile(f.getPath());
            snap.setImageBitmap(bm);
            logo.setVisibility(bm == null ? View.VISIBLE : View.GONE);
            if (bm != null) fb.setVisibility(View.INVISIBLE);
        };
        snapBinders.put(key, bindSnap);
        bindSnap.run();
        View shade = new View(c);
        shade.setBackground(grad(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0x00000000, 0xB3000000}, new float[]{0f, 0.4f, 1f}));
        thumb.addView(shade, new LayoutParams(-1, -1));
        TextView nm = tv(e.getName(), 30, 600, Color.WHITE); nm.setMaxWidth(px(320));
        LayoutParams nl = new LayoutParams(-2, -2); nl.leftMargin = px(20); nl.topMargin = px(146);
        thumb.addView(nm, nl);
        if (p != null) {
            long total = Math.max(1, p.getStop().getEpochSecond() - p.getStart().getEpochSecond());
            float f = Math.min(1f, Math.max(0f, (now.getEpochSecond() - p.getStart().getEpochSecond()) / (float) total));
            View track = new View(c); track.setBackground(pill(0x33FFFFFF, 0, 0));
            LayoutParams tp = new LayoutParams(px(320), px(4)); tp.leftMargin = px(20); tp.topMargin = px(184);
            thumb.addView(track, tp);
            View fill = new View(c); fill.setBackground(pill(0xFF5CD0FF, 0, 0));
            LayoutParams fp = new LayoutParams(Math.max(px(4), (int) (px(320) * f)), px(4)); fp.leftMargin = px(20); fp.topMargin = px(184);
            thumb.addView(fill, fp);
        }
        // Figma focus: white ring plus a cyan glow (0 16 48 at 55%) around the card
        FrameLayout wrap = new FrameLayout(c); wrap.setClipChildren(false);
        View glow = new View(c);
        GradientDrawable gg = new GradientDrawable(); gg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        gg.setGradientRadius(px(165)); gg.setColors(new int[]{0x665CD1FF, 0x005CD1FF}); // radius = the glow view's half height, so no hard edge
        glow.setBackground(gg); glow.setAlpha(0f);
        LayoutParams glp = new LayoutParams(px(560), px(330)); glp.leftMargin = -px(100); glp.topMargin = -px(64);
        wrap.addView(glow, glp);
        wrap.addView(thumb, new LayoutParams(px(360), px(203)));
        col.addView(wrap, new LinearLayout.LayoutParams(px(360), px(203)));
        String t1 = p != null ? p.getTitle() : OnboardingText.INSTANCE.category(lang, e.getCategory());
        TextView sub1 = tv(t1, 24, 400, Color.WHITE); sub1.setMaxWidth(px(360));
        LinearLayout.LayoutParams s1 = new LinearLayout.LayoutParams(-2, -2); s1.topMargin = px(8);
        col.addView(sub1, s1);
        String t2 = p != null ? I18n.get(lang, "tv.mh_left_min", Math.max(1, (p.getStop().getEpochSecond() - now.getEpochSecond()) / 60)) : I18n.get(lang, "tv.mh_live");
        TextView sub2 = tv(t2, 19, 500, 0x99FFFFFF);
        LinearLayout.LayoutParams s2 = new LinearLayout.LayoutParams(-2, -2); s2.topMargin = px(4);
        col.addView(sub2, s2);
        col.setOnFocusChangeListener((v, f) -> {
            col.setAlpha(f ? 1f : 0.72f);
            GradientDrawable ring = new GradientDrawable(); ring.setCornerRadius(px(20)); ring.setColor(Color.TRANSPARENT);
            if (f) ring.setStroke(px(6), Color.WHITE);
            thumb.setForeground(f ? ring : null);
            glow.animate().alpha(f ? 1f : 0f).setDuration(Ui.dur(home, Ui.FAST)).start();
            if (f) { showChannel(index, true); scroller.post(() -> scroller.smoothScrollTo(Math.max(0, col.getLeft() - px(10)), 0)); }
        });
        col.setOnClickListener(v -> openChannel(e));
        return col;
    }

    private void showChannel(int i, boolean preview) {
        if (i < 0 || i >= entries.size() || i == shown) return;
        shown = i;
        LocalTv.Entry e = entries.get(i);
        Instant now = Instant.now();
        Epg.Programme p = onAir(e, now);
        eyebrow.setText(I18n.get(lang, "tv.mh_now_playing", e.getName()).toUpperCase(Locale.ROOT));
        title.setText(p != null ? p.getTitle() : e.getName());
        String d = p != null && p.getDesc() != null && !p.getDesc().isEmpty() ? p.getDesc() : OnboardingText.INSTANCE.category(lang, e.getCategory());
        desc.setText(d);
        hint.animate().alpha(1f).setDuration(Ui.dur(home, Ui.MED)).start();
        if (!preview) return;
        List<M3u.Channel> raw = new ArrayList<>(); raw.add(e.getChannel()); raw.addAll(e.getAlternates());
        List<M3u.Channel> ok = new ArrayList<>();
        for (M3u.Channel ch : tech.kousozo.tahoetv.core.SourceHealth.order(raw))
            if (!ch.getDrm() && !tech.kousozo.tahoetv.core.Drm.isDash(ch.getUrl(), ch.getDrmType(), ch.getManifestType())) ok.add(ch);
        if (!ok.isEmpty() && home.sheet == null && home.profiles == null) home.hero.previewChannels(ok); else home.hero.stopPreview();
    }

    private void watchCurrent() { if (shown >= 0 && shown < entries.size()) openChannel(entries.get(shown)); else home.start(new Intent(home, LiveTv.class)); }

    private void openChannel(LocalTv.Entry e) {
        home.hero.stopPreview();
        home.start(new Intent(home, LiveTv.class).putExtra("url", e.getChannel().getUrl()));
    }

    private void addTab(LinearLayout nav, String label, boolean active, Runnable action) {
        TextView t = tv(label, 19, 500, active ? 0xFF0D0D12 : 0xBFFFFFFF);
        t.setPadding(px(20), px(13), px(20), px(13));
        t.setBackground(pill(active ? 0xFFFFFFFF : 0, 0, 0));
        t.setFocusable(true); t.setClickable(true);
        t.setOnFocusChangeListener((v, f) -> {
            GradientDrawable g = pill(active ? 0xFFFFFFFF : f ? 0x33FFFFFF : 0, f ? 0xFFFFFFFF : 0, f ? px(3) : 0);
            t.setBackground(g);
            t.setTextColor(active ? 0xFF0D0D12 : f ? Color.WHITE : 0xBFFFFFFF);
        });
        if (action != null) t.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); if (nav.getChildCount() > 0) lp.setMarginStart(px(6));
        nav.addView(t, lp);
    }

    private TextView chip(LinearLayout parent, int icon, String text, Runnable action) {
        LinearLayout c = new LinearLayout(home); c.setGravity(Gravity.CENTER_VERTICAL);
        final int rest = text == null ? 0x1AFFFFFF : 0; // info chips sit flat, only the round buttons have a fill
        c.setPadding(px(text == null ? 15 : 14), px(15), px(text == null ? 15 : 14), px(15));
        c.setBackground(pill(rest, 0, 0));
        c.setFocusable(true); c.setClickable(true);
        c.setOnClickListener(v -> action.run());
        c.setOnFocusChangeListener((v, f) -> c.setBackground(pill(f ? 0x40FFFFFF : rest, f ? 0xFFFFFFFF : 0, f ? px(3) : 0)));
        ImageView i = Ui.icon(home, icon, 20, Color.WHITE);
        c.addView(i, new LinearLayout.LayoutParams(px(26), px(26)));
        TextView t = null;
        if (text != null) { t = tv(text, 22, 500, 0xE6FFFFFF); t.setPadding(px(8), 0, 0, 0); c.addView(t); }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); if (parent.getChildCount() > 0) lp.setMarginStart(px(10));
        parent.addView(c, lp);
        return t == null ? new TextView(home) : t;
    }

    private TextView button(String label, boolean primary, int icon, Runnable action) {
        TextView b = tv(label, primary ? 25.3f : 24.2f, 500, primary ? 0xFF0D0F14 : 0xFFF7F8FA);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(px(primary ? 32 : 30), px(primary ? 19 : 18), px(primary ? 38 : 36), px(primary ? 19 : 18));
        if (icon != 0) {
            android.graphics.drawable.Drawable d = getResources().getDrawable(icon, null).mutate();
            d.setTint(0xFF0D0F14); d.setBounds(0, 0, px(28), px(28));
            b.setCompoundDrawables(d, null, null, null); b.setCompoundDrawablePadding(px(16));
        }
        b.setBackground(pill(primary ? 0xFFF7F8FA : 0x17FFFFFF, primary ? 0 : 0x33FFFFFF, primary ? 0 : px(2)));
        b.setFocusable(true); b.setClickable(true);
        b.setOnFocusChangeListener((v, f) -> {
            b.setBackground(pill(primary ? 0xFFF7F8FA : f ? 0x40FFFFFF : 0x17FFFFFF, f ? 0xFF63D1FF : primary ? 0 : 0x33FFFFFF, f ? px(4) : primary ? 0 : px(2)));
        });
        b.setOnClickListener(v -> action.run());
        return b;
    }

    // ------------------------------------------------------------------ clock

    private final Runnable clockTick = this::tickClock;

    private void tickClock() {
        SimpleDateFormat f = new SimpleDateFormat("HH.mm", Locale.US);
        f.setTimeZone(Prayer.tz(home));
        clock.setText(f.format(new Date()));
        refreshChips();
        ui.removeCallbacks(clockTick);
        ui.postDelayed(clockTick, 20_000);
    }

    // ------------------------------------------------------------------ greeting (typewriter, replaces the old "Tahoe TV" label)

    private String[] greetLines = new String[0];
    private int greetLine, greetChar;
    private final Runnable greetTick = this::greetStep;

    private void startGreeting() {
        java.util.Calendar cal = java.util.Calendar.getInstance(Prayer.tz(home));
        int h = cal.get(java.util.Calendar.HOUR_OF_DAY);
        String k = h < 11 ? "greet.morning" : h < 15 ? "greet.day" : h < 18 ? "greet.evening" : "greet.night";
        if (h >= 18 && h < 19) k = "greet.evening";
        greetLines = new String[]{I18n.get(lang, k), "Tahoe TV"};
        greetLine = 0; greetChar = 0;
        if (Ui.dur(home, Ui.MED) == 0) { greeting.setText(greetLines[0]); return; } // reduced motion: static
        ui.removeCallbacks(greetTick); ui.post(greetTick);
    }

    private void greetStep() {
        String full = greetLines[greetLine];
        greeting.setText(full.substring(0, greetChar));
        if (greetChar < full.length()) { greetChar++; ui.postDelayed(greetTick, 75); return; }
        // fully typed: hold, fade out, start the next line
        greetLine = (greetLine + 1) % greetLines.length; greetChar = 0;
        ui.postDelayed(() -> greeting.animate().alpha(0f).setDuration(260).withEndAction(() -> {
            greeting.setAlpha(1f); greeting.setText(""); ui.postDelayed(greetTick, 120); }).start(), 5200);
    }

    @Override protected void onDetachedFromWindow() { ui.removeCallbacks(clockTick); ui.removeCallbacks(greetTick); greeting.animate().cancel(); super.onDetachedFromWindow(); }

    // ------------------------------------------------------------------ helpers

    private int px(float v) { return Math.round(v * S); }

    private TextView tv(String s, float sizePx, int wght, int color) {
        TextView t = new TextView(home);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx * S * (sizePx >= 40 ? 1.08f : 1.15f)); // a little larger than the design: this is read from the sofa
        t.setTypeface(Ui.font(home, wght)); t.setTextColor(color); t.setText(s);
        t.setSingleLine(); t.setEllipsize(android.text.TextUtils.TruncateAt.END); t.setIncludeFontPadding(false);
        return t;
    }

    private static GradientDrawable grad(GradientDrawable.Orientation o, int[] colors, float[] stops) {
        GradientDrawable g = new GradientDrawable(o, colors);
        if (android.os.Build.VERSION.SDK_INT >= 29) g.setColors(colors, stops);
        return g;
    }

    private GradientDrawable pill(int fill, int stroke, int strokePx) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(999)); g.setColor(fill);
        if (strokePx > 0) g.setStroke(strokePx, stroke);
        return g;
    }
}
