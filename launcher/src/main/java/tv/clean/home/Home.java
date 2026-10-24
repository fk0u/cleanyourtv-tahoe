package tv.clean.home;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Tahoe TV home, tvOS 26 style: full-bleed editorial hero, top bar chips, macOS-style dock with magnification,
 *  Launchpad (games shelf + all apps), sheets (weather, prayer, control center, assistant, pairing).
 *  Focus is a model driven by our own key handling: row -1 = top bar chips, row 0 = dock, row >= 1 = Launchpad rows. */
public class Home extends Activity implements Companion.Listener, Tools {
    static Home live;

    static final class App {
        String pkg, label; Drawable banner; Intent launch; int color; boolean system, game;
    }

    final Handler ui = new Handler(Looper.getMainLooper());
    SharedPreferences prefs;
    int W, H, dockSize, cols;
    float M, tileW, tileH, gap, padTileW, padTileH, padGap, padStep;
    FrameLayout root, dockLayer, pad, padRowsView;
    Hero hero;
    View dockBand, padScrim, transition;
    TextView greeting, clock, tooltip, padTitle;
    LinearLayout chips;
    final List<LinearLayout> chipViews = new ArrayList<>();
    LinearLayout heroAction; ImageView heroActionIcon; TextView heroActionText;
    final List<Runnable> chipActions = new ArrayList<>();
    final List<App> dock = new ArrayList<>(), all = new ArrayList<>();
    final List<Tile> dockTiles = new ArrayList<>();
    final List<List<App>> padRows = new ArrayList<>();
    final List<List<Tile>> padTiles = new ArrayList<>();
    final List<Float> padRowTop = new ArrayList<>();
    // Focus rows: -2 top bar chips, -1 hero action button, 0 dock, >= 1 Launchpad rows.
    int row = 0, col = 0, lastDockCol = 0, chipCol = 0, firstPadRow = 1;
    Tile focused;
    Sheet sheet; View sheetView;
    Lock.Profiles profiles; // boot "who's watching" screen
    JSONObject weather, phoneMedia;
    List<Trending.Video> trending = new ArrayList<>();
    int trendIndex;
    Companion companion;
    boolean gamepad;

    // ---------------------------------------------------------------- lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        live = this;
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        enableAppsKey();
        dockSize = Math.max(4, Math.min(7, getResources().getInteger(R.integer.dock_size)));
        cols = Math.max(4, Math.min(7, getResources().getInteger(R.integer.columns)));
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        root.post(() -> {
            W = root.getWidth(); H = root.getHeight();
            build();
            loadApps();
            hero.showWall(false);
            refreshData(false);
            companion = new Companion(this, this);
            companion.startAll();
        });
        registerReceiver(new BroadcastReceiver() { public void onReceive(Context c, Intent i) { tick(); } }, new IntentFilter(Intent.ACTION_TIME_TICK));
        IntentFilter pf = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED); pf.addAction(Intent.ACTION_PACKAGE_CHANGED); pf.addDataScheme("package");
        registerReceiver(new BroadcastReceiver() { public void onReceive(Context c, Intent i) { ui.removeCallbacks(reload); ui.postDelayed(reload, 2000); } }, pf);
        ((InputManager) getSystemService(INPUT_SERVICE)).registerInputDeviceListener(new InputManager.InputDeviceListener() {
            public void onInputDeviceAdded(int id) { checkGamepad(); }
            public void onInputDeviceRemoved(int id) { checkGamepad(); }
            public void onInputDeviceChanged(int id) {}
        }, ui);
    }

    final Runnable reload = this::loadApps;

    @Override protected void onResume() {
        super.onResume();
        if (hero == null) return;
        tick(); checkGamepad(); markRunning();
        if (transition != null) { // coming back from an app: dissolve the launch overlay
            View t = transition; transition = null;
            t.animate().alpha(0).setDuration(Ui.MED).withEndAction(() -> root.removeView(t)).start();
            dockLayer.setTranslationY(Ui.dp(this, 24)); dockLayer.setAlpha(0);
            dockLayer.animate().translationY(0).alpha(1).setDuration(Ui.SLOW).setInterpolator(Ui.out()).start();
        }
        refreshData(false);
    }

    @Override protected void onDestroy() {
        if (companion != null) companion.stopAll();
        if (live == this) live = null;
        super.onDestroy();
    }

    /** This firmware drops accessibility services at boot; turn the Apps-button helper back on (WRITE_SECURE_SETTINGS). */
    void enableAppsKey() {
        String me = getPackageName() + "/" + Keys.class.getName();
        try {
            ContentResolver cr = getContentResolver();
            String cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (cur == null || cur.isEmpty()) cur = me; else if (!cur.contains(me)) cur = cur + ":" + me;
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, cur);
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (SecurityException ignored) {}
    }

    // ---------------------------------------------------------------- layout

    float dp(float v) { return Ui.dp(this, v); }

    FrameLayout.LayoutParams at(float x, float y, float w, float h) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w < 0 ? -2 : (int) w, h < 0 ? -2 : (int) h);
        p.leftMargin = (int) x; p.topMargin = (int) y; return p;
    }

    void build() {
        M = dp(56);
        hero = new Hero(this, W, Math.max(1, getResources().getInteger(R.integer.wallpaper_minutes)));
        root.addView(hero, new FrameLayout.LayoutParams(-1, -1));

        // Top bar: greeting left; chips + clock right.
        greeting = Ui.eyebrow(this, Ui.TEXT_2);
        root.addView(greeting, at(M, dp(Ui.SAFE_TOP + 12), W * 0.4f, -1));
        LinearLayout right = new LinearLayout(this); right.setGravity(Gravity.CENTER_VERTICAL);
        chips = new LinearLayout(this); chips.setGravity(Gravity.CENTER_VERTICAL);
        right.addView(chips);
        clock = Ui.text(this, 26, 300, Ui.TEXT);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2); cl.leftMargin = (int) dp(18);
        right.addView(clock, cl);
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        rl.topMargin = (int) dp(Ui.SAFE_TOP); rl.rightMargin = (int) M;
        root.addView(right, rl);

        heroAction = new LinearLayout(this); heroAction.setGravity(Gravity.CENTER_VERTICAL);
        heroAction.setPadding((int) dp(16), (int) dp(10), (int) dp(20), (int) dp(10));
        heroAction.setBackground(new GlassDrawable(dp(22), Ui.GLASS, dp(1)));
        heroActionIcon = Ui.icon(this, R.drawable.ic_play, 18, Ui.TEXT);
        heroActionText = Ui.text(this, 15, 600, Ui.TEXT); heroActionText.setPadding((int) dp(10), 0, 0, 0);
        heroAction.addView(heroActionIcon); heroAction.addView(heroActionText);
        LinearLayout.LayoutParams hal = new LinearLayout.LayoutParams(-2, -2); hal.topMargin = (int) dp(22);
        hero.block.addView(heroAction, hal);
        heroAction.setPivotX(0); // tvOS lift grows away from the text edge, not past it
        hero.block.setClipChildren(false); hero.setClipChildren(false);

        // Dock (macOS style): centred glass shelf, tiles magnify toward the focused one.
        tileW = dp(118); tileH = tileW * 9 / 16; gap = dp(16);
        dockLayer = new FrameLayout(this);
        dockLayer.setClipChildren(false); dockLayer.setClipToPadding(false);
        root.setClipChildren(false);
        root.addView(dockLayer, new FrameLayout.LayoutParams(-1, -1));
        dockBand = new View(this);
        dockBand.setBackground(new GlassDrawable(dp(26), 0x52141417, dp(1)));
        dockLayer.addView(dockBand, at(0, 0, 10, 10));
        tooltip = Ui.text(this, 13, 600, Ui.TEXT);
        tooltip.setPadding((int) dp(12), (int) dp(6), (int) dp(12), (int) dp(6));
        tooltip.setBackground(new GlassDrawable(dp(14), Ui.GLASS_STRONG, dp(1)));
        tooltip.setAlpha(0);
        dockLayer.addView(tooltip, new FrameLayout.LayoutParams(-2, -2));

        // Launchpad
        padScrim = new View(this);
        padScrim.setBackgroundColor(0xE6050508); padScrim.setAlpha(0);
        root.addView(padScrim, new FrameLayout.LayoutParams(-1, -1));
        pad = new FrameLayout(this); pad.setAlpha(0); pad.setClipChildren(false);
        root.addView(pad, new FrameLayout.LayoutParams(-1, -1));
        padTitle = Ui.text(this, Ui.HEADLINE, 600, Ui.TEXT);
        padTitle.setText("Aplikasi");
        pad.addView(padTitle, at(M, dp(36), -1, -1));
        padRowsView = new FrameLayout(this); padRowsView.setClipChildren(false);
        pad.addView(padRowsView, new FrameLayout.LayoutParams(-1, -1));
        padGap = dp(22);
        padTileW = (W - 2 * M - (cols - 1) * padGap) / cols; padTileH = padTileW * 9 / 16;
        padStep = padTileH + dp(46);

        // Boot: "Siapa yang menonton?" (owner with last app, or guest).
        String[] last = recentApp();
        Drawable lastIcon = null;
        if (last != null) try { ApplicationInfo ai = getPackageManager().getApplicationInfo(last[0], 0); lastIcon = ai.loadBanner(getPackageManager()); if (lastIcon == null) lastIcon = ai.loadIcon(getPackageManager()); } catch (Exception ignored) {}
        profiles = new Lock.Profiles(this, last == null ? null : last[1], lastIcon, p -> {
            prefs.edit().putString("profile", p).apply();
            Lock.Profiles v = profiles; profiles = null;
            v.unlock(() -> root.removeView(v));
            tick(); loadApps(); render(false);
        });
        root.addView(profiles, new FrameLayout.LayoutParams(-1, -1));
        profiles.start();
        buildChips();
        tick();
    }

    boolean guest() { return "guest".equals(prefs.getString("profile", "main")); }

    /** Most recently used launchable app other than us: {package, label}. */
    String[] recentApp() {
        try {
            UsageStatsManager um = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            UsageStats best = null;
            for (UsageStats s : um.queryUsageStats(UsageStatsManager.INTERVAL_WEEKLY, now - 7 * 86_400_000L, now)) {
                if (s.getPackageName().equals(getPackageName()) || s.getTotalTimeInForeground() < 30_000) continue;
                if (getPackageManager().getLeanbackLaunchIntentForPackage(s.getPackageName()) == null) continue;
                if (best == null || s.getLastTimeUsed() > best.getLastTimeUsed()) best = s;
            }
            if (best == null) return null;
            PackageManager pm = getPackageManager();
            return new String[]{best.getPackageName(), pm.getApplicationLabel(pm.getApplicationInfo(best.getPackageName(), 0)).toString()};
        } catch (Exception e) { return null; }
    }

    void buildChips() {
        chips.removeAllViews(); chipViews.clear(); chipActions.clear();
        addChip(R.drawable.ic_cloud, "Cuaca", this::openWeather);
        addChip(R.drawable.ic_moon_star, "Shalat", this::openPrayer);
        if (phoneMedia != null) addChip(R.drawable.ic_music, "", this::openControl);
        if (gamepad) addChip(R.drawable.ic_gamepad_2, null, () -> { if (row == 0) lastDockCol = col; openPad(); });
        addChip(R.drawable.ic_sparkles, "Tanya", this::openAssistant);
        addChip(R.drawable.ic_smartphone, null, this::openPair);
        // Profile photo chip opens Control Center.
        LinearLayout av = new LinearLayout(this);
        av.setPadding((int) dp(3), (int) dp(3), (int) dp(3), (int) dp(3));
        av.setBackground(new GlassDrawable(dp(20), Ui.GLASS, dp(1)));
        av.addView(Avatar.view(this, 30));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.leftMargin = (int) dp(8);
        chips.addView(av, lp); chipViews.add(av); chipActions.add(this::openControl);
        updateChips();
        chipCol = Math.min(chipCol, chipViews.size() - 1);
    }

    LinearLayout addChip(int icon, String text, Runnable action) {
        LinearLayout c = new LinearLayout(this); c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding((int) dp(12), (int) dp(8), (int) dp(text == null ? 12 : 15), (int) dp(8));
        c.setBackground(new GlassDrawable(dp(18), Ui.GLASS, dp(1)));
        ImageView i = Ui.icon(this, icon, 18, Ui.TEXT); i.setTag("i");
        c.addView(i);
        if (text != null) {
            TextView t = Ui.text(this, 14, 500, Ui.TEXT); t.setText(text); t.setTag("t");
            t.setPadding((int) dp(8), 0, 0, 0);
            c.addView(t);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.leftMargin = (int) dp(8);
        chips.addView(c, lp); chipViews.add(c); chipActions.add(action);
        return c;
    }

    void chipText(int i, int icon, String text) {
        if (i >= chipViews.size()) return;
        ImageView iv = chipViews.get(i).findViewWithTag("i");
        TextView tv = chipViews.get(i).findViewWithTag("t");
        if (iv != null && icon != 0) iv.setImageResource(icon);
        if (tv != null) tv.setText(text);
    }

    void updateChips() {
        if (chipViews.isEmpty()) return;
        JSONObject cur = weather == null ? null : weather.optJSONObject("current");
        if (cur != null) chipText(0, Weather.icon(cur.optInt("weather_code"), cur.optInt("is_day", 1) == 1), Math.round(cur.optDouble("temperature_2m")) + "°");
        Object[] n = Prayer.next(this);
        chipText(1, 0, n == null ? "Shalat" : n[0] + "  " + new SimpleDateFormat("HH.mm", Locale.getDefault()).format(new Date((Long) n[1])));
        if (phoneMedia != null) chipText(2, 0, phoneMedia.optString("title"));
    }

    // ---------------------------------------------------------------- data

    void refreshData(boolean force) {
        Weather.refresh(this, prefs, force, o -> { weather = o; updateChips(); renderHero(); }, ui);
        Prayer.fetchIfStale(this, () -> { Prayer.schedule(this); updateChips(); renderHero(); }, ui);
        trending = Trending.cached(this);
        Trending.refresh(this, () -> { trending = Trending.cached(this); renderHero(); }, ui);
    }

    void tick() {
        if (clock == null) return;
        Date now = new Date();
        boolean h24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(h24 ? "HH.mm" : "h.mm", Locale.getDefault()).format(now));
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String g = h < 4 ? "Selamat malam" : h < 11 ? "Selamat pagi" : h < 15 ? "Selamat siang" : h < 18 ? "Selamat sore" : "Selamat malam";
        String name = guest() ? "" : getString(R.string.user_name);
        greeting.setText(name.isEmpty() ? g : g + ", " + name);
        updateChips();
        if (row != 0 || focusedApp() == null) renderHero();
        if (!Prayer.today().equals(prefs.getString("prayer2_date", ""))) Prayer.fetchIfStale(this, this::updateChips, ui);
    }

    // ---------------------------------------------------------------- apps

    String dockKey() { return guest() ? "dock_guest" : "dock"; }

    List<String> dockPackages() {
        String saved = prefs.getString(dockKey(), null);
        List<String> defaults = new ArrayList<>(Arrays.asList(getResources().getStringArray(R.array.dock_apps)));
        if (saved == null) return defaults;
        List<String> l = saved.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(saved.split(",")));
        if (!guest() && !prefs.getBoolean("dock_v2", false)) { // one-time: grow an old 4-app dock with the new defaults
            for (String p : defaults) if (!l.contains(p)) l.add(p);
            prefs.edit().putBoolean("dock_v2", true).putString("dock", android.text.TextUtils.join(",", l)).apply();
        }
        return l;
    }

    void saveDock(List<String> pkgs) {
        prefs.edit().putString(dockKey(), android.text.TextUtils.join(",", pkgs)).putBoolean("dock_v2", true).apply();
        loadApps();
    }

    static final Set<String> GAME_APPS = new HashSet<>(Arrays.asList(
            "com.limelight", "com.valvesoftware.steamlink", "com.playstation.remoteplay", "com.nvidia.geforcenow",
            "io.github.streetpea.chiaking", "com.metallic.chiaki", "com.gamepass", "com.microsoft.xcloud"));

    void loadApps() {
        final String keep = focusedApp() == null ? null : focusedApp().pkg;
        Net.bg.execute(() -> {
            PackageManager pm = getPackageManager();
            Map<String, App> found = new LinkedHashMap<>();
            for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                    ActivityInfo ai = ri.activityInfo;
                    if (ai.packageName.equals(getPackageName()) || found.containsKey(ai.packageName)) continue;
                    App a = new App();
                    a.pkg = ai.packageName; a.label = String.valueOf(ri.loadLabel(pm));
                    a.system = (ai.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    a.game = GAME_APPS.contains(a.pkg) || a.label.toLowerCase(Locale.ROOT).contains("chiaki")
                            || ai.applicationInfo.category == ApplicationInfo.CATEGORY_GAME;
                    a.launch = new Intent(Intent.ACTION_MAIN).addCategory(cat).setClassName(ai.packageName, ai.name)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    Drawable bn = ai.loadBanner(pm);
                    a.banner = bn != null ? bn : Tile.iconBanner(this, ri.loadIcon(pm), a.label);
                    a.color = Ui.average(a.banner);
                    found.put(a.pkg, a);
                }
            }
            List<App> d = new ArrayList<>();
            for (String p : dockPackages()) { App a = found.get(p); if (a != null && d.size() < dockSize) d.add(a); }
            List<App> everything = new ArrayList<>(found.values());
            Collections.sort(everything, (x, y) -> x.label.compareToIgnoreCase(y.label));
            ui.post(() -> populate(d, everything, keep));
        });
    }

    void populate(List<App> d, List<App> everything, String keep) {
        for (Tile t : dockTiles) dockLayer.removeView(t);
        padRowsView.removeAllViews();
        dockTiles.clear(); padTiles.clear(); padRows.clear(); padRowTop.clear(); focused = null;
        dock.clear(); dock.addAll(d); all.clear(); all.addAll(everything);

        // Dock geometry
        float pad = dp(16), bandW = dock.size() * tileW + Math.max(0, dock.size() - 1) * gap + 2 * pad, bandH = tileH + 2 * pad;
        float bandX = (W - bandW) / 2, bandY = H - dp(30) - bandH;
        FrameLayout.LayoutParams bl = (FrameLayout.LayoutParams) dockBand.getLayoutParams();
        bl.width = (int) bandW; bl.height = (int) bandH; bl.leftMargin = (int) bandX; bl.topMargin = (int) bandY;
        dockBand.setLayoutParams(bl);
        for (int i = 0; i < dock.size(); i++) {
            Tile t = new Tile(this, dock.get(i), tileW, tileH);
            dockLayer.addView(t, at(bandX + pad + i * (tileW + gap), bandY + pad, tileW, tileH + dp(16)));
            dockTiles.add(t);
        }
        tooltip.bringToFront();

        // Launchpad rows: games shelf (if any), then everything.
        List<App> games = new ArrayList<>();
        for (App a : everything) if (a.game) games.add(a);
        float y = dp(96);
        if (!games.isEmpty()) {
            y = addSection("Game", y);
            for (int i = 0; i < games.size(); i += cols) y = addPadRow(games.subList(i, Math.min(games.size(), i + cols)), y);
            y += dp(10);
        }
        y = addSection("Semua aplikasi", y);
        for (int i = 0; i < everything.size(); i += cols) y = addPadRow(everything.subList(i, Math.min(everything.size(), i + cols)), y);
        padRowsView.getLayoutParams().height = (int) (y + dp(40));
        padRowsView.requestLayout();

        if (keep != null) {
            for (int i = 0; i < dock.size(); i++) if (dock.get(i).pkg.equals(keep) && row == 0) col = i;
        }
        clampFocus();
        markRunning();
        render(false);
    }

    float addSection(String label, float y) {
        TextView t = Ui.eyebrow(this, Ui.TEXT_3); t.setText(label);
        padRowsView.addView(t, at(M, y, -1, -1));
        return y + dp(24);
    }

    float addPadRow(List<App> apps, float y) {
        List<Tile> tiles = new ArrayList<>();
        for (int i = 0; i < apps.size(); i++) {
            Tile t = new Tile(this, apps.get(i), padTileW, padTileH);
            padRowsView.addView(t, at(M + i * (padTileW + padGap), y, padTileW, padTileH + dp(36)));
            TextView label = Ui.text(this, 13, 500, Ui.TEXT); label.setText(apps.get(i).label);
            label.setGravity(Gravity.CENTER); label.setAlpha(0); label.setTag("label");
            FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams((int) padTileW, -2); ll.topMargin = (int) (padTileH + dp(14));
            t.addView(label, ll);
            tiles.add(t);
        }
        padRows.add(new ArrayList<>(apps)); padTiles.add(tiles); padRowTop.add(y);
        return y + padStep;
    }

    /** Dots under dock apps used in the last 30 minutes (needs GET_USAGE_STATS appop). */
    void markRunning() {
        Set<String> recent = new HashSet<>();
        try {
            UsageStatsManager um = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            for (UsageStats s : um.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 30 * 60_000L, now))
                if (s.getLastTimeUsed() > now - 30 * 60_000L && s.getTotalTimeInForeground() > 0) recent.add(s.getPackageName());
        } catch (Exception ignored) {}
        for (Tile t : dockTiles) t.running(recent.contains(t.app.pkg));
    }

    void checkGamepad() {
        boolean has = false;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual()) continue;
            // The Mi remote also claims SOURCE_GAMEPAD; a real controller has a joystick and A/B buttons.
            boolean[] ab = d.hasKeys(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B);
            if ((d.getSources() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK && ab[0] && ab[1]) has = true;
        }
        if (has != gamepad && chips != null) { gamepad = has; buildChips(); render(false); }
    }

    // ---------------------------------------------------------------- focus

    int rows() { return padRows.size(); }
    int rowLen(int r) {
        if (r == -2) return chipViews.size();
        if (r == -1) return dock.isEmpty() ? 0 : 1;
        return r == 0 ? dock.size() : r >= 1 && r <= padRows.size() ? padRows.get(r - 1).size() : 0;
    }

    void clampFocus() {
        if (row > rows()) row = rows();
        if (row == 0 && dock.isEmpty()) row = rows() > 0 ? 1 : -1;
        col = Math.max(0, Math.min(col, rowLen(row) - 1));
    }

    App focusedApp() {
        if (row == 0) return col < dock.size() ? dock.get(col) : null;
        if (row >= 1 && row <= padRows.size()) { List<App> r = padRows.get(row - 1); return col < r.size() ? r.get(col) : null; }
        return null;
    }

    Tile focusedTile() {
        if (row == 0) return col < dockTiles.size() ? dockTiles.get(col) : null;
        if (row >= 1 && row <= padTiles.size()) { List<Tile> r = padTiles.get(row - 1); return col < r.size() ? r.get(col) : null; }
        return null;
    }

    void move(int dr, int dc) {
        if (dc != 0) {
            int n = col + dc;
            if (n < 0 || n >= rowLen(row)) return; // ends never wrap or fall through
            col = n;
        } else {
            int target = row + dr;
            if (target == 0 && dock.isEmpty()) target += dr;
            if (target == -1 && rowLen(-1) == 0) target += dr;
            if (target < -2 || target > rows() || rowLen(target) == 0) return;
            if (row == -2) chipCol = col;
            if (row == 0) lastDockCol = col;
            if (target == -2) col = chipCol;
            else if (target == -1) col = 0;
            else if (target == 0) col = lastDockCol;
            row = target;
            col = Math.min(col, rowLen(row) - 1);
        }
        render(true);
    }

    void openPad() { if (rows() == 0) return; if (row == 0) lastDockCol = col; row = 1; col = 0; firstPadRow = 1; render(true); }
    void toDock() { if (dock.isEmpty()) return; row = 0; col = Math.min(lastDockCol, dock.size() - 1); render(true); }

    void render(boolean animate) {
        if (hero == null) return;
        int ms = animate && Ui.motion(this) > 0 ? Ui.MED : 0;
        boolean inPad = row >= 1;

        // Chips and the hero action: tvOS focus = bright fill, dark content, slight lift.
        for (int i = 0; i < chipViews.size(); i++) focusPill(chipViews.get(i), row == -2 && col == i, ms);
        focusPill(heroAction, row == -1, ms);

        // Dock magnification: focused 1.25, neighbours 1.1, others 1.
        for (int i = 0; i < dockTiles.size(); i++) {
            int dist = row == 0 ? Math.abs(i - col) : 99;
            float s = dist == 0 ? 1.25f : dist == 1 ? 1.1f : 1f;
            Tile t = dockTiles.get(i);
            if (t.scale != s || (dist == 0) != (t == focused)) t.lift(s, dist == 0, ms);
        }
        Tile ft = focusedTile();
        if (row == 0 && ft != null) {
            tooltip.setText(ft.app.label);
            tooltip.measure(0, 0);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) ft.getLayoutParams();
            float x = lp.leftMargin + tileW / 2 - tooltip.getMeasuredWidth() / 2f, y = lp.topMargin + tileH - tileH * 1.25f - dp(40);
            tooltip.animate().alpha(1).x(x).y(y).setDuration(ms).setInterpolator(Ui.out()).start();
        } else tooltip.animate().alpha(0).setDuration(Ui.FAST).start();

        // Launchpad tiles: lift + label for the focused one.
        for (List<Tile> rr : padTiles) for (Tile t : rr) {
            boolean f = t == ft && inPad;
            if (f != (t == focused) || (f && t.scale != 1.1f) || (!f && t.scale != 1f)) {
                t.setPivotY(t.h / 2);
                t.lift(f ? 1.1f : 1f, f, ms);
                View label = t.findViewWithTag("label");
                if (label != null) label.animate().alpha(f ? 1 : 0).translationY(f ? dp(6) : 0).setDuration(ms).start();
            }
        }
        focused = ft;

        if (inPad) {
            int vis = Math.max(1, (int) ((H - dp(110)) / padStep));
            if (row < firstPadRow) firstPadRow = row;
            if (row >= firstPadRow + vis) firstPadRow = row - vis + 1;
        }
        float shift = inPad && firstPadRow > 1 ? -(padRowTop.get(firstPadRow - 1) - dp(96)) : 0;
        padScrim.animate().alpha(inPad ? 1 : 0).setDuration(ms).start();
        pad.animate().alpha(inPad ? 1 : 0).setDuration(ms).start();
        padRowsView.animate().translationY(inPad ? shift : dp(40)).setDuration(ms).setInterpolator(Ui.out()).start();
        dockLayer.animate().alpha(inPad ? 0 : 1).translationY(inPad ? dp(40) : 0).setDuration(ms).start();
        hero.block.animate().alpha(inPad ? 0 : 1).setDuration(ms).start();
        renderHero();
    }

    void focusPill(LinearLayout p, boolean f, int ms) {
        ((GlassDrawable) p.getBackground()).fill(f ? 0xF2F5F5F7 : Ui.GLASS);
        int c = f ? 0xFF111114 : Ui.TEXT;
        for (int k = 0; k < p.getChildCount(); k++) {
            View v = p.getChildAt(k);
            if (v instanceof TextView) { ((TextView) v).setTextColor(c); ((TextView) v).setShadowLayer(0, 0, 0, 0); }
            else if (v instanceof ImageView && ((ImageView) v).getImageTintList() != null) ((ImageView) v).setImageTintList(android.content.res.ColorStateList.valueOf(c));
        }
        p.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).translationZ(f ? dp(8) : 0).setDuration(ms == 0 ? 0 : Ui.FAST).start();
    }

    // ---------------------------------------------------------------- hero

    final Runnable trendTick = () -> { trendIndex++; renderHero(); };

    void renderHero() {
        if (hero == null) return;
        ui.removeCallbacks(trendTick);
        App a = row == 0 ? focusedApp() : row == -1 && !dock.isEmpty() ? dock.get(Math.min(lastDockCol, dock.size() - 1)) : null;
        heroAction.setVisibility(a == null ? View.GONE : View.VISIBLE);
        if (a != null && a.pkg.equals(Trending.YT) && !trending.isEmpty()) {
            Trending.Video v = trending.get(trendIndex % trending.size());
            hero.context(v.thumb);
            hero.text("Trending di YouTube · #" + (trendIndex % trending.size() + 1), v.title, v.channel + (v.views.isEmpty() ? "" : "  ·  " + v.views));
            heroActionIcon.setImageResource(R.drawable.ic_play); heroActionText.setText("Putar video");
            if (row == 0) ui.postDelayed(trendTick, 7000); // hold still while the button is focused
            return;
        }
        hero.context(null);
        if (a != null) {
            hero.text(a.system ? "Aplikasi sistem" : "Di dock", a.label, "Tahan OK untuk opsi");
            heroActionIcon.setImageResource(R.drawable.ic_play); heroActionText.setText("Buka " + a.label);
            return;
        }
        // Idle: editorial date + weather + next prayer (+ phone music).
        JSONObject cur = weather == null ? null : weather.optJSONObject("current");
        String date = new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(new Date());
        String w = cur == null ? "" : Math.round(cur.optDouble("temperature_2m")) + "° " + Weather.text(cur.optInt("weather_code"));
        Object[] n = Prayer.next(this);
        String p = n == null ? "" : n[0] + " dalam " + Prayer.countdown((Long) n[1]);
        String meta = (phoneMedia != null ? "♪ " + phoneMedia.optString("title") + " — " + phoneMedia.optString("artist") + "  ·  " : "") + p;
        hero.text(date + (w.isEmpty() ? "" : "  ·  " + w), getString(R.string.city), meta);
    }

    void heroActivate() {
        if (dock.isEmpty()) return;
        int i = Math.min(lastDockCol, dock.size() - 1);
        App a = dock.get(i);
        if (a.pkg.equals(Trending.YT) && !trending.isEmpty()) { start(Trending.watch(trending.get(trendIndex % trending.size()).id)); return; }
        launch(a.launch, a, dockTiles.get(i));
    }

    // ---------------------------------------------------------------- launching

    void launch(App a) { launch(a.launch, a, focusedTile()); }

    /** Tile grows to fill the screen in the app's colour, then the app fades in over it. */
    void launch(Intent intent, App a, Tile from) {
        if (from == null || Ui.motion(this) == 0) { start(intent); return; }
        int[] xy = new int[2]; from.image.getLocationInWindow(xy);
        float sw = from.w * from.scale, sh = from.h * from.scale;
        FrameLayout t = new FrameLayout(this);
        t.setBackgroundColor(a.color | 0xFF000000);
        ImageView img = new ImageView(this); img.setScaleType(ImageView.ScaleType.CENTER_CROP); img.setImageDrawable(a.banner.getConstantState() == null ? a.banner : a.banner.getConstantState().newDrawable());
        t.addView(img, new FrameLayout.LayoutParams(-1, -1));
        Ui.clip(t, dp(Ui.R_SMALL));
        root.addView(t, at(xy[0], xy[1], sw, sh));
        t.setPivotX(0); t.setPivotY(0);
        t.animate().scaleX(W / sw).scaleY(H / sh).translationX(-xy[0]).translationY(-xy[1]).setDuration(Ui.MED).setInterpolator(Ui.out()).start();
        img.animate().alpha(0.15f).setDuration(Ui.MED).start();
        transition = t;
        ui.postDelayed(() -> {
            try { startActivity(intent, ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out).toBundle()); }
            catch (Exception e) { toast("Tidak bisa dibuka"); root.removeView(t); transition = null; }
        }, Ui.MED - 40);
    }

    void start(Intent i) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception e) { toast("Tidak bisa dibuka"); }
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ---------------------------------------------------------------- sheets

    void openSheet(View v) {
        closeSheet();
        sheetView = v; sheet = (Sheet) v;
        root.addView(v, new FrameLayout.LayoutParams(-1, -1));
        v.setAlpha(0); v.setScaleX(1.03f); v.setScaleY(1.03f);
        v.animate().alpha(1).scaleX(1).scaleY(1).setDuration(Ui.MED).setInterpolator(Ui.out()).start();
    }

    void closeSheet() {
        if (sheetView == null) return;
        View v = sheetView; Sheet s = sheet;
        sheetView = null; sheet = null;
        s.onClose();
        v.animate().alpha(0).setDuration(Ui.FAST).withEndAction(() -> root.removeView(v)).start();
    }

    void openWeather() { if (weather != null) openSheet(new Weather.Panel(this, weather)); else { toast("Memuat cuaca…"); refreshData(true); } }
    void openPrayer() { openSheet(new Prayer.Panel(this)); }
    void openControl() { openSheet(new Sheets.Control(this)); }
    void openPair() { openSheet(new Sheets.Pair(this)); }
    void openAssistant() { openSheet(new Assistant(this, this)); }
    void openAppMenu() { App a = focusedApp(); if (a != null) openSheet(new Sheets.AppMenu(this, a, focusedTile())); }

    // ---------------------------------------------------------------- keys (remote, gamepad, phone)

    long lastStick;

    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK || e.getAction() != MotionEvent.ACTION_MOVE)
            return super.dispatchGenericMotionEvent(e);
        float x = e.getAxisValue(MotionEvent.AXIS_X), y = e.getAxisValue(MotionEvent.AXIS_Y);
        float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        if (Math.abs(hx) > 0.5f) x = hx; if (Math.abs(hy) > 0.5f) y = hy;
        int key = x < -0.55f ? KeyEvent.KEYCODE_DPAD_LEFT : x > 0.55f ? KeyEvent.KEYCODE_DPAD_RIGHT
                : y < -0.55f ? KeyEvent.KEYCODE_DPAD_UP : y > 0.55f ? KeyEvent.KEYCODE_DPAD_DOWN : 0;
        long now = System.currentTimeMillis();
        if (key == 0) { lastStick = 0; return true; }
        if (now - lastStick < (lastStick == 0 ? 0 : 160)) return true; // deadzone + repeat rate
        lastStick = now;
        tap(key);
        return true;
    }

    /** Synthesise a full press for keys coming from the stick or the phone. */
    void tap(int key) {
        long t = android.os.SystemClock.uptimeMillis();
        dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, key, 0));
        dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, key, 0));
    }

    static int gamepadToRemote(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_BUTTON_A: return KeyEvent.KEYCODE_DPAD_CENTER;
            case KeyEvent.KEYCODE_BUTTON_B: return KeyEvent.KEYCODE_BACK;
            default: return k;
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = gamepadToRemote(e.getKeyCode());
        if (k != e.getKeyCode()) e = new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(), k, e.getRepeatCount(), e.getMetaState());
        if (profiles != null && k != KeyEvent.KEYCODE_HOME) return profiles.key(e);
        if (sheet != null && k != KeyEvent.KEYCODE_HOME) {
            if (sheet.key(e)) return true;
            if (e.getAction() == KeyEvent.ACTION_DOWN && (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_MENU)) closeSheet();
            return true;
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            switch (k) {
                case KeyEvent.KEYCODE_BUTTON_X: openAppMenu(); return true;
                case KeyEvent.KEYCODE_BUTTON_Y: openControl(); return true;
                case KeyEvent.KEYCODE_BUTTON_START: if (row >= 1) toDock(); else openPad(); return true;
                case KeyEvent.KEYCODE_BUTTON_SELECT: openAssistant(); return true;
                case KeyEvent.KEYCODE_BUTTON_L1: if (row >= 1) { row = Math.max(1, row - 3); clampFocus(); render(true); } return true;
                case KeyEvent.KEYCODE_BUTTON_R1: if (row >= 1) { row = Math.min(rows(), row + 3); clampFocus(); render(true); } return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override public boolean onKeyDown(int k, KeyEvent e) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: move(-1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: move(0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(0, 1); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                if (e.getRepeatCount() == 0) e.startTracking(); // act on release so a hold opens the menu
                return true;
            case KeyEvent.KEYCODE_ALL_APPS: if (row >= 1) toDock(); else openPad(); return true;
            case KeyEvent.KEYCODE_MENU: openControl(); return true;
            case KeyEvent.KEYCODE_BACK: if (row != 0) toDock(); return true;
        }
        return super.onKeyDown(k, e);
    }

    @Override public boolean onKeyLongPress(int k, KeyEvent e) {
        if ((k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) && row >= 0) { openAppMenu(); return true; }
        if ((k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) && row == -1) { row = 0; col = lastDockCol; openAppMenu(); return true; }
        return super.onKeyLongPress(k, e);
    }

    @Override public boolean onKeyUp(int k, KeyEvent e) {
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
            if (e.isTracking() && !e.isCanceled() || e.getDownTime() == e.getEventTime()) { // synthetic taps have no tracking
                if (row >= 0) { App a = focusedApp(); if (a != null) launch(a); }
                else if (row == -1) heroActivate();
                else if (col < chipActions.size()) chipActions.get(col).run();
            }
            return true;
        }
        return super.onKeyUp(k, e);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        closeSheet();
        if (Intent.ACTION_ALL_APPS.equals(i.getAction())) openPad();
        else toDock(); // Home: always back to the dock
    }

    // ---------------------------------------------------------------- companion (phone)

    @Override public void onKey(String key) {
        int code;
        switch (key) {
            case "up": code = KeyEvent.KEYCODE_DPAD_UP; break;
            case "down": code = KeyEvent.KEYCODE_DPAD_DOWN; break;
            case "left": code = KeyEvent.KEYCODE_DPAD_LEFT; break;
            case "right": code = KeyEvent.KEYCODE_DPAD_RIGHT; break;
            case "ok": code = KeyEvent.KEYCODE_DPAD_CENTER; break;
            case "menu": code = KeyEvent.KEYCODE_MENU; break;
            case "apps": code = KeyEvent.KEYCODE_ALL_APPS; break;
            // phone-as-gamepad buttons
            case "a": code = KeyEvent.KEYCODE_BUTTON_A; break;
            case "b": code = KeyEvent.KEYCODE_BUTTON_B; break;
            case "x": code = KeyEvent.KEYCODE_BUTTON_X; break;
            case "y": code = KeyEvent.KEYCODE_BUTTON_Y; break;
            case "start": code = KeyEvent.KEYCODE_BUTTON_START; break;
            case "select": code = KeyEvent.KEYCODE_BUTTON_SELECT; break;
            case "l1": code = KeyEvent.KEYCODE_BUTTON_L1; break;
            case "r1": code = KeyEvent.KEYCODE_BUTTON_R1; break;
            case "back": if (!hasWindowFocus() && Keys.inst != null) { Keys.inst.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); return; }
                code = KeyEvent.KEYCODE_BACK; break;
            case "home": start(new Intent(this, Home.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)); return;
            default: return;
        }
        if (hasWindowFocus()) tap(code);
    }

    @Override public void onLaunch(String pkg) {
        for (App a : all) if (a.pkg.equals(pkg)) { start(a.launch); return; }
    }

    @Override public void onUrl(String url) {
        Uri u = Uri.parse(url);
        Intent i = new Intent(Intent.ACTION_VIEW, u);
        String host = u.getHost() == null ? "" : u.getHost();
        if (host.contains("youtube.com") || host.contains("youtu.be")) i.setPackage(Trending.YT);
        start(i);
    }

    @Override public void onAsk(String text) {
        if (!(sheet instanceof Assistant)) openAssistant();
        if (sheet instanceof Assistant) ((Assistant) sheet).ask(text);
    }

    @Override public void onVolume(int dir) {
        ((AudioManager) getSystemService(AUDIO_SERVICE)).adjustSuggestedStreamVolume(dir > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                AudioManager.STREAM_MUSIC, AudioManager.FLAG_SHOW_UI);
    }

    @Override public void onPhoneNotification(String app, String title, String text) {
        Notif.banner(this, null, app.isEmpty() ? "HP" : app, title, text);
    }

    @Override public void onPhoneMedia(JSONObject media) {
        boolean had = phoneMedia != null;
        phoneMedia = media;
        if (had != (media != null)) buildChips(); else updateChips();
        renderHero();
    }

    @Override public void onUploaded(String kind) {
        if (kind.equals("photo") || kind.equals("wallpaper")) { hero.loadWalls(); toast("Foto diterima dari HP"); }
        else toast("Suara adzan disimpan");
    }

    @Override public JSONArray apps() {
        JSONArray out = new JSONArray();
        try { for (App a : all) out.put(new JSONObject().put("pkg", a.pkg).put("label", a.label).put("dock", dock.contains(a))); } catch (Exception ignored) {}
        return out;
    }

    void phoneMediaCommand(String c) {
        try { companion.broadcast(new JSONObject().put("t", "media_cmd").put("c", c)); } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- assistant tools

    @Override public String call(String name, JSONObject args) {
        try {
            switch (name) {
                case "open_app": {
                    String q = args.optString("name").toLowerCase(Locale.ROOT);
                    for (App a : all) if (a.label.toLowerCase(Locale.ROOT).contains(q)) { ui.postDelayed(() -> { closeSheet(); start(a.launch); }, 1800); return "Membuka " + a.label; }
                    return "Aplikasi tidak ditemukan";
                }
                case "youtube_search": {
                    Intent i = new Intent(Intent.ACTION_SEARCH).setPackage(Trending.YT).putExtra("query", args.optString("query"));
                    ui.postDelayed(() -> { closeSheet(); start(i); }, 1800);
                    return "Mencari " + args.optString("query") + " di YouTube";
                }
                case "weather": {
                    JSONObject c = weather == null ? null : weather.optJSONObject("current");
                    JSONObject d = weather == null ? null : weather.optJSONObject("daily");
                    if (c == null) return "Data cuaca belum ada";
                    return Math.round(c.optDouble("temperature_2m")) + "°C, " + Weather.text(c.optInt("weather_code"))
                            + (d == null ? "" : ", maks " + Math.round(d.optJSONArray("temperature_2m_max").optDouble(0)) + "°, peluang hujan "
                            + d.optJSONArray("precipitation_probability_max").optInt(0) + "%");
                }
                case "prayer_times": {
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<String, Long> en : Prayer.times(this).entrySet())
                        sb.append(en.getKey()).append(' ').append(new SimpleDateFormat("HH:mm", Locale.US).format(new Date(en.getValue()))).append(", ");
                    Object[] n = Prayer.next(this);
                    return sb + (n == null ? "" : "berikutnya " + n[0] + " dalam " + Prayer.countdown((Long) n[1]));
                }
                case "set_volume": {
                    AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                    String lv = args.optString("level");
                    if (lv.contains("naik")) onVolume(1); else if (lv.contains("turun")) onVolume(-1);
                    else am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(Integer.parseInt(lv.replaceAll("\\D", "")) / 100f * am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), AudioManager.FLAG_SHOW_UI);
                    return "Volume diatur";
                }
                case "next_wallpaper": hero.nextWall(); return "Wallpaper diganti";
            }
        } catch (Exception e) { return "Gagal: " + e.getMessage(); }
        return "Tool tidak dikenal";
    }
}
