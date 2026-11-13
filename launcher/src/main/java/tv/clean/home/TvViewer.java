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
        }
        e.apply();
        // new place or prayer settings: weather and prayer times are fetched again
        c.getSharedPreferences("home", Context.MODE_PRIVATE).edit().remove("weather2_at").remove("prayer2_date").apply();
        if (!before.getCountries().equals(v.getCountries())) { Live.cached = null; Live.epg = null; } // other channel countries
        else Live.cached = null; // categories / favourite order: re-arranged on the next load
        c.getSharedPreferences("live", Context.MODE_PRIVATE).edit()
                .putStringSet("fav", new java.util.HashSet<>(v.getFavorites())).apply(); // LiveTv's ★ Favorit row
    }

    /** Prayer times shown and adhan scheduled: on unless the onboarding turned them off (older setups keep them). */
    static boolean prayer(Context c) { Viewer v = get(c); return !v.getDone() || v.getPrayer(); }

    /** Aladhan method: the onboarding's choice, Kemenag RI for older setups. */
    static int prayerMethod(Context c) { Viewer v = get(c); return v.getDone() ? v.getPrayerMethod() : 20; }
}
