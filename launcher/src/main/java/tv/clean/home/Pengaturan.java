package tv.clean.home;

import tech.kousozo.tahoetv.core.AccountText;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import tech.kousozo.tahoetv.core.I18n;
import tech.kousozo.tahoetv.core.PlayPrefs;

/** The Figma "TV / Pengaturan" screen: section list on the left, the section's panel on the right.
 *  Native D-pad focus inside the sheet (key() forwards the arrows to focusSearch). Display & text is the full design;
 *  the other sections list the existing screens and switches for that area. */
final class Pengaturan extends FrameLayout implements Sheet {
    private final Home home;
    private final float S;
    private final String lang;
    private final SharedPreferences cfg;
    private final LinearLayout nav, panel;
    private final TextView[] navItems = new TextView[6];
    private int section = 0;

    Pengaturan(Home h) {
        super(h);
        home = h; S = h.W / 1920f; lang = TvViewer.get(h).getLanguage(); cfg = Cfg.p(h);
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF0D1429, 0xFF08080D}));
        setClickable(true);

        panel = new LinearLayout(h); panel.setOrientation(LinearLayout.VERTICAL); // before the nav: its click handlers reach for it
        nav = new LinearLayout(h); nav.setOrientation(LinearLayout.VERTICAL);
        TextView title = tv(I18n.get(lang, "tv.st_title"), 46, 600, 0xFFF7F8FA); title.setLetterSpacing(-0.02f);
        nav.addView(title);
        String[] keys = {"tv.st_display", "tv.st_sound", "tv.st_channels", "tv.st_profiles", "tv.st_network", "tv.st_about"};
        for (int i = 0; i < keys.length; i++) {
            final int idx = i;
            TextView t = tv(I18n.get(lang, keys[i]), 30, 600, 0xCCF7F8FA);
            t.setPadding(px(28), px(22), px(28), px(22));
            t.setFocusable(true); t.setFocusableInTouchMode(true); t.setClickable(true);
            t.setOnFocusChangeListener((v, f) -> { if (f) show(idx); styleNav(); });
            t.setOnClickListener(v -> { if (panel.getChildCount() > 1) panel.getChildAt(1).requestFocus(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = px(i == 0 ? 34 : 10);
            nav.addView(t, lp); navItems[i] = t;
        }
        FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(px(520), -2); nl.leftMargin = px(112); nl.topMargin = px(80);
        addView(nav, nl);

        panel.setPadding(px(44), px(44), px(44), px(44));
        GradientDrawable pb = new GradientDrawable(); pb.setColor(0x0FFFFFFF); pb.setCornerRadius(px(40)); pb.setStroke(Math.max(1, px(1)), 0x1AFFFFFF);
        ScrollView sv = new ScrollView(h); sv.setVerticalScrollBarEnabled(false); sv.setBackground(pb);
        Ui.clip(sv, px(40));
        sv.addView(panel, new ScrollView.LayoutParams(-1, -2));
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(px(1088), px(920)); pl.leftMargin = px(720); pl.topMargin = px(80);
        addView(sv, pl);

        show(0);
        post(() -> navItems[0].requestFocus());
    }

    // ------------------------------------------------------------------ sections

    private void show(int i) {
        if (i == section && panel.getChildCount() > 0) return;
        section = i; fill();
    }

    private void styleNav() {
        for (int i = 0; i < navItems.length; i++) {
            boolean sel = i == section;
            GradientDrawable g = new GradientDrawable(); g.setCornerRadius(px(24));
            g.setColor(sel ? 0x24FFFFFF : Color.TRANSPARENT);
            if (navItems[i].isFocused()) g.setStroke(px(3), 0xFF5CD0FF);
            navItems[i].setBackground(g);
            navItems[i].setTextColor(sel ? 0xFFF7F8FA : 0xCCF7F8FA);
        }
    }

    private void fill() {
        View keep = null;
        panel.removeAllViews();
        String[] keys = {"tv.st_display", "tv.st_sound", "tv.st_channels", "tv.st_profiles", "tv.st_network", "tv.st_about"};
        TextView head = tv(I18n.get(lang, keys[section]), 46, 600, 0xFFF7F8FA); head.setLetterSpacing(-0.02f);
        panel.addView(head);
        switch (section) {
            case 0: display(); break;
            case 1: toggleRow(I18n.get(lang, "tv.click_sound"), I18n.get(lang, "tv.st_click_sub"), "click_sound", false); break;
            case 2:
                linkRow(I18n.get(lang, "ui.channel_languages"), "", () -> { home.closeSheet(); home.openSheet(new Sheets.ChannelLanguages(home)); });
                toggleRow(I18n.get(lang, "tv.live_preview"), I18n.get(lang, "tv.live_preview_sub"), "live_preview", true);
                playbackRows();
                linkRow(I18n.get(lang, "tv.region_language"), "", () -> { home.closeSheet(); home.startActivity(new Intent(home, Onboarding.class)); });
                break;
            case 3:
                accountRow();
                linkRow(I18n.get(lang, "tv.cc_kids_profile"), "", () -> { home.closeSheet(); home.openSheet(new ParentalSheets.KidsSetup(home)); });
                linkRow(I18n.get(lang, "recap.title"), "", () -> { home.closeSheet(); home.openSheet(new ParentalSheets.Recap(home)); });
                break;
            case 4:
                linkRow(I18n.get(lang, "tv.home_chip_connect_phone"), "", () -> { home.closeSheet(); home.openPair(); });
                toggleRow(I18n.get(lang, "tv.st_tunnel"), I18n.get(lang, "tv.st_tunnel_sub"), "@tunnel", true);
                linkRow(I18n.get(lang, "tv.cc_assistant"), Assistant.enabled() ? "Gemini" : I18n.get(lang, "tv.cc_unset"), () -> { home.closeSheet(); home.openAssistant(); });
                linkRow(I18n.get(lang, "tv.cc_smart_home"), HomeControl.enabled() ? "Home Assistant" : "Hey Google", () -> { home.closeSheet(); home.openSheet(new ConnectSheets.Devices(home)); });
                break;
            default:
                linkRow(I18n.get(lang, "tv.cc_about"), About.version(home), () -> { home.closeSheet(); home.openSheet(new About(home)); });
                linkRow(I18n.get(lang, "device.title"), "", () -> { home.closeSheet(); home.openSheet(new ConnectSheets.DeviceInfo(home)); });
                linkRow(I18n.get(lang, "tv.cc_send_log"), I18n.get(lang, "tv.cc_send_log_sub"), () -> { home.closeSheet(); home.sendLog(); });
                linkRow(I18n.get(lang, "tv.st_system"), I18n.get(lang, "tv.st_system_sub"), () -> home.start(new Intent(android.provider.Settings.ACTION_SETTINGS)));
        }
        styleNav();
    }

    private static final java.util.Set<String> SYNCED = new java.util.HashSet<>(java.util.Arrays.asList("clock24", "clock_seconds", "greeting_on", "reduce_motion"));

    private void display() {
        LinearLayout cards = new LinearLayout(home);
        String cur = cfg.getString("text_size", "normal");
        String[] ids = {"normal", "large", "xlarge"}, names = {"tv.text_normal", "tv.text_large", "tv.text_xlarge"};
        float[] aa = {38, 48, 61};
        for (int i = 0; i < 3; i++) {
            final String id = ids[i];
            boolean sel = id.equals(cur);
            LinearLayout c = new LinearLayout(home); c.setOrientation(LinearLayout.VERTICAL); c.setGravity(Gravity.CENTER);
            c.setPadding(px(8), px(28), px(8), px(28));
            TextView a = tv("Aa", aa[i], 600, sel ? 0xFF0D0F14 : 0xFFF7F8FA);
            TextView n = tv(I18n.get(lang, names[i]), 24, 400, sel ? 0xFF0D0F14 : 0xFFF7F8FA);
            LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, -2); c.addView(a, al); LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, -2); nl.topMargin = px(12); c.addView(n, nl);
            c.setBackground(card(sel, false));
            c.setFocusable(true); c.setFocusableInTouchMode(true); c.setClickable(true);
            c.setOnFocusChangeListener((v, f) -> c.setBackground(card(sel, f)));
            c.setOnClickListener(v -> { cfg.edit().putString("text_size", id).apply(); home.closeSheet(); home.recreate(); }); // text scale is read when screens are rebuilt
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, px(190), 1); if (i > 0) lp.setMarginStart(px(16)); // same height for all three
            cards.addView(c, lp);
        }
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2); cl.topMargin = px(20); panel.addView(cards, cl);
        // simple mode lives in the viewer settings, the rest in the shared prefs
        toggleRow(I18n.get(lang, "tv.cc_simple_mode"), I18n.get(lang, "tv.st_simple_sub"), null, false);
        toggleRow(I18n.get(lang, "ui.high_contrast"), I18n.get(lang, "tv.st_contrast_sub"), "high_contrast", false);
        toggleRow(I18n.get(lang, "ui.reduce_motion"), I18n.get(lang, "tv.reduce_motion_sub"), "reduce_motion", false);
        toggleRow(I18n.get(lang, "tv.click_sound"), I18n.get(lang, "tv.st_click_sub"), "click_sound", false);
        toggleRow(AccountText.get(lang, "clock24"), "", "clock24", true);
        toggleRow(AccountText.get(lang, "seconds"), "", "clock_seconds", false);
        toggleRow(AccountText.get(lang, "greeting"), "", "greeting_on", true);
        String size = Acct.clockSize(home);
        linkRow(AccountText.get(lang, "clock_size"), AccountText.get(lang, size), () -> {
            cfg.edit().putString("clock_size", size.equals("s") ? "m" : size.equals("m") ? "l" : "s").apply();
            Acct.push(home); home.recreate();
        });
    }

    /** Account status: signed in (OK signs out) or how to sign in. Signing in happens on the phone. */
    private void accountRow() {
        if (Acct.linked(home)) linkRow(AccountText.get(lang, "title"), AccountText.get(lang, "signed_as", Acct.email(home)) + " · " + AccountText.get(lang, "signout"), () -> { Acct.unlink(home, true); refill(); });
        else linkRow(AccountText.get(lang, "title"), AccountText.get(lang, "to_tv"), () -> { home.closeSheet(); home.openPair(); });
    }

    private GradientDrawable card(boolean selected, boolean focused) {
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(px(28));
        g.setColor(selected ? 0xFFFFFFFF : 0x14FFFFFF);
        if (focused) g.setStroke(px(5), selected ? 0xFF5CD0FF : 0xFFFFFFFF);
        return g;
    }

    /** One row: title + caption on the left, a pill switch on the right. [key] null = simple mode. */
    private void toggleRow(String title, String sub, String key, boolean dflt) {
        final boolean tunnel = "@tunnel".equals(key); // lives in the companion's own prefs, not the launcher's
        boolean on = key == null ? TvViewer.simpleMode(home) : tunnel ? (home.companion == null || home.companion.tunnelOn()) : "clock24".equals(key) ? Acct.clock24(home) : cfg.getBoolean(key, dflt);
        LinearLayout row = rowBase();
        LinearLayout text = new LinearLayout(home); text.setOrientation(LinearLayout.VERTICAL);
        text.addView(tv(title, 30, 600, 0xFFF7F8FA));
        if (!sub.isEmpty()) { LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2); sl.topMargin = px(4); text.addView(tv(sub, 21, 500, 0xCCF7F8FA), sl); }
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(sw(on), new LinearLayout.LayoutParams(px(96), px(56)));
        row.setOnClickListener(v -> {
            if (key == null) { TvViewer.setSimpleMode(home, !on); home.applySimpleMode(); }
            else if (tunnel) { if (home.companion != null) home.companion.setTunnelOn(!on); }
            else { cfg.edit().putBoolean(key, !on).apply(); if (SYNCED.contains(key)) { Acct.push(home); if (!"reduce_motion".equals(key)) home.recreate(); } }
            int keepFocus = indexOfFocus();
            fill();
            if (keepFocus >= 0 && keepFocus < panel.getChildCount()) panel.getChildAt(keepFocus).requestFocus();
        });
    }

    /** Video quality, stream latency and subtitle size: a row that steps to the next value on OK and shows the current one. */
    private void playbackRows() {
        SharedPreferences lp = home.getSharedPreferences("live", Context.MODE_PRIVATE);
        PlayPrefs.Quality q = PlayPrefs.Quality.of(lp.getString("quality", null));
        PlayPrefs.Latency lat = PlayPrefs.Latency.of(lp.getString("latency", null));
        PlayPrefs.SubtitleSize sz = PlayPrefs.SubtitleSize.of(lp.getString("sub_size", null));
        linkRow(I18n.get(lang, "tv.cc_video_quality"), q.label(lang), () -> { lp.edit().putString("quality", PlayPrefs.next(q, 1).getKey()).apply(); refill(); });
        linkRow(I18n.get(lang, "tv.cc_stream_latency"), lat.label(lang), () -> { lp.edit().putString("latency", PlayPrefs.next(lat, 1).getKey()).apply(); refill(); });
        linkRow(I18n.get(lang, "sub.size_label"), sz.label(lang), () -> { lp.edit().putString("sub_size", PlayPrefs.SubtitleSize.next(sz, 1).getKey()).apply(); refill(); });
    }

    private void refill() {
        int keepFocus = indexOfFocus();
        fill();
        if (keepFocus >= 0 && keepFocus < panel.getChildCount()) panel.getChildAt(keepFocus).requestFocus();
    }

    private void linkRow(String title, String sub, Runnable go) {
        LinearLayout row = rowBase();
        LinearLayout text = new LinearLayout(home); text.setOrientation(LinearLayout.VERTICAL);
        text.addView(tv(title, 30, 600, 0xFFF7F8FA));
        if (!sub.isEmpty()) { LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2); sl.topMargin = px(4); text.addView(tv(sub, 21, 500, 0xCCF7F8FA), sl); }
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(tv("›", 40, 400, 0xCCF7F8FA));
        row.setOnClickListener(v -> go.run());
    }

    private LinearLayout rowBase() {
        LinearLayout row = new LinearLayout(home); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(28), px(24), px(28), px(24));
        row.setBackground(rowBg(false));
        row.setFocusable(true); row.setFocusableInTouchMode(true); row.setClickable(true);
        row.setOnFocusChangeListener((v, f) -> row.setBackground(rowBg(f)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = px(20);
        panel.addView(row, lp);
        return row;
    }

    private GradientDrawable rowBg(boolean focused) {
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(px(24)); g.setColor(focused ? 0x26FFFFFF : 0x0DFFFFFF);
        if (focused) g.setStroke(px(3), 0xFF5CD0FF);
        return g;
    }

    private View sw(boolean on) {
        FrameLayout f = new FrameLayout(home);
        GradientDrawable track = new GradientDrawable(); track.setCornerRadius(px(28)); track.setColor(on ? 0xFF5CD0FF : 0xFF4A4D57);
        f.setBackground(track);
        View knob = new View(home);
        GradientDrawable kd = new GradientDrawable(); kd.setShape(GradientDrawable.OVAL); kd.setColor(on ? 0xFF0D0F14 : 0xFFE0E0E4);
        knob.setBackground(kd);
        FrameLayout.LayoutParams kl = new FrameLayout.LayoutParams(px(44), px(44), on ? Gravity.END | Gravity.CENTER_VERTICAL : Gravity.START | Gravity.CENTER_VERTICAL);
        kl.setMargins(px(6), 0, px(6), 0);
        f.addView(knob, kl);
        return f;
    }

    private int indexOfFocus() {
        View f = panel.findFocus();
        for (int i = 0; i < panel.getChildCount(); i++) if (panel.getChildAt(i) == f) return i;
        return -1;
    }

    // ------------------------------------------------------------------ Sheet

    @Override public boolean key(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) return false; // Home closes the sheet
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        View f = findFocus();
        if (f == null) { navItems[section].requestFocus(); return true; } // focus was lost (a row rebuilt): take it back
        int dir = code == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : code == KeyEvent.KEYCODE_DPAD_DOWN ? View.FOCUS_DOWN
                : code == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT : code == KeyEvent.KEYCODE_DPAD_RIGHT ? View.FOCUS_RIGHT : 0;
        if (dir != 0 && f != null) { View n = f.focusSearch(dir); if (n != null) n.requestFocus(); return true; }
        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && f != null) { if (e.getRepeatCount() == 0) f.performClick(); return true; }
        return true;
    }

    @Override public void onClose() {}

    // ------------------------------------------------------------------ helpers

    private int px(float v) { return Math.round(v * S); }

    private TextView tv(String s, float sizePx, int wght, int color) {
        TextView t = new TextView(home);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx * S * (sizePx >= 40 ? 1.08f : 1.15f));
        t.setTypeface(Ui.font(home, wght)); t.setTextColor(color); t.setText(s);
        t.setSingleLine(); t.setEllipsize(android.text.TextUtils.TruncateAt.END); t.setIncludeFontPadding(false);
        return t;
    }
}
