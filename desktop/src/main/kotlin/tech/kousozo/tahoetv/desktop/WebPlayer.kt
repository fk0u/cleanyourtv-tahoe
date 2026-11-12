package tech.kousozo.tahoetv.desktop

import java.io.File
import java.net.URLEncoder
import javax.swing.SwingUtilities
import org.json.JSONArray
import org.json.JSONObject
import tech.kousozo.tahoetv.core.DiagLog
import tech.kousozo.tahoetv.core.M3u

/** Live TV in the browser engine instead of JavaFX: the IPTV server's /watch page (hls.js over the server's relay,
 *  which adds each source's headers). Used on Windows, where JavaFX's media stack fails on HLS ("Could not create
 *  player!") for every channel. Opens in an Edge app window (no tabs or address bar), or the default browser.
 *
 *  One window, driven by the app: the page polls GET /api/watch for the current session (channel, sources, stop), and
 *  reports back (playing / failed / closed, ◀ ▶). So switching channels reuses the open window, Stop stops it, the app
 *  learns when video really plays, and ◀ ▶ in the page change channel in the app. */
object WebPlayer {
    /** Windows: always (JavaFX can't play the channels there). */
    val preferred: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    @Volatile private var seq = 0
    @Volatile private var ids: List<String> = emptyList()
    @Volatile private var title = ""
    @Volatile private var stopped = true
    @Volatile private var lastPoll = 0L
    private var onSwitch: (Int) -> Unit = {}
    private var onStatus: (String?) -> Unit = {}

    /** The page polled within the last few seconds: it will pick up a new session by itself. */
    private val pageAlive get() = System.currentTimeMillis() - lastPoll < 5_000

    /** Plays [streams] (main stream first, then backups) in the web player. HLS only: DASH/DRM sources are skipped (the
     *  dashboard's Shaka player handles those). Returns an error text, or null when the page has it. Blocks (server start). */
    @Synchronized fun open(streams: List<M3u.Channel>, title: String, onSwitch: (Int) -> Unit, onStatus: (String?) -> Unit): String? {
        val hls = streams.filter { !it.drm && !it.url.substringBefore('?').endsWith(".mpd", true) }
        if (hls.isEmpty()) return "Saluran ini memakai DASH/DRM. Tonton di Web Player server (Setelan → Server IPTV)."
        val port = IptvServer.ensureRunning()
        if (!IptvServer.isRunning.value) return "Server pemutar tidak bisa dijalankan."
        val found = hls.mapNotNull { IptvServer.idFor(it.url) }.distinct()
        if (found.isEmpty()) return "Saluran ini tidak ada di daftar server."
        this.onSwitch = onSwitch; this.onStatus = onStatus
        ids = found; this.title = title; stopped = false; seq++
        DiagLog.add("live", "$title → web player (${found.size} sumber)")
        return if (pageAlive) null else launch(url(port))
    }

    /** "Tampilkan pemutar": the last channel again (on the server's current port). */
    @Synchronized fun reopen() {
        if (ids.isEmpty()) return
        stopped = false; seq++
        val port = IptvServer.ensureRunning()
        if (!pageAlive) launch(url(port))
    }

    /** Stop: the page stops playing and closes itself. */
    fun stop() { if (!stopped) { stopped = true; seq++ } }

    // ------------------------------------------------------------------ the page's side (via IptvServer /api/watch)

    fun state(): JSONObject {
        lastPoll = System.currentTimeMillis()
        return JSONObject().put("seq", seq).put("ids", JSONArray(ids)).put("title", title).put("stopped", stopped)
            .put("q", Settings.quality.maxHeight).put("lat", Settings.latency.offsetMs / 1000)
    }

    /** The page reports for session [s]: "playing", "failed" (with [msg]) or "closed". Older sessions are ignored. */
    fun report(s: Int, event: String, msg: String?) {
        if (s != seq) return
        when (event) {
            "playing" -> SwingUtilities.invokeLater { onStatus(null) }
            "failed" -> SwingUtilities.invokeLater { onStatus(msg?.take(200) ?: "Saluran ini sedang tidak bisa diputar.") }
            "closed" -> { stopped = true; lastPoll = 0; SwingUtilities.invokeLater { onStatus(PlayerWindow.CLOSED) } }
        }
    }

    /** ◀ ▶ in the page: the app picks the previous/next channel and opens it here. */
    fun zap(dir: Int) { SwingUtilities.invokeLater { onSwitch(dir) } }

    private fun url(port: Int) = "http://127.0.0.1:$port/watch?t=" + URLEncoder.encode(title, "UTF-8") +
        "&ids=" + URLEncoder.encode(ids.joinToString(","), "UTF-8") + "&s=$seq"

    private fun launch(url: String): String? {
        edge()?.let { exe ->
            // the URL is one argument to the exe itself (no cmd.exe, so '&' in it isn't taken as a command separator)
            if (runCatching { ProcessBuilder(exe.path, "--app=$url", "--new-window").start() }.isSuccess) return null
        }
        return runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)); null }
            .getOrElse { DiagLog.error("live", "web player tidak bisa dibuka", it); "Browser tidak bisa dibuka. Buka sendiri: $url" }
    }

    private fun edge(): File? = listOfNotNull(System.getenv("ProgramFiles(x86)"), System.getenv("ProgramFiles"), System.getenv("LOCALAPPDATA"))
        .map { File(it, "Microsoft/Edge/Application/msedge.exe") }.firstOrNull { it.isFile }
}
