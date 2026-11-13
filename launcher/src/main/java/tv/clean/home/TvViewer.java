package tv.clean.home;

import android.content.Context;
import android.content.SharedPreferences;
import tech.kousozo.tahoetv.core.Viewer;

/** The onboarding choices on the TV (#77): one Viewer JSON in the "cfg" prefs, shared rules with the phone and desktop.
 *  Saving also writes the older single settings (city, coordinates, province) that weather and prayer read. */
final class TvViewer {
    private TvViewer() {}

    static Viewer get(Context c) { return Viewer.fromJson(Cfg.p(c).getString("viewer", null)); }

    /** Done on this TV, or set up before onboarding existed (the phone already configured a city): don't ask again. */
    static boolean done(Context c) {
        SharedPreferences p = Cfg.p(c);
        return get(c).getDone() || p.contains("latitude") || p.contains("city");
    }

    static void save(Context c, Viewer v) {
        Viewer before = get(c);
        SharedPreferences.Editor e = Cfg.p(c).edit().putString("viewer", v.toJson());
        if (v.getLat() != null && v.getLon() != null) {
            e.putString("city", v.getCity()).putString("latitude", String.valueOf(v.getLat())).putString("longitude", String.valueOf(v.getLon()))
                    .putString("province", v.getRegion());
        } else e.remove("city").remove("latitude").remove("longitude").remove("province"); // no city: nothing left over
        e.apply();
        if (!prayer(c)) { // off (or no city): drop today's times and the pending adhan alarm
            Prayer.prefs(c).edit().remove("prayer2").remove("prayer2_date").apply();
            Prayer.schedule(c); // no next prayer → cancels the alarm
        }
        // new place or prayer settings: weather and prayer times are fetched again
        c.getSharedPreferences("home", Context.MODE_PRIVATE).edit().remove("weather2_at").remove("prayer2_date").apply();
        if (!before.getCountries().equals(v.getCountries())) { Live.cached = null; Live.epg = null; } // other channel countries
        else Live.cached = null; // categories / favourite order: re-arranged on the next load
        c.getSharedPreferences("live", Context.MODE_PRIVATE).edit()
                .putStringSet("fav", new java.util.HashSet<>(v.getFavorites())).apply(); // LiveTv's ★ Favorit row
    }

    /** A ★ toggled in Live TV: keep the onboarding's order, new ones last (so the next save doesn't lose it). */
    static void favoritesChanged(Context c, java.util.Set<String> favs) {
        Viewer v = get(c);
        if (!v.getDone()) return;
        java.util.List<String> order = new java.util.ArrayList<>();
        for (String u : v.getFavorites()) if (favs.contains(u)) order.add(u);
        for (String u : favs) if (!order.contains(u)) order.add(u);
        Viewer n = new Viewer(v.getLanguage(), v.getCountry(), v.getCity(), v.getLat(), v.getLon(), v.getRegion(), v.getTimeZone(), v.getPrayer(),
                v.getPrayerMethod(), v.getChannelCountries(), v.getCategories(), order);
        Cfg.p(c).edit().putString("viewer", n.toJson()).apply();
    }

    /** Prayer times shown and adhan scheduled: on unless the onboarding turned them off (older setups keep them). */
    static boolean prayer(Context c) { Viewer v = get(c); return !v.getDone() || (v.getPrayer() && v.getLat() != null); }

    /** Weather has a place to show: a city from onboarding (older setups keep theirs). */
    static boolean hasCity(Context c) { Viewer v = get(c); return !v.getDone() || v.getLat() != null; }

    /** Aladhan method: the onboarding's choice, Kemenag RI for older setups. */
    static int prayerMethod(Context c) { Viewer v = get(c); return v.getDone() ? v.getPrayerMethod() : 20; }
}
