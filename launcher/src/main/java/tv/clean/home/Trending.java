package tv.clean.home;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** YouTube "Top Shelf": trending videos in Indonesia for the hero when the YouTube tile is focused.
 *  Needs youtube.apiKey (1 quota unit per refresh); refreshed at most hourly, thumbnails cached on disk. */
final class Trending {
    static final String YT = "com.google.android.youtube.tv";
    static final long TTL = 60 * 60_000L;

    static final class Video { String id, title, channel, views; File thumb; }

    static boolean enabled() { return !BuildConfig.YOUTUBE_API_KEY.isEmpty(); }

    static List<Video> cached(Context c) {
        List<Video> out = new ArrayList<>();
        String js = c.getSharedPreferences("home", Context.MODE_PRIVATE).getString("yt_trending", null);
        if (js == null) return out;
        try {
            JSONArray items = new JSONObject(js).getJSONArray("items");
            File dir = new File(c.getCacheDir(), "yt");
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i), sn = it.getJSONObject("snippet");
                Video v = new Video();
                v.id = it.getString("id"); v.title = sn.getString("title"); v.channel = sn.getString("channelTitle");
                long views = it.optJSONObject("statistics") == null ? 0 : it.getJSONObject("statistics").optLong("viewCount");
                v.views = views >= 1_000_000 ? String.format(java.util.Locale.US, "%.1f jt x ditonton", views / 1e6)
                        : views >= 1000 ? (views / 1000) + " rb x ditonton" : "";
                v.thumb = new File(dir, v.id + ".jpg");
                if (v.thumb.exists()) out.add(v);
            }
        } catch (Exception ignored) {}
        return out;
    }

    static void refresh(Context c, Runnable done, android.os.Handler ui) {
        if (!enabled()) return;
        SharedPreferences p = c.getSharedPreferences("home", Context.MODE_PRIVATE);
        if (System.currentTimeMillis() - p.getLong("yt_at", 0) < TTL && !cached(c).isEmpty()) return;
        Net.bg.execute(() -> {
            try {
                String js = Net.get("https://www.googleapis.com/youtube/v3/videos?part=snippet,statistics&chart=mostPopular&regionCode=ID&maxResults=8&key="
                        + BuildConfig.YOUTUBE_API_KEY);
                JSONArray items = new JSONObject(js).getJSONArray("items");
                File dir = new File(c.getCacheDir(), "yt"); dir.mkdirs();
                for (File old : dir.listFiles()) old.delete();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.getJSONObject(i), th = it.getJSONObject("snippet").getJSONObject("thumbnails");
                    JSONObject best = th.optJSONObject("maxres") != null ? th.getJSONObject("maxres") : th.optJSONObject("high");
                    if (best != null) try { Net.download(best.getString("url"), new File(dir, it.getString("id") + ".jpg")); } catch (Exception ignored) {}
                }
                p.edit().putString("yt_trending", js).putLong("yt_at", System.currentTimeMillis()).apply();
                ui.post(done);
            } catch (Exception ignored) {}
        });
    }

    static android.content.Intent watch(String id) {
        return new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=" + id))
                .setPackage(YT).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
