package tv.clean.home;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import tech.kousozo.tahoetv.core.LocalTv;
import tech.kousozo.tahoetv.core.OnboardingText;
import tech.kousozo.tahoetv.core.Region;
import tech.kousozo.tahoetv.core.Sources;
import tech.kousozo.tahoetv.core.Viewer;
import tech.kousozo.tahoetv.core.Web;

/** First run on the TV (#77): the same 10 steps and texts as the phone and desktop, made for the remote — big rows,
 *  D-pad focus, OK to choose. Language comes first. Can also be filled from a paired phone (it sends its choices). */
public class Onboarding extends Activity {
    enum Step { LANGUAGE, COUNTRY, CITY, TIMEZONE, PRAYER, COUNTRIES, CATEGORIES, FAVORITES, ORDER, SUMMARY }

    /** The choices being made (Viewer is immutable; built at the end). */
    String language, country, city = "", region = "", timeZone = "";
    Double lat, lon;
    boolean prayer; int method;
    final LinkedHashSet<String> countries = new LinkedHashSet<>(), categories = new LinkedHashSet<>();
    final List<String> favorites = new ArrayList<>();
    List<LocalTv.Entry> catalog; // channels of the chosen countries (favourites + order steps)
    String catalogFor = "";

    Step step = Step.LANGUAGE;
    TextView stepLabel, title, sub;
    LinearLayout list; ScrollView scroll; LinearLayout footer;
    final Handler ui = new Handler(Looper.getMainLooper());
    static Onboarding open; // a phone's choices arrive while this is showing

    int dp(float v) { return (int) Ui.dp(this, v); }
    float S = 1f; // Figma 1920 grid
    int px(float v) { return Math.round(v * S); }

    /** Text from the Figma sizes, a touch larger for the sofa. */
    TextView otv(float sizePx, int wght, int color) {
        TextView t = new TextView(this);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sizePx * S * (sizePx >= 40 ? 1.08f : 1.15f));
        t.setTypeface(Ui.font(this, wght)); t.setTextColor(color); t.setIncludeFontPadding(false);
        return t;
    }
    String t(String k) { return OnboardingText.INSTANCE.get(language, k); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (TvGuard.blockIfNotTv(this)) return;
        Viewer v = TvViewer.get(this);
        if (v.getDone()) load(v);
        else {
            kotlin.Pair<String, String> s = Region.suggest(Locale.getDefault());
            load(Viewer.suggested(s.getFirst(), s.getSecond()));
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.NIGHT);
        S = getResources().getDisplayMetrics().widthPixels / 1920f;
        // Figma "TV / Onboarding": copy on the left (x140, y200, 720 wide), the choices on the right (x1040, 740 wide)
        LinearLayout left = new LinearLayout(this); left.setOrientation(LinearLayout.VERTICAL);
        stepLabel = otv(16, 600, 0xFF5CD0FF); stepLabel.setLetterSpacing(0.1f);
        left.addView(stepLabel);
        title = otv(84, 700, 0xFFF7F8FA); title.setSingleLine(false); title.setMaxLines(3); title.setLetterSpacing(-0.02f);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2); tl.topMargin = px(24); left.addView(title, tl);
        sub = otv(24, 400, 0xCCF7F8FA); sub.setSingleLine(false); sub.setLineSpacing(0, 1.1f);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2); sl.topMargin = px(24); left.addView(sub, sl);
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(px(720), -2); ll.leftMargin = px(140); ll.topMargin = px(200);
        root.addView(left, ll);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        scroll = new ScrollView(this); scroll.setClipToPadding(false); scroll.setVerticalScrollBarEnabled(false);
        scroll.setPadding(px(8), px(8), px(8), px(8)); // room for the focus glow
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        footer = new LinearLayout(this); footer.setOrientation(LinearLayout.HORIZONTAL); footer.setGravity(Gravity.END);
        footer.setPadding(0, px(16), 0, 0);
        col.addView(footer);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(px(756), -1); cl.leftMargin = px(1032); cl.topMargin = px(130); cl.bottomMargin = px(60);
        root.addView(col, cl);
        Ui.direction(root);
        setContentView(root);
        open = this;
        render();
    }

    @Override protected void onDestroy() { if (open == this) open = null; super.onDestroy(); }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = Home.gamepadToRemote(e.getKeyCode());
        if (k != e.getKeyCode()) e = new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(), k, e.getRepeatCount(), e.getMetaState(), e.getDeviceId(), e.getScanCode(), e.getFlags(), e.getSource());
        return super.dispatchKeyEvent(e);
    }

    void load(Viewer v) {
        language = v.getLanguage(); country = v.getCountry(); city = v.getCity(); region = v.getRegion(); timeZone = v.getTimeZone();
        lat = v.getLat(); lon = v.getLon(); prayer = v.getPrayer(); method = v.getPrayerMethod();
        countries.clear(); countries.addAll(v.getCountries());
        categories.clear(); categories.addAll(v.getCategories());
        favorites.clear(); favorites.addAll(v.getFavorites());
    }

    Viewer build() {
        Viewer prev = TvViewer.get(this);
        return new Viewer(language, country, city, lat, lon, region, timeZone, prayer, method,
                new ArrayList<>(countries), new LinkedHashSet<>(categories), new ArrayList<>(favorites),
                prev.getSchema(), prev.getRevision(), prev.getSimpleMode(), prev.getSortMode());
    }

    /** A phone sent its onboarding or incremental patch (Companion "viewer"/"viewer_patch"): merge and finish. */
    static void fromPhone(android.content.Context c, String json) {
        Viewer v = Viewer.applyPatch(TvViewer.get(c), json);
        if (!v.getDone()) return;
        TvViewer.save(c, v);
        if (open != null) open.runOnUiThread(open::finish);
    }

    @Override public void onBackPressed() {
        if (!TvGuard.isAndroidTv(this)) { finishAndRemoveTask(); return; }
        if (step.ordinal() > 0) go(-1);
        else if (TvViewer.done(this)) finish(); // reopened from Settings: leave unchanged
    }

    void showSetupQr() {
        Home h = Home.live;
        Companion c = h != null ? h.companion : null;
        if (c == null) return;
        String url = c.setupUrl();
        String code = c.code();
        android.graphics.Bitmap bm = ConnectSheets.Pair.qr(url, dp(260));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(24), dp(20), dp(24), dp(16));
        if (bm != null) {
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageBitmap(bm);
            box.addView(iv, new LinearLayout.LayoutParams(dp(240), dp(240)));
        }
        TextView msg = Ui.text(this, Ui.BODY, 500, Ui.TEXT);
        msg.setSingleLine(false);
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(0, dp(14), 0, 0);
        msg.setText(tech.kousozo.tahoetv.core.I18n.get(language, "qr.setup_web_hint", "http://" + Companion.ip() + ":" + Companion.PORT + "/setup", code));
        box.addView(msg);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(tech.kousozo.tahoetv.core.I18n.get(language, "qr.setup_web_btn"))
                .setView(box)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    void go(int d) {
        focusKey = null;
        int n = Math.max(0, Math.min(Step.values().length - 1, step.ordinal() + d));
        step = Step.values()[n];
        render();
    }

    final Runnable pendingRender = this::render;
    void postRender() { ui.removeCallbacks(pendingRender); ui.post(pendingRender); }

    // ------------------------------------------------------------------ rendering

    void render() {
        list.removeAllViews(); footer.removeAllViews(); scroll.scrollTo(0, 0);
        if (focusKey == null) query = ""; // a new step: its search box is empty, so is the filter
        stepLabel.setText(t("step").replace("%s", (step.ordinal() + 1) + "/" + Step.values().length).toUpperCase(Locale.ROOT));
        String[] ts = keys();
        title.setText(t(ts[0])); sub.setText(t(ts[1]));
        switch (step) {
            case LANGUAGE:
                for (String l : Region.INSTANCE.getLANGUAGES()) row(OnboardingText.INSTANCE.getLANGUAGE_NAMES().get(l), 0, l.equals(language), () -> { language = l; postRender(); });
                row(tech.kousozo.tahoetv.core.I18n.get(language, "qr.setup_web_btn"), R.drawable.ic_smartphone, false, this::showSetupQr);
                if (!TvViewer.done(this)) note(phoneHint());
                break;
            case COUNTRY: countryList(false); break;
            case CITY: cityStep(); break;
            case TIMEZONE: zoneStep(); break;
            case PRAYER:
                row(t("prayer.on") + (prayer ? "  ●" : "  ○"), 0, prayer, () -> { prayer = !prayer; postRender(); });
                if (prayer) {
                    boolean wakeOn = Cfg.p(this).getBoolean("prayer_wake_screen", true);
                    row(tech.kousozo.tahoetv.core.I18n.get(language, "tv.prayer_wake_screen") + (wakeOn ? "  ●" : "  ○"),
                            R.drawable.ic_moon, wakeOn, () -> { Cfg.p(this).edit().putBoolean("prayer_wake_screen", !wakeOn).apply(); postRender(); });
                }
                if (prayer && lat == null) note(t("prayer.needcity"));
                if (prayer) {
                    note(t("prayer.method"));
                    for (tech.kousozo.tahoetv.core.Prayer.Method m : tech.kousozo.tahoetv.core.Prayer.INSTANCE.getMETHODS())
                        row(m.getName(), 0, m.getId() == method, () -> { method = m.getId(); postRender(); });
                }
                break;
            case COUNTRIES: countryList(true); break;
            case CATEGORIES:
                for (LocalTv.Category cat : LocalTv.Category.values()) {
                    if (cat == LocalTv.Category.Other) continue;
                    if ((cat == LocalTv.Category.Tvri || cat == LocalTv.Category.Near) && !countries.contains(Region.HOME)) continue;
                    boolean on = categories.contains(cat.name());
                    row(OnboardingText.INSTANCE.category(language, cat), 0, on, () -> { if (!categories.remove(cat.name())) categories.add(cat.name()); postRender(); });
                }
                break;
            case FAVORITES: favoritesStep(); break;
            case ORDER: orderStep(); break;
            case SUMMARY: summary(); break;
        }
        if (step.ordinal() > 0) button(t("back"), false, () -> go(-1));
        if (step == Step.SUMMARY) button(t("done"), true, () -> { TvViewer.save(this, build()); finish(); });
        else button(step == Step.CITY && lat == null ? t("skip") : t("next"), true, () -> go(1));
        // focus the chosen row, else the first
        View first = null, chosen = null;
        for (int i = 0; i < list.getChildCount(); i++) {
            View c = list.getChildAt(i);
            if (!c.isFocusable()) continue;
            if (first == null) first = c;
            if (chosen == null && Boolean.TRUE.equals(c.getTag())) chosen = c;
        }
        View focus = chosen != null ? chosen : first != null ? first : footer.getChildAt(footer.getChildCount() - 1);
        if (focus != null) focus.post(focus::requestFocus);
    }

    String[] keys() {
        switch (step) {
            case LANGUAGE: return new String[]{"lang.title", "lang.sub"};
            case COUNTRY: return new String[]{"country.title", "country.sub"};
            case CITY: return new String[]{"city.title", "city.sub"};
            case TIMEZONE: return new String[]{"tz.title", "tz.sub"};
            case PRAYER: return new String[]{"prayer.title", "prayer.sub"};
            case COUNTRIES: return new String[]{"countries.title", "countries.sub"};
            case CATEGORIES: return new String[]{"cats.title", "cats.sub"};
            case FAVORITES: return new String[]{"favs.title", "favs.sub"};
            case ORDER: return new String[]{"order.title", "order.sub"};
            default: return new String[]{"summary.title", "summary.sub"};
        }
    }

    String phoneHint() {
        switch (language) {
            case "id": return "Lebih mudah dari HP: buka app Tahoe di HP yang tersambung → Wilayah & bahasa. Pilihannya langsung dikirim ke TV ini.";
            case "zh": return "用手机更方便：在已连接的手机上打开 Tahoe → 地区与语言，选择会直接发送到这台电视。";
            default: return "Easier from your phone: open Tahoe on a paired phone → Region & language. Your choices are sent to this TV.";
        }
    }

    // ------------------------------------------------------------------ steps

    String query = "";

    void countryList(boolean multi) {
        searchField(t("search"), q -> { query = q; refreshList(multi); });
        refreshList(multi);
    }

    /** Rebuilds the rows under the search field (the field keeps focus while typing). */
    void refreshList(boolean multi) {
        while (list.getChildCount() > 1) list.removeViewAt(1);
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Region.Country> all = Region.countries(language);
        List<Region.Country> top = new ArrayList<>(), rest = new ArrayList<>();
        for (Region.Country c : all) {
            if (!q.isEmpty() && !c.getName().toLowerCase(Locale.ROOT).contains(q) && !c.getCode().equalsIgnoreCase(q)) continue;
            boolean sel = multi ? countries.contains(c.getCode()) : c.getCode().equals(country);
            (sel ? top : rest).add(c);
        }
        top.addAll(rest);
        for (Region.Country c : top) {
            boolean sel = multi ? countries.contains(c.getCode()) : c.getCode().equals(country);
            row(c.getFlag() + "   " + c.getName(), 0, sel, c.getCode(), () -> {
                if (multi) { if (countries.contains(c.getCode())) { if (countries.size() > 1) countries.remove(c.getCode()); } else countries.add(c.getCode()); }
                else if (!c.getCode().equals(country)) {
                    country = c.getCode(); prayer = Region.prayerByDefault(country); method = tech.kousozo.tahoetv.core.Prayer.methodFor(country);
                    countries.clear(); countries.add(country); city = ""; lat = null; lon = null; region = "";
                    timeZone = tech.kousozo.tahoetv.core.CountryCatalog.defaultZone(country);
                }
                refreshList(multi);
            });
        }
        if (!multi && country != null && !country.isEmpty())
            note(t(country.equals(Region.HOME) ? "note.home" : "note.other").replace("%s", Region.name(country, language)));
    }

    int citySearch;

    void cityStep() {
        if (lat != null) row(city + ", " + Region.name(country, language), R.drawable.ic_map_pin, true, R.drawable.ic_close, null, () -> {
            city = ""; lat = null; lon = null; region = ""; timeZone = tech.kousozo.tahoetv.core.CountryCatalog.defaultZone(country); postRender(); // OK again clears it
        });
        else {
            note(t("city.none"));
            for (tech.kousozo.tahoetv.core.CountryCatalog.CityOption s : tech.kousozo.tahoetv.core.CountryCatalog.suggestedCities(country, language)) {
                row(s.label(), R.drawable.ic_map_pin, false, () -> {
                    city = s.getCity(); lat = s.getLat(); lon = s.getLon(); region = s.getRegion();
                    timeZone = s.getZoneId();
                    postRender();
                });
            }
        }
        final int[] baseCount = new int[1];
        searchField(tech.kousozo.tahoetv.core.I18n.get(language, "onboarding.city_subdistrict_hint"), q -> {
            if (q.trim().length() < 2) return;
            String lang = language;
            int mine = ++citySearch;
            Net.bg.execute(() -> {
                List<Web.City> found = Web.INSTANCE.searchPlaces(q.trim(), lang, country);
                ui.post(() -> {
                    if (mine != citySearch || step != Step.CITY) return; // a newer search, or the viewer moved on
                    while (list.getChildCount() > baseCount[0]) list.removeViewAt(baseCount[0]);
                    if (found.isEmpty()) note(t("city.empty"));
                    for (Web.City c : found) row(c.getLabel(), R.drawable.ic_map_pin, false, () -> {
                        city = c.getName(); lat = c.getLat(); lon = c.getLon(); region = c.getRegion();
                        if (!c.getTimeZone().isEmpty()) timeZone = c.getTimeZone();
                        postRender();
                    });
                });
            });
        }, true);
        baseCount[0] = list.getChildCount();
    }

    void zoneStep() {
        String defZone = tech.kousozo.tahoetv.core.CountryCatalog.defaultZone(country);
        String device = java.util.TimeZone.getDefault().getID();
        String picked = timeZone.isEmpty() ? defZone : timeZone;
        row(defZone.replace('_', ' ') + "  " + offset(defZone), R.drawable.ic_tv, picked.equals(defZone), () -> { timeZone = defZone; postRender(); });
        if (!device.equals(defZone)) {
            row(t("tz.device").replace("%s", device), R.drawable.ic_tv, timeZone.equals(device), () -> { timeZone = device; postRender(); });
        }
        if (!timeZone.isEmpty() && !timeZone.equals(defZone) && !timeZone.equals(device)) {
            row(timeZone.replace('_', ' ') + "  " + offset(timeZone), 0, true, () -> {});
        }
        final int[] baseCount = new int[1];
        searchField(t("search"), q -> {
            while (list.getChildCount() > baseCount[0]) list.removeViewAt(list.getChildCount() - 1);
            String qq = q.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
            if (qq.length() < 2) return;
            int n = 0;
            List<String> zones = new ArrayList<>(ZoneId.getAvailableZoneIds()); java.util.Collections.sort(zones);
            zones.add(0, "UTC");
            for (String z : zones) {
                if ((!z.contains("/") && !z.equals("UTC")) || z.startsWith("Etc/") || z.startsWith("SystemV/") || !z.toLowerCase(Locale.ROOT).contains(qq)) continue;
                row(z.replace('_', ' ') + "  " + offset(z), 0, z.equals(picked), () -> { timeZone = z; postRender(); });
                if (++n >= 40) break;
            }
        }, false);
        baseCount[0] = list.getChildCount();
    }

    static String offset(String zone) {
        try {
            java.time.ZoneOffset o = ZoneId.of(zone).getRules().getOffset(java.time.Instant.now());
            return "UTC" + (o.getTotalSeconds() == 0 ? "" : o.getId());
        } catch (Exception e) { return ""; }
    }

    void favoritesStep() {
        String key = String.join(",", countries);
        if (catalog == null || !key.equals(catalogFor)) {
            note(t("favs.loading"));
            Viewer v = build();
            Net.bg.execute(() -> {
                List<LocalTv.Entry> l;
                try {
                    List<tech.kousozo.tahoetv.core.M3u.Channel> loaded = Sources.loadCountriesPriority(Cfg.p(this).getString("playlist_url", ""), v.getCountries());
                    List<String> names = new ArrayList<>();
                    for (tech.kousozo.tahoetv.core.M3u.Channel ch : loaded) names.add(ch.getName());
                    tech.kousozo.tahoetv.core.PopularityScraper.prefetchAsync(v.getCountries(), names, null);
                    l = LocalTv.INSTANCE.arrange(loaded, v.getNearRegion(), v.getCountries(), v.getSort(), v.getCity(), v.getCountry());
                } catch (Exception e) { l = null; }
                List<LocalTv.Entry> got = l;
                ui.post(() -> { catalog = got; catalogFor = key; if (step == Step.FAVORITES) postRender(); });
            });
            return;
        }
        if (catalog.isEmpty()) { note(t("favs.failed")); return; }
        searchField(t("search"), q -> { query = q; refreshFavs(); });
        query = ""; refreshFavs();
    }

    void refreshFavs() {
        while (list.getChildCount() > 1) list.removeViewAt(1);
        String q = query.trim().toLowerCase(Locale.ROOT);
        int n = 0;
        // favourites first, then the rest (capped: a 1 GB TV doesn't need 1,000 rows at once; search narrows it)
        List<LocalTv.Entry> ordered = new ArrayList<>(), rest = new ArrayList<>();
        for (LocalTv.Entry e : catalog) (favorites.contains(e.getChannel().getUrl()) ? ordered : rest).add(e);
        ordered.addAll(rest);
        for (LocalTv.Entry e : ordered) {
            if (!q.isEmpty() && !e.getName().toLowerCase(Locale.ROOT).contains(q)) continue;
            String url = e.getChannel().getUrl();
            boolean on = favorites.contains(url);
            row(e.getName(), on ? R.drawable.ic_heart_filled : R.drawable.ic_heart, on, url, () -> { if (!favorites.remove(url)) favorites.add(url); refreshFavs(); });
            if (++n >= 150) break;
        }
    }

    /** OK picks a favourite up; ▲ ▼ move it; OK again puts it down. */
    int moving = -1;

    void orderStep() {
        if (favorites.isEmpty()) { note(t("order.empty")); return; }
        java.util.Map<String, String> names = new java.util.HashMap<>();
        if (catalog != null) for (LocalTv.Entry e : catalog) names.put(e.getChannel().getUrl(), e.getName());
        for (int i = 0; i < favorites.size(); i++) {
            final int at = i;
            String url = favorites.get(i);
            String name = names.containsKey(url) ? names.get(url) : url.substring(url.lastIndexOf('/') + 1);
            TextView r = row((i + 1) + ".  " + name + (moving == i ? "   ▲▼" : ""), 0, moving == i, () -> { moving = moving == at ? -1 : at; postRender(); });
            r.setOnKeyListener((v, code, ev) -> {
                if (moving != at || ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                int to = code == KeyEvent.KEYCODE_DPAD_UP ? at - 1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? at + 1 : -2;
                if (to == -2 || to < 0 || to >= favorites.size()) return to != -2; // swallow moves past the ends
                favorites.add(to, favorites.remove(at)); moving = to; postRender();
                return true;
            });
        }
    }

    void summary() {
        String[][] rows = {
                {"sum.language", OnboardingText.INSTANCE.getLANGUAGE_NAMES().get(language)},
                {"sum.country", Region.flag(country) + "  " + Region.name(country, language)},
                {"sum.city", city.isEmpty() ? t("sum.none") : city},
                {"sum.tz", timeZone.isEmpty() ? tech.kousozo.tahoetv.core.CountryCatalog.defaultZone(country) : timeZone},
                {"sum.prayer", prayer ? tech.kousozo.tahoetv.core.Prayer.name(method) : t("sum.off")},
                {"sum.channels", flags()},
                {"sum.cats", categories.isEmpty() ? t("sum.all") : cats()},
                {"sum.favs", favorites.isEmpty() ? t("sum.none") : String.valueOf(favorites.size())},
        };
        for (String[] r : rows) note(t(r[0]) + ":  " + r[1]);
    }

    String flags() { StringBuilder s = new StringBuilder(); for (String c : countries) s.append(Region.flag(c)).append(' ').append(Region.name(c, language)).append("   "); return s.toString().trim(); }
    String cats() {
        StringBuilder s = new StringBuilder();
        for (LocalTv.Category c : LocalTv.Category.values()) if (categories.contains(c.name())) s.append(s.length() == 0 ? "" : ", ").append(OnboardingText.INSTANCE.category(language, c));
        return s.toString();
    }

    // ------------------------------------------------------------------ views

    /** Row to focus after the list is rebuilt (the one just toggled), by key. */
    String focusKey;

    TextView row(String text, int icon, boolean selected, Runnable onClick) { return row(text, icon, selected, null, onClick); }

    TextView row(String text, int icon, boolean selected, String key, Runnable onClick) { return row(text, icon, selected, 0, key, onClick); }

    /** [icon] at the front (0 = none) and [tail] at the end (0 = none; a check when selected and no tail). */
    TextView row(String text, int icon, boolean selected, int tail, String key, Runnable onClick) {
        TextView r = otv(30, 600, 0xFFF7F8FA);
        r.setSingleLine(); r.setEllipsize(android.text.TextUtils.TruncateAt.END);
        CharSequence label = icon == 0 ? text : Ui.iconText(r, icon, 0xFFF7F8FA, text);
        int end = tail != 0 ? tail : selected ? R.drawable.ic_check : 0;
        r.setText(end == 0 ? label : Ui.textIcon(r, label, end, 0xFFF7F8FA));
        r.setPadding(px(36), px(28), px(36), px(28));
        r.setFocusable(true); r.setClickable(true); r.setTag(selected);
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(px(28));
        bg.setColor(selected ? 0x2664D2FF : 0x14FFFFFF);
        r.setBackground(bg);
        r.setOnFocusChangeListener((v, f) -> {
            if (f && Ui.highContrast(this)) {
                r.setBackground(Ui.focusRing(this, px(28)));
                r.setTextColor(0xFFF7F8FA);
            } else {
                GradientDrawable g = new GradientDrawable(); g.setCornerRadius(px(28));
                g.setColor(f ? 0xFFFFFFFF : selected ? 0x2664D2FF : 0x14FFFFFF);
                if (f) g.setStroke(Math.max(2, px(5)), 0xFF5CD0FF);
                r.setBackground(g);
                r.setTextColor(f ? 0xFF0D0F14 : 0xFFF7F8FA);
            }
        });
        r.setOnClickListener(v -> { focusKey = key; onClick.run(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = px(16);
        list.addView(r, lp);
        if (key != null && key.equals(focusKey)) r.post(r::requestFocus);
        return r;
    }

    void note(String text) {
        TextView n = otv(19, 500, 0xCCF7F8FA);
        n.setSingleLine(false); n.setText(text); n.setPadding(px(8), px(8), px(8), px(14));
        list.addView(n);
    }

    interface OnText { void on(String q); }

    /** Search box: [live] = filter on every change; otherwise searches when OK/Done is pressed. */
    void searchField(String hint, OnText on) { searchField(hint, on, true); }
    void searchField(String hint, OnText on, boolean live) {
        EditText e = new EditText(this);
        e.setHint(Ui.iconText(e, R.drawable.ic_search_cari, 0x99F7F8FA, hint)); e.setHintTextColor(0x99F7F8FA); e.setTextColor(0xFFF7F8FA); e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 30 * S * 1.15f); e.setTypeface(Ui.font(this, 600));
        e.setSingleLine(); e.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        e.setPadding(px(36), px(22), px(36), px(22));
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(px(999)); bg.setColor(0x1AFFFFFF); bg.setStroke(Math.max(2, px(3)), 0xFF5CD0FF);
        e.setBackground(bg);
        if (live) e.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) { on.on(s.toString()); }
        });
        else e.setOnEditorActionListener((v, id, ev) -> { on.on(e.getText().toString()); return false; });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = px(16);
        list.addView(e, list.getChildCount() == 0 ? 0 : list.getChildCount(), lp);
    }

    void button(String text, boolean primary, Runnable onClick) {
        TextView b = otv(24, 600, primary ? 0xFF0D0F14 : 0xFFF7F8FA);
        b.setText(text); b.setGravity(Gravity.CENTER);
        b.setPadding(px(36), px(22), px(36), px(22)); b.setFocusable(true); b.setClickable(true);
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(px(999));
        bg.setColor(primary ? 0xFFFFFFFF : 0x24FFFFFF); b.setBackground(bg);
        b.setOnFocusChangeListener((v, f) -> {
            if (f && Ui.highContrast(this)) b.setBackground(Ui.focusRing(this, px(999)));
            else {
                GradientDrawable g = new GradientDrawable(); g.setCornerRadius(px(999));
                g.setColor(primary ? 0xFFFFFFFF : f ? 0x47FFFFFF : 0x24FFFFFF);
                if (f) g.setStroke(Math.max(2, px(5)), 0xFF5CD0FF);
                b.setBackground(g);
            }
        });
        b.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.leftMargin = px(20);
        footer.addView(b, lp);
    }
}
