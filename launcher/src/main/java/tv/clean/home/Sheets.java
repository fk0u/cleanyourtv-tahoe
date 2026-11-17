package tv.clean.home;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.util.*;
import tech.kousozo.tahoetv.core.DeviceProfile;
import tech.kousozo.tahoetv.core.I18n;
import tech.kousozo.tahoetv.core.PlayPrefs;
import tech.kousozo.tahoetv.core.Viewer;
import tech.kousozo.tahoetv.core.WallpaperProvider;
import tech.kousozo.tahoetv.core.WatchRecap;

/** Core Control Center grid, shared sheet base ([Grid]), slider, and focused-app menu ([AppMenu]). */
final class Sheets {
    private Sheets() {}

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
                    if (i > 0) lp.setMarginStart((int) (10 * d));
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
            TextView ti = Ui.text(ctx, simple ? 19 : 17, 600, Ui.TEXT); ti.setText(it.title); ti.setTag("t");
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1); tl.setMarginStart((int) (12 * d));
            top.addView(g); top.addView(ti, tl);
            t.addView(top);
            if (it.sub != null && !it.sub.isEmpty()) {
                TextView s = Ui.text(ctx, simple ? 15.5f : 13.5f, 400, Ui.TEXT_2); s.setText(it.sub); s.setTag("s");
                s.setPaddingRelative((int) (32 * d), (int) (2 * d), 0, 0);
                t.addView(s);
            }
            if (it.level >= 0) {
                Slider sl = new Slider(ctx, it.level); sl.setTag("sl");
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, (int) (10 * d)); sp.topMargin = (int) (10 * d);
                t.addView(sl, sp);
            }
            t.setBackground(new GlassDrawable(Ui.dp(ctx, 20), Ui.cardFill(ctx, it.on ? 0x40FFFFFF : 0x1FFFFFFF), Ui.highContrast(ctx) ? 2 * d : d));
            return t;
        }

        void render() {
            if (views.isEmpty() || r < 0 || r >= views.size() || c < 0 || c >= views.get(r).size()) return;
            for (int i = 0; i < views.size(); i++) for (int j = 0; j < views.get(i).size(); j++) {
                boolean f = i == r && j == c;
                View v = views.get(i).get(j);
                Item it = rows.get(i).get(j);
                ((GlassDrawable) v.getBackground()).fill(f ? Ui.focusFill(v.getContext(), 0xF2F5F5F7) : Ui.cardFill(v.getContext(), it.on ? 0x40FFFFFF : 0x1FFFFFFF));
                int col = f ? 0xFF111114 : Ui.TEXT;
                ImageView ic = v.findViewWithTag("g");
                if (ic != null) ic.setImageTintList(android.content.res.ColorStateList.valueOf(col));
                for (String tag : new String[]{"t", "s"}) {
                    TextView tv = v.findViewWithTag(tag);
                    if (tv != null) { tv.setTextColor(tag.equals("s") ? (f ? 0xB3111114 : Ui.TEXT_2) : col); tv.setShadowLayer(0, 0, 0, 0); }
                }
                Slider sl = v.findViewWithTag("sl");
                if (sl != null) { sl.dark = f; sl.invalidate(); }
                v.animate().scaleX(f ? 1.03f : 1f).scaleY(f ? 1.03f : 1f).setDuration(Ui.dur(v.getContext(), Ui.FAST)).start();
            }
            View line = (View) views.get(r).get(c).getParent();
            scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, line.getTop() - (int) (60 * d))));
        }

        @Override public boolean key(KeyEvent e) {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
            if (rows.isEmpty()) return false;
            Item it = rows.get(r).get(c);
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP: Ui.click(panel); if (r > 0) { r--; c = Math.min(c, rows.get(r).size() - 1); render(); } return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: Ui.click(panel); if (r < rows.size() - 1) { r++; c = Math.min(c, rows.get(r).size() - 1); render(); } return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: {
                    Ui.click(panel);
                    int dir = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                    if (panel.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) dir = -dir; // D-pad follows the mirrored grid
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
            final String lang = TvViewer.get(h).getLanguage();
            addProfileHeader(h, lang);
            Item switchProfile = new Item(R.drawable.ic_users, I18n.get(lang, "tv.cc_switch_profile"),
                    I18n.get(lang, h.kids() ? "tv.cc_need_pin" : "tv.cc_profiles_sub"), () -> { h.closeSheet(); h.showProfiles(); });
            addVolumeAndMediaRows(h, lang);
            if (h.kids()) {
                int left = h.sleepLeft();
                add(new Item(R.drawable.ic_moon, I18n.get(lang, "tv.cc_sleep_timer"),
                        (left == 0 ? I18n.get(lang, "tv.cc_off") : I18n.get(lang, "tv.cc_min_left", String.valueOf(left))) + "  ·  ◀ ▶",
                        () -> h.stepSleep(1)).adjust(h::stepSleep),
                    new Item(R.drawable.ic_cloud_sun, I18n.get(lang, "tv.home_chip_weather"),
                        I18n.get(lang, "tv.cc_hourly_daily"), () -> { h.closeSheet(); h.openWeather(); }));
                add(switchProfile.wide());
                return;
            }
            addQuickRows(h, lang);
            addLiveAndDeviceRows(h, lang);
            addFamilyAndSystemRows(h, lang, switchProfile);
        }

        private void addProfileHeader(Home h, String lang) {
            String name = Cfg.s(h, "user_name", R.string.user_name);
            LinearLayout who = new LinearLayout(h); who.setGravity(Gravity.CENTER_VERTICAL);
            who.setPadding((int) (6 * d), 0, 0, (int) (8 * d));
            who.addView(Avatar.view(h, 44, !h.guest() && !h.kids()));
            LinearLayout names = new LinearLayout(h); names.setOrientation(LinearLayout.VERTICAL); names.setPadding((int) (14 * d), 0, 0, 0);
            TextView e = Ui.eyebrow(h, Ui.TEXT_3); e.setText(I18n.get(lang, "tv.control_center"));
            TextView t = Ui.text(h, Ui.HEADLINE, 600, Ui.TEXT);
            t.setText(h.kids() ? I18n.get(lang, "tv.lock_kids") : h.guest() ? I18n.get(lang, "tv.lock_guest") : name.isEmpty() ? "Tahoe TV Launcher" : name);
            names.addView(e); names.addView(t); who.addView(names);
            panel.addView(who);
        }

        private void addVolumeAndMediaRows(Home h, String lang) {
            AudioManager am = (AudioManager) h.getSystemService(Context.AUDIO_SERVICE);
            int v = am.getStreamVolume(AudioManager.STREAM_MUSIC), max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            add(new Item(R.drawable.ic_volume_2, I18n.get(lang, "tv.cc_volume"), Math.round(100f * v / max) + "%   ◀ ▶", null).wide().level((float) v / max)
                    .adjust(dir -> am.adjustSuggestedStreamVolume(dir > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.STREAM_MUSIC, 0)));
            if (h.phoneMedia != null) {
                boolean playing = h.phoneMedia.optBoolean("playing");
                add(new Item(playing ? R.drawable.ic_pause : R.drawable.ic_play, h.phoneMedia.optString("title"),
                        I18n.get(lang, "tv.cc_on_phone", h.phoneMedia.optString("artist")), () -> {
                    h.phoneMediaCommand(playing ? "pause" : "play");
                }).wide().on(playing).adjust(dir -> h.phoneMediaCommand(dir > 0 ? "next" : "prev")));
            }
        }

        private void addQuickRows(Home h, String lang) {
            add(new Item(R.drawable.ic_wifi, I18n.get(lang, "tv.cc_wifi"), ssid(h), () -> h.start(new Intent(Settings.ACTION_WIFI_SETTINGS))),
                new Item(R.drawable.ic_bluetooth, I18n.get(lang, "tv.cc_bluetooth"),
                        I18n.get(lang, "tv.cc_bluetooth_sub"), () -> h.start(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))));
            WallpaperProvider.Theme theme = WallpaperProvider.Theme.fromKey(h.prefs.getString("wall_theme", "nature"));
            boolean personal = Hero.wallMode(h).equals("personal");
            add(new Item(R.drawable.ic_image, I18n.get(lang, "tv.cc_wallpaper"),
                    (h.guest() ? I18n.get(lang, "tv.cc_default_guest")
                            : I18n.get(lang, personal ? "tv.cc_personal" : "tv.cc_default") + " · " + I18n.get(lang, "wall.theme_" + theme.getKey())) + "  ·  ◀ ▶",
                    () -> h.hero.nextWall())
                    .adjust(dir -> {
                        if (h.guest()) return;
                        h.prefs.edit().putString("wall_mode", Hero.wallMode(h).equals("personal") ? "default" : "personal").apply();
                        h.hero.refreshWallpaperSettings();
                    }),
                new Item(R.drawable.ic_cloud_sun, I18n.get(lang, "tv.home_chip_weather"),
                        I18n.get(lang, "tv.cc_hourly_daily"), () -> { h.closeSheet(); h.openWeather(); }));
            add(new Item(R.drawable.ic_moon_star, I18n.get(lang, "tv.cc_prayer"),
                    I18n.get(lang, "tv.cc_prayer_sub"), () -> { h.closeSheet(); h.openPrayer(); }),
                new Item(R.drawable.ic_sparkles, I18n.get(lang, "tv.cc_assistant"),
                        Assistant.enabled() ? "Gemini" : I18n.get(lang, "tv.cc_unset"), () -> { h.closeSheet(); h.openAssistant(); }));
            add(new Item(R.drawable.ic_house, I18n.get(lang, "tv.cc_smart_home"),
                    HomeControl.enabled() ? "Home Assistant" : "Hey Google", () -> { h.closeSheet(); h.openSheet(new ConnectSheets.Devices(h)); }),
                new Item(R.drawable.ic_smartphone, I18n.get(lang, "tv.cc_phone_lbl"),
                        h.companion == null || h.companion.pairedCount() == 0
                                ? I18n.get(lang, "tv.cc_pairing")
                                : I18n.get(lang, "tv.cc_phones_cnt", String.valueOf(h.companion.pairedCount())),
                        () -> { h.closeSheet(); h.openPair(); }),
                new Item(R.drawable.ic_settings, I18n.get(lang, "tv.cc_settings"),
                        I18n.get(lang, "tv.cc_system"), () -> h.start(new Intent(Settings.ACTION_SETTINGS))));
        }

        private void addLiveAndDeviceRows(Home h, String lang) {
            SharedPreferences lp = h.getSharedPreferences("live", Context.MODE_PRIVATE);
            PlayPrefs.Quality q = PlayPrefs.Quality.of(lp.getString("quality", null));
            PlayPrefs.Latency lat = PlayPrefs.Latency.of(lp.getString("latency", null));
            add(new Item(R.drawable.ic_tv, I18n.get(lang, "tv.cc_video_quality"), q.label(lang) + "  ·  ◀ ▶", null)
                    .adjust(dir -> lp.edit().putString("quality", PlayPrefs.next(q, dir).getKey()).apply()),
                new Item(R.drawable.ic_clock, I18n.get(lang, "tv.cc_stream_latency"), lat.label(lang) + "  ·  ◀ ▶", null)
                    .adjust(dir -> lp.edit().putString("latency", PlayPrefs.next(lat, dir).getKey()).apply()));
            PlayPrefs.SubtitleSize subSz = PlayPrefs.SubtitleSize.of(lp.getString("sub_size", null));
            DeviceProfile.Profile devProf = DeviceProfiler.current(h);
            add(new Item(R.drawable.ic_tv, I18n.get(lang, "sub.size_label"), subSz.label(lang) + "  ·  ◀ ▶", null)
                    .adjust(dir -> lp.edit().putString("sub_size", PlayPrefs.SubtitleSize.next(subSz, dir).getKey()).apply()),
                new Item(R.drawable.ic_info, I18n.get(lang, "device.title"),
                    (devProf.getManualOverride() == null
                            ? I18n.get(lang, "device.tier_auto", devProf.getActiveTier().label(lang))
                            : devProf.getActiveTier().label(lang)) + " · " + devProf.getSpecs().getRamMb() + " MB",
                    () -> { h.closeSheet(); h.openSheet(new ConnectSheets.DeviceInfo(h)); }));
        }

        private void addFamilyAndSystemRows(Home h, String lang, Item switchProfile) {
            int left = h.sleepLeft();
            add(new Item(R.drawable.ic_moon, I18n.get(lang, "tv.cc_sleep_timer"),
                    (left == 0 ? I18n.get(lang, "tv.cc_off") : I18n.get(lang, "tv.cc_min_left", String.valueOf(left))) + "  ·  ◀ ▶",
                    () -> h.stepSleep(1)).adjust(h::stepSleep),
                new Item(R.drawable.ic_info, I18n.get(lang, "tv.cc_about"),
                        I18n.get(lang, "tv.cc_about_sub", About.version(h)), () -> { h.closeSheet(); h.openSheet(new About(h)); }));
            Viewer vw = TvViewer.get(h);
            add(new Item(R.drawable.ic_settings, I18n.get(lang, "tv.region_language"), vw.getDone()
                    ? tech.kousozo.tahoetv.core.Region.flag(vw.getCountry()) + " " + tech.kousozo.tahoetv.core.OnboardingText.INSTANCE.getLANGUAGE_NAMES().get(vw.getLanguage())
                    : I18n.get(lang, "tv.cc_region_sub"), () -> { h.closeSheet(); h.startActivity(new Intent(h, Onboarding.class)); }),
                new Item(R.drawable.ic_smartphone, I18n.get(lang, "tv.cc_send_log"),
                        I18n.get(lang, "tv.cc_send_log_sub"), () -> { h.closeSheet(); h.sendLog(); }));
            add(new Item(R.drawable.ic_tv, I18n.get(lang, "ui.channel_languages"),
                    vw.getChannelLanguages().isEmpty() ? I18n.get(lang, "common.off") : String.join(", ", vw.getChannelLanguages()),
                    () -> { h.closeSheet(); h.openSheet(new ChannelLanguages(h)); }).wide());
            WatchRecap.Summary recap = WatchRecap.summarize(new File(h.getFilesDir(), "recap"), h.prefs.getString("profile", "main"), WatchRecap.currentMonth(), lang);
            add(new Item(R.drawable.ic_tv, I18n.get(lang, "recap.title"),
                    recap.getTotalHoursDecimal(), () -> { h.closeSheet(); h.openSheet(new ParentalSheets.Recap(h)); }).wide());
            if (h.guest()) add(switchProfile.wide());
            else add(switchProfile, new Item(R.drawable.ic_lock, I18n.get(lang, "tv.cc_kids_profile"),
                    Kids.hasPin(h) ? I18n.get(lang, "tv.cc_kids_sub") : I18n.get(lang, "tv.cc_unset"),
                    () -> { h.closeSheet(); h.openSheet(new ParentalSheets.KidsSetup(h)); }));
            boolean simple = TvViewer.simpleMode(h);
            add(new Item(R.drawable.ic_layout_grid, I18n.get(lang, "tv.cc_simple_mode"),
                    I18n.get(lang, simple ? "tv.cc_simple_on" : "tv.cc_simple_off"), () -> {
                TvViewer.setSimpleMode(h, !simple);
                h.applySimpleMode();
                rebuild();
            }).wide().on(simple));
            android.content.SharedPreferences cfg = Cfg.p(h);
            boolean hc = cfg.getBoolean("high_contrast", false), rm = cfg.getBoolean("reduce_motion", false), cs = cfg.getBoolean("click_sound", false);
            add(new Item(R.drawable.ic_layout_grid, I18n.get(lang, "ui.high_contrast"), I18n.get(lang, hc ? "common.on" : "common.off"),
                    () -> { cfg.edit().putBoolean("high_contrast", !hc).apply(); rebuild(); }).on(hc),
                new Item(R.drawable.ic_layout_grid, I18n.get(lang, "ui.reduce_motion"), I18n.get(lang, "tv.reduce_motion_sub"),
                    () -> { cfg.edit().putBoolean("reduce_motion", !rm).apply(); rebuild(); }).on(rm));
            add(new Item(R.drawable.ic_layout_grid, I18n.get(lang, "tv.click_sound"), I18n.get(lang, cs ? "common.on" : "common.off"),
                    () -> { cfg.edit().putBoolean("click_sound", !cs).apply(); rebuild(); }).on(cs),
                new Item(R.drawable.ic_tv, I18n.get(lang, "tv.live_preview"), I18n.get(lang, "tv.live_preview_sub"),
                    () -> { cfg.edit().putBoolean("live_preview", !cfg.getBoolean("live_preview", true)).apply(); rebuild(); }).on(cfg.getBoolean("live_preview", true)));
            // text size (#181): Normal → Besar → Sangat besar; applies as screens are rebuilt
            String ts = cfg.getString("text_size", "normal");
            String tsLabel = I18n.get(lang, "large".equals(ts) ? "tv.text_large" : "xlarge".equals(ts) ? "tv.text_xlarge" : "tv.text_normal");
            add(new Item(R.drawable.ic_layout_grid, I18n.get(lang, "tv.text_size"), tsLabel + "  ·  ◀ ▶",
                    () -> { cfg.edit().putString("text_size", "normal".equals(ts) ? "large" : "large".equals(ts) ? "xlarge" : "normal").apply(); h.recreate(); })
                    .adjust(dir -> { String[] o = {"normal", "large", "xlarge"}; int i = java.util.Arrays.asList(o).indexOf(ts);
                        cfg.edit().putString("text_size", o[Math.max(0, Math.min(2, i + dir))]).apply(); h.recreate(); }).wide());
            if (BuildConfig.STANDALONE_MODE) {
                add(new Item(R.drawable.ic_tv, I18n.get(lang, "tv.standalone_title"),
                        I18n.get(lang, "tv.standalone_guide"), null).wide());
            }
        }

        static String ssid(Context c) {
            String s = ((WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE)).getConnectionInfo().getSSID();
            return s == null || s.contains("unknown ssid") || s.equals("0x")
                    ? I18n.get(TvViewer.get(c).getLanguage(), "tv.cc_wifi_off")
                    : s.replace("\"", "");
        }
    }

    // ---------------------------------------------------------------- App menu (hold OK)

    /** Extra channel languages (#71): iptv-org per-language playlists loaded on top of the chosen countries. */
    static final class ChannelLanguages extends Grid {
        ChannelLanguages(Home h) { super(h, 560, Gravity.CENTER); rebuild(); }

        @Override void fill() {
            Home h = home;
            String lang = TvViewer.get(h).getLanguage();
            header(I18n.get(lang, "ui.channel_languages_sub"), I18n.get(lang, "ui.channel_languages"));
            List<String> codes = tech.kousozo.tahoetv.core.Region.channelLanguages();
            for (int i = 0; i < codes.size(); i += 2) {
                Item a = item(h, codes.get(i));
                if (i + 1 < codes.size()) add(a, item(h, codes.get(i + 1))); else add(a.wide());
            }
        }

        private Item item(Home h, String code) {
            Viewer v = TvViewer.get(h);
            boolean on = v.getChannelLanguages().contains(code);
            java.util.Locale loc = java.util.Locale.forLanguageTag(code);
            String name = loc.getDisplayLanguage(loc);
            return new Item(R.drawable.ic_tv, name.isEmpty() ? code : name, null, () -> {
                Viewer cur = TvViewer.get(h);
                List<String> next = new ArrayList<>(cur.getChannelLanguages());
                if (!next.remove(code)) next.add(code);
                TvViewer.save(h, cur.withChannelLanguages(next));
                Live.cached = null;
                rebuild();
            }).on(on);
        }
    }

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
            header(I18n.get(lang, app.system ? "tv.home_sys_app" : "tv.app_label"), app.label);
            add(new Item(R.drawable.ic_play, I18n.get(lang, "tv.open"), null, () -> { h.closeSheet(); h.launch(app.launch, app, h.focusedTile()); }).wide());
            if (h.kids()) return;
            if (app.pkg.equals(Trending.YT) && !h.trending.isEmpty()) {
                Trending.Video v = h.trending.get(h.trendIndex % h.trending.size());
                add(new Item(R.drawable.ic_tv, I18n.get(lang, "tv.play_trending"), v.title, () -> { h.closeSheet(); h.start(Trending.watch(v.id)); }).wide());
            }
            List<String> pins = HomeDock.dockPackages(h);
            int at = pins.indexOf(app.pkg);
            if (at >= 0) {
                add(new Item(R.drawable.ic_pin_off, I18n.get(lang, "tv.unpin_dock"), null, () -> { pins.remove(app.pkg); h.closeSheet(); HomeDock.saveDock(h, pins); }),
                    new Item(R.drawable.ic_arrow_left_right, I18n.get(lang, "tv.move"), "◀ ▶", null).adjust(dir -> {
                        int i = pins.indexOf(app.pkg), j = i + dir;
                        if (j >= 0 && j < pins.size()) { Collections.swap(pins, i, j); HomeDock.saveDock(h, pins); }
                    }));
            } else if (h.dock.size() < HomeDock.dockSlotCap(h))
                add(new Item(R.drawable.ic_pin, I18n.get(lang, "tv.pin_dock"), null, () -> { pins.add(app.pkg); h.closeSheet(); HomeDock.saveDock(h, pins); }).wide());
            else add(new Item(R.drawable.ic_pin, I18n.get(lang, "tv.dock_full"), I18n.get(lang, "tv.dock_remove_one", String.valueOf(HomeDock.dockSlotCap(h))), null).wide());
            Item info = new Item(R.drawable.ic_info, I18n.get(lang, "tv.info"), null, () -> { h.closeSheet(); h.start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg))); });
            if (app.system) add(info.wide());
            else add(info, new Item(R.drawable.ic_trash_2, I18n.get(lang, "tv.uninstall"), null, () -> { h.closeSheet(); h.start(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.pkg))); }));
        }

        @Override public boolean key(KeyEvent e) {
            if ((e.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER || e.getKeyCode() == KeyEvent.KEYCODE_ENTER) && e.getRepeatCount() > 0) return true;
            return super.key(e);
        }
    }
}
