package tv.clean.home;

import android.content.Context;
import android.content.Intent;
import android.graphics.*;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.util.*;

/** Control Center, app menu, phone pairing and smart-home sheets. All are focus-driven grids of glass tiles. */
final class Sheets {
    interface Adjust { void by(int dir); }

    static final class Item {
        int icon; String title, sub; Runnable enter; Adjust adjust; boolean wide; float level = -1; boolean on;
        Item(int icon, String title, String sub, Runnable enter) { this.icon = icon; this.title = title; this.sub = sub; this.enter = enter; }
        Item wide() { wide = true; return this; }
        Item adjust(Adjust a) { adjust = a; return this; }
        Item level(float l) { level = l; return this; }
        Item on(boolean b) { on = b; return this; }
    }

    /** Glass panel with rows of items (a row holds one wide item or up to two normal ones). */
    static abstract class Grid extends FrameLayout implements Sheet {
        final Home home;
        final LinearLayout panel;
        final ScrollView scroll;
        final List<List<Item>> rows = new ArrayList<>();
        final List<List<View>> views = new ArrayList<>();
        int r, c;
        final float d;

        Grid(Home h, int widthDp, int gravity) {
            super(h);
            home = h; d = Ui.dp(h, 1);
            setBackgroundColor(0x80000000);
            panel = new LinearLayout(h); panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding((int) (16 * d), (int) (16 * d), (int) (16 * d), (int) (16 * d));
            setClickable(true);
            setOnClickListener(v -> h.closeSheet());
            // Scrolls when the content is taller than the screen; render() keeps the focused row visible.
            scroll = new ScrollView(h);
            scroll.setClickable(true);
            scroll.setVerticalScrollBarEnabled(false); scroll.setFillViewport(false);
            scroll.setBackground(new GlassDrawable(Ui.dp(h, 34), Ui.GLASS_STRONG, d));
            Ui.clip(scroll, Ui.dp(h, 34));
            scroll.addView(panel, new ScrollView.LayoutParams(-1, -2));
            int responsiveW = (int) Math.min(widthDp * d, Math.max(280 * d, h.W - 32 * d));
            LayoutParams lp = new LayoutParams(responsiveW, -2, gravity);
            lp.topMargin = lp.bottomMargin = lp.rightMargin = lp.leftMargin = (int) (16 * d);
            addView(scroll, lp);
        }

        abstract void fill();

        void header(String eyebrow, String title) {
            TextView e = Ui.eyebrow(getContext(), Ui.TEXT_3); e.setText(eyebrow);
            TextView t = Ui.text(getContext(), Ui.HEADLINE, 600, Ui.TEXT); t.setText(title);
            e.setPadding((int) (6 * d), 0, 0, 0); t.setPadding((int) (6 * d), (int) (4 * d), 0, (int) (12 * d));
            panel.addView(e); panel.addView(t);
        }

        void add(Item... items) { rows.add(Arrays.asList(items)); }

        void rebuild() {
            int keepR = r, keepC = c;
            panel.removeAllViews(); rows.clear(); views.clear();
            fill();
            for (int rIdx = 0; rIdx < rows.size(); rIdx++) {
                List<Item> row = rows.get(rIdx);
                LinearLayout line = new LinearLayout(getContext());
                List<View> vs = new ArrayList<>();
                for (int i = 0; i < row.size(); i++) {
                    final Item it = row.get(i);
                    final int rr = rIdx, cc = i;
                    View v = tile(it);
                    v.setClickable(true);
                    v.setFocusable(false);
                    v.setFocusableInTouchMode(false);
                    v.setOnClickListener(clicked -> {
                        r = rr; c = cc;
                        render();
                        if (it.enter != null) it.enter.run();
                        else if (it.adjust != null) { it.adjust.by(1); rebuild(); }
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                    if (i > 0) lp.leftMargin = (int) (10 * d);
                    line.addView(v, lp); vs.add(v);
                }
                LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2); ll.topMargin = (int) (8 * d);
                panel.addView(line, ll);
                views.add(vs);
            }
            if (rows.isEmpty()) { r = 0; c = 0; return; }
            r = Math.min(keepR, rows.size() - 1); c = Math.min(keepC, rows.get(r).size() - 1);
            render();
        }

        View tile(Item it) {
            Context ctx = getContext();
            boolean simple = TvViewer.simpleMode(ctx);
            LinearLayout t = new LinearLayout(ctx); t.setOrientation(LinearLayout.VERTICAL);
            t.setPadding((int) (14 * d), (int) (11 * d), (int) (14 * d), (int) (11 * d));
            t.setContentDescription(it.title + (it.sub != null && !it.sub.isEmpty() ? ", " + it.sub : ""));
            LinearLayout top = new LinearLayout(ctx); top.setGravity(Gravity.CENTER_VERTICAL);
            ImageView g = Ui.icon(ctx, it.icon, simple ? 24 : 20, Ui.TEXT); g.setTag("g");
            TextView ti = Ui.text(ctx, simple ? 18 : 15, 600, Ui.TEXT); ti.setText(it.title); ti.setTag("t");
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1); tl.leftMargin = (int) (12 * d);
            top.addView(g); top.addView(ti, tl);
            t.addView(top);
            if (it.sub != null && !it.sub.isEmpty()) {
                TextView s = Ui.text(ctx, simple ? 14.5f : 12.5f, 400, Ui.TEXT_2); s.setText(it.sub); s.setTag("s");
                s.setPadding((int) (32 * d), (int) (2 * d), 0, 0);
                t.addView(s);
            }
            if (it.level >= 0) {
                Slider sl = new Slider(ctx, it.level); sl.setTag("sl");
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, (int) (10 * d)); sp.topMargin = (int) (10 * d);
                t.addView(sl, sp);
            }
            t.setBackground(new GlassDrawable(Ui.dp(ctx, 20), it.on ? 0x40FFFFFF : 0x1FFFFFFF, d));
            return t;
        }

        void render() {
            if (views.isEmpty() || r < 0 || r >= views.size() || c < 0 || c >= views.get(r).size()) return;
            for (int i = 0; i < views.size(); i++) for (int j = 0; j < views.get(i).size(); j++) {
                boolean f = i == r && j == c;
                View v = views.get(i).get(j);
                Item it = rows.get(i).get(j);
                ((GlassDrawable) v.getBackground()).fill(f ? 0xF2F5F5F7 : it.on ? 0x40FFFFFF : 0x1FFFFFFF);
                int col = f ? 0xFF111114 : Ui.TEXT;
                ImageView ic = v.findViewWithTag("g");
                if (ic != null) ic.setImageTintList(android.content.res.ColorStateList.valueOf(col));
                for (String tag : new String[]{"t", "s"}) {
                    TextView tv = v.findViewWithTag(tag);
                    if (tv != null) { tv.setTextColor(tag.equals("s") ? (f ? 0xB3111114 : Ui.TEXT_2) : col); tv.setShadowLayer(0, 0, 0, 0); }
                }
                Slider sl = v.findViewWithTag("sl");
                if (sl != null) { sl.dark = f; sl.invalidate(); }
                v.animate().scaleX(f ? 1.03f : 1f).scaleY(f ? 1.03f : 1f).setDuration(Ui.FAST).start();
            }
            View line = (View) views.get(r).get(c).getParent();
            scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, line.getTop() - (int) (60 * d))));
        }

        @Override public boolean key(KeyEvent e) {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
            if (rows.isEmpty()) return false;
            Item it = rows.get(r).get(c);
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP: if (r > 0) { r--; c = Math.min(c, rows.get(r).size() - 1); render(); } return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: if (r < rows.size() - 1) { r++; c = Math.min(c, rows.get(r).size() - 1); render(); } return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: {
                    int dir = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                    if (it.adjust != null) { it.adjust.by(dir); rebuild(); }
                    else if (c + dir >= 0 && c + dir < rows.get(r).size()) { c += dir; render(); }
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                    if (e.getRepeatCount() == 0) {
                        if (it.enter != null) it.enter.run();
                        else if (it.adjust != null) { it.adjust.by(1); rebuild(); }
                    }
                    return true;
            }
            return false;
        }

        @Override public void onClose() {}
    }

    /** Thin capsule slider for volume. */
    static final class Slider extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); final float level; boolean dark;
        Slider(Context c, float level) { super(c); this.level = level; }
        @Override protected void onDraw(Canvas c) {
            float h = getHeight(), w = getWidth();
            p.setColor(dark ? 0x26000000 : 0x33FFFFFF); c.drawRoundRect(0, 0, w, h, h / 2, h / 2, p);
            p.setColor(dark ? 0xFF111114 : 0xFFF5F5F7); c.drawRoundRect(0, 0, Math.max(h, w * level), h, h / 2, h / 2, p);
        }
    }

    // ---------------------------------------------------------------- Control Center

    static final class Control extends Grid {
        Control(Home h) { super(h, 440, Gravity.END | Gravity.TOP); rebuild(); }

        @Override void fill() {
            Home h = home;
            String name = Cfg.s(h, "user_name", R.string.user_name);
            LinearLayout who = new LinearLayout(h); who.setGravity(Gravity.CENTER_VERTICAL);
            who.setPadding((int) (6 * d), 0, 0, (int) (8 * d));
            who.addView(Avatar.view(h, 44, !h.guest() && !h.kids()));
            final String lang = TvViewer.get(h).getLanguage();
            LinearLayout names = new LinearLayout(h); names.setOrientation(LinearLayout.VERTICAL); names.setPadding((int) (14 * d), 0, 0, 0);
            TextView e = Ui.eyebrow(h, Ui.TEXT_3); e.setText(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.control_center"));
            TextView t = Ui.text(h, Ui.HEADLINE, 600, Ui.TEXT); t.setText(h.kids() ? tech.kousozo.tahoetv.core.I18n.get(lang, "tv.lock_kids") : h.guest() ? tech.kousozo.tahoetv.core.I18n.get(lang, "tv.lock_guest") : name.isEmpty() ? "Tahoe TV Launcher" : name);
            names.addView(e); names.addView(t); who.addView(names);
            panel.addView(who);
            Item switchProfile = new Item(R.drawable.ic_users, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_switch_profile"),
                    tech.kousozo.tahoetv.core.I18n.get(lang, h.kids() ? "tv.cc_need_pin" : "tv.cc_profiles_sub"), () -> { h.closeSheet(); h.showProfiles(); });
            AudioManager am = (AudioManager) h.getSystemService(Context.AUDIO_SERVICE);
            int v = am.getStreamVolume(AudioManager.STREAM_MUSIC), max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            add(new Item(R.drawable.ic_volume_2, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_volume"), Math.round(100f * v / max) + "%   ◀ ▶", null).wide().level((float) v / max)
                    .adjust(dir -> am.adjustSuggestedStreamVolume(dir > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.STREAM_MUSIC, 0)));
            if (h.phoneMedia != null) {
                boolean playing = h.phoneMedia.optBoolean("playing");
                add(new Item(playing ? R.drawable.ic_pause : R.drawable.ic_play, h.phoneMedia.optString("title"),
                        tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_on_phone", h.phoneMedia.optString("artist")), () -> {
                    h.phoneMediaCommand(playing ? "pause" : "play");
                }).wide().on(playing).adjust(dir -> h.phoneMediaCommand(dir > 0 ? "next" : "prev")));
            }
            if (h.kids()) { // no settings, pairing, smart home or assistant: just the basics
                int left = h.sleepLeft();
                add(new Item(R.drawable.ic_moon, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_sleep_timer"),
                        (left == 0 ? tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_off") : tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_min_left", String.valueOf(left))) + "  ·  ◀ ▶",
                        () -> h.stepSleep(1)).adjust(h::stepSleep),
                    new Item(R.drawable.ic_cloud_sun, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.home_chip_weather"),
                        tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_hourly_daily"), () -> { h.closeSheet(); h.openWeather(); }));
                add(switchProfile.wide());
                return;
            }
            add(new Item(R.drawable.ic_wifi, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_wifi"), ssid(h), () -> h.start(new Intent(Settings.ACTION_WIFI_SETTINGS))),
                new Item(R.drawable.ic_bluetooth, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_bluetooth"),
                        tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_bluetooth_sub"), () -> h.start(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))));
            boolean personal = Hero.wallMode(h).equals("personal");
            add(new Item(R.drawable.ic_image, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_wallpaper"),
                    h.guest() ? tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_default_guest")
                            : tech.kousozo.tahoetv.core.I18n.get(lang, personal ? "tv.cc_personal" : "tv.cc_default") + "  ·  ◀ ▶",
                    () -> h.hero.nextWall())
                    .adjust(dir -> {
                        if (h.guest()) return;
                        h.prefs.edit().putString("wall_mode", Hero.wallMode(h).equals("personal") ? "default" : "personal").apply();
                        h.hero.loadWalls(); h.hero.showWall(true);
                    }),
                new Item(R.drawable.ic_cloud_sun, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.home_chip_weather"),
                        tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_hourly_daily"), () -> { h.closeSheet(); h.openWeather(); }));
            add(new Item(R.drawable.ic_moon_star, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_prayer"),
                    tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_prayer_sub"), () -> { h.closeSheet(); h.openPrayer(); }),
                new Item(R.drawable.ic_sparkles, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_assistant"),
                        Assistant.enabled() ? "Gemini" : tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_unset"), () -> { h.closeSheet(); h.openAssistant(); }));
            add(new Item(R.drawable.ic_house, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_smart_home"),
                    HomeControl.enabled() ? "Home Assistant" : "Hey Google", () -> { h.closeSheet(); h.openSheet(new Devices(h)); }),
                new Item(R.drawable.ic_smartphone, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_phone_lbl"),
                        h.companion == null || h.companion.pairedCount() == 0
                                ? tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_pairing")
                                : tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_phones_cnt", String.valueOf(h.companion.pairedCount())),
                        () -> { h.closeSheet(); h.openPair(); }),
                new Item(R.drawable.ic_settings, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_settings"),
                        tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_system"), () -> h.start(new Intent(Settings.ACTION_SETTINGS))));
            // Live TV: highest quality and distance from live (◀ ▶); stored where LiveTv reads them
            android.content.SharedPreferences lp = h.getSharedPreferences("live", Context.MODE_PRIVATE);
            tech.kousozo.tahoetv.core.PlayPrefs.Quality q = tech.kousozo.tahoetv.core.PlayPrefs.Quality.of(lp.getString("quality", null));
            tech.kousozo.tahoetv.core.PlayPrefs.Latency lat = tech.kousozo.tahoetv.core.PlayPrefs.Latency.of(lp.getString("latency", null));
            add(new Item(R.drawable.ic_tv, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_video_quality"), q.label(lang) + "  ·  ◀ ▶", null)
                    .adjust(dir -> lp.edit().putString("quality", tech.kousozo.tahoetv.core.PlayPrefs.next(q, dir).getKey()).apply()),
                new Item(R.drawable.ic_clock, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_stream_latency"), lat.label(lang) + "  ·  ◀ ▶", null)
                    .adjust(dir -> lp.edit().putString("latency", tech.kousozo.tahoetv.core.PlayPrefs.next(lat, dir).getKey()).apply()));
            tech.kousozo.tahoetv.core.PlayPrefs.SubtitleSize subSz = tech.kousozo.tahoetv.core.PlayPrefs.SubtitleSize.of(lp.getString("sub_size", null));
            tech.kousozo.tahoetv.core.DeviceProfile.Profile devProf = DeviceProfiler.current(h);
            add(new Item(R.drawable.ic_tv, tech.kousozo.tahoetv.core.I18n.get(lang, "sub.size_label"), subSz.label(lang) + "  ·  ◀ ▶", null)
                    .adjust(dir -> lp.edit().putString("sub_size", tech.kousozo.tahoetv.core.PlayPrefs.SubtitleSize.next(subSz, dir).getKey()).apply()),
                new Item(R.drawable.ic_info, tech.kousozo.tahoetv.core.I18n.get(lang, "device.title"),
                    (devProf.getManualOverride() == null
                            ? tech.kousozo.tahoetv.core.I18n.get(lang, "device.tier_auto", devProf.getActiveTier().label(lang))
                            : devProf.getActiveTier().label(lang)) + " · " + devProf.getSpecs().getRamMb() + " MB",
                    () -> { h.closeSheet(); h.openSheet(new DeviceInfo(h)); }));
            int left = h.sleepLeft();
            add(new Item(R.drawable.ic_moon, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_sleep_timer"),
                    (left == 0 ? tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_off") : tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_min_left", String.valueOf(left))) + "  ·  ◀ ▶",
                    () -> h.stepSleep(1)).adjust(h::stepSleep),
                new Item(R.drawable.ic_info, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_about"),
                        tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_about_sub", About.version(h)), () -> { h.closeSheet(); h.openSheet(new About(h)); }));
            tech.kousozo.tahoetv.core.Viewer vw = TvViewer.get(h);
            add(new Item(R.drawable.ic_settings, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.region_language"), vw.getDone()
                    ? tech.kousozo.tahoetv.core.Region.flag(vw.getCountry()) + " " + tech.kousozo.tahoetv.core.OnboardingText.INSTANCE.getLANGUAGE_NAMES().get(vw.getLanguage())
                    : tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_region_sub"), () -> { h.closeSheet(); h.startActivity(new android.content.Intent(h, Onboarding.class)); }),
                new Item(R.drawable.ic_smartphone, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_send_log"),
                        tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_send_log_sub"), () -> { h.closeSheet(); h.sendLog(); }));
            tech.kousozo.tahoetv.core.WatchRecap.Summary recap = tech.kousozo.tahoetv.core.WatchRecap.summarize(
                    new java.io.File(h.getFilesDir(), "recap"),
                    h.prefs.getString("profile", "main"),
                    tech.kousozo.tahoetv.core.WatchRecap.currentMonth(),
                    lang);
            add(new Item(R.drawable.ic_tv, tech.kousozo.tahoetv.core.I18n.get(lang, "recap.title"),
                    recap.getTotalHoursDecimal(), () -> { h.closeSheet(); h.openSheet(new Recap(h)); }).wide());
            if (h.guest()) add(switchProfile.wide());
            else add(switchProfile, new Item(R.drawable.ic_lock, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_kids_profile"),
                    Kids.hasPin(h) ? tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_kids_sub") : tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_unset"),
                    () -> { h.closeSheet(); h.openSheet(new KidsSetup(h)); }));
            boolean simple = TvViewer.simpleMode(h);
            add(new Item(R.drawable.ic_layout_grid, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_simple_mode"),
                    tech.kousozo.tahoetv.core.I18n.get(lang, simple ? "tv.cc_simple_on" : "tv.cc_simple_off"), () -> {
                TvViewer.setSimpleMode(h, !simple);
                h.applySimpleMode();
                rebuild();
            }).wide().on(simple));
        }

        static String ssid(Context c) {
            String s = ((WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE)).getConnectionInfo().getSSID();
            return s == null || s.contains("unknown ssid") || s.equals("0x")
                    ? tech.kousozo.tahoetv.core.I18n.get(TvViewer.get(c).getLanguage(), "tv.cc_wifi_off")
                    : s.replace("\"", "");
        }
    }

    // ---------------------------------------------------------------- App menu (hold OK)

    static final class AppMenu extends Grid {
        final Home.App app;

        AppMenu(Home h, Home.App app, Tile anchor) {
            super(h, 340, Gravity.CENTER);
            this.app = app;
            setBackgroundColor(0x66000000);
            rebuild();
        }

        @Override void fill() {
            Home h = home;
            String lang = TvViewer.get(h).getLanguage();
            header(tech.kousozo.tahoetv.core.I18n.get(lang, app.system ? "tv.home_sys_app" : "tv.app_label"), app.label);
            add(new Item(R.drawable.ic_play, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.open"), null, () -> { h.closeSheet(); h.launch(app.launch, app, h.focusedTile()); }).wide());
            if (h.kids()) return; // no dock editing, info or uninstall in the kids profile
            if (app.pkg.equals(Trending.YT) && !h.trending.isEmpty()) {
                Trending.Video v = h.trending.get(h.trendIndex % h.trending.size());
                add(new Item(R.drawable.ic_tv, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.play_trending"), v.title, () -> { h.closeSheet(); h.start(Trending.watch(v.id)); }).wide());
            }
            List<String> pins = h.dockPackages();
            int at = pins.indexOf(app.pkg);
            if (at >= 0) {
                add(new Item(R.drawable.ic_pin_off, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.unpin_dock"), null, () -> { pins.remove(app.pkg); h.closeSheet(); h.saveDock(pins); }),
                    new Item(R.drawable.ic_arrow_left_right, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.move"), "◀ ▶", null).adjust(dir -> {
                        int i = pins.indexOf(app.pkg), j = i + dir;
                        if (j >= 0 && j < pins.size()) { Collections.swap(pins, i, j); h.saveDock(pins); }
                    }));
            } else if (h.dock.size() < h.dockSlotCap())
                add(new Item(R.drawable.ic_pin, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.pin_dock"), null, () -> { pins.add(app.pkg); h.closeSheet(); h.saveDock(pins); }).wide());
            else add(new Item(R.drawable.ic_pin, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.dock_full"), tech.kousozo.tahoetv.core.I18n.get(lang, "tv.dock_remove_one", String.valueOf(h.dockSlotCap())), null).wide());
            Item info = new Item(R.drawable.ic_info, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.info"), null, () -> { h.closeSheet(); h.start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg))); });
            if (app.system) add(info.wide());
            else add(info, new Item(R.drawable.ic_trash_2, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.uninstall"), null, () -> { h.closeSheet(); h.start(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.pkg))); }));
        }

        @Override public boolean key(KeyEvent e) {
            // still holding OK from the long-press that opened us
            if ((e.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER || e.getKeyCode() == KeyEvent.KEYCODE_ENTER) && e.getRepeatCount() > 0) return true;
            return super.key(e);
        }
    }

    // ---------------------------------------------------------------- Kids profile setup (owner only)

    /** The owner's settings for the "Anak" profile: its PIN and which apps it may open (Live TV shows only kids' and
     *  documentary channels there regardless). */
    static final class KidsSetup extends Grid {
        KidsSetup(Home h) { super(h, 520, Gravity.CENTER); rebuild(); }

        @Override void fill() {
            Home h = home;
            String lang = TvViewer.get(h).getLanguage();
            header(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.kids_title"), tech.kousozo.tahoetv.core.I18n.get(lang, "tv.kids_sub"));
            add(new Item(R.drawable.ic_lock, tech.kousozo.tahoetv.core.I18n.get(lang, Kids.hasPin(h) ? "tv.kids_pin_change" : "tv.kids_pin_create"),
                    tech.kousozo.tahoetv.core.I18n.get(lang, "tv.kids_pin_sub"),
                    () -> h.createPin(() -> { h.openSheet(new KidsSetup(h)); h.toast(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.kids_pin_saved")); }, () -> h.openSheet(new KidsSetup(h)))).wide());
            tech.kousozo.tahoetv.core.MathGate.Mode gm = Kids.gateMode(h);
            tech.kousozo.tahoetv.core.MathGate.Difficulty gd = Kids.mathDifficulty(h);
            add(new Item(R.drawable.ic_lock, tech.kousozo.tahoetv.core.I18n.get(lang, "kids.gate_mode"),
                    gm.label(lang) + "  ·  ◀ ▶", null)
                    .adjust(dir -> Kids.setGateMode(h, tech.kousozo.tahoetv.core.MathGate.Mode.next(gm, dir))),
                new Item(R.drawable.ic_sparkles, tech.kousozo.tahoetv.core.I18n.get(lang, "kids.gate_diff"),
                    gd.label(lang) + "  ·  ◀ ▶", null)
                    .adjust(dir -> Kids.setMathDifficulty(h, tech.kousozo.tahoetv.core.MathGate.Difficulty.next(gd, dir))));
            tech.kousozo.tahoetv.core.KidsPolicy.Config pol = Kids.policy(h);
            int[] limitPresets = {0, 30, 60, 90, 120, 180};
            String limTxt = pol.getDailyLimitMinutes() <= 0
                    ? tech.kousozo.tahoetv.core.I18n.get(lang, "kids.limit_off")
                    : tech.kousozo.tahoetv.core.I18n.get(lang, "kids.limit_fmt", pol.getDailyLimitMinutes());
            String schedTxt = !pol.getScheduleEnabled()
                    ? tech.kousozo.tahoetv.core.I18n.get(lang, "kids.schedule_off")
                    : tech.kousozo.tahoetv.core.KidsPolicy.formatClockMinutes(pol.getAllowedStartMin()) + "–" + tech.kousozo.tahoetv.core.KidsPolicy.formatClockMinutes(pol.getAllowedEndMin());
            add(new Item(R.drawable.ic_clock, tech.kousozo.tahoetv.core.I18n.get(lang, "kids.daily_limit"), limTxt + "  ·  ◀ ▶", null)
                    .adjust(dir -> {
                        int idx = 0;
                        for (int i = 0; i < limitPresets.length; i++) if (limitPresets[i] == pol.getDailyLimitMinutes()) { idx = i; break; }
                        int next = limitPresets[((idx + dir) % limitPresets.length + limitPresets.length) % limitPresets.length];
                        Kids.setPolicy(h, pol.copy(next, pol.getScheduleEnabled(), pol.getAllowedStartMin(), pol.getAllowedEndMin(), pol.getExtraChannelIds(), pol.getBlockedChannelIds()));
                    }),
                new Item(R.drawable.ic_moon, tech.kousozo.tahoetv.core.I18n.get(lang, "kids.schedule_label"), schedTxt + "  ·  ◀ ▶", null)
                    .adjust(dir -> {
                        int[][] w = {{0, 360, 1140}, {1, 360, 1140}, {1, 360, 1200}, {1, 420, 1260}};
                        int cur = 0;
                        if (pol.getScheduleEnabled()) {
                            for (int i = 1; i < w.length; i++) if (w[i][1] == pol.getAllowedStartMin() && w[i][2] == pol.getAllowedEndMin()) { cur = i; break; }
                            if (cur == 0) cur = 2;
                        }
                        int ni = ((cur + dir) % w.length + w.length) % w.length;
                        Kids.setPolicy(h, pol.copy(pol.getDailyLimitMinutes(), w[ni][0] == 1, w[ni][1], w[ni][2], pol.getExtraChannelIds(), pol.getBlockedChannelIds()));
                    }));
            org.json.JSONObject rep = Kids.reportJson(h);
            int watchedMin = rep.optInt("watchedMinutes", 0);
            String repText = pol.getDailyLimitMinutes() > 0
                    ? tech.kousozo.tahoetv.core.I18n.get(lang, "kids.report_today", watchedMin, pol.getDailyLimitMinutes())
                    : tech.kousozo.tahoetv.core.I18n.get(lang, "kids.report_no_limit", watchedMin);
            add(new Item(R.drawable.ic_info, tech.kousozo.tahoetv.core.I18n.get(lang, "kids.report_title"), repText, null).wide());
            List<Item> row = new ArrayList<>();
            for (Home.App a : h.all) {
                boolean on = Kids.appAllowed(h, a.pkg);
                row.add(new Item(on ? R.drawable.ic_play : R.drawable.ic_lock, a.label,
                        tech.kousozo.tahoetv.core.I18n.get(lang, on ? "tv.kids_allowed" : "tv.kids_locked"), () -> {
                    Kids.setApp(h, a.pkg, !Kids.appAllowed(h, a.pkg)); rebuild();
                }).on(on));
                if (row.size() == 2) { add(row.toArray(new Item[0])); row.clear(); }
            }
            if (!row.isEmpty()) add(row.toArray(new Item[0]));
        }
    }

    // ---------------------------------------------------------------- PIN pad (kids profile)

    /** A 4-digit PIN entered with the D-pad (the Mi remote has no number keys) or number keys. [done] gets the PIN once
     *  four digits are in; it calls [fail] to clear and say why. Back runs [cancel]. */
    static final class PinPad extends Grid {
        interface Done { void done(PinPad pad, String pin); }
        final String title; String hint; final Done done; final Runnable cancel;
        String pin = "";

        PinPad(Home h, String title, String hint, Done done, Runnable cancel) {
            super(h, 400, Gravity.CENTER);
            this.title = title; this.hint = hint; this.done = done; this.cancel = cancel;
            setBackgroundColor(0xB3000000);
            rebuild();
        }

        boolean error;
        String backupLabel;
        Runnable backupAction;

        void withBackup(String label, Runnable action) {
            this.backupLabel = label;
            this.backupAction = action;
            rebuild();
        }

        @Override void fill() {
            TextView ti = Ui.text(getContext(), Ui.HEADLINE, 600, Ui.TEXT); ti.setText(title);
            TextView hi = Ui.text(getContext(), 14, 500, error ? 0xFFFF6B6B : Ui.TEXT_2); hi.setText(hint);
            ti.setPadding((int) (6 * d), 0, 0, 0); hi.setPadding((int) (6 * d), (int) (4 * d), 0, (int) (12 * d));
            panel.addView(ti); panel.addView(hi);
            StringBuilder dots = new StringBuilder();
            for (int i = 0; i < 4; i++) dots.append(i < pin.length() ? "●" : "○").append(i < 3 ? "   " : "");
            TextView t = Ui.text(getContext(), 30, 600, Ui.TEXT); t.setText(dots); t.setGravity(Gravity.CENTER);
            t.setPadding(0, 0, 0, (int) (10 * d));
            panel.addView(t, new LinearLayout.LayoutParams(-1, -2));
            add(digit("1"), digit("2"), digit("3"));
            add(digit("4"), digit("5"), digit("6"));
            add(digit("7"), digit("8"), digit("9"));
            String cancelText = tech.kousozo.tahoetv.core.I18n.get(TvViewer.get(getContext()).getLanguage(), "common.cancel");
            add(new Item(0, "⌫", null, this::erase), digit("0"), new Item(0, cancelText, null, cancel));
            if (backupLabel != null && backupAction != null) {
                add(new Item(0, backupLabel, null, backupAction).wide());
            }
        }

        Item digit(String n) { return new Item(0, n, null, () -> type(n)); }

        void type(String n) {
            if (pin.length() >= 4) return;
            pin += n;
            if (pin.length() < 4) { rebuild(); return; }
            String p = pin; pin = ""; rebuild();
            done.done(this, p);
        }

        void erase() { if (!pin.isEmpty()) { pin = pin.substring(0, pin.length() - 1); rebuild(); } }

        /** Wrong or mismatched PIN: clear, say why, shake. */
        void fail(String why) {
            hint = why; error = true; pin = ""; rebuild();
            scroll.animate().translationX(d * 14).setDuration(50).withEndAction(() -> scroll.animate().translationX(-d * 10).setDuration(60)
                    .withEndAction(() -> scroll.animate().translationX(0).setDuration(60).start()).start()).start();
        }

        void status(String msg, boolean err) {
            hint = msg; error = err; pin = ""; rebuild();
        }

        /** Keeps the title, message and dots in view: scroll only as far as the focused key needs (Grid scrolls it to the top). */
        @Override void render() {
            super.render();
            View line = (View) views.get(r).get(c).getParent();
            scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, line.getBottom() + (int) (16 * d) - scroll.getHeight())));
        }

        @Override View tile(Item it) {
            if (it.icon != 0) return super.tile(it);
            TextView t = Ui.text(getContext(), it.title.length() == 1 ? 24 : 15, 600, Ui.TEXT);
            t.setText(it.title); t.setTag("t"); t.setGravity(Gravity.CENTER);
            t.setMinHeight((int) (52 * d));
            t.setPadding(0, (int) (8 * d), 0, (int) (8 * d));
            t.setBackground(new GlassDrawable(Ui.dp(getContext(), 20), 0x1FFFFFFF, d));
            return t;
        }

        @Override public boolean key(KeyEvent e) {
            int k = e.getKeyCode();
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (k == KeyEvent.KEYCODE_BACK) { cancel.run(); return true; }
                if (!isEnabled()) return true;
                if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) { type(String.valueOf(k - KeyEvent.KEYCODE_0)); return true; }
                if (k == KeyEvent.KEYCODE_DEL) { erase(); return true; }
            } else if (!isEnabled()) return true;
            return super.key(e);
        }
    }

    // ---------------------------------------------------------------- Phone pairing

    static final class Pair extends FrameLayout implements Sheet {
        final Home home;
        final ImageView qr;
        final TextView code, url, count;

        Pair(Home h) {
            super(h);
            home = h;
            String lang = TvViewer.get(h).getLanguage();
            float d = Ui.dp(h, 1);
            setBackgroundColor(0xF2080A10);
            Ui.crumb(this, R.drawable.ic_smartphone, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.home_chip_connect_phone"));
            LinearLayout left = new LinearLayout(h); left.setOrientation(LinearLayout.VERTICAL);
            TextView eb = Ui.eyebrow(h, Ui.TEXT_3); eb.setText(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.pair_eb"));
            TextView ti = Ui.text(h, Ui.TITLE, 600, Ui.TEXT); ti.setText(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.pair_scan_title"));
            TextView body = Ui.text(h, Ui.BODY, 400, Ui.TEXT_2);
            body.setSingleLine(false); body.setLineSpacing(0, 1.3f);
            body.setText(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.pair_scan_body"));
            code = Ui.text(h, 54, 300, Ui.TEXT); code.setLetterSpacing(0.25f);
            url = Ui.text(h, Ui.BODY, 500, Ui.ACCENT);
            count = Ui.text(h, Ui.CAPTION + 1, 500, Ui.TEXT_3);
            left.addView(eb); left.addView(ti);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2); bl.topMargin = (int) (14 * d);
            left.addView(body, bl);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2); cl.topMargin = (int) (26 * d);
            left.addView(code, cl); left.addView(url); left.addView(count);
            LayoutParams ll = new LayoutParams((int) (520 * d), -2, Gravity.CENTER_VERTICAL); ll.leftMargin = (int) (64 * d);
            addView(left, ll);
            qr = new ImageView(h);
            qr.setBackground(new GlassDrawable(Ui.dp(h, 28), 0xFFF5F5F7, d));
            qr.setPadding((int) (18 * d), (int) (18 * d), (int) (18 * d), (int) (18 * d));
            LayoutParams ql = new LayoutParams((int) (260 * d), (int) (260 * d), Gravity.END | Gravity.CENTER_VERTICAL); ql.rightMargin = (int) (72 * d);
            addView(qr, ql);
            TextView hint = Ui.text(h, Ui.CAPTION, 500, Ui.TEXT_3); hint.setText(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.pair_hint_keys"));
            LayoutParams hl = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); hl.bottomMargin = (int) (18 * d);
            addView(hint, hl);
            refresh();
        }

        void refresh() {
            Companion c = home.companion;
            if (c == null) { code.setText("—"); return; }
            code.setText(c.code());
            url.setText("http://" + Companion.ip() + ":" + Companion.PORT);
            String lang = TvViewer.get(home).getLanguage();
            count.setText(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_phones_cnt", String.valueOf(c.pairedCount())));
            qr.setImageBitmap(qr(c.pairUrl(), 512));
        }

        static Bitmap qr(String text, int size) {
            try {
                BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
                Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
                int[] px = new int[size * size];
                for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) px[y * size + x] = m.get(x, y) ? 0xFF111114 : 0xFFF5F5F7;
                bm.setPixels(px, 0, size, 0, 0, size, size);
                return bm;
            } catch (Exception e) { return null; }
        }

        @Override public boolean key(KeyEvent e) {
            if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() > 0) return true;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: if (home.companion != null) { home.companion.newCode(); refresh(); } return true;
                case KeyEvent.KEYCODE_MENU: if (home.companion != null) { home.companion.forgetAll(); refresh(); home.toast(tech.kousozo.tahoetv.core.I18n.get(TvViewer.get(home).getLanguage(), "tv.pair_forgot_all")); } return true;
                case KeyEvent.KEYCODE_BACK: return false;
            }
            return true;
        }

        /** The code expires after a while and changes after a lockout: keep what's shown current while open. */
        final Runnable tick = new Runnable() { @Override public void run() {
            Companion c = home.companion;
            if (c != null && !c.code().equals(code.getText().toString())) refresh();
            postDelayed(this, 5000);
        } };
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); postDelayed(tick, 5000); }
        @Override protected void onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow(); }

        @Override public void onClose() {}
    }

    // ---------------------------------------------------------------- Smart home (Home Assistant)

    static final class Devices extends Grid {
        List<HomeControl.Device> list;
        String status = "Memuat…";

        Devices(Home h) {
            super(h, 520, Gravity.CENTER);
            rebuild();
            if (HomeControl.enabled()) Net.bg.execute(() -> {
                try { List<HomeControl.Device> l = HomeControl.devices(); post(() -> { list = l; status = l.isEmpty() ? "Tidak ada perangkat" : null; rebuild(); }); }
                catch (Exception e) { post(() -> { status = "Gagal: " + e.getMessage(); rebuild(); }); }
            });
        }

        @Override void fill() {
            header("Rumah", HomeControl.enabled() ? "Perangkat" : "Kontrol rumah");
            if (!HomeControl.enabled()) {
                add(new Item(R.drawable.ic_mic, "Pakai tombol mic remote", "\"Hey Google, matikan lampu\" — Google Assistant bawaan TV", null).wide());
                add(new Item(R.drawable.ic_house, "Home Assistant", "Isi homeassistant.url & token di secrets.properties", null).wide());
                return;
            }
            if (list == null || list.isEmpty()) { add(new Item(R.drawable.ic_clock, status == null ? "" : status, null, null).wide()); return; }
            for (int i = 0; i < list.size(); i += 2) {
                HomeControl.Device a = list.get(i);
                if (i + 1 < list.size()) add(item(a), item(list.get(i + 1))); else add(item(a).wide());
            }
        }

        Item item(HomeControl.Device dv) {
            int g = dv.domain.equals("light") ? R.drawable.ic_lightbulb : dv.domain.equals("fan") ? R.drawable.ic_fan
                    : dv.domain.equals("climate") ? R.drawable.ic_thermometer : R.drawable.ic_power;
            return new Item(g, dv.name, dv.on ? "Nyala" : "Mati", () -> Net.bg.execute(() -> {
                try { HomeControl.toggle(dv); post(this::rebuild); } catch (Exception e) { post(() -> home.toast("Gagal: " + e.getMessage())); }
            })).on(dv.on);
        }
    }

    // ---------------------------------------------------------------- Device hardware info & profile (#38)

    static final class DeviceInfo extends Grid {
        DeviceInfo(Home h) { super(h, 520, Gravity.CENTER); rebuild(); }

        @Override void fill() {
            Home h = home;
            String lang = TvViewer.get(h).getLanguage();
            tech.kousozo.tahoetv.core.DeviceProfile.Profile prof = DeviceProfiler.current(h);
            tech.kousozo.tahoetv.core.DeviceProfile.Specs sp = prof.getSpecs();
            header(tech.kousozo.tahoetv.core.I18n.get(lang, "device.title"), tech.kousozo.tahoetv.core.I18n.get(lang, "device.sub"));
            String tierVal = (prof.getManualOverride() == null
                    ? tech.kousozo.tahoetv.core.I18n.get(lang, "device.tier_auto", prof.getDetectedTier().label(lang))
                    : prof.getActiveTier().label(lang)) + "  ·  ◀ ▶";
            add(new Item(R.drawable.ic_settings, tech.kousozo.tahoetv.core.I18n.get(lang, "device.tier_label"), tierVal, null)
                    .wide()
                    .adjust(dir -> {
                        tech.kousozo.tahoetv.core.DeviceProfile.Tier next = dir > 0
                                ? tech.kousozo.tahoetv.core.DeviceProfile.cycleOverride(prof.getManualOverride())
                                : tech.kousozo.tahoetv.core.DeviceProfile.cycleOverride(
                                    tech.kousozo.tahoetv.core.DeviceProfile.cycleOverride(
                                        tech.kousozo.tahoetv.core.DeviceProfile.cycleOverride(prof.getManualOverride())));
                        DeviceProfiler.setOverrideTier(h, next);
                    }));
            add(new Item(R.drawable.ic_info, tech.kousozo.tahoetv.core.I18n.get(lang, "device.ram"),
                    sp.getRamMb() + " MB" + (sp.isLowRam() ? " (Low-RAM)" : ""), null),
                new Item(R.drawable.ic_info, tech.kousozo.tahoetv.core.I18n.get(lang, "device.cpu"),
                    sp.getCpuCores() + " cores · " + sp.getAbi() + " · API " + sp.getApiLevel(), null));
            add(new Item(R.drawable.ic_tv, tech.kousozo.tahoetv.core.I18n.get(lang, "device.display"),
                    sp.getWidthPx() + "×" + sp.getHeightPx() + " @ " + sp.getRefreshHz() + " Hz" + (sp.getHdrSupported() ? " · HDR" : ""), null),
                new Item(R.drawable.ic_play, tech.kousozo.tahoetv.core.I18n.get(lang, "device.codecs"),
                    "HEVC:" + (sp.getHwHevc() ? "✓" : "–") + "  AV1:" + (sp.getHwAv1() ? "✓" : "–") + "  AC3:" + (sp.getHwAc3() ? "✓" : "–"), null));
            long bootMs = tech.kousozo.tahoetv.core.DeviceProfile.INSTANCE.getStartupMs();
            add(new Item(R.drawable.ic_clock, tech.kousozo.tahoetv.core.I18n.get(lang, "device.startup_latency"),
                    bootMs >= 0 ? (bootMs + " ms") : "—", null).wide());
        }
    }

    // ---------------------------------------------------------------- Adult Math Gate (#47)

    static final class MathPad extends Grid {
        final String title;
        final tech.kousozo.tahoetv.core.MathGate.Difficulty diff;
        final Runnable onSuccess, cancel;
        tech.kousozo.tahoetv.core.MathGate.Problem problem;
        String input = "";
        String statusMsg;
        boolean error;

        MathPad(Home h, String title, tech.kousozo.tahoetv.core.MathGate.Difficulty diff, Runnable onSuccess, Runnable cancel) {
            super(h, 420, Gravity.CENTER);
            this.title = title;
            this.diff = diff;
            this.onSuccess = onSuccess;
            this.cancel = cancel;
            this.problem = tech.kousozo.tahoetv.core.MathGate.generate(diff, null);
            setBackgroundColor(0xB3000000);
            rebuild();
            if (Kids.pinLockLeftMs(h) > 0) tickLock();
        }

        @Override void fill() {
            String lang = TvViewer.get(getContext()).getLanguage();
            TextView ti = Ui.text(getContext(), Ui.HEADLINE, 600, Ui.TEXT);
            ti.setText(title == null || title.isEmpty() ? tech.kousozo.tahoetv.core.I18n.get(lang, "kids.math_title") : title);
            String hint = statusMsg != null ? statusMsg : tech.kousozo.tahoetv.core.I18n.get(lang, "kids.math_hint", problem.getQuestion());
            TextView hi = Ui.text(getContext(), 15, 600, error ? 0xFFFF6B6B : Ui.TEXT_2);
            hi.setSingleLine(false);
            hi.setText(hint);
            ti.setPadding((int) (6 * d), 0, 0, 0);
            hi.setPadding((int) (6 * d), (int) (4 * d), 0, (int) (10 * d));
            panel.addView(ti);
            panel.addView(hi);

            TextView qv = Ui.text(getContext(), 28, 700, Ui.TEXT);
            qv.setText(problem.getQuestion().replace("?", input.isEmpty() ? "_" : input));
            qv.setGravity(Gravity.CENTER);
            qv.setPadding(0, (int) (4 * d), 0, (int) (12 * d));
            panel.addView(qv, new LinearLayout.LayoutParams(-1, -2));

            add(digit("1"), digit("2"), digit("3"));
            add(digit("4"), digit("5"), digit("6"));
            add(digit("7"), digit("8"), digit("9"));
            String cancelText = tech.kousozo.tahoetv.core.I18n.get(lang, "common.cancel");
            add(new Item(0, "⌫", null, this::erase), digit("0"), new Item(0, tech.kousozo.tahoetv.core.I18n.get(lang, "kids.math_submit"), null, this::submit));
            add(new Item(0, cancelText, null, cancel).wide());
        }

        private Item digit(String n) { return new Item(0, n, null, () -> type(n)); }

        private void type(String n) {
            if (Kids.pinLockLeftMs(home) > 0 || input.length() >= 4) return;
            input += n;
            error = false;
            statusMsg = null;
            rebuild();
        }

        private void erase() {
            if (input.isEmpty()) return;
            input = input.substring(0, input.length() - 1);
            rebuild();
        }

        private void submit() {
            String lang = TvViewer.get(getContext()).getLanguage();
            if (Kids.pinLockLeftMs(home) > 0) { tickLock(); return; }
            if (tech.kousozo.tahoetv.core.MathGate.verify(problem, input)) {
                Kids.clearPinBackoff(home);
                home.closeSheet();
                onSuccess.run();
                return;
            }
            int sec = Kids.recordWrongPin(home);
            input = "";
            error = true;
            problem = tech.kousozo.tahoetv.core.MathGate.generate(diff, problem.getQuestion());
            statusMsg = tech.kousozo.tahoetv.core.I18n.get(lang, "kids.math_wrong") + " (" + sec + "s)";
            rebuild();
            scroll.animate().translationX(d * 14).setDuration(50).withEndAction(() -> scroll.animate().translationX(-d * 10).setDuration(60)
                    .withEndAction(() -> scroll.animate().translationX(0).setDuration(60).start()).start()).start();
            tickLock();
        }

        private void tickLock() {
            long left = Kids.pinLockLeftMs(home);
            String lang = TvViewer.get(getContext()).getLanguage();
            if (left <= 0) {
                setEnabled(true); setAlpha(1f);
                error = false; statusMsg = null; rebuild();
                return;
            }
            setEnabled(false); setAlpha(0.65f);
            long sec = (left + 999) / 1000;
            error = true;
            statusMsg = tech.kousozo.tahoetv.core.I18n.get(lang, "tv.home_pin_locked", String.valueOf(sec));
            rebuild();
            postDelayed(() -> { if (isAttachedToWindow()) tickLock(); }, Math.min(left, 1000L));
        }

        @Override View tile(Item it) {
            if (it.icon != 0) return super.tile(it);
            TextView t = Ui.text(getContext(), it.title.length() <= 2 ? 22 : 15, 600, Ui.TEXT);
            t.setText(it.title); t.setTag("t"); t.setGravity(Gravity.CENTER);
            t.setMinHeight((int) (48 * d));
            t.setPadding(0, (int) (8 * d), 0, (int) (8 * d));
            t.setBackground(new GlassDrawable(Ui.dp(getContext(), 20), 0x1FFFFFFF, d));
            return t;
        }

        @Override public boolean key(KeyEvent e) {
            int k = e.getKeyCode();
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (k == KeyEvent.KEYCODE_BACK) { cancel.run(); return true; }
                if (!isEnabled()) return true;
                if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) { type(String.valueOf(k - KeyEvent.KEYCODE_0)); return true; }
                if (k == KeyEvent.KEYCODE_DEL) { erase(); return true; }
            } else if (!isEnabled()) return true;
            return super.key(e);
        }
    }

    // ---------------------------------------------------------------- Kids time/schedule block (#54)

    static final class KidsBlocked extends Grid {
        final tech.kousozo.tahoetv.core.KidsPolicy.EvalResult eval;

        KidsBlocked(Home h, tech.kousozo.tahoetv.core.KidsPolicy.EvalResult eval) {
            super(h, 460, Gravity.CENTER);
            this.eval = eval;
            setBackgroundColor(0xD905060B);
            rebuild();
        }

        @Override void fill() {
            Home h = home;
            String lang = TvViewer.get(h).getLanguage();
            tech.kousozo.tahoetv.core.KidsPolicy.Config pol = Kids.policy(h);
            String body = eval.getReason() == tech.kousozo.tahoetv.core.KidsPolicy.BlockReason.OUTSIDE_SCHEDULE
                    ? tech.kousozo.tahoetv.core.I18n.get(lang, "kids.outside_hours_body",
                        tech.kousozo.tahoetv.core.KidsPolicy.formatClockMinutes(pol.getAllowedStartMin()),
                        tech.kousozo.tahoetv.core.KidsPolicy.formatClockMinutes(pol.getAllowedEndMin()))
                    : tech.kousozo.tahoetv.core.I18n.get(lang, "kids.time_up_body");
            header(tech.kousozo.tahoetv.core.I18n.get(lang, "kids.time_up_title"), body);
            add(new Item(R.drawable.ic_clock, tech.kousozo.tahoetv.core.I18n.get(lang, "kids.extend_30m"), null, () ->
                    h.verifyAdult(tech.kousozo.tahoetv.core.I18n.get(lang, "kids.extend_30m"), () -> {
                        Kids.grantBonusMinutes(h, 30);
                        h.closeSheet();
                        h.toast("+30 min");
                    })).wide());
            add(new Item(R.drawable.ic_users, tech.kousozo.tahoetv.core.I18n.get(lang, "kids.exit_profile"), null, () -> {
                h.closeSheet();
                h.showProfiles();
            }).wide());
        }

        @Override public boolean key(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_DOWN) return true; // must choose adult unlock or switch profile
            return super.key(e);
        }
    }

    // ---------------------------------------------------------------- Local Monthly Recap (#62)

    static final class Recap extends Grid {
        Recap(Home h) { super(h, 520, Gravity.CENTER); rebuild(); }

        @Override void fill() {
            Home h = home;
            String lang = TvViewer.get(h).getLanguage();
            java.io.File dir = new java.io.File(h.getFilesDir(), "recap");
            String prof = h.prefs.getString("profile", "main");
            tech.kousozo.tahoetv.core.WatchRecap.Summary s = tech.kousozo.tahoetv.core.WatchRecap.summarize(
                    dir, prof, tech.kousozo.tahoetv.core.WatchRecap.currentMonth(), lang);
            header(tech.kousozo.tahoetv.core.I18n.get(lang, "recap.title"), s.getTagline());
            add(new Item(R.drawable.ic_clock, tech.kousozo.tahoetv.core.I18n.get(lang, "recap.total_time"),
                    s.getTotalHoursDecimal() + " · " + tech.kousozo.tahoetv.core.I18n.get(lang, "recap.sessions", s.getSessionCount()), null),
                new Item(R.drawable.ic_sparkles, tech.kousozo.tahoetv.core.I18n.get(lang, "recap.peak_hour"),
                    s.getPeakHour() >= 0 ? String.format(java.util.Locale.US, "%02d:00", s.getPeakHour()) : "—", null));
            for (int i = 0; i < s.getTopChannels().size(); i++) {
                tech.kousozo.tahoetv.core.WatchRecap.RankedItem it = s.getTopChannels().get(i);
                add(new Item(R.drawable.ic_tv, "#" + (i + 1) + "  " + it.getName(), it.getMinutes() + " min", null).wide());
            }
            boolean on = tech.kousozo.tahoetv.core.WatchRecap.isEnabled(dir);
            add(new Item(on ? R.drawable.ic_sparkles : R.drawable.ic_lock, tech.kousozo.tahoetv.core.I18n.get(lang, "recap.enable"),
                    tech.kousozo.tahoetv.core.I18n.get(lang, on ? "tv.cc_simple_on" : "tv.cc_simple_off"), () -> {
                tech.kousozo.tahoetv.core.WatchRecap.setEnabled(dir, !on);
                rebuild();
            }).on(on),
                new Item(R.drawable.ic_trash_2, tech.kousozo.tahoetv.core.I18n.get(lang, "recap.clear"), null, () -> {
                    tech.kousozo.tahoetv.core.WatchRecap.clear(dir);
                    h.toast(tech.kousozo.tahoetv.core.I18n.get(lang, "recap.cleared"));
                    rebuild();
                }));
        }
    }
}
