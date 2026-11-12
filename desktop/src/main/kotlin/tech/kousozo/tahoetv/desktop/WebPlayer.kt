package tech.kousozo.tahoetv.desktop

import java.io.File
import java.net.URLEncoder
import tech.kousozo.tahoetv.core.DiagLog
import tech.kousozo.tahoetv.core.M3u

/** Live TV in the browser engine instead of JavaFX: the IPTV server's /watch page (hls.js over the server's relay,
 *  which adds each source's headers). Used on Windows, where JavaFX's media stack fails on HLS ("Could not create
 *  player!") for every channel. Opens in an Edge app window (no tabs or address bar, so it feels like a player), or the
 *  default browser when Edge isn't there. */
object WebPlayer {
    /** Windows: always (JavaFX can't play the channels there). */
    val preferred: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private var last: String? = null

    /** Opens [streams] (main stream first, then backups) titled [title]. Returns an error text, or null when opened. */
    fun open(streams: List<M3u.Channel>, title: String): String? {
        val port = IptvServer.ensureRunning()
        if (!IptvServer.isRunning.value) return "Server pemutar tidak bisa dijalankan."
        val ids = streams.mapNotNull { IptvServer.idFor(it.url) }.distinct()
        if (ids.isEmpty()) return "Saluran ini tidak ada di daftar server."
        val url = "http://127.0.0.1:$port/watch?t=" + URLEncoder.encode(title, "UTF-8") + "&ids=" + URLEncoder.encode(ids.joinToString(","), "UTF-8")
        last = url
        DiagLog.add("live", "$title → web player (${ids.size} sumber)")
        return launch(url)
    }

    /** "Tampilkan pemutar": opens the last channel again. */
    fun reopen() { last?.let(::launch) }

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
