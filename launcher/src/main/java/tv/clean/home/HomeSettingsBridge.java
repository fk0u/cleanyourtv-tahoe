package tv.clean.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;
import tech.kousozo.tahoetv.core.*;

/** Maps TV Launcher state to/from [TvSettings.Snapshot] and enforces Kids screen-time policy for [Home]. */
final class HomeSettingsBridge {
    private HomeSettingsBridge() {}

    static String tvName(Home h) {
        String n = h.prefs.getString("tv_name", "Tahoe TV");
        return (n == null || n.trim().isEmpty()) ? "Tahoe TV" : n.trim();
    }

    static boolean enforceKidsAllowance(Home h) {
        if (!h.kids()) return true;
        KidsPolicy.EvalResult ev = Kids.evaluate(h);
        if (!ev.getAllowed()) {
            if (!(h.sheetView instanceof ParentalSheets.KidsBlocked)) h.openSheet(new ParentalSheets.KidsBlocked(h, ev));
            return false;
        }
        if (ev.getWarnFiveMinutes()) {
            Kids.markWarned(h);
            String msg = I18n.get(TvViewer.get(h).getLanguage(), "kids.warn_5m");
            h.toast(msg);
            Notif.banner(h, null, "Tahoe TV", msg, "");
        }
        return true;
    }

    static JSONObject snapshot(Home h) {
        SharedPreferences liveP = h.getSharedPreferences("live", Context.MODE_PRIVATE);
        SharedPreferences cfgP = Cfg.p(h);
        Viewer vw = TvViewer.get(h);
        DeviceProfile.Profile prof = DeviceProfiler.current(h);
        List<String> dockPkgs = HomeDock.dockPackages(h);
        Set<String> dockSet = new HashSet<>(dockPkgs);
        Set<String> kidsAppSet = Kids.apps(h);
        List<TvSettings.AppItem> appItems = new ArrayList<>();
        for (Home.App a : h.all) {
            appItems.add(new TvSettings.AppItem(a.pkg, a.label, dockSet.contains(a.pkg), kidsAppSet.contains(a.pkg)));
        }
        String perfTier = prof.getManualOverride() == null ? "auto" : prof.getManualOverride().getKey();
        int rotateMin = Math.max(1, h.prefs.getInt("hero_rotate_min", Math.max(1, h.getResources().getInteger(R.integer.wallpaper_minutes))));
        TvSettings.Snapshot snap = new TvSettings.Snapshot(
                cfgP.getString("user_name", ""),
                tvName(h),
                vw.getLanguage(),
                TvViewer.simpleMode(h),
                vw.getSortMode(),
                perfTier,
                PlayPrefs.SubtitleSize.of(liveP.getString("sub_size", null)).getKey(),
                h.prefs.getString("wall_mode", "personal"),
                WallpaperProvider.Source.fromKey(h.prefs.getString("wall_source", "auto")).getKey(),
                WallpaperProvider.Theme.fromKey(h.prefs.getString("wall_theme", "nature")).getKey(),
                rotateMin,
                h.prefs.getBoolean("dream_enabled", true),
                HomeDock.dockSlotCap(h),
                dockPkgs,
                appItems,
                cfgP.getString("playlist_url", ""),
                cfgP.getString("epg_url", ""),
                cfgP.getString("ott_server_url", tech.kousozo.tahoetv.core.OttEngine.DEFAULT_PRIMARY_OTT_URL),
                cfgP.getString("ott_user_agent", tech.kousozo.tahoetv.core.OttEngine.DEFAULT_OTT_UA),
                h.sleepLeft(),
                Kids.gateMode(h).getKey(),
                Kids.mathDifficulty(h).getKey(),
                new ArrayList<>(kidsAppSet),
                Kids.policy(h),
                !cfgP.getString("gemini_key", "").isEmpty(),
                !cfgP.getString("youtube_key", "").isEmpty(),
                !cfgP.getString("tmdb_key", "").isEmpty(),
                !cfgP.getString("ha_url", "").isEmpty(),
                !cfgP.getString("unsplash_key", "").isEmpty(),
                !cfgP.getString("pexels_key", "").isEmpty()
        );
        return snap.toJson();
    }

    static JSONObject apply(Home h, JSONObject p) {
        if (p == null) return snapshot(h);
        SharedPreferences.Editor he = h.prefs.edit();
        SharedPreferences.Editor ce = Cfg.p(h).edit();
        SharedPreferences.Editor le = h.getSharedPreferences("live", Context.MODE_PRIVATE).edit();
        boolean reloadApps = applyGeneral(h, p, he, ce, le);
        boolean reloadHero = p.has("wallMode") || p.has("wallSource") || p.has("wallTheme") || p.has("wallRotateMinutes");
        if (applyKids(h, p, he)) reloadApps = true;
        applyKeys(p, ce);
        he.apply(); ce.apply(); le.apply();

        applyViewerChanges(h, p);
        if (p.has("dockPackages") && !h.kids()) {
            JSONArray arr = p.optJSONArray("dockPackages");
            if (arr != null) {
                List<String> dp = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    String pkg = arr.optString(i).trim();
                    if (!pkg.isEmpty() && !dp.contains(pkg)) dp.add(pkg);
                }
                HomeDock.saveDock(h, dp);
                reloadApps = false;
            }
        }

        if (reloadHero && h.hero != null) h.hero.refreshWallpaperSettings();
        if (p.has("simpleMode")) h.applySimpleMode();
        else if (reloadApps) HomeDock.loadApps(h);
        h.buildChips();
        h.tick();
        h.pushState();
        h.toast(I18n.get(TvViewer.get(h).getLanguage(), "tvset.saved"));
        return snapshot(h);
    }

    private static boolean applyGeneral(Home h, JSONObject p, SharedPreferences.Editor he, SharedPreferences.Editor ce, SharedPreferences.Editor le) {
        boolean reloadApps = false;
        if (p.has("userName")) ce.putString("user_name", p.optString("userName", "").trim());
        if (p.has("tvName")) {
            String tn = p.optString("tvName", "Tahoe TV").trim();
            he.putString("tv_name", tn.isEmpty() ? "Tahoe TV" : tn);
        }
        if (p.has("wallMode")) he.putString("wall_mode", "default".equals(p.optString("wallMode")) ? "default" : "personal");
        if (p.has("wallSource")) he.putString("wall_source", WallpaperProvider.Source.fromKey(p.optString("wallSource", "auto")).getKey());
        if (p.has("wallTheme")) he.putString("wall_theme", WallpaperProvider.Theme.fromKey(p.optString("wallTheme", "nature")).getKey());
        if (p.has("wallRotateMinutes")) he.putInt("hero_rotate_min", Math.max(1, Math.min(120, p.optInt("wallRotateMinutes", 5))));
        if (p.has("screensaverEnabled")) he.putBoolean("dream_enabled", p.optBoolean("screensaverEnabled", true));
        if (p.has("dockSlots")) { he.putInt("dock_slots", Math.max(3, Math.min(12, p.optInt("dockSlots", 6)))); reloadApps = true; }
        if (p.has("subtitleSize")) le.putString("sub_size", PlayPrefs.SubtitleSize.of(p.optString("subtitleSize")).getKey());
        if (p.has("performanceTier")) {
            String t = p.optString("performanceTier", "auto").trim();
            DeviceProfile.Tier manual = "auto".equalsIgnoreCase(t) ? null : DeviceProfile.Tier.of(t);
            DeviceProfiler.setOverrideTier(h, manual);
        }
        if (p.has("playlistUrl")) { ce.putString("playlist_url", p.optString("playlistUrl", "").trim()); Live.cachedAt = 0L; }
        if (p.has("epgUrl")) { ce.putString("epg_url", p.optString("epgUrl", "").trim()); Live.cachedAt = 0L; }
        if (p.has("ottServerUrl")) {
            String u = p.optString("ottServerUrl", "").trim();
            ce.putString("ott_server_url", u);
            tech.kousozo.tahoetv.core.OttEngine.setPrimaryServer(u);
            Live.cachedAt = 0L;
        }
        if (p.has("ottUserAgent")) {
            String ua = p.optString("ottUserAgent", "").trim();
            ce.putString("ott_user_agent", ua);
            tech.kousozo.tahoetv.core.OttEngine.setPrimaryServer(Cfg.p(h).getString("ott_server_url", ""), ua, null);
            Live.cachedAt = 0L;
        }
        if (p.has("sleepMinutes")) h.remote.onSleep(p.optInt("sleepMinutes", 0));
        return reloadApps;
    }

    private static boolean applyKids(Home h, JSONObject p, SharedPreferences.Editor he) {
        boolean reloadApps = false;
        if (p.has("kidsGateMode")) Kids.setGateMode(h, MathGate.Mode.of(p.optString("kidsGateMode")));
        if (p.has("kidsMathDifficulty")) Kids.setMathDifficulty(h, MathGate.Difficulty.of(p.optString("kidsMathDifficulty")));
        if (p.has("kidsApps")) {
            JSONArray arr = p.optJSONArray("kidsApps");
            if (arr != null) {
                List<String> ka = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    String pkg = arr.optString(i).trim();
                    if (!pkg.isEmpty()) ka.add(pkg);
                }
                he.putString("kids_apps", TextUtils.join(",", ka));
                reloadApps = true;
            }
        }
        if (p.has("kidsPolicy")) Kids.setPolicy(h, KidsPolicy.Config.fromJson(p.optJSONObject("kidsPolicy")));
        if (p.has("kidsBonusMinutes")) {
            int mins = p.optInt("kidsBonusMinutes", 0);
            if (mins > 0) Kids.grantBonusMinutes(h, mins);
        }
        return reloadApps;
    }

    private static void applyKeys(JSONObject p, SharedPreferences.Editor ce) {
        String[] pairs = {
            "geminiKey", "gemini_key",
            "youtubeKey", "youtube_key",
            "tmdbKey", "tmdb_key",
            "haUrl", "ha_url",
            "haToken", "ha_token",
            "unsplashKey", "unsplash_key",
            "pexelsKey", "pexels_key"
        };
        for (int i = 0; i < pairs.length; i += 2) {
            String val = p.optString(pairs[i], "").trim();
            if (!val.isEmpty()) ce.putString(pairs[i + 1], val);
        }
    }

    private static void applyViewerChanges(Home h, JSONObject p) {
        if (!p.has("language") && !p.has("simpleMode") && !p.has("sortMode")) return;
        Viewer cur = TvViewer.get(h);
        String nextLang = p.has("language") ? Region.language(p.optString("language")) : cur.getLanguage();
        boolean nextSimple = p.has("simpleMode") ? p.optBoolean("simpleMode", false) : cur.getSimpleMode();
        String nextSort = p.has("sortMode") ? LocalTv.SortMode.fromKey(p.optString("sortMode")).getKey() : cur.getSortMode();
        TvViewer.save(h, new Viewer(
                nextLang, cur.getCountry(), cur.getCity(), cur.getLat(), cur.getLon(),
                cur.getRegion(), cur.getTimeZone(), cur.getPrayer(), cur.getPrayerMethod(),
                cur.getChannelCountries(), cur.getCategories(), cur.getFavorites(),
                cur.getSchema(), cur.getRevision(), nextSimple, nextSort
        ));
    }
}
