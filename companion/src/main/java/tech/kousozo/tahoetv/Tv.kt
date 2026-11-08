package tech.kousozo.tahoetv

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import org.json.JSONObject

/** Everything the phone does to a TV over network ADB (port 5555), with the phone's own RSA key.
 *  The first connection makes the TV show "Allow USB debugging?"; with "Always allow" ticked it never asks again.
 *  All calls block: run them off the main thread. */
class Tv private constructor(val host: String, private val adb: Dadb) : AutoCloseable {
    val props = mutableMapOf<String, String>()

    fun sh(cmd: String): String = adb.shell(cmd).allOutput

    val sdk get() = props["ro.build.version.sdk"]?.toIntOrNull() ?: 0
    val model get() = "${props["ro.product.manufacturer"]} ${props["ro.product.model"]}"
    val release get() = props["ro.build.version.release"].orEmpty()
    val lowRam get() = props["ro.config.low_ram"] == "true"
    val serial get() = props["ro.serialno"].orEmpty()

    private fun pkgList(flags: String) =
        sh("pm list packages $flags").lines().filter { it.startsWith("package:") }.map { it.substring(8).trim() }.toSet()

    fun packages() = pkgList("")
    fun disabled() = pkgList("-d")

    fun installedVersion(): String? =
        sh("dumpsys package $PKG | grep versionName").lines().firstOrNull { "versionName=" in it }?.substringAfter('=')?.trim()

    /** Launchable TV apps (package names) from the LEANBACK_LAUNCHER intent. */
    fun tvApps(): List<String> =
        sh("cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER")
            .lines().map { it.trim() }.filter { it.startsWith("packageName=") }.map { it.substring(12) }
            .filter { it != PKG }.distinct()

    /** App labels as shown on the TV (best effort; falls back to the package name). */
    fun label(pkg: String): String = KNOWN[pkg] ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }

    /** Install the launcher APK bundled in this app (assets/tahoe-launcher.apk). */
    fun installLauncher(c: Context, log: (String) -> Unit) {
        val apk = File(c.cacheDir, "tahoe-launcher.apk")
        c.assets.open("tahoe-launcher.apk").use { input -> apk.outputStream().use { input.copyTo(it) } }
        log("Mengirim APK (${apk.length() / 1024} KB)…")
        adb.push(apk, "/data/local/tmp/tahoe.apk", 420 /* 0644 */, System.currentTimeMillis())
        log("Memasang…")
        val r = sh("pm install -r -g /data/local/tmp/tahoe.apk; rm -f /data/local/tmp/tahoe.apk")
        if ("Success" !in r) error("Pasang gagal: ${r.trim()}")
    }

    /** Grants, Home, stock launcher, screensaver. Returns the package that now answers Home. */
    fun makeHome(log: (String) -> Unit, disableStock: Boolean): String {
        listOf( // one command per call: chained pm commands can stop after the first
            "pm grant $PKG android.permission.WRITE_SECURE_SETTINGS",
            "pm grant $PKG android.permission.ACCESS_FINE_LOCATION",
            "pm grant $PKG android.permission.RECORD_AUDIO",
            "appops set $PKG SYSTEM_ALERT_WINDOW allow",
            "appops set $PKG GET_USAGE_STATS allow",
            "settings put secure screensaver_components $PKG/$PKG.Lock",
            "settings put secure screensaver_enabled 1",
        ).forEach { sh(it) }
        log("Izin diberikan, screensaver dipasang")
        sh("cmd package set-home-activity $PKG/.Home")
        var home = homePackage()
        if (home != PKG && disableStock) {
            val pkgs = packages()
            STOCK_LAUNCHERS.filter { it in pkgs }.forEach { sh("pm disable-user --user 0 $it"); log("Launcher bawaan dimatikan: $it") }
            home = homePackage()
        }
        sh("input keyevent KEYCODE_HOME")
        return home
    }

    fun homePackage(): String =
        sh("cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME")
            .lines().map { it.trim() }.firstOrNull { it.startsWith("packageName=") }?.substring(12) ?: "?"

    /** Push settings to the launcher (Config receiver; only the shell may send it). Returns the receiver's JSON. */
    fun configure(values: Map<String, String>, pair: Boolean): JSONObject {
        val cmd = StringBuilder("am broadcast -n $PKG/.Config")
        values.forEach { (k, v) -> cmd.append(" --es ").append(k).append(' ').append(quote(v)) }
        if (pair) cmd.append(" --ez pair true")
        val out = sh(cmd.toString())
        val i = out.indexOf("data=\"")
        return if (i < 0) JSONObject() else JSONObject(out.substring(i + 6, out.lastIndexOf('"')))
    }

    override fun close() { runCatching { adb.close() } }

    data class Bloat(val pkg: String, val what: String, val default: Boolean)

    companion object {
        const val PKG = "tv.clean.home"
        val STOCK_LAUNCHERS = listOf("com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx")

        /** Blocking. Waits up to ~60 s so there is time to accept the prompt on the TV. */
        fun connect(c: Context, host: String): Tv {
            val dir = File(c.filesDir, "adb")
            val priv = File(dir, "adbkey"); val pub = File(dir, "adbkey.pub")
            if (!priv.exists()) { dir.mkdirs(); AdbKeyPair.generate(priv, pub) }
            val tv = Tv(host, Dadb.create(host, 5555, AdbKeyPair.read(priv, pub), 8000, 60_000))
            tv.sh("echo ok") // forces the handshake (and the TV's auth prompt)
            listOf("ro.product.model", "ro.product.manufacturer", "ro.build.version.release", "ro.build.version.sdk",
                "ro.config.low_ram", "ro.serialno", "ro.product.device").forEach { tv.props[it] = tv.sh("getprop $it").trim() }
            return tv
        }

        fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

        /** Never disabled: the TV may not boot or work without them (same rule as tools/disable.sh). */
        fun protectedPkg(p: String): Boolean {
            if (p in setOf("android", "com.android.systemui", "com.android.shell", "com.android.phone", "com.android.settings",
                    "com.android.tv.settings", "com.android.vending", "com.google.android.gms", "com.google.android.gsf",
                    "com.google.android.inputmethod.latin", "com.google.android.tv.remote.service",
                    "com.google.android.tungsten.setupwraith", PKG)) return true
            if (listOf("permissioncontroller", "packageinstaller", "ext.services", "ext.shared", "modulemetadata").any { it in p }
                || p.startsWith("com.google.android.overlay.modules")) return true
            if (p.startsWith("com.android.providers."))
                return listOf(".calendar", ".contacts", ".userdictionary", ".blockednumber").none { p.endsWith(it) }
            val l = p.lowercase()
            return "factory" in l || ".fac." in l // never touch factory menus
        }

        /** Curated list: only installed ones are shown. */
        val CATALOG = listOf(
            Bloat("com.miui.tv.analytics", "Telemetri Xiaomi", true),
            Bloat("tv.alphonso.alphonso_eula", "Pelacak tontonan & iklan (Alphonso ACR)", true),
            Bloat("com.google.android.feedback", "Feedback Google", true),
            Bloat("com.google.android.tv.bugreportsender", "Pengirim laporan bug", true),
            Bloat("com.mitv.tvhome.atv", "PatchWall (beranda iklan Xiaomi)", true),
            Bloat("com.mitv.tvhome.michannel", "Kanal rekomendasi PatchWall", true),
            Bloat("com.xm.webcontent", "Konten web PatchWall", true),
            Bloat("com.google.android.tvrecommendations", "Baris rekomendasi Google", true),
            Bloat("com.mitv.videoplayer", "Pemutar video Mi", true),
            Bloat("com.mitv.download.service", "Downloader Mi", true),
            Bloat("com.google.android.youtube.tvmusic", "YouTube Music", false),
            Bloat("com.google.android.videos", "Google Play Film", false),
            Bloat("com.google.android.play.games", "Google Play Games", false),
            Bloat("com.google.android.katniss", "Google Assistant (tombol mic)", false),
            Bloat("com.google.android.apps.mediashell", "Chromecast bawaan", false),
            Bloat("com.mitv.milinkservice", "Mi Miracast / screen share", false),
            Bloat("com.tcl.usercenter", "TCL user center", true),
            Bloat("com.tcl.tvweishi", "TCL pembersih / iklan", true),
        )

        /** Friendly names for the dock picker; anything else shows its last package segment. */
        val KNOWN = mapOf(
            "com.google.android.youtube.tv" to "YouTube", "com.netflix.ninja" to "Netflix",
            "com.spotify.tv.android" to "Spotify", "com.tiktok.tv" to "TikTok", "com.vidio.android.tv" to "Vidio",
            "com.live_streaming_tv.online_tv" to "BitTV", "com.amazon.amazonvideo.livingroom" to "Prime Video",
            "com.disney.disneyplus" to "Disney+", "com.android.vending" to "Play Store", "com.google.android.youtube.tvmusic" to "YouTube Music",
            "com.google.android.videos" to "Google TV", "com.mitv.dream" to "Mi Screensaver", "com.mitv.tvhome.atv" to "PatchWall",
            "nextapp.fx" to "FX File Explorer", "app.tvtoolbox" to "TV Toolbox", "com.nuvio.tv" to "Nuvio", "com.tailscale.ipn" to "Tailscale",
            "com.yablio.sendfilestotv" to "Send Files to TV", "com.google.android.apps.mediashell" to "Chromecast",
        )
    }
}
