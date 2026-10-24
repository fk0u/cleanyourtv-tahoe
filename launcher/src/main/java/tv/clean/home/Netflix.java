package tv.clean.home;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** "Populer di Netflix (Indonesia)" for the hero when the Netflix tile is focused.
 *  Netflix has no public API, so this uses TMDB's watch-provider data (provider 8 = Netflix); needs tmdb.apiKey. */
final class Netflix {
    static final String PKG = "com.netflix.ninja";
    static final long TTL = 6 * 60 * 60_000L;

    static boolean enabled() { return !BuildConfig.TMDB_API_KEY.isEmpty(); }

    static List<Trending.Video> cached(Context c) {
        List<Trending.Video> out = new ArrayList<>();
        String js = c.getSharedPreferences("home", Context.MODE_PRIVATE).getString("nf", null);
        if (js == null) return out;
        try {
            JSONArray r = new JSONObject(js).getJSONArray("results");
            File dir = new File(c.getCacheDir(), "nf");
            for (int i = 0; i < r.length(); i++) {
                JSONObject m = r.getJSONObject(i);
                Trending.Video v = new Trending.Video();
                v.id = String.valueOf(m.optLong("id"));
                v.title = m.optString("title", m.optString("name"));
                String year = m.optString("release_date", m.optString("first_air_date", ""));
                v.channel = (year.length() >= 4 ? year.substring(0, 4) + "  ·  " : "") + "★ " + String.format(java.util.Locale.US, "%.1f", m.optDouble("vote_average"));
                v.views = m.optString("overview");
                v.thumb = new File(dir, v.id + ".jpg");
                if (v.thumb.exists()) out.add(v);
            }
        } catch (Exception ignored) {}
        return out;
    }

    static void refresh(Context c, Runnable done, android.os.Handler ui) {
        if (!enabled()) return;
        SharedPreferences p = c.getSharedPreferences("home", Context.MODE_PRIVATE);
        if (System.currentTimeMillis() - p.getLong("nf_at", 0) < TTL && !cached(c).isEmpty()) return;
        Net.bg.execute(() -> {
            try {
                String js = Net.get("https://api.themoviedb.org/3/discover/movie?api_key=" + BuildConfig.TMDB_API_KEY
                        + "&with_watch_providers=8&watch_region=ID&sort_by=popularity.desc&language=id-ID&page=1");
                JSONArray r = new JSONObject(js).getJSONArray("results");
                File dir = new File(c.getCacheDir(), "nf"); dir.mkdirs();
                for (File old : dir.listFiles()) old.delete();
                for (int i = 0; i < Math.min(8, r.length()); i++) {
                    JSONObject m = r.getJSONObject(i);
                    String bp = m.optString("backdrop_path", "");
                    if (bp.isEmpty() || bp.equals("null")) continue;
                    try { Net.download("https://image.tmdb.org/t/p/w1280" + bp, new File(dir, m.optLong("id") + ".jpg")); } catch (Exception ignored) {}
                }
                p.edit().putString("nf", js).putLong("nf_at", System.currentTimeMillis()).apply();
                ui.post(done);
            } catch (Exception ignored) {}
        });
    }
}
