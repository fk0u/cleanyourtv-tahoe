package tv.clean.home;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import java.util.*;

/** App scanning, dock persistence, Kids app tracking, and recent-app inspection extracted from [Home]. */
final class HomeDock {
    private HomeDock() {}

    static final Set<String> GAME_APPS = new HashSet<>(Arrays.asList(
            "com.limelight", "com.valvesoftware.steamlink", "com.playstation.remoteplay", "com.nvidia.geforcenow",
            "io.github.streetpea.chiaking", "com.metallic.chiaki", "com.gamepass", "com.microsoft.xcloud"));

    static String dockKey(Home h) { return h.guest() ? "dock_guest" : "dock"; }

    static int dockSlotCap(Home h) {
        return Math.max(3, Math.min(12, h.prefs.getInt("dock_slots", h.dockSize)));
    }

    static List<String> dockPackages(Home h) {
        if (h.kids()) return new ArrayList<>(Kids.apps(h));
        String saved = h.prefs.getString(dockKey(h), null);
        List<String> defaults = new ArrayList<>(Arrays.asList(h.getResources().getStringArray(R.array.dock_apps)));
        defaults.add(0, LiveTv.PKG);
        if (saved == null) return defaults;
        List<String> l = saved.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(saved.split(",")));
        if (!h.guest() && !h.prefs.getBoolean("dock_v2", false)) {
            for (String p : defaults) if (!l.contains(p)) l.add(p);
            h.prefs.edit().putBoolean("dock_v2", true).putString("dock", TextUtils.join(",", l)).apply();
        }
        if (!h.guest() && !h.prefs.getBoolean("dock_live", false)) {
            l.remove(LiveTv.PKG); l.add(0, LiveTv.PKG);
            h.prefs.edit().putBoolean("dock_live", true).putString("dock", TextUtils.join(",", l)).apply();
        }
        return l;
    }

    static void saveDock(Home h, List<String> pkgs) {
        h.prefs.edit().putString(dockKey(h), TextUtils.join(",", pkgs)).putBoolean("dock_v2", true).apply();
        loadApps(h);
    }

    static void loadApps(Home h) {
        final String keep = h.focusedApp() == null ? null : h.focusedApp().pkg;
        Net.local.execute(() -> {
            PackageManager pm = h.getPackageManager();
            Map<String, Home.App> found = new LinkedHashMap<>();
            for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                    ActivityInfo ai = ri.activityInfo;
                    if (ai.packageName.equals(h.getPackageName()) || found.containsKey(ai.packageName)) continue;
                    Home.App a = new Home.App();
                    a.pkg = ai.packageName; a.label = String.valueOf(ri.loadLabel(pm));
                    a.system = (ai.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
                    a.game = GAME_APPS.contains(a.pkg) || a.label.toLowerCase(Locale.ROOT).contains("chiaki")
                            || ai.applicationInfo.category == android.content.pm.ApplicationInfo.CATEGORY_GAME;
                    a.launch = new Intent(Intent.ACTION_MAIN).addCategory(cat).setClassName(ai.packageName, ai.name)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    Drawable bn = ai.loadBanner(pm);
                    a.banner = bn != null ? bn : Tile.iconBanner(h, ri.loadIcon(pm), a.label);
                    a.color = Ui.average(a.banner);
                    found.put(a.pkg, a);
                }
            }
            Home.App live = new Home.App();
            live.pkg = LiveTv.PKG; live.label = "Tahoe Live"; live.system = true;
            live.launch = new Intent(h, LiveTv.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            live.banner = LiveTv.banner(h); live.color = Ui.average(live.banner);
            found.put(live.pkg, live);
            if (h.kids()) found.keySet().retainAll(Kids.apps(h));
            int cap = dockSlotCap(h);
            List<Home.App> d = new ArrayList<>();
            for (String p : dockPackages(h)) { Home.App a = found.get(p); if (a != null && d.size() < cap) d.add(a); }
            List<Home.App> everything = new ArrayList<>(found.values());
            Collections.sort(everything, (x, y) -> x.label.compareToIgnoreCase(y.label));
            h.ui.post(() -> h.populate(d, everything, keep));
        });
    }

    static void markRunning(Home h) {
        Set<String> recent = new HashSet<>();
        try {
            UsageStatsManager um = (UsageStatsManager) h.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            for (UsageStats s : um.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 30 * 60_000L, now))
                if (s.getLastTimeUsed() > now - 30 * 60_000L && s.getTotalTimeInForeground() > 0) recent.add(s.getPackageName());
        } catch (Exception ignored) {}
        for (Tile t : h.dockTiles) t.running(recent.contains(t.app.pkg));
    }

    static String[] recentApp(Home h) {
        try {
            UsageStatsManager um = (UsageStatsManager) h.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            UsageStats best = null;
            for (UsageStats s : um.queryUsageStats(UsageStatsManager.INTERVAL_WEEKLY, now - 7 * 86_400_000L, now)) {
                if (s.getPackageName().equals(h.getPackageName()) || s.getTotalTimeInForeground() < 30_000) continue;
                if (h.getPackageManager().getLeanbackLaunchIntentForPackage(s.getPackageName()) == null) continue;
                if (best == null || s.getLastTimeUsed() > best.getLastTimeUsed()) best = s;
            }
            if (best == null) return null;
            PackageManager pm = h.getPackageManager();
            return new String[]{best.getPackageName(), pm.getApplicationLabel(pm.getApplicationInfo(best.getPackageName(), 0)).toString()};
        } catch (Exception e) { return null; }
    }

    static void flushKidsAppUsage(Home h) {
        if (h.launchedKidsAppLabel != null && h.launchedKidsAtMs > 0L) {
            long sec = (System.currentTimeMillis() - h.launchedKidsAtMs) / 1000L;
            if (sec >= 15L && h.kids()) {
                Kids.addAppSeconds(h, h.launchedKidsAppLabel, (int) Math.min(sec, 3600L));
            }
        }
        h.launchedKidsAppLabel = null;
        h.launchedKidsAtMs = 0L;
    }
}
