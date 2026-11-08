package tv.clean.companion;

import android.content.Context;
import java.io.File;
import java.util.*;
import java.util.function.Consumer;
import dadb.AdbKeyPair;
import dadb.AdbShellResponse;
import dadb.Dadb;

/** Everything the phone does to a TV over network ADB (port 5555), with the phone's own RSA key.
 *  The first connection makes the TV show "Allow USB debugging?"; with "Always allow" ticked it never asks again. */
final class Tv implements AutoCloseable {
    static final String PKG = "tv.clean.home";

    final String host;
    final Dadb adb;
    final Map<String, String> props = new HashMap<>();

    private Tv(String host, Dadb adb) { this.host = host; this.adb = adb; }

    /** Blocking. Waits up to ~60 s so there is time to accept the prompt on the TV. */
    static Tv connect(Context c, String host) throws Exception {
        File dir = new File(c.getFilesDir(), "adb");
        File priv = new File(dir, "adbkey"), pub = new File(dir, "adbkey.pub");
        if (!priv.exists()) { dir.mkdirs(); AdbKeyPair.Companion.generate(priv, pub); }
        AdbKeyPair keys = AdbKeyPair.Companion.read(priv, pub);
        Dadb d = Dadb.Companion.create(host, 5555, keys, 8000, 60_000);
        Tv tv = new Tv(host, d);
        tv.sh("echo ok"); // forces the handshake (and the TV's auth prompt)
        for (String p : new String[]{"ro.product.model", "ro.product.manufacturer", "ro.build.version.release",
                "ro.build.version.sdk", "ro.config.low_ram", "ro.serialno", "ro.product.device"})
            tv.props.put(p, tv.sh("getprop " + p).trim());
        return tv;
    }

    String sh(String cmd) throws Exception {
        AdbShellResponse r = adb.shell(cmd);
        return r.getAllOutput();
    }

    int sdk() { try { return Integer.parseInt(props.get("ro.build.version.sdk")); } catch (Exception e) { return 0; } }
    String model() { return props.get("ro.product.manufacturer") + " " + props.get("ro.product.model"); }
    boolean lowRam() { return "true".equals(props.get("ro.config.low_ram")); }

    Set<String> packages() throws Exception {
        Set<String> out = new HashSet<>();
        for (String l : sh("pm list packages").split("\n")) if (l.startsWith("package:")) out.add(l.substring(8).trim());
        return out;
    }

    Set<String> disabled() throws Exception {
        Set<String> out = new HashSet<>();
        for (String l : sh("pm list packages -d").split("\n")) if (l.startsWith("package:")) out.add(l.substring(8).trim());
        return out;
    }

    String installedVersion() throws Exception {
        for (String l : sh("dumpsys package " + PKG + " | grep versionName").split("\n"))
            if (l.contains("versionName=")) return l.substring(l.indexOf('=') + 1).trim();
        return null;
    }

    /** Launchable TV apps: [package, label-ish] from the LEANBACK_LAUNCHER intent. */
    List<String> tvApps() throws Exception {
        List<String> out = new ArrayList<>();
        for (String l : sh("cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER").split("\n")) {
            l = l.trim();
            if (l.startsWith("packageName=")) { String p = l.substring(12); if (!out.contains(p) && !p.equals(PKG)) out.add(p); }
        }
        return out;
    }

    // ---------------------------------------------------------------- debloat

    /** Never disabled: the TV may not boot or work without them (same rule as tools/disable.sh). */
    static boolean protectedPkg(String p) {
        if (p.equals("android") || p.equals("com.android.systemui") || p.equals("com.android.shell") || p.equals("com.android.phone")
                || p.equals("com.android.settings") || p.equals("com.android.tv.settings") || p.equals("com.android.vending")
                || p.equals("com.google.android.gms") || p.equals("com.google.android.gsf") || p.equals("com.google.android.inputmethod.latin")
                || p.equals("com.google.android.tv.remote.service") || p.equals("com.google.android.tungsten.setupwraith")
                || p.equals(PKG)) return true;
        if (p.contains("permissioncontroller") || p.contains("packageinstaller") || p.contains("ext.services") || p.contains("ext.shared")
                || p.contains("modulemetadata") || p.startsWith("com.google.android.overlay.modules")) return true;
        if (p.startsWith("com.android.providers.")) return !(p.endsWith(".calendar") || p.endsWith(".contacts") || p.endsWith(".userdictionary") || p.endsWith(".blockednumber"));
        String l = p.toLowerCase(Locale.ROOT);
        return l.contains("factory") || l.contains(".fac.") || l.contains("factorymenu"); // never touch factory menus
    }

    /** Curated list: package, what it is, ticked by default. Only installed ones are shown. */
    static final String[][] CATALOG = {
            {"com.miui.tv.analytics", "Telemetri Xiaomi", "1"},
            {"tv.alphonso.alphonso_eula", "Pelacak tontonan & iklan (Alphonso ACR)", "1"},
            {"com.google.android.feedback", "Feedback Google", "1"},
            {"com.google.android.tv.bugreportsender", "Pengirim laporan bug", "1"},
            {"com.mitv.tvhome.atv", "PatchWall (beranda iklan Xiaomi)", "1"},
            {"com.mitv.tvhome.michannel", "Kanal rekomendasi PatchWall", "1"},
            {"com.xm.webcontent", "Konten web PatchWall", "1"},
            {"com.google.android.tvrecommendations", "Baris rekomendasi Google", "1"},
            {"com.mitv.videoplayer", "Pemutar video Mi", "1"},
            {"com.mitv.download.service", "Downloader Mi", "1"},
            {"com.google.android.youtube.tvmusic", "YouTube Music", "0"},
            {"com.google.android.videos", "Google Play Film", "0"},
            {"com.google.android.play.games", "Google Play Games", "0"},
            {"com.google.android.katniss", "Google Assistant (tombol mic)", "0"},
            {"com.google.android.apps.mediashell", "Chromecast bawaan", "0"},
            {"com.mitv.milinkservice", "Mi Miracast / screen share", "0"},
            {"com.tcl.usercenter", "TCL user center", "1"},
            {"com.tcl.tvweishi", "TCL pembersih / iklan", "1"},
    };

    static final String[] STOCK_LAUNCHERS = {"com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx"};

    // ---------------------------------------------------------------- install + setup

    /** Install the launcher APK bundled in this app (assets/tahoe-launcher.apk). */
    void installLauncher(Context c, Consumer<String> log) throws Exception {
        File apk = new File(c.getCacheDir(), "tahoe-launcher.apk");
        try (java.io.InputStream in = c.getAssets().open("tahoe-launcher.apk"); java.io.OutputStream out = new java.io.FileOutputStream(apk)) {
            byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
        log.accept("Mengirim APK (" + apk.length() / 1024 + " KB)…");
        adb.push(apk, "/data/local/tmp/tahoe.apk", 0644, System.currentTimeMillis());
        log.accept("Memasang…");
        String r = sh("pm install -r -g /data/local/tmp/tahoe.apk; rm -f /data/local/tmp/tahoe.apk");
        if (!r.contains("Success")) throw new Exception("Pasang gagal: " + r.trim());
    }

    /** Grants, Home, stock launcher, screensaver. Returns the package that now answers Home. */
    String makeHome(Consumer<String> log, boolean disableStock) throws Exception {
        String[] grants = {
                "pm grant " + PKG + " android.permission.WRITE_SECURE_SETTINGS",
                "pm grant " + PKG + " android.permission.ACCESS_FINE_LOCATION",
                "pm grant " + PKG + " android.permission.RECORD_AUDIO",
                "appops set " + PKG + " SYSTEM_ALERT_WINDOW allow",
                "appops set " + PKG + " GET_USAGE_STATS allow",
                "settings put secure screensaver_components " + PKG + "/" + PKG + ".Lock",
                "settings put secure screensaver_enabled 1"};
        for (String g : grants) sh(g); // one command per call: chained pm commands can stop after the first
        log.accept("Izin diberikan, screensaver dipasang");
        sh("cmd package set-home-activity " + PKG + "/.Home");
        String home = homePackage();
        if (!home.equals(PKG) && disableStock) {
            Set<String> pkgs = packages();
            for (String s : STOCK_LAUNCHERS) if (pkgs.contains(s)) { sh("pm disable-user --user 0 " + s); log.accept("Launcher bawaan dimatikan: " + s); }
            home = homePackage();
        }
        sh("input keyevent KEYCODE_HOME");
        return home;
    }

    String homePackage() throws Exception {
        for (String l : sh("cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME").split("\n"))
            if (l.trim().startsWith("packageName=")) return l.trim().substring(12);
        return "?";
    }

    /** Push settings to the launcher (Config receiver; only the shell may send it). Returns the receiver's JSON. */
    String configure(Map<String, String> values, boolean pair) throws Exception {
        StringBuilder cmd = new StringBuilder("am broadcast -n " + PKG + "/.Config");
        for (Map.Entry<String, String> e : values.entrySet())
            if (e.getValue() != null) cmd.append(" --es ").append(e.getKey()).append(' ').append(quote(e.getValue()));
        if (pair) cmd.append(" --ez pair true");
        String out = sh(cmd.toString());
        int i = out.indexOf("data=\"");
        return i < 0 ? out : out.substring(i + 6, out.lastIndexOf('"'));
    }

    static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    @Override public void close() { try { adb.close(); } catch (Exception ignored) {} }
}
