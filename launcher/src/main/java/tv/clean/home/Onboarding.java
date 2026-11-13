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
    String t(String k) { return OnboardingText.INSTANCE.get(language, k); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Viewer v = TvViewer.get(this);
        if (v.getDone()) load(v);
        else {
            kotlin.Pair<String, String> s = Region.suggest(Locale.getDefault());
            load(Viewer.suggested(s.getFirst(), s.getSecond()));
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.NIGHT);
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(Ui.SAFE_SIDE * 2), dp(Ui.SAFE_TOP + 10), dp(Ui.SAFE_SIDE * 2), dp(Ui.SAFE_TOP));
        stepLabel = Ui.eyebrow(this, Ui.TEXT_3); col.addView(stepLabel);
        title = Ui.text(this, Ui.TITLE, 700, Ui.TEXT); title.setPadding(0, dp(8), 0, 0); col.addView(title);
        sub = Ui.text(this, Ui.BODY, 400, Ui.TEXT_2); sub.setSingleLine(false); sub.setPadding(0, dp(6), 0, dp(14)); col.addView(sub);
        scroll = new ScrollView(this); scroll.setClipToPadding(false);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        footer = new LinearLayout(this); footer.setOrientation(LinearLayout.HORIZONTAL); footer.setGravity(Gravity.END);
        footer.setPadding(0, dp(14), 0, 0);
        col.addView(footer);
        root.addView(col);
        setContentView(root);
        open = this;
        render();
    }

    @Override protected void onDestroy() { if (open == this) open = null; super.onDestroy(); }

    void load(Viewer v) {
        language = v.getLanguage(); country = v.getCountry(); city = v.getCity(); region = v.getRegion(); timeZone = v.getTimeZone();
        lat = v.getLat(); lon = v.getLon(); prayer = v.getPrayer(); method = v.getPrayerMethod();
        countries.clear(); countries.addAll(v.getCountries());
        categories.clear(); categories.addAll(v.getCategories());
        favorites.clear(); favorites.addAll(v.getFavorites());
    }

    Viewer build() {
        return new Viewer(language, country, city, lat, lon, region, timeZone, prayer, method,
                new ArrayList<>(countries), new LinkedHashSet<>(categories), new ArrayList<>(favorites));
    }

    /** A phone sent its onboarding (Companion "viewer" message): take it and finish. */
    static void fromPhone(android.content.Context c, String json) {
        Viewer v = Viewer.fromJson(json);
        if (!v.getDone()) return;
        TvViewer.save(c, v);
        if (open != null) open.runOnUiThread(open::finish);
    }

    @Override public void onBackPressed() {
        if (step.ordinal() > 0) go(-1);
        else if (TvViewer.done(this)) finish(); // reopened from Settings: leave unchanged
    }

    void go(int d) {
        int n = Math.max(0, Math.min(Step.values().length - 1, step.ordinal() + d));
        step = Step.values()[n];
        render();
    }

    // ------------------------------------------------------------------ rendering

    void render() {
        list.removeAllViews(); footer.removeAllViews(); scroll.scrollTo(0, 0);
        stepLabel.setText(t("step").replace("%s", (step.ordinal() + 1) + "/" + Step.values().length));
        String[] ts = keys();
        title.setText(t(ts[0])); sub.setText(t(ts[1]));
        switch (step) {
            case LANGUAGE:
                for (String l : Region.INSTANCE.getLANGUAGES()) row(OnboardingText.INSTANCE.getLANGUAGE_NAMES().get(l), null, l.equals(language), () -> { language = l; render(); });
                if (!TvViewer.done(this)) note("📱 " + phoneHint());
                break;
            case COUNTRY: countryList(false); break;
            case CITY: cityStep(); break;
            case TIMEZONE: zoneStep(); break;
            case PRAYER:
                row(t("prayer.on") + (prayer ? "  ●" : "  ○"), null, prayer, () -> { prayer = !prayer; render(); });
                if (prayer && lat == null) note(t("prayer.needcity"));
                if (prayer) {
                    note(t("prayer.method"));
                    for (tech.kousozo.tahoetv.core.Prayer.Method m : tech.kousozo.tahoetv.core.Prayer.INSTANCE.getMETHODS())
                        row(m.getName(), null, m.getId() == method, () -> { method = m.getId(); render(); });
                }
                break;
            case COUNTRIES: countryList(true); break;
            case CATEGORIES:
                for (LocalTv.Category cat : LocalTv.Category.values()) {
                    if (cat == LocalTv.Category.Other) continue;
                    if ((cat == LocalTv.Category.Tvri || cat == LocalTv.Category.Near) && !countries.contains(Region.HOME)) continue;
                    boolean on = categories.contains(cat.name());
                    row(OnboardingText.INSTANCE.category(language, cat), null, on, () -> { if (!categories.remove(cat.name())) categories.add(cat.name()); render(); });
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
            row(c.getFlag() + "   " + c.getName(), null, sel, () -> {
                if (multi) { if (countries.contains(c.getCode())) { if (countries.size() > 1) countries.remove(c.getCode()); } else countries.add(c.getCode()); }
                else if (!c.getCode().equals(country)) {
                    country = c.getCode(); prayer = Region.prayerByDefault(country); method = tech.kousozo.tahoetv.core.Prayer.methodFor(country);
                    countries.clear(); countries.add(country); city = ""; lat = null; lon = null; region = ""; timeZone = "";
                }
                refreshList(multi);
            });
        }
        if (!multi && country != null && !country.isEmpty())
            note(t(country.equals(Region.HOME) ? "note.home" : "note.other").replace("%s", Region.name(country, language)));
    }

    void cityStep() {
        if (lat != null) row("📍  " + city + ", " + Region.name(country, language) + "   ✕", null, true, () -> {
            city = ""; lat = null; lon = null; region = ""; timeZone = ""; render(); // OK again clears it
        });
        else note(t("city.none"));
        searchField(t("city.search"), q -> {
            if (q.trim().length() < 2) return;
            String lang = Region.HOME.equals(country) ? "id" : language; // Indonesian province names for "near you"
            Net.bg.execute(() -> {
                List<Web.City> found = Web.INSTANCE.searchPlaces(q.trim(), lang, country);
                ui.post(() -> {
                    while (list.getChildCount() > 2) list.removeViewAt(2);
                    if (found.isEmpty()) note(t("city.empty"));
                    for (Web.City c : found) row("📍  " + c.getLabel(), null, false, () -> {
                        city = c.getName(); lat = c.getLat(); lon = c.getLon(); region = c.getRegion();
                        if (!c.getTimeZone().isEmpty()) timeZone = c.getTimeZone();
                        render();
                    });
                });
            });
        }, true);
    }

    void zoneStep() {
        String device = java.util.TimeZone.getDefault().getID();
        String picked = timeZone.isEmpty() ? device : timeZone;
        row("📺  " + t("tz.device").replace("%s", device), null, timeZone.isEmpty() || timeZone.equals(device), () -> { timeZone = ""; render(); });
        if (!timeZone.isEmpty() && !timeZone.equals(device)) row(timeZone.replace('_', ' ') + "  " + offset(timeZone), null, true, () -> {});
        searchField(t("search"), q -> {
            while (list.getChildCount() > (timeZone.isEmpty() || timeZone.equals(device) ? 2 : 3)) list.removeViewAt(list.getChildCount() - 1);
            String qq = q.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
            if (qq.length() < 2) return;
            int n = 0;
            List<String> zones = new ArrayList<>(ZoneId.getAvailableZoneIds()); java.util.Collections.sort(zones);
            zones.add(0, "UTC");
            for (String z : zones) {
                if ((!z.contains("/") && !z.equals("UTC")) || z.startsWith("Etc/") || z.startsWith("SystemV/") || !z.toLowerCase(Locale.ROOT).contains(qq)) continue;
                row(z.replace('_', ' ') + "  " + offset(z), null, z.equals(picked), () -> { timeZone = z; render(); });
                if (++n >= 40) break;
            }
        }, false);
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
                try { l = LocalTv.INSTANCE.arrange(Sources.loadCountries(Cfg.p(this).getString("playlist_url", ""), v.getCountries()), v.getNearRegion()); }
                catch (Exception e) { l = null; }
                List<LocalTv.Entry> got = l;
                ui.post(() -> { catalog = got; catalogFor = key; if (step == Step.FAVORITES) render(); });
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
            row((on ? "♥  " : "♡  ") + e.getName(), null, on, () -> { if (!favorites.remove(url)) favorites.add(url); refreshFavs(); });
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
            TextView r = row((i + 1) + ".  " + name + (moving == i ? "   ▲▼" : ""), null, moving == i, () -> { moving = moving == at ? -1 : at; render(); });
            r.setOnKeyListener((v, code, ev) -> {
                if (moving != at || ev.getAction() != KeyEvent.ACTION_DOWN) return false;
                int to = code == KeyEvent.KEYCODE_DPAD_UP ? at - 1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? at + 1 : -2;
                if (to == -2 || to < 0 || to >= favorites.size()) return to != -2; // swallow moves past the ends
                favorites.add(to, favorites.remove(at)); moving = to; render();
                return true;
            });
        }
    }

    void summary() {
        String[][] rows = {
                {"sum.language", OnboardingText.INSTANCE.getLANGUAGE_NAMES().get(language)},
                {"sum.country", Region.flag(country) + "  " + Region.name(country, language)},
                {"sum.city", city.isEmpty() ? t("sum.none") : city},
                {"sum.tz", timeZone.isEmpty() ? java.util.TimeZone.getDefault().getID() : timeZone},
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

    TextView row(String text, String icon, boolean selected, Runnable onClick) {
        TextView r = Ui.text(this, Ui.BODY + 2, selected ? 600 : 400, Ui.TEXT);
        r.setText((icon == null ? "" : icon + "  ") + text + (selected ? "   ✓" : ""));
        r.setPadding(dp(20), dp(14), dp(20), dp(14));
        r.setFocusable(true); r.setClickable(true); r.setTag(selected);
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(Ui.R_SMALL));
        bg.setColor(selected ? 0x2664D2FF : Ui.GLASS);
        r.setBackground(bg);
        r.setOnFocusChangeListener((v, f) -> {
            bg.setColor(f ? 0xE6F5F5F7 : selected ? 0x2664D2FF : Ui.GLASS);
            r.setTextColor(f ? 0xFF111114 : Ui.TEXT);
            r.setShadowLayer(f ? 0 : dp(10), 0, f ? 0 : dp(1), f ? 0 : 0x66000000);
        });
        r.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(8);
        list.addView(r, lp);
        return r;
    }

    void note(String text) {
        TextView n = Ui.text(this, Ui.CAPTION + 2, 400, Ui.TEXT_2);
        n.setSingleLine(false); n.setText(text); n.setPadding(dp(4), dp(8), dp(4), dp(10));
        list.addView(n);
    }

    interface OnText { void on(String q); }

    /** Search box: [live] = filter on every change; otherwise searches when OK/Done is pressed. */
    void searchField(String hint, OnText on) { searchField(hint, on, true); }
    void searchField(String hint, OnText on, boolean live) {
        EditText e = new EditText(this);
        e.setHint("🔍  " + hint); e.setHintTextColor(Ui.TEXT_3); e.setTextColor(Ui.TEXT); e.setTextSize(Ui.BODY + 2);
        e.setSingleLine(); e.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        e.setPadding(dp(20), dp(12), dp(20), dp(12));
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(Ui.R_SMALL)); bg.setColor(0x33FFFFFF); bg.setStroke(dp(1), 0x40FFFFFF);
        e.setBackground(bg);
        if (live) e.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) { on.on(s.toString()); }
        });
        else e.setOnEditorActionListener((v, id, ev) -> { on.on(e.getText().toString()); return false; });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(12);
        list.addView(e, list.getChildCount() == 0 ? 0 : list.getChildCount(), lp);
    }

    void button(String text, boolean primary, Runnable onClick) {
        TextView b = Ui.text(this, Ui.BODY + 2, 600, primary ? 0xFF111114 : Ui.TEXT);
        b.setText(text); b.setGravity(Gravity.CENTER);
        b.setPadding(dp(28), dp(12), dp(28), dp(12)); b.setFocusable(true); b.setClickable(true);
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(40));
        bg.setColor(primary ? Ui.TEXT : Ui.GLASS); b.setBackground(bg);
        b.setShadowLayer(0, 0, 0, 0);
        b.setOnFocusChangeListener((v, f) -> { v.setScaleX(f ? 1.08f : 1f); v.setScaleY(f ? 1.08f : 1f); if (!primary) bg.setColor(f ? 0x66FFFFFF : Ui.GLASS); });
        b.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.leftMargin = dp(12);
        footer.addView(b, lp);
    }
}
