package tv.clean.home;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.*;
import android.content.pm.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.*;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.*;
import android.view.accessibility.AccessibilityManager;
import android.widget.*;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;
import tech.kousozo.tahoetv.core.ControlPlane;
import tech.kousozo.tahoetv.core.DockGrid;

/** Tahoe TV home, tvOS 26 style: full-bleed editorial hero, top bar chips, macOS-style dock with magnification,
 *  Launchpad (games shelf + all apps), sheets (weather, prayer, control center, assistant, pairing).
 *  Focus is a model driven by our own key handling: row -1 = top bar chips, row 0 = dock, row >= 1 = Launchpad rows. */
public class Home extends Activity {
    static Home live;
    final RemoteController remote = new RemoteController(this);

    static final class App {
        String pkg, label; Drawable banner; Intent launch; int color; boolean system, game;
    }

    final Handler ui = new Handler(Looper.getMainLooper());
    SharedPreferences prefs;
    int W, H, dockSize, cols, rawCols;
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
    String launchedKidsAppLabel;
    long launchedKidsAtMs;
    List<Trending.Video> trending = new ArrayList<>(), netflix = new ArrayList<>();
    int trendIndex;
    Companion companion;
    boolean gamepad, pendingCenterKey;
    AutoCloseable pushStream;
    long bootStartMs;

    // ---------------------------------------------------------------- lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        bootStartMs = android.os.SystemClock.elapsedRealtime();
        if (TvGuard.blockIfNotTv(this)) return;
        Diag.install(this);
        DeviceProfiler.refresh(this);
        live = this;
        Cfg.p(this); // runtime settings from the phone setup app
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        if (PushHelper.isTv(this)) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
        enableAppsKey();
        if (Build.VERSION.SDK_INT >= 33 && !PushHelper.isTv(this)
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("asked_notif", false)) {
            prefs.edit().putBoolean("asked_notif", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 901);
        }
        PushHelper.scheduleBackgroundPoll(this);
        pushStream = ControlPlane.startPushStream(getFilesDir(), PushHelper.profile(this), new ControlPlane.PushListener() {
            @Override public void onNotification(ControlPlane.PushNotification n) {
                PushHelper.showSystemNotification(Home.this, n);
                ui.post(() -> {
                    PushHelper.showInAppBanner(Home.this, "Tahoe TV", n.getTitle(), n.getBody());
                    if (n.getForceModal() && !n.getActionUrl().isEmpty()) {
                        About.dailyCheck(Home.this);
                    }
                });
            }
            @Override public void onPolicyUpdated(ControlPlane.UpdatePolicy policy, ControlPlane.ChannelOverrides overrides) {
                if (policy.getForceModal() || policy.getMinVersionCode() > tech.kousozo.tahoetv.core.TahoeVersion.VERSION_CODE) {
                    ui.post(() -> About.dailyCheck(Home.this));
                }
            }
        });
        About.dailyCheck(this);
        Live.fetch(this, l -> {}, e -> {}, () -> {}); // load channels + check every server now, so Live TV starts instantly
        dockSize = Math.max(4, Math.min(7, getResources().getInteger(R.integer.dock_size)));
        rawCols = Math.max(4, Math.min(7, getResources().getInteger(R.integer.columns)));
        cols = DockGrid.effectiveCols(rawCols, TvViewer.simpleMode(this));
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        Ui.direction(root);
        setContentView(root);
        root.post(() -> {
            W = root.getWidth(); H = root.getHeight();
            build();
            tech.kousozo.tahoetv.core.DeviceProfile.recordStartupMs(android.os.SystemClock.elapsedRealtime() - bootStartMs);
            loadApps();
            hero.showWall(false);
            refreshData(false);
            companion = new Companion(this, remote);
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

    final Runnable reload = () -> HomeDock.loadApps(this);

    private boolean warming;
    /** Loads just the channel list in the background (once) so the hero has channels to preview before Live TV was ever opened. */
    void warmChannels() {
        if (warming || (Live.cached != null && !Live.cached.isEmpty())) return;
        warming = true;
        Live.fetch(this, true, l -> {
            warming = false;
            if (!isFinishing() && !isDestroyed() && sheet == null && profiles == null && row <= 0) render(false);
        }, e -> { }, () -> warming = false);
    }

    @Override protected void onResume() {
        super.onResume();
        if (!TvGuard.isAndroidTv(this)) return;
        updateFocusBlocking();
        // first run (#77): onboarding until it's done, also after Home was pressed in the middle of it
        if (!TvViewer.done(this) && Onboarding.open == null) startActivity(new android.content.Intent(this, Onboarding.class));
        if (hero == null) return;
        HomeDock.flushKidsAppUsage(this);
        tick(); checkGamepad(); markRunning();
        warmChannels();
        if (kids()) HomeSettingsBridge.enforceKidsAllowance(this);
        if (transition != null) { // coming back from an app: dissolve the launch overlay
            View t = transition; transition = null;
            t.animate().alpha(0).setDuration(Ui.MED).withEndAction(() -> root.removeView(t)).start();
            dockLayer.setTranslationY(Ui.dp(this, 24)); dockLayer.setAlpha(0);
            dockLayer.animate().translationY(0).alpha(1).setDuration(Ui.SLOW).setInterpolator(Ui.out()).start();
        }
        refreshData(false);
    }

    @Override protected void onPause() {
        if (hero != null) hero.stopPreview();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (hero != null) hero.releasePreview();
        remote.cancelSleep();
        if (companion != null) companion.stopAll();
        if (pushStream != null) { try { pushStream.close(); } catch (Exception ignored) {} }
        if (live == this) live = null;
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (root != null) {
            root.post(() -> {
                W = root.getWidth();
                H = root.getHeight();
                applyLayoutMetrics();
                applyTextScale();
                buildChips();
                loadApps();
            });
        }
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
        applyLayoutMetrics();
        hero = new Hero(this, W, Math.max(1, getResources().getInteger(R.integer.wallpaper_minutes)));
        root.addView(hero, new FrameLayout.LayoutParams(-1, -1));

        // Top bar: greeting left, chips + clock right.
        greeting = Ui.eyebrow(this, Ui.TEXT_2);
        root.addView(greeting, at(M, dp(Ui.SAFE_TOP + 12), W * 0.4f, -1));
        chips = new LinearLayout(this); chips.setGravity(Gravity.CENTER_VERTICAL);
        clock = Ui.text(this, 26, 300, Ui.TEXT);
        LinearLayout right = new LinearLayout(this); right.setGravity(Gravity.CENTER_VERTICAL);
        right.addView(chips);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2); cl.leftMargin = (int) dp(18);
        right.addView(clock, cl);
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        rl.topMargin = (int) dp(Ui.SAFE_TOP); rl.rightMargin = (int) M;
        root.addView(right, rl);

        heroAction = new LinearLayout(this); heroAction.setGravity(Gravity.CENTER_VERTICAL);
        heroAction.setPadding((int) dp(16), (int) dp(10), (int) dp(20), (int) dp(10));
        heroAction.setBackground(new GlassDrawable(dp(22), Ui.GLASS, dp(1)));
        heroAction.setClickable(true);
        heroAction.setFocusable(false); heroAction.setFocusableInTouchMode(false);
        heroAction.setOnClickListener(v -> heroActivate());
        heroActionIcon = Ui.icon(this, R.drawable.ic_play, 18, Ui.TEXT);
        heroActionText = Ui.text(this, 15, 600, Ui.TEXT); heroActionText.setPadding((int) dp(10), 0, 0, 0);
        heroAction.addView(heroActionIcon); heroAction.addView(heroActionText);
        LinearLayout.LayoutParams hal = new LinearLayout.LayoutParams(-2, -2); hal.topMargin = (int) dp(22);
        hero.block.addView(heroAction, hal);
        heroAction.setPivotX(0); // tvOS lift grows away from the text edge, not past it
        hero.block.setClipChildren(false); hero.setClipChildren(false);

        // Dock (macOS style): centred glass shelf, tiles magnify toward the focused one.
        applyLayoutMetrics();
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
        padScrim.setBackgroundColor(0xE6050508); padScrim.setAlpha(0); padScrim.setVisibility(View.GONE);
        padScrim.setClickable(true);
        padScrim.setFocusable(false); padScrim.setFocusableInTouchMode(false);
        padScrim.setOnClickListener(v -> {
            if (row >= 1) {
                row = 0; col = Math.min(col, Math.max(0, dock.size() - 1));
                render(true);
            }
        });
        root.addView(padScrim, new FrameLayout.LayoutParams(-1, -1));
        pad = new FrameLayout(this); pad.setAlpha(0); pad.setVisibility(View.GONE); pad.setClipChildren(false);
        root.addView(pad, new FrameLayout.LayoutParams(-1, -1));
        padTitle = Ui.text(this, Ui.HEADLINE, 600, Ui.TEXT);
        padTitle.setText(tech.kousozo.tahoetv.core.I18n.get(TvViewer.get(this).getLanguage(), "tv.home_apps_title"));
        pad.addView(padTitle, at(M, dp(36), -1, -1));
        padRowsView = new FrameLayout(this); padRowsView.setClipChildren(false);
        pad.addView(padRowsView, new FrameLayout.LayoutParams(-1, -1));
        applyLayoutMetrics();
        applyTextScale();

        showProfiles(); // boot: "Siapa yang menonton?"
        buildChips();
        tick();
        updateFocusBlocking();
    }

    /** "Siapa yang menonton?": owner (with last app), guest, kids. Leaving the kids profile takes its PIN; choosing it
     *  the first time creates one. Also opened from Control Center ("Ganti profil"). */
    void showProfiles() {
        if (profiles != null) return;
        closeSheet();
        String[] last = HomeDock.recentApp(this);
        Drawable lastIcon = null;
        if (last != null) try { ApplicationInfo ai = getPackageManager().getApplicationInfo(last[0], 0); lastIcon = ai.loadBanner(getPackageManager()); if (lastIcon == null) lastIcon = ai.loadIcon(getPackageManager()); } catch (Exception ignored) {}
        String current = prefs.getString("profile", "main");
        profiles = new Lock.Profiles(this, last == null || kids() ? null : last[1], lastIcon, current, p -> {
            Runnable go = () -> enterProfile(p);
            String lang = TvViewer.get(this).getLanguage();
            tech.kousozo.tahoetv.core.MathGate.Mode mode = Kids.gateMode(this);
            if (kids() && !p.equals(Kids.PROFILE)) verifyAdult(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.home_exit_kids"), go);
            else if (p.equals(Kids.PROFILE) && mode != tech.kousozo.tahoetv.core.MathGate.Mode.MATH && !Kids.hasPin(this)) createPin(go, this::closeSheet);
            else go.run();
        });
        root.addView(profiles, new FrameLayout.LayoutParams(-1, -1));
        profiles.start();
        pendingCenterKey = false;
        updateFocusBlocking();
    }

    void enterProfile(String p) {
        closeSheet();
        prefs.edit().putString("profile", p).apply();
        Lock.Profiles v = profiles; profiles = null;
        if (v != null) v.unlock(() -> root.removeView(v));
        pendingCenterKey = false;
        updateFocusBlocking();
        row = 0; tick(); buildChips(); loadApps(); hero.refreshWallpaperSettings(); render(false);
    }

    /** New PIN for the kids profile: type it, type it again. */
    void createPin(Runnable ok, Runnable cancel) {
        String lang = TvViewer.get(this).getLanguage();
        openSheet(new ParentalSheets.PinPad(this, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.pin_set_title"),
                tech.kousozo.tahoetv.core.I18n.get(lang, "tv.home_pin_create_hint"), (pad, first) ->
                openSheet(new ParentalSheets.PinPad(this, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.home_pin_repeat_title"),
                        tech.kousozo.tahoetv.core.I18n.get(lang, "tv.home_pin_repeat_hint"), (pad2, again) -> {
                    if (!again.equals(first)) { pad2.fail(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.home_pin_mismatch")); return; }
                    Kids.setPin(this, first);
                    closeSheet(); ok.run();
                }, () -> { closeSheet(); cancel.run(); })), () -> { closeSheet(); cancel.run(); }));
    }

    /** Verifies adult identity using configured Kids gate mode: PIN, Math challenge, or PIN + Math (#47). */
    void verifyAdult(String title, Runnable ok) {
        tech.kousozo.tahoetv.core.MathGate.Mode mode = Kids.gateMode(this);
        if (mode == tech.kousozo.tahoetv.core.MathGate.Mode.MATH) {
            verifyMath(title, ok);
            return;
        }
        if (!Kids.hasPin(this)) {
            if (mode == tech.kousozo.tahoetv.core.MathGate.Mode.PIN_AND_MATH) verifyMath(title, ok);
            else ok.run();
            return;
        }
        Runnable nextStep = mode == tech.kousozo.tahoetv.core.MathGate.Mode.PIN_AND_MATH ? () -> verifyMath(title, ok) : ok;
        verifyPin(title, nextStep, mode == tech.kousozo.tahoetv.core.MathGate.Mode.PIN);
    }

    /** Adult math challenge sheet with difficulty selection and lockout protection (#47). */
    void verifyMath(String title, Runnable ok) {
        openSheet(new ParentalSheets.MathPad(this, title, Kids.mathDifficulty(this), ok, this::closeSheet));
    }

    /** Asks for the kids PIN; [ok] runs only when it's right. Progressive delays slow brute-force guessing. */
    void verifyPin(String title, Runnable ok) { verifyPin(title, ok, true); }

    void verifyPin(String title, Runnable ok, boolean allowMathBackup) {
        String lang = TvViewer.get(this).getLanguage();
        Runnable[] lock = new Runnable[1];
        boolean[] refreshMsg = {true};
        ParentalSheets.PinPad sheet = new ParentalSheets.PinPad(this, title, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.home_pin_prompt"), (pad, pin) -> {
            if (Kids.pinLockLeftMs(this) > 0) { refreshMsg[0] = true; lock[0].run(); return; }
            if (Kids.checkPin(this, pin)) { Kids.clearPinBackoff(this); closeSheet(); ok.run(); return; }
            int sec = Kids.recordWrongPin(this);
            pad.fail(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.home_pin_wait", String.valueOf(sec)));
            refreshMsg[0] = false;
            lock[0].run();
        }, this::closeSheet);
        if (allowMathBackup) {
            sheet.withBackup(tech.kousozo.tahoetv.core.I18n.get(lang, "kids.math_backup"), () -> verifyMath(title, ok));
        }
        lock[0] = () -> {
            long left = Kids.pinLockLeftMs(this);
            if (left <= 0) {
                sheet.setEnabled(true); sheet.setAlpha(1f);
                sheet.status(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.home_pin_prompt"), false);
                return;
            }
            sheet.setEnabled(false); sheet.setAlpha(0.6f);
            if (refreshMsg[0]) {
                long sec = (left + 999) / 1000;
                sheet.status(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.home_pin_locked", String.valueOf(sec)), true);
            }
            refreshMsg[0] = true;
            ui.postDelayed(() -> {
                if (!sheet.isAttachedToWindow()) return;
                lock[0].run();
            }, Math.min(left, 1000L));
        };
        openSheet(sheet);
        if (Kids.pinLockLeftMs(this) > 0) lock[0].run();
    }

    boolean guest() { return "guest".equals(prefs.getString("profile", "main")); }
    boolean kids() { return Kids.PROFILE.equals(prefs.getString("profile", "main")); }

    void applyLayoutMetrics() {
        boolean simple = TvViewer.simpleMode(this);
        M = dp(56);
        cols = DockGrid.effectiveCols(rawCols, simple);
        tileW = dp(simple ? 146 : 118);
        gap = dp(simple ? 18 : 16);
        tileH = tileW * 9 / 16;
        padGap = dp(22);
        padTileW = Math.max(dp(84), (W - 2 * M - (cols - 1) * padGap) / Math.max(1, cols));
        padTileH = padTileW * 9 / 16;
        padStep = padTileH + dp(simple ? 52 : 46);
    }

    void applyTextScale() {
        boolean simple = TvViewer.simpleMode(this);
        if (greeting != null) greeting.setTextSize(simple ? 15f : 12f);
        if (clock != null) clock.setTextSize(simple ? 30f : 26f);
        if (heroActionText != null) heroActionText.setTextSize(simple ? 18f : 15f);
        if (tooltip != null) tooltip.setTextSize(simple ? 16f : 13f);
        if (padTitle != null) padTitle.setTextSize(simple ? 32f : Ui.HEADLINE);
    }

    void applySimpleMode() {
        applyLayoutMetrics();
        applyTextScale();
        buildChips();
        loadApps();
        render(false);
    }

    void buildChips() {
        String lang = TvViewer.get(this).getLanguage();
        chips.removeAllViews(); chipViews.clear(); chipActions.clear();
        addChip(R.drawable.ic_cloud, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.home_chip_weather"), this::openWeather);
        addChip(R.drawable.ic_moon_star, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.home_chip_prayer"), this::openPrayer);
        if (phoneMedia != null) addChip(R.drawable.ic_music, "", this::openControl).setContentDescription(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.home_chip_music"));
        if (gamepad) addChip(R.drawable.ic_gamepad_2, null, () -> { if (row == 0) lastDockCol = col; openPad(); }).setContentDescription(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.home_chip_gamepad"));
        addChip(R.drawable.ic_tv, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.home_chip_guide"), () -> start(new Intent(this, Live.class)));
        if (!kids()) { // no assistant or phone pairing in the kids profile
            addChip(R.drawable.ic_sparkles, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.home_chip_ask"), this::openAssistant);
            addChip(R.drawable.ic_smartphone, null, this::openPair).setContentDescription(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.home_chip_connect_phone"));
        }
        // Profile photo chip opens Control Center.
        LinearLayout av = new LinearLayout(this);
        av.setPadding((int) dp(3), (int) dp(3), (int) dp(3), (int) dp(3));
        av.setBackground(new GlassDrawable(dp(20), Ui.GLASS, dp(1)));
        av.addView(Avatar.view(this, 30, !guest() && !kids()));
        av.setContentDescription(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.control_center"));
        av.setClickable(true);
        av.setFocusable(false); av.setFocusableInTouchMode(false);
        av.setOnClickListener(v -> openControl());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.leftMargin = (int) dp(8);
        chips.addView(av, lp); chipViews.add(av); chipActions.add(this::openControl);
        updateChips();
        chipCol = Math.min(chipCol, chipViews.size() - 1);
    }

    LinearLayout addChip(int icon, String text, Runnable action) {
        LinearLayout c = new LinearLayout(this); c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding((int) dp(12), (int) dp(8), (int) dp(text == null ? 12 : 15), (int) dp(8));
        c.setBackground(new GlassDrawable(dp(18), Ui.GLASS, dp(1)));
        c.setClickable(true);
        c.setFocusable(false); c.setFocusableInTouchMode(false);
        c.setOnClickListener(v -> action.run());
        ImageView i = Ui.icon(this, icon, 18, Ui.TEXT); i.setTag("i");
        c.addView(i);
        if (text != null) {
            TextView t = Ui.text(this, TvViewer.simpleMode(this) ? 17 : 14, 500, Ui.TEXT); t.setText(text); t.setTag("t");
            t.setPadding((int) dp(8), 0, 0, 0);
            c.addView(t);
            c.setContentDescription(text);
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
        if (text != null && !text.isEmpty()) chipViews.get(i).setContentDescription(text);
    }

    void updateChips() {
        if (chipViews.isEmpty()) return;
        String lang = TvViewer.get(this).getLanguage();
        JSONObject cur = weather == null ? null : weather.optJSONObject("current");
        if (cur != null) chipText(0, Weather.icon(cur.optInt("weather_code"), cur.optInt("is_day", 1) == 1), Math.round(cur.optDouble("temperature_2m")) + "°");
        else if (!TvViewer.hasCity(this)) chipText(0, 0, "—"); // no city: no stale temperature
        Object[] n = Prayer.next(this);
        SimpleDateFormat pf = new SimpleDateFormat("HH.mm", Locale.forLanguageTag(lang));
        pf.setTimeZone(Prayer.tz(this));
        chipText(1, 0, n == null ? tech.kousozo.tahoetv.core.I18n.get(lang, "tv.home_chip_prayer") : n[0] + "  " + pf.format(new Date((Long) n[1])));
        if (phoneMedia != null) chipText(2, 0, phoneMedia.optString("title"));
    }

    // ---------------------------------------------------------------- data

    void refreshData(boolean force) {
        if (TvViewer.hasCity(this)) Weather.refresh(this, prefs, force, o -> { weather = o; updateChips(); renderHero(); }, ui);
        else weather = null; // onboarding without a city: no weather
        Prayer.fetchIfStale(this, () -> { Prayer.schedule(this); updateChips(); renderHero(); }, ui);
        trending = Trending.cached(this);
        Trending.refresh(this, () -> { trending = Trending.cached(this); renderHero(); }, ui);
        netflix = Netflix.cached(this);
        Netflix.refresh(this, () -> { netflix = Netflix.cached(this); renderHero(); }, ui);
    }

    void tick() {
        if (clock == null) return;
        String lang = TvViewer.get(this).getLanguage();
        Date now = new Date();
        boolean h24 = android.text.format.DateFormat.is24HourFormat(this);
        java.util.TimeZone tz = Prayer.tz(this);
        SimpleDateFormat cf = new SimpleDateFormat(h24 ? "HH.mm" : "h.mm", Locale.forLanguageTag(lang));
        cf.setTimeZone(tz);
        clock.setText(cf.format(now));
        int h = Calendar.getInstance(tz).get(Calendar.HOUR_OF_DAY);
        String g = tech.kousozo.tahoetv.core.I18n.greeting(lang, h);
        String name = guest() || kids() ? "" : Cfg.s(this, "user_name", R.string.user_name);
        greeting.setText(name.isEmpty() ? g : g + ", " + name);
        updateChips();
        if (row != 0 || focusedApp() == null) renderHero();
        if (!Prayer.today(this).equals(prefs.getString("prayer2_date", ""))) Prayer.fetchIfStale(this, this::updateChips, ui);
    }

    // ---------------------------------------------------------------- apps (delegated to HomeDock)

    void loadApps() { HomeDock.loadApps(this); }
    void markRunning() { HomeDock.markRunning(this); }

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
            final int dockIdx = i;
            App appItem = dock.get(i);
            Tile t = new Tile(this, appItem, tileW, tileH);
            t.setClickable(true);
            t.setFocusable(false); t.setFocusableInTouchMode(false);
            t.setOnClickListener(v -> { row = 0; col = dockIdx; render(true); launch(appItem.launch, appItem, t); });
            t.setOnLongClickListener(v -> { row = 0; col = dockIdx; render(true); openAppMenu(); return true; });
            dockLayer.addView(t, at(bandX + pad + i * (tileW + gap), bandY + pad, tileW, tileH + dp(16)));
            dockTiles.add(t);
        }
        tooltip.bringToFront();

        // Launchpad rows: games shelf (if any), then everything.
        String lang = TvViewer.get(this).getLanguage();
        List<App> games = new ArrayList<>();
        for (App a : everything) if (a.game) games.add(a);
        float y = dp(96);
        if (!games.isEmpty()) {
            y = addSection(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.home_games"), y);
            for (int i = 0; i < games.size(); i += cols) y = addPadRow(games.subList(i, Math.min(games.size(), i + cols)), y);
            y += dp(10);
        }
        y = addSection(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.apps_all"), y);
        for (int i = 0; i < everything.size(); i += cols) y = addPadRow(everything.subList(i, Math.min(everything.size(), i + cols)), y);
        padRowsView.getLayoutParams().height = (int) (y + dp(40));
        padRowsView.requestLayout();

        if (keep != null) {
            for (int i = 0; i < dock.size(); i++) if (dock.get(i).pkg.equals(keep) && row == 0) col = i;
        }
        clampFocus();
        HomeDock.markRunning(this);
        updateFocusBlocking();
        render(false);
    }

        // populate footer continues directly into markRunning() above

    float addSection(String label, float y) {
        TextView t = Ui.eyebrow(this, Ui.TEXT_3); t.setText(label);
        padRowsView.addView(t, at(M, y, -1, -1));
        return y + dp(24);
    }

    float addPadRow(List<App> apps, float y) {
        final int padRowIdx = padRows.size() + 1;
        List<Tile> tiles = new ArrayList<>();
        for (int i = 0; i < apps.size(); i++) {
            final int colIdx = i;
            App appItem = apps.get(i);
            Tile t = new Tile(this, appItem, padTileW, padTileH);
            t.setClickable(true);
            t.setFocusable(false); t.setFocusableInTouchMode(false);
            t.setOnClickListener(v -> { row = padRowIdx; col = colIdx; render(true); launch(appItem.launch, appItem, t); });
            t.setOnLongClickListener(v -> { row = padRowIdx; col = colIdx; render(true); openAppMenu(); return true; });
            padRowsView.addView(t, at(M + i * (padTileW + padGap), y, padTileW, padTileH + dp(36)));
            TextView label = Ui.text(this, TvViewer.simpleMode(this) ? 16 : 13, 500, Ui.TEXT); label.setText(apps.get(i).label);
            label.setGravity(Gravity.CENTER); label.setAlpha(0); label.setTag("label");
            FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams((int) padTileW, -2); ll.topMargin = (int) (padTileH + dp(14));
            t.addView(label, ll);
            tiles.add(t);
        }
        padRows.add(new ArrayList<>(apps)); padTiles.add(tiles); padRowTop.add(y);
        return y + padStep;
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
        row = DockGrid.clampRow(row, dock.size(), rows());
        col = DockGrid.clampCol(col, rowLen(row));
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
            int nextCol = DockGrid.moveHorizontal(col, dc, rowLen(row));
            if (nextCol == col) return; // ends never wrap or fall through
            col = nextCol;
        } else {
            int target = DockGrid.nextVerticalRow(row, dr, dock.size(), rowLen(-1), rows(), chipViews.size());
            if (target == row || rowLen(target) == 0) return;
            if (row == -2) chipCol = col;
            if (row == 0) lastDockCol = col;
            col = DockGrid.targetColForRow(target, col, chipCol, lastDockCol, rowLen(target));
            row = target;
        }
        render(true);
        App fa = focusedApp();
        if (fa != null && focused != null) {
            focused.announceForAccessibility(fa.label);
        } else if (row == -1 && heroAction != null && heroAction.getContentDescription() != null) {
            heroAction.announceForAccessibility(heroAction.getContentDescription());
        } else if (row == -2 && col < chipViews.size()) {
            CharSequence cd = chipViews.get(col).getContentDescription();
            if (cd != null) chipViews.get(col).announceForAccessibility(cd);
        }
    }

    void openPad() { if (rows() == 0) return; if (hero != null) hero.stopPreview(); if (row == 0) lastDockCol = col; row = 1; col = 0; firstPadRow = 1; render(true); }
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
        setPadVisible(inPad, ms);
        padRowsView.animate().translationY(inPad ? shift : dp(40)).setDuration(ms).setInterpolator(Ui.out()).start();
        dockLayer.animate().alpha(inPad ? 0 : 1).translationY(inPad ? dp(40) : 0).setDuration(ms).start();
        hero.block.animate().alpha(inPad ? 0 : 1).setDuration(ms).start();
        renderHero();
    }

    /** Toggles Launchpad visibility so hidden overlay tiles never intercept home touches. */
    void setPadVisible(boolean show, int ms) {
        for (View v : new View[]{padScrim, pad}) {
            v.animate().cancel();
            if (show) {
                v.setVisibility(View.VISIBLE);
                v.animate().alpha(1f).setDuration(ms).withEndAction(null).start();
            } else if (v.getVisibility() != View.GONE) {
                if (ms <= 0) {
                    v.setAlpha(0f);
                    v.setVisibility(View.GONE);
                } else {
                    v.animate().alpha(0f).setDuration(ms).withEndAction(() -> {
                        if (row < 1) v.setVisibility(View.GONE);
                    }).start();
                }
            }
        }
    }

    void focusPill(LinearLayout p, boolean f, int ms) {
        if (f && Ui.highContrast(this)) {
            p.setBackground(Ui.focusRing(this, dp(22)));
        } else {
            GlassDrawable g = p.getBackground() instanceof GlassDrawable ? (GlassDrawable) p.getBackground() : new GlassDrawable(dp(22), Ui.GLASS, dp(1));
            g.fill(f ? 0xF2F5F5F7 : Ui.GLASS);
            p.setBackground(g);
        }
        int c = f && !Ui.highContrast(this) ? 0xFF111114 : Ui.TEXT;
        for (int k = 0; k < p.getChildCount(); k++) {
            View v = p.getChildAt(k);
            if (v instanceof TextView) { ((TextView) v).setTextColor(c); ((TextView) v).setShadowLayer(0, 0, 0, 0); }
            else if (v instanceof ImageView && ((ImageView) v).getImageTintList() != null) ((ImageView) v).setImageTintList(android.content.res.ColorStateList.valueOf(c));
        }
        long d = ms == 0 ? 0 : Ui.dur(this, Ui.FAST);
        p.animate().scaleX(f ? 1.08f : 1f).scaleY(f ? 1.08f : 1f).translationZ(f ? dp(8) : 0).setDuration(d).setInterpolator(Ui.out()).start();
    }

    // ---------------------------------------------------------------- hero

    final Runnable trendTick = () -> { trendIndex++; renderHero(); };

    void renderHero() {
        if (hero == null) return;
        ui.removeCallbacks(trendTick);
        App a = row == 0 ? focusedApp() : row == -1 && !dock.isEmpty() ? dock.get(Math.min(lastDockCol, dock.size() - 1)) : null;
        heroAction.setVisibility(a == null ? View.GONE : View.VISIBLE);
        if (a != null && a.pkg.equals(Trending.YT) && !trending.isEmpty()) {
            hero.stopPreview();
            String lang = TvViewer.get(this).getLanguage();
            Trending.Video v = trending.get(trendIndex % trending.size());
            hero.context(v.thumb);
            hero.text(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.home_trending_yt", String.valueOf(trendIndex % trending.size() + 1)), v.title, v.channel + (v.views.isEmpty() ? "" : "  ·  " + v.views));
            String playVid = tech.kousozo.tahoetv.core.I18n.get(lang, "tv.home_play_video");
            heroActionIcon.setImageResource(R.drawable.ic_play); heroActionText.setText(playVid); heroAction.setContentDescription(playVid);
            if (row == 0) ui.postDelayed(trendTick, 7000); // hold still while the button is focused
            return;
        }
        if (a != null && a.pkg.equals(Netflix.PKG) && !netflix.isEmpty()) {
            hero.stopPreview();
            String lang = TvViewer.get(this).getLanguage();
            Trending.Video v = netflix.get(trendIndex % netflix.size());
            hero.context(v.thumb);
            hero.text(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.home_netflix_pop", String.valueOf(trendIndex % netflix.size() + 1)), v.title, v.channel);
            String openNf = tech.kousozo.tahoetv.core.I18n.get(lang, "tv.home_open_netflix");
            heroActionIcon.setImageResource(R.drawable.ic_play); heroActionText.setText(openNf); heroAction.setContentDescription(openNf);
            if (row == 0) ui.postDelayed(trendTick, 7000);
            return;
        }
        if (a != null) hero.context(a.banner, a.pkg); // the focused app's own artwork fills the background
        else hero.context(null);
        if (a != null && a.pkg.equals(LiveTv.PKG)) {
            String lang = TvViewer.get(this).getLanguage();
            String sub = tech.kousozo.tahoetv.core.CountryCatalog.featuredSubtitle(
                    Live.cached != null ? Live.cached : Collections.emptyList(),
                    TvViewer.get(this).getCountry(), lang);
            hero.text(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.home_featured_badge"), "Live TV", sub);
            heroActionIcon.setImageResource(R.drawable.ic_play);
            String watchTxt = tech.kousozo.tahoetv.core.I18n.get(lang, "tv.home_watch_livetv");
            heroActionText.setText(watchTxt); heroAction.setContentDescription(watchTxt);
            // every playable source of the first channels, healthiest first: the preview walks through them until one plays
            java.util.List<tech.kousozo.tahoetv.core.M3u.Channel> previewList = new java.util.ArrayList<>();
            if (Live.cached != null) {
                for (tech.kousozo.tahoetv.core.LocalTv.Entry en : Live.cached) {
                    java.util.List<tech.kousozo.tahoetv.core.M3u.Channel> raw = new java.util.ArrayList<>();
                    raw.add(en.getChannel()); raw.addAll(en.getAlternates());
                    for (tech.kousozo.tahoetv.core.M3u.Channel c0 : tech.kousozo.tahoetv.core.SourceHealth.order(raw)) {
                        if (!c0.getDrm() && !tech.kousozo.tahoetv.core.Drm.isDash(c0.getUrl(), c0.getDrmType(), c0.getManifestType())) previewList.add(c0);
                    }
                    if (previewList.size() >= 6) break;
                }
            }
            if (row <= 0 && sheet == null && profiles == null && !previewList.isEmpty()) hero.previewChannels(previewList);
            else hero.stopPreview();
            return;
        }
        hero.stopPreview();
        if (a != null) {
            String lang = TvViewer.get(this).getLanguage();
            hero.text(tech.kousozo.tahoetv.core.I18n.get(lang, a.system ? "tv.home_sys_app" : "tv.home_dock_app"), a.label, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.home_hold_ok_opts"));
            String openApp = tech.kousozo.tahoetv.core.I18n.get(lang, "tv.home_open_app", a.label);
            heroActionIcon.setImageResource(R.drawable.ic_play); heroActionText.setText(openApp); heroAction.setContentDescription(openApp);
            return;
        }
        // Idle: editorial date + weather + next prayer (+ phone music).
        String lang = TvViewer.get(this).getLanguage();
        JSONObject cur = weather == null ? null : weather.optJSONObject("current");
        SimpleDateFormat df = new SimpleDateFormat("EEEE, d MMMM", Locale.forLanguageTag(lang));
        df.setTimeZone(Prayer.tz(this));
        String date = df.format(new Date());
        String w = cur == null ? "" : Math.round(cur.optDouble("temperature_2m")) + "° " + Weather.text(this, cur.optInt("weather_code"));
        Object[] n = Prayer.next(this);
        String p = n == null ? "" : tech.kousozo.tahoetv.core.I18n.get(lang, "tv.home_prayer_in", String.valueOf(n[0]), Prayer.countdown(this, (Long) n[1]));
        String meta = (phoneMedia != null ? "♪ " + phoneMedia.optString("title") + " — " + phoneMedia.optString("artist") + "  ·  " : "") + p;
        hero.text(date + (w.isEmpty() ? "" : "  ·  " + w), Cfg.s(this, "city", R.string.city), meta);
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
        if (hero != null) hero.stopPreview();
        if (kids()) {
            tech.kousozo.tahoetv.core.KidsPolicy.EvalResult ev = Kids.evaluate(this);
            if (!ev.getAllowed()) {
                openSheet(new ParentalSheets.KidsBlocked(this, ev));
                return;
            }
            if (!LiveTv.PKG.equals(a.pkg)) {
                launchedKidsAppLabel = a.label;
                launchedKidsAtMs = System.currentTimeMillis();
            }
        }
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
            catch (Exception e) { toast(tech.kousozo.tahoetv.core.I18n.get(TvViewer.get(this).getLanguage(), "tv.home_cannot_open")); root.removeView(t); transition = null; }
        }, Ui.MED - 40);
    }

    void start(Intent i) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception e) { toast(tech.kousozo.tahoetv.core.I18n.get(TvViewer.get(this).getLanguage(), "tv.home_cannot_open")); }
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ---------------------------------------------------------------- sheets

    void updateFocusBlocking() {
        if (root == null) return;
        boolean homeOwnsFocus = sheet == null && profiles == null;
        root.setDescendantFocusability(homeOwnsFocus ? ViewGroup.FOCUS_BLOCK_DESCENDANTS : ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (homeOwnsFocus) root.clearFocus();
    }

    void openSheet(View v) {
        if (hero != null) hero.stopPreview();
        closeSheet();
        pendingCenterKey = false;
        sheetView = v; sheet = (Sheet) v;
        updateFocusBlocking();
        root.addView(v, new FrameLayout.LayoutParams(-1, -1));
        long d = Ui.dur(this, Ui.SLOW);
        if (d <= 0) {
            v.setAlpha(1f); v.setScaleX(1f); v.setScaleY(1f);
            return;
        }
        v.setAlpha(0); v.setScaleX(1.03f); v.setScaleY(1.03f);
        v.animate().alpha(1).scaleX(1).scaleY(1).setDuration(d).setInterpolator(Ui.drawer()).start();
    }

    void closeSheet() {
        pendingCenterKey = false;
        if (sheetView == null) { updateFocusBlocking(); return; }
        View v = sheetView; Sheet s = sheet;
        sheetView = null; sheet = null;
        updateFocusBlocking();
        s.onClose();
        long d = Ui.dur(this, Ui.FAST);
        if (d <= 0) {
            root.removeView(v);
        } else {
            v.animate().alpha(0).setDuration(d).setInterpolator(Ui.out()).withEndAction(() -> root.removeView(v)).start();
        }
    }

    void openWeather() { if (weather != null) openSheet(new Weather.Panel(this, weather)); else { toast(tech.kousozo.tahoetv.core.I18n.get(TvViewer.get(this).getLanguage(), "tv.home_loading_weather")); refreshData(true); } }
    void openPrayer() { openSheet(new Prayer.Panel(this)); }
    void openControl() { openSheet(new Sheets.Control(this)); }
    void openPair() { if (kids()) toast(tech.kousozo.tahoetv.core.I18n.get(TvViewer.get(this).getLanguage(), "tv.home_kids_unavailable")); else openSheet(new ConnectSheets.Pair(this)); }
    void openAssistant() { if (kids()) toast(tech.kousozo.tahoetv.core.I18n.get(TvViewer.get(this).getLanguage(), "tv.home_kids_unavailable")); else openSheet(new Assistant(this, remote)); }
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
    void tap(int key) { tap(this, key); }

    static void tap(android.app.Activity a, int key) {
        long t = android.os.SystemClock.uptimeMillis();
        a.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, key, 0));
        a.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, key, 0));
    }

    static int gamepadToRemote(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return KeyEvent.KEYCODE_DPAD_CENTER;
            case KeyEvent.KEYCODE_BUTTON_B:
                return KeyEvent.KEYCODE_BACK;
            default:
                return k;
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = gamepadToRemote(e.getKeyCode());
        if (k != e.getKeyCode()) e = new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(), k, e.getRepeatCount(), e.getMetaState(), e.getDeviceId(), e.getScanCode(), e.getFlags(), e.getSource());
        if (sheet != null && k != KeyEvent.KEYCODE_HOME) { // first: the PIN pad opens over the profile picker
            if (sheetView instanceof ParentalSheets.PinPad && !sheetView.isEnabled()) return true; // pause after a wrong PIN
            if (sheet.key(e)) return true;
            if (e.getAction() == KeyEvent.ACTION_DOWN && (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_MENU)) closeSheet();
            return true;
        }
        if (profiles != null && k != KeyEvent.KEYCODE_HOME) return profiles.key(e);
        if (root != null && root.findFocus() != null) updateFocusBlocking();
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
        if (!TvGuard.isAndroidTv(this)) {
            if (k == KeyEvent.KEYCODE_BACK) { finishAndRemoveTask(); return true; }
            return super.onKeyDown(k, e);
        }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: move(-1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: move(0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(0, 1); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                if (e.getRepeatCount() == 0) { pendingCenterKey = true; e.startTracking(); } // act on release so a hold opens the menu
                return true;
            case KeyEvent.KEYCODE_ALL_APPS: if (row >= 1) toDock(); else openPad(); return true;
            case KeyEvent.KEYCODE_MENU: openControl(); return true;
            case KeyEvent.KEYCODE_BACK: if (row != 0) toDock(); return true;
        }
        return super.onKeyDown(k, e);
    }

    @Override public boolean onKeyLongPress(int k, KeyEvent e) {
        if (!TvGuard.isAndroidTv(this)) return super.onKeyLongPress(k, e);
        if ((k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) && row >= 0) { pendingCenterKey = false; openAppMenu(); return true; }
        if ((k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) && row == -1) { pendingCenterKey = false; row = 0; col = lastDockCol; openAppMenu(); return true; }
        return super.onKeyLongPress(k, e);
    }

    @Override public boolean onKeyUp(int k, KeyEvent e) {
        if (!TvGuard.isAndroidTv(this)) return super.onKeyUp(k, e);
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
            boolean fire = !e.isCanceled() && (pendingCenterKey || e.isTracking() || e.getDownTime() == e.getEventTime()); // synthetic taps also count
            pendingCenterKey = false;
            if (fire) {
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
        if (!TvGuard.isAndroidTv(this)) return;
        updateFocusBlocking();
        closeSheet();
        if (Intent.ACTION_ALL_APPS.equals(i.getAction())) openPad();
        else toDock(); // Home: always back to the dock
    }

    // ---------------------------------------------------------------- companion (phone) & sleep timer

    static final int[] SLEEP = RemoteController.SLEEP;

    int sleepLeft() { return remote.sleepLeft(); }
    void stepSleep(int dir) { remote.stepSleep(dir); }
    void pushState() { remote.pushState(); }
    void sendLog() { remote.sendLog(); }
    void phoneMediaCommand(String c) { remote.phoneMediaCommand(c); }
    String tvName() { return HomeSettingsBridge.tvName(this); }
}
