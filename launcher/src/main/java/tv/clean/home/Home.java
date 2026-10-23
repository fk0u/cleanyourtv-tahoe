package tv.clean.home;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.media.AudioManager;
import android.net.*;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.*;
import android.view.animation.*;
import android.widget.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** macOS Tahoe style TV home: rotating wallpaper, menu bar, glass widgets (clock, weather, prayer times,
 *  banners), glass dock, Launchpad grid and Control Center.
 *  Focus is a model (row, col) driven by our own key handling:
 *  row -1 = menu bar items, row 0 = dock, row >= 1 = Launchpad rows. */
public class Home extends Activity {
    static Home live;
    static class App { String pkg, label; Drawable banner; Intent launch; int glow; boolean system; }
    interface Adjust { void by(int dir); }
    static class Item { String text; Runnable enter; Adjust adjust; Item(String t, Runnable e, Adjust a) { text = t; enter = e; adjust = a; } }

    final Handler ui = new Handler(Looper.getMainLooper());
    final ExecutorService bg = Executors.newSingleThreadExecutor();
    SharedPreferences prefs;
    int cols, W, H, wpMinutes;
    float tileW, tileH, M, G, PAD = 18, step;
    FrameLayout root, desk, pad;
    ImageView wpBack, wpFront;
    View padScrim, glow, band, deskHi, padHi;
    TextView menuApp, menuWeather, menuWifi, menuClock, greeting, clock, date, title;
    TextView wTemp, wDesc, wRange;
    LinearLayout prayerRows;
    TextView prayerNext;
    final TextView[] menuItems = new TextView[1]; // Control Center (notification access is blocked on low-RAM firmware)
    final List<App> dock = new ArrayList<>(), grid = new ArrayList<>();
    final List<FrameLayout> dockTiles = new ArrayList<>(), gridTiles = new ArrayList<>();
    int row = 0, col = 0, lastDockCol = 0, menuCol = 0, firstRow = 1;
    View lastTile;
    // Modal panel (Notification Center / Control Center)
    LinearLayout panel; final List<Item> panelItems = new ArrayList<>(); int panelSel; Runnable panelRefresh;
    List<String> wallpapers = new ArrayList<>(); int wpIndex;
    Lock.LockView lock; // boot lock screen, dismissed with OK

    // ---- shared style helpers (Notif uses them too) ----

    static final SparseArray<Typeface> fonts = new SparseArray<>();
    static Typeface font(Context c, int wght) {
        Typeface t = fonts.get(wght);
        if (t == null) {
            try { t = new Typeface.Builder(c.getAssets(), "Outfit.ttf").setFontVariationSettings("'wght' " + wght).build(); }
            catch (Exception e) { t = Typeface.DEFAULT; }
            fonts.put(wght, t);
        }
        return t;
    }

    /** "Liquid glass" without blur: tinted fill, a sheen across the top half, a light rim. */
    static Drawable glassOf(Context c, float radius, int fill) {
        float d = c.getResources().getDisplayMetrics().density;
        GradientDrawable base = new GradientDrawable();
        base.setCornerRadius(radius); base.setColor(fill); base.setStroke(Math.max(1, (int) d), 0x40FFFFFF);
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x1FFFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        sheen.setCornerRadius(radius);
        return new LayerDrawable(new Drawable[]{base, sheen});
    }

    Drawable glass(float radius, int fill) { return glassOf(this, radius, fill); }
    float dp(float v) { return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    TextView text(float sp, int wght, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp); t.setTypeface(font(this, wght)); t.setTextColor(color); t.setSingleLine();
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setShadowLayer(dp(8), 0, dp(1), 0x80000000);
        return t;
    }

    FrameLayout.LayoutParams at(float x, float y, float w, float h) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w < 0 ? -2 : (int) w, h < 0 ? -2 : (int) h);
        p.leftMargin = (int) x; p.topMargin = (int) y; return p;
    }

    // ---- lifecycle ----

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        live = this;
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        enableAppsKey();
        cols = getResources().getInteger(R.integer.columns);
        wpMinutes = Math.max(1, getResources().getInteger(R.integer.wallpaper_minutes));
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        root.post(() -> { buildStatic(); updateWifi(); loadApps(); loadWallpapers(); fetchWeather(); fetchPrayer(); schedulePrayer(); });

        registerReceiver(new BroadcastReceiver() { public void onReceive(Context c, Intent i) { tick(); } },
                new IntentFilter(Intent.ACTION_TIME_TICK));
        IntentFilter pf = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED); pf.addAction(Intent.ACTION_PACKAGE_CHANGED); pf.addDataScheme("package");
        registerReceiver(new BroadcastReceiver() { public void onReceive(Context c, Intent i) {
            // Play Store updates fire bursts of these: rebuild once, after the burst.
            ui.removeCallbacks(reload); ui.postDelayed(reload, 2000);
        } }, pf);
        ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            public void onAvailable(Network n) { ui.post(() -> { updateWifi(); fetchWeather(); fetchPrayer(); }); }
            public void onLost(Network n) { ui.post(Home.this::updateWifi); }
        });
    }

    final Runnable reload = this::loadApps;

    /** This firmware drops enabled accessibility services at boot; turn the Apps-button helper back on.
     *  Needs `pm grant tv.clean.home android.permission.WRITE_SECURE_SETTINGS` (done once over ADB). */
    void enableAppsKey() {
        String me = getPackageName() + "/" + Keys.class.getName();
        try {
            ContentResolver cr = getContentResolver();
            String cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (cur == null || cur.isEmpty()) cur = me; else if (!cur.contains(me)) cur = cur + ":" + me;
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, cur);
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (SecurityException ignored) {} // permission not granted: the Apps button just stays inert
    }

    @Override protected void onResume() {
        super.onResume();
        tick(); updateWifi();
    }

    @Override protected void onDestroy() { if (live == this) live = null; super.onDestroy(); }

    // ---- static layout ----

    void buildStatic() {
        W = root.getWidth(); H = root.getHeight();
        M = dp(56); G = dp(22);
        tileW = (W - 2 * M - 2 * dp(PAD) - (cols - 1) * G) / cols;
        tileH = tileW * 9 / 16;
        step = tileH + dp(52);

        wpBack = new ImageView(this); wpFront = new ImageView(this);
        for (ImageView v : new ImageView[]{wpBack, wpFront}) { v.setScaleType(ImageView.ScaleType.CENTER_CROP); root.addView(v, new FrameLayout.LayoutParams(-1, -1)); }
        wpFront.setAlpha(0);
        // Legibility scrims over the wallpaper.
        View top = new View(this);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x99000000, 0x33000000, 0x00000000}));
        root.addView(top, new FrameLayout.LayoutParams(-1, (int) (H * 0.45f)));
        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xCC000000, 0x55000000, 0x00000000}));
        root.addView(bottom, new FrameLayout.LayoutParams(-1, (int) (H * 0.55f), Gravity.BOTTOM));

        desk = new FrameLayout(this);
        root.addView(desk, new FrameLayout.LayoutParams(-1, -1));
        buildWidgets();
        float dockY = dockTop();
        glow = new View(this);
        desk.addView(glow, at(M - dp(120), dockY - dp(250), W * 0.55f, dp(280)));
        title = text(40, 300, 0xFFF5F5F7);
        desk.addView(title, at(M + dp(6), dockY - dp(PAD) - dp(70), W - 2 * M, -1));
        band = new View(this);
        band.setBackground(glass(dp(30), 0x66141416));
        desk.addView(band, at(M, dockY - dp(PAD), W - 2 * M, tileH + dp(PAD) * 2 + dp(24)));
        deskHi = new View(this);
        deskHi.setBackground(glass(dp(18), 0x38FFFFFF));
        desk.addView(deskHi, at(0, 0, tileW, tileH));

        // Launchpad: dim the desk, grid on top.
        padScrim = new View(this);
        padScrim.setBackgroundColor(0xD9000000); padScrim.setAlpha(0);
        root.addView(padScrim, new FrameLayout.LayoutParams(-1, -1));
        pad = new FrameLayout(this);
        pad.setAlpha(0);
        root.addView(pad, new FrameLayout.LayoutParams(-1, -1));
        padHi = new View(this);
        padHi.setBackground(glass(dp(18), 0x38FFFFFF));
        pad.addView(padHi, at(0, 0, tileW, tileH));

        buildMenuBar();
        tick();
        // Process start = boot (or a crash): greet with the lock screen, macOS style.
        lock = new Lock.LockView(this);
        root.addView(lock, new FrameLayout.LayoutParams(-1, -1));
        lock.start();
    }

    float dockTop() { return H - dp(36) - dp(PAD) - dp(24) - tileH; }
    float tileX(int c) { return M + dp(PAD) + c * (tileW + G); }
    float padRowTop(int r) { return dp(84) + (r - 1) * step; }
    int visibleRows() { return Math.max(1, (int) ((H - dp(84) - dp(20)) / step)); }

    void buildMenuBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding((int) dp(22), 0, (int) dp(16), 0);
        bar.setBackgroundColor(0x33000000);
        menuApp = text(13, 600, Color.WHITE);
        bar.addView(menuApp, new LinearLayout.LayoutParams(0, -2, 1));
        menuWeather = text(13, 400, Color.WHITE); menuWifi = text(13, 400, Color.WHITE);
        bar.addView(menuWeather); addGap(bar);
        bar.addView(menuWifi); addGap(bar);
        String[] names = {"Pusat Kontrol"};
        for (int i = 0; i < names.length; i++) {
            TextView t = text(13, 500, Color.WHITE);
            t.setText(names[i]); t.setPadding((int) dp(10), (int) dp(3), (int) dp(10), (int) dp(3));
            menuItems[i] = t; bar.addView(t); addGap(bar);
        }
        menuClock = text(13, 500, Color.WHITE);
        bar.addView(menuClock);
        root.addView(bar, new FrameLayout.LayoutParams(-1, (int) dp(30)));
    }

    void addGap(LinearLayout l) { l.addView(new View(this), new LinearLayout.LayoutParams((int) dp(14), 1)); }

    void buildWidgets() {
        // Clock + greeting, top left.
        LinearLayout head = new LinearLayout(this); head.setOrientation(LinearLayout.VERTICAL);
        greeting = text(19, 400, 0xE6FFFFFF); clock = text(84, 200, Color.WHITE); date = text(17, 300, 0xCCFFFFFF);
        clock.setEllipsize(null); // digits differ in width; never cut the time
        head.addView(greeting); head.addView(clock); head.addView(date);
        desk.addView(head, at(M, dp(46), W * 0.42f, -1));

        float cw = dp(170), ch = dp(150), gap = dp(14), y = dp(52);
        float right = W - M;
        // Prayer times card.
        LinearLayout prayer = card();
        prayer.addView(cardLabel("SHOLAT · " + getString(R.string.city).toUpperCase(Locale.getDefault())));
        prayerNext = text(15, 500, Color.WHITE);
        prayer.addView(prayerNext);
        prayerRows = new LinearLayout(this); prayerRows.setOrientation(LinearLayout.VERTICAL);
        prayerRows.setPadding(0, (int) dp(4), 0, 0);
        prayer.addView(prayerRows);
        desk.addView(prayer, at(right - cw, y, cw, ch));
        // Weather card.
        LinearLayout weather = card();
        weather.addView(cardLabel(getString(R.string.city).toUpperCase(Locale.getDefault())));
        wTemp = text(44, 200, Color.WHITE); wDesc = text(15, 400, Color.WHITE); wRange = text(13, 400, 0xB3FFFFFF);
        wTemp.setText("--°"); wDesc.setText("Memuat cuaca…");
        weather.addView(wTemp); weather.addView(wDesc); weather.addView(wRange);
        desk.addView(weather, at(right - 2 * cw - gap, y, cw, ch));
    }

    LinearLayout card() {
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(glass(dp(22), 0x8C141416));
        l.setPadding((int) dp(16), (int) dp(12), (int) dp(16), (int) dp(12));
        return l;
    }
    TextView cardLabel(String s) { TextView t = text(11, 600, 0x99FFFFFF); t.setText(s); t.setLetterSpacing(0.08f); return t; }
    ViewOutlineProvider round(float r) {
        return new ViewOutlineProvider() { public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); } };
    }

    // ---- apps ----

    void loadApps() {
        final String keep = focusedApp() == null ? null : focusedApp().pkg;
        bg.execute(() -> {
            PackageManager pm = getPackageManager();
            Map<String, App> all = new LinkedHashMap<>();
            for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                    ActivityInfo ai = ri.activityInfo;
                    if (ai.packageName.equals(getPackageName()) || all.containsKey(ai.packageName)) continue;
                    App a = new App();
                    a.pkg = ai.packageName; a.label = String.valueOf(ri.loadLabel(pm));
                    a.system = (ai.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    a.launch = new Intent(Intent.ACTION_MAIN).addCategory(cat).setClassName(ai.packageName, ai.name)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    Drawable bn = ai.loadBanner(pm);
                    a.banner = bn != null ? bn : iconTile(ri.loadIcon(pm));
                    all.put(a.pkg, a);
                }
            }
            List<App> d = new ArrayList<>(), g;
            for (String p : dockPackages()) {
                App a = all.remove(p);
                if (a != null && d.size() < cols) { a.glow = sample(a.banner); d.add(a); }
            }
            g = new ArrayList<>(all.values());
            Collections.sort(g, (x, y) -> x.label.compareToIgnoreCase(y.label));
            ui.post(() -> populate(d, g, keep));
        });
    }

    /** Dock order: what the user pinned (long-press menu), else config.xml. */
    List<String> dockPackages() {
        String saved = prefs.getString("dock", null);
        if (saved == null) return new ArrayList<>(Arrays.asList(getResources().getStringArray(R.array.dock_apps))); // mutable: pin/unpin edit it
        return saved.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(saved.split(",")));
    }

    void saveDock(List<String> pkgs) {
        prefs.edit().putString("dock", android.text.TextUtils.join(",", pkgs)).apply();
        loadApps();
    }

    void populate(List<App> d, List<App> g, String keep) {
        for (View v : dockTiles) desk.removeView(v);
        for (View v : gridTiles) pad.removeView(v);
        dockTiles.clear(); gridTiles.clear(); lastTile = null;
        dock.clear(); dock.addAll(d); grid.clear(); grid.addAll(g);
        for (int i = 0; i < dock.size(); i++) dockTiles.add(addTile(desk, dock.get(i), tileX(i), dockTop()));
        // As tall as the whole grid: rows below the screen edge would otherwise be measured at zero height.
        pad.getLayoutParams().height = (int) (padRowTop(gridRows() + 1) + dp(40));
        pad.requestLayout();
        for (int i = 0; i < grid.size(); i++) gridTiles.add(addTile(pad, grid.get(i), tileX(i % cols), padRowTop(i / cols + 1)));
        deskHi.bringToFront(); padHi.bringToFront();
        // Keep focus on the same app across a rebuild, otherwise clamp.
        if (keep != null) {
            for (int i = 0; i < dock.size(); i++) if (dock.get(i).pkg.equals(keep)) { row = 0; col = i; }
            for (int i = 0; i < grid.size(); i++) if (grid.get(i).pkg.equals(keep)) { row = i / cols + 1; col = i % cols; }
        }
        clampFocus();
        render(false);
    }

    FrameLayout addTile(FrameLayout parent, App a, float x, float y) {
        FrameLayout t = new FrameLayout(this);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setImageDrawable(a.banner);
        img.setBackgroundColor(0xFF2C2C2E);
        img.setOutlineProvider(round(dp(14)));
        img.setClipToOutline(true);
        img.setPivotX(tileW / 2); img.setPivotY(tileH / 2);
        t.addView(img, new FrameLayout.LayoutParams((int) tileW, (int) tileH));
        TextView label = text(14, 500, Color.WHITE);
        label.setText(a.label); label.setGravity(Gravity.CENTER); label.setAlpha(0);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) tileW, -2); lp.topMargin = (int) (tileH + dp(12));
        t.addView(label, lp);
        parent.addView(t, at(x, y, tileW, tileH + dp(40)));
        return t;
    }

    Drawable iconTile(Drawable icon) {
        Bitmap bm = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        c.drawColor(0xFF2C2C2E);
        icon.setBounds(120, 50, 200, 130); icon.draw(c);
        return new BitmapDrawable(getResources(), bm);
    }

    int sample(Drawable d) {
        Bitmap bm = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, 16, 9); d.draw(new Canvas(bm));
        int[] px = new int[16 * 9]; bm.getPixels(px, 0, 16, 0, 0, 16, 9);
        long r = 0, g = 0, b = 0; int n = 0;
        for (int p : px) { if (Color.alpha(p) < 128) continue; r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++; }
        if (n == 0) return 0xFF5E5CE6;
        float[] hsv = new float[3];
        Color.RGBToHSV((int) (r / n), (int) (g / n), (int) (b / n), hsv);
        hsv[1] = Math.min(1, hsv[1] * 1.6f + 0.2f); hsv[2] = Math.max(hsv[2], 0.8f);
        return Color.HSVToColor(hsv);
    }

    // ---- wallpaper ----

    void loadWallpapers() {
        File dir = getExternalFilesDir("wallpapers");
        wallpapers.clear();
        String[] names = dir == null ? null : dir.list();
        if (names != null) { Arrays.sort(names); for (String n : names) if (n.matches("(?i).*\\.(jpe?g|png|webp)")) wallpapers.add(new File(dir, n).getPath()); }
        if (wallpapers.isEmpty()) return;
        wpIndex = prefs.getInt("wp", 0) % wallpapers.size();
        showWallpaper(false);
    }

    final Runnable nextWallpaper = () -> { if (!wallpapers.isEmpty()) { wpIndex = (wpIndex + 1) % wallpapers.size(); showWallpaper(true); } };

    void showWallpaper(boolean fade) {
        ui.removeCallbacks(nextWallpaper);
        ui.postDelayed(nextWallpaper, wpMinutes * 60_000L);
        prefs.edit().putInt("wp", wpIndex).apply();
        final String path = wallpapers.get(wpIndex);
        bg.execute(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true; BitmapFactory.decodeFile(path, o);
            o.inSampleSize = 1; while (o.outWidth / (o.inSampleSize * 2) >= W) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false; o.inPreferredConfig = Bitmap.Config.RGB_565; // half the memory of ARGB
            Bitmap bm = BitmapFactory.decodeFile(path, o);
            if (bm == null) return;
            ui.post(() -> {
                if (!fade) { wpBack.setImageBitmap(bm); return; }
                wpFront.setImageBitmap(bm); wpFront.setAlpha(0f);
                wpFront.animate().alpha(1).setDuration(1600).withEndAction(() -> {
                    wpBack.setImageBitmap(bm); wpFront.setAlpha(0f); wpFront.setImageDrawable(null);
                }).start();
            });
        });
    }

    // ---- clock, greeting, wifi ----

    void tick() {
        if (clock == null) return;
        Date now = new Date();
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String g = h < 4 ? "Selamat malam" : h < 11 ? "Selamat pagi" : h < 15 ? "Selamat siang" : h < 18 ? "Selamat sore" : "Selamat malam";
        String name = getString(R.string.user_name);
        greeting.setText(name.isEmpty() ? g : g + ", " + name);
        boolean h24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(h24 ? "HH.mm" : "h.mm", Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now));
        menuClock.setText(new SimpleDateFormat(h24 ? "EEE d MMM  HH.mm" : "EEE d MMM  h.mm", Locale.getDefault()).format(now));
        renderPrayer();
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now);
        if (!today.equals(prefs.getString("prayer_date", ""))) fetchPrayer();
        if (System.currentTimeMillis() - prefs.getLong("weather_at", 0) > 30 * 60_000L) fetchWeather();
    }

    void updateWifi() {
        if (menuWifi == null) return;
        String ssid = ((WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE)).getConnectionInfo().getSSID();
        boolean ok = ssid != null && !ssid.contains("unknown ssid") && !ssid.equals("0x");
        menuWifi.setText(ok ? "Wi-Fi  " + ssid.replace("\"", "") : "Tidak terhubung");
    }

    // ---- weather (Open-Meteo, no key) ----

    String http(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(8000);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
            StringBuilder sb = new StringBuilder(); String l;
            while ((l = r.readLine()) != null) sb.append(l);
            return sb.toString();
        } finally { c.disconnect(); }
    }

    boolean weatherBusy;
    void fetchWeather() {
        if (wTemp == null) return;
        showWeather(prefs.getString("weather", null));
        if (weatherBusy) return;
        weatherBusy = true;
        bg.execute(() -> {
            try {
                String js = http("https://api.open-meteo.com/v1/forecast?latitude=" + getString(R.string.latitude)
                        + "&longitude=" + getString(R.string.longitude)
                        + "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1");
                prefs.edit().putString("weather", js).putLong("weather_at", System.currentTimeMillis()).apply();
                ui.post(() -> showWeather(js));
            } catch (Exception ignored) {}
            weatherBusy = false;
        });
    }

    void showWeather(String js) {
        if (js == null) return;
        try {
            JSONObject o = new JSONObject(js), cur = o.getJSONObject("current"), d = o.getJSONObject("daily");
            int code = cur.getInt("weather_code");
            boolean day = cur.optInt("is_day", 1) == 1;
            String[] w = weatherText(code, day);
            long t = Math.round(cur.getDouble("temperature_2m"));
            wTemp.setText(t + "°");
            wDesc.setText(w[0] + "  " + w[1]);
            wRange.setText("T:" + Math.round(d.getJSONArray("temperature_2m_max").getDouble(0)) + "°  R:" + Math.round(d.getJSONArray("temperature_2m_min").getDouble(0)) + "°");
            menuWeather.setText(w[0] + " " + t + "°");
        } catch (Exception ignored) {}
    }

    static String[] weatherText(int c, boolean day) {
        if (c == 0) return new String[]{day ? "☀" : "☾", "Cerah"};
        if (c <= 2) return new String[]{"⛅", "Cerah berawan"};
        if (c == 3) return new String[]{"☁", "Berawan"};
        if (c <= 48) return new String[]{"☁", "Berkabut"};
        if (c <= 57) return new String[]{"☂", "Gerimis"};
        if (c <= 67 || (c >= 80 && c <= 82)) return new String[]{"☔", "Hujan"};
        if (c >= 95) return new String[]{"⚡", "Badai petir"};
        return new String[]{"☁", "Berawan"};
    }

    // ---- prayer times (Aladhan, Kemenag RI method) ----

    static final String[][] PRAYERS = {{"Fajr", "Subuh"}, {"Dhuhr", "Dzuhur"}, {"Asr", "Ashar"}, {"Maghrib", "Maghrib"}, {"Isha", "Isya"}};
    boolean prayerBusy;

    void fetchPrayer() {
        if (prayerRows == null || prayerBusy) return;
        prayerBusy = true;
        bg.execute(() -> {
            try {
                Date now = new Date();
                String js = http("https://api.aladhan.com/v1/timings/" + new SimpleDateFormat("dd-MM-yyyy", Locale.US).format(now)
                        + "?latitude=" + getString(R.string.latitude) + "&longitude=" + getString(R.string.longitude) + "&method=20");
                JSONObject t = new JSONObject(js).getJSONObject("data").getJSONObject("timings");
                StringBuilder sb = new StringBuilder();
                for (String[] p : PRAYERS) sb.append(p[1]).append('=').append(t.getString(p[0]).substring(0, 5)).append(';');
                prefs.edit().putString("prayer", sb.toString())
                        .putString("prayer_date", new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)).apply();
                ui.post(() -> { renderPrayer(); schedulePrayer(); });
            } catch (Exception ignored) {}
            prayerBusy = false;
        });
    }

    final Runnable prayerDue = () -> {
        String[] name = new String[1];
        Notif.nextPrayer(prefs, System.currentTimeMillis() - 60_000, name);
        if (name[0] != null) Notif.banner(this, null, "Jadwal Sholat", "Waktunya sholat " + name[0],
                "Untuk wilayah " + getString(R.string.city) + " dan sekitarnya");
        ui.postDelayed(this::schedulePrayer, 90_000);
    };

    void schedulePrayer() {
        ui.removeCallbacks(prayerDue);
        long next = Notif.nextPrayer(prefs, System.currentTimeMillis(), null);
        if (next > 0) ui.postDelayed(prayerDue, next - System.currentTimeMillis());
    }

    void renderPrayer() {
        if (prayerRows == null) return;
        String[] next = new String[1];
        long at = Notif.nextPrayer(prefs, System.currentTimeMillis(), next);
        prayerRows.removeAllViews();
        String data = prefs.getString("prayer", "");
        if (data.isEmpty()) { prayerNext.setText("Memuat jadwal…"); return; }
        if (at > 0) {
            long mins = (at - System.currentTimeMillis()) / 60_000 + 1;
            prayerNext.setText(next[0] + " " + (mins >= 60 ? (mins / 60) + "j " + (mins % 60) + "m" : mins + " menit"));
        } else prayerNext.setText("Subuh besok " + data.substring(data.indexOf("=") + 1, data.indexOf("=") + 6));
        for (String part : data.split(";")) {
            String[] kv = part.split("=");
            if (kv.length != 2) continue;
            boolean hot = kv[0].equals(next[0]);
            LinearLayout r = new LinearLayout(this);
            TextView n = text(12, hot ? 600 : 400, hot ? Color.WHITE : 0xB3FFFFFF);
            TextView v = text(12, hot ? 600 : 400, hot ? Color.WHITE : 0xB3FFFFFF);
            n.setText(kv[0]); v.setText(kv[1]);
            r.addView(n, new LinearLayout.LayoutParams(0, -2, 1)); r.addView(v);
            prayerRows.addView(r);
        }
    }

    /** Banner inside the launcher, for when the overlay permission is missing. */
    void inAppBanner(Drawable icon, String app, String title, String text) {
        View v = Notif.card(this, icon, app, title, text);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) dp(360), -2, Gravity.TOP | Gravity.END);
        lp.topMargin = (int) dp(40); lp.rightMargin = (int) dp(24);
        root.addView(v, lp);
        v.setTranslationX(dp(400));
        v.animate().translationX(0).setDuration(380).setInterpolator(new DecelerateInterpolator(2)).start();
        v.postDelayed(() -> v.animate().translationX(dp(400)).setDuration(300).withEndAction(() -> root.removeView(v)).start(), 5500);
    }

    // ---- focus ----

    int rowLen(int r) {
        if (r < 0) return menuItems.length;
        if (r == 0) return dock.size();
        int start = (r - 1) * cols;
        return Math.max(0, Math.min(cols, grid.size() - start));
    }
    int gridRows() { return (grid.size() + cols - 1) / cols; }

    void clampFocus() {
        if (row > gridRows()) row = gridRows();
        if (row == 0 && dock.isEmpty()) row = grid.isEmpty() ? -1 : 1;
        col = Math.max(0, Math.min(col, rowLen(row) - 1));
    }

    void move(int dr, int dc) {
        if (dc != 0) {
            int n = col + dc;
            if (n < 0 || n >= rowLen(row)) return; // ends never wrap or fall through
            col = n;
        } else {
            int target = row + dr;
            if (target == 0 && dock.isEmpty()) target += dr;
            if (target < -1 || target > gridRows() || (target >= 1 && rowLen(target) == 0)) return;
            if (row == -1) menuCol = col;
            if (row == 0) lastDockCol = col;
            if (target == -1) col = menuCol;
            else if (target == 0) col = lastDockCol;
            row = target;
            col = Math.min(col, rowLen(row) - 1);
        }
        render(true);
    }

    View focusedTile() {
        if (row == 0 && col < dockTiles.size()) return dockTiles.get(col);
        int i = (row - 1) * cols + col;
        return row >= 1 && i < gridTiles.size() ? gridTiles.get(i) : null;
    }

    App focusedApp() {
        if (row == 0 && col < dock.size()) return dock.get(col);
        int i = (row - 1) * cols + col;
        return row >= 1 && i < grid.size() ? grid.get(i) : null;
    }

    void openPad() { if (grid.isEmpty()) return; if (row == 0) lastDockCol = col; row = 1; col = 0; firstRow = 1; render(true); }
    void toDock() { if (dock.isEmpty()) return; row = 0; col = Math.min(lastDockCol, dock.size() - 1); render(true); }

    void focusTile(View t, boolean on, boolean animate) {
        if (t == null) return;
        View img = ((FrameLayout) t).getChildAt(0), label = ((FrameLayout) t).getChildAt(1);
        float s = on ? 1.08f : 1f;
        int ms = animate ? 220 : 0;
        img.animate().scaleX(s).scaleY(s).setDuration(ms).setInterpolator(new OvershootInterpolator(1.2f)).start();
        label.animate().alpha(on ? 1 : 0).translationY(on ? dp(6) : 0).setDuration(animate ? 180 : 0).start();
    }

    void render(boolean animate) {
        if (desk == null) return;
        View ft = focusedTile();
        if (ft != lastTile) { focusTile(lastTile, false, animate); focusTile(ft, true, animate); lastTile = ft; } // only two tiles animate per move
        for (int i = 0; i < menuItems.length; i++)
            menuItems[i].setBackground(row == -1 && col == i ? glass(dp(12), 0x59FFFFFF) : null);

        boolean inPad = row >= 1;
        if (inPad) { // scroll so the focused row stays in view, one row at a time
            int vis = visibleRows();
            if (row < firstRow) firstRow = row;
            if (row >= firstRow + vis) firstRow = row - vis + 1;
        }
        int ms = animate ? 260 : 0;
        padScrim.animate().alpha(inPad ? 1 : 0).setDuration(ms).start();
        pad.animate().alpha(inPad ? 1 : 0).translationY(inPad ? -(firstRow - 1) * step : dp(40)).setDuration(ms)
                .setInterpolator(new DecelerateInterpolator(2)).start();
        desk.animate().alpha(inPad ? 0 : 1).scaleX(inPad ? 0.96f : 1).scaleY(inPad ? 0.96f : 1).setDuration(ms).start();

        View hi = inPad ? padHi : deskHi;
        (inPad ? deskHi : padHi).setAlpha(0);
        if (ft != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) ft.getLayoutParams();
            float grow = tileW * 0.04f + dp(7);
            ViewGroup.LayoutParams hl = hi.getLayoutParams();
            hl.width = (int) (tileW + 2 * grow); hl.height = (int) (tileH * 1.08f + 2 * dp(7));
            hi.setLayoutParams(hl);
            hi.animate().alpha(1).x(lp.leftMargin - grow).y(lp.topMargin - (hl.height - tileH) / 2f)
                    .setDuration(animate ? 280 : 0).setInterpolator(new OvershootInterpolator(1.1f)).start();
        } else hi.animate().alpha(0).setDuration(120).start();

        App fa = focusedApp();
        menuApp.setText(fa != null ? fa.label : row == -1 ? "Pusat Kontrol" : "Beranda");
        App dockApp = row == 0 ? fa : (dock.isEmpty() ? null : dock.get(Math.min(lastDockCol, dock.size() - 1)));
        title.setText(dockApp == null ? "" : dockApp.label);
        title.animate().alpha(row == 0 ? 1 : 0).setDuration(200).start();
        if (dockApp != null) {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{(dockApp.glow & 0xFFFFFF) | 0x66000000, 0x00000000});
            g.setGradientType(GradientDrawable.RADIAL_GRADIENT); g.setGradientRadius(W * 0.3f);
            glow.setBackground(g);
        }
        glow.animate().alpha(row == 0 ? 1 : 0).setDuration(300).start();
    }

    // ---- panels: Notification Center and Control Center ----

    void openPanel(String heading, Runnable fill) { openPanel(heading, fill, null); }

    /** With an anchor the panel opens over that tile like a context menu, else under the menu bar. */
    void openPanel(String heading, Runnable fill, View anchor) {
        closePanel();
        panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(glass(dp(24), 0xF71C1C1F));
        panel.setPadding((int) dp(10), (int) dp(12), (int) dp(10), (int) dp(10));
        panelRefresh = () -> {
            int keep = panelSel;
            panel.removeAllViews(); panelItems.clear();
            TextView h = text(13, 600, 0x99FFFFFF); h.setText(heading); h.setPadding((int) dp(12), 0, 0, (int) dp(6));
            panel.addView(h);
            fill.run();
            panelSel = Math.min(keep, panelItems.size() - 1);
            renderPanel();
        };
        FrameLayout.LayoutParams lp;
        if (anchor == null) {
            lp = new FrameLayout.LayoutParams((int) dp(360), -2, Gravity.TOP | Gravity.END);
            lp.topMargin = (int) dp(38); lp.rightMargin = (int) dp(16);
        } else {
            int[] xy = new int[2]; anchor.getLocationOnScreen(xy);
            lp = new FrameLayout.LayoutParams((int) dp(300), -2, Gravity.TOP | Gravity.START);
            lp.leftMargin = (int) Math.min(xy[0] + dp(24), W - dp(316));
            lp.topMargin = (int) Math.max(dp(38), Math.min(xy[1] + dp(24), H - dp(300)));
        }
        root.addView(panel, lp);
        panelSel = 0;
        panelRefresh.run();
        panel.setAlpha(0); panel.setTranslationY(-dp(10));
        panel.animate().alpha(1).translationY(0).setDuration(180).setInterpolator(new DecelerateInterpolator(2)).start();
    }

    void addItem(String label, Runnable enter, Adjust adjust) {
        TextView t = text(15, 400, Color.WHITE);
        t.setSingleLine(false); t.setMaxLines(3);
        t.setText(label); t.setPadding((int) dp(14), (int) dp(10), (int) dp(14), (int) dp(10));
        panel.addView(t); panelItems.add(new Item(label, enter, adjust));
    }

    void renderPanel() {
        for (int i = 0; i < panelItems.size(); i++)
            panel.getChildAt(i + 1).setBackground(i == panelSel ? glass(dp(14), 0x40FFFFFF) : null);
    }

    void closePanel() { if (panel != null) root.removeView(panel); panel = null; panelRefresh = null; }

    void openControls() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        openPanel("PUSAT KONTROL", () -> {
            int v = am.getStreamVolume(AudioManager.STREAM_MUSIC), max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            addItem("Volume   " + (max == 0 ? 0 : Math.round(100f * v / max)) + "%   ◀ ▶", null, dir -> {
                am.adjustSuggestedStreamVolume(dir > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.STREAM_MUSIC, 0);
                panelRefresh.run();
            });
            addItem(menuWifi.getText().toString(), () -> start(new Intent(Settings.ACTION_WIFI_SETTINGS)), null);
            addItem("Bluetooth & remote", () -> start(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)), null);
            if (wallpapers.size() > 1) addItem("Ganti wallpaper   (" + (wpIndex + 1) + "/" + wallpapers.size() + ")", () -> {
                nextWallpaper.run(); panelRefresh.run();
            }, null);
            addItem("Pengaturan", () -> start(new Intent(Settings.ACTION_SETTINGS)), null);
        });
    }

    void openAppMenu() {
        App a = focusedApp();
        if (a == null) return;
        List<String> pins = dockPackages();
        openPanel(a.label.toUpperCase(Locale.getDefault()), () -> {
            addItem("Buka", () -> start(a.launch), null);
            int at = pins.indexOf(a.pkg);
            if (at >= 0) {
                addItem("Lepas dari Dock", () -> { pins.remove(a.pkg); closePanel(); saveDock(pins); }, null);
                addItem("Geser posisi   ◀ ▶", null, dir -> {
                    int i = pins.indexOf(a.pkg), j = i + dir;
                    if (j < 0 || j >= pins.size()) return;
                    Collections.swap(pins, i, j); saveDock(pins);
                });
            } else if (dock.size() < cols)
                addItem("Sematkan ke Dock", () -> { pins.add(a.pkg); closePanel(); saveDock(pins); }, null);
            else addItem("Dock penuh (" + cols + ") — lepas satu dulu", null, null);
            addItem("Info aplikasi", () -> start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg))), null);
            if (!a.system) addItem("Uninstall", () -> start(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg))), null);
        }, focusedTile());
    }

    void start(Intent i) {
        closePanel();
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) { Toast.makeText(this, "Tidak bisa dibuka", Toast.LENGTH_SHORT).show(); }
    }

    // ---- keys ----

    @Override public boolean onKeyDown(int k, KeyEvent e) {
        if (panel != null) {
            Item it = panelItems.isEmpty() ? null : panelItems.get(panelSel);
            switch (k) {
                case KeyEvent.KEYCODE_DPAD_UP: panelSel = Math.max(0, panelSel - 1); renderPanel(); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: panelSel = Math.min(panelItems.size() - 1, panelSel + 1); renderPanel(); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (it != null && it.adjust != null) it.adjust.by(k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                    if (e.getRepeatCount() > 0) return true; // still holding from the long-press that opened us
                    if (it != null && it.enter != null) it.enter.run();
                    return true;
                case KeyEvent.KEYCODE_BACK: closePanel(); return true;
                case KeyEvent.KEYCODE_HOME: return false;
            }
            return true;
        }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: move(-1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: move(0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(0, 1); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                if (e.getRepeatCount() == 0) e.startTracking(); // act on release, so a hold can open the menu
                return true;
            case KeyEvent.KEYCODE_ALL_APPS: if (row >= 1) toDock(); else openPad(); return true;
            case KeyEvent.KEYCODE_MENU: openControls(); return true;
            case KeyEvent.KEYCODE_BACK: if (row != 0) toDock(); return true;
        }
        return super.onKeyDown(k, e);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (lock == null || e.getKeyCode() == KeyEvent.KEYCODE_HOME) return super.dispatchKeyEvent(e);
        if (Lock.isOk(e.getKeyCode())) {
            if (e.getAction() == KeyEvent.ACTION_UP) { Lock.LockView l = lock; lock = null; l.unlock(() -> root.removeView(l)); }
        } else if (e.getAction() == KeyEvent.ACTION_DOWN) lock.nudge();
        return true;
    }

    @Override public boolean onKeyLongPress(int k, KeyEvent e) {
        if ((k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) && panel == null && row >= 0) { openAppMenu(); return true; }
        return super.onKeyLongPress(k, e);
    }

    @Override public boolean onKeyUp(int k, KeyEvent e) {
        if ((k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) && panel == null) {
            if (e.isTracking() && !e.isCanceled()) { // short press (a long press arrives canceled)
                if (row >= 0) { App a = focusedApp(); if (a != null) start(a.launch); }
                else openControls();
            }
            return true;
        }
        return super.onKeyUp(k, e);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        closePanel();
        if (Intent.ACTION_ALL_APPS.equals(i.getAction())) openPad();
        else toDock(); // Home: always back to the dock
    }
}
