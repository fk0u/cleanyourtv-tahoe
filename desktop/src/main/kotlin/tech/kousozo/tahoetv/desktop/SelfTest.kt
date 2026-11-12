package tech.kousozo.tahoetv.desktop

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javafx.application.Platform
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import tech.kousozo.tahoetv.core.DiagLog
import tech.kousozo.tahoetv.core.HlsProbe
import tech.kousozo.tahoetv.core.LocalTv
import tech.kousozo.tahoetv.core.Sources
import tech.kousozo.tahoetv.core.Web

/** `Tahoe TV --selftest [report-file]`: runs the core paths of the desktop app one by one, without a window, and writes
 *  a plain report (also to ~/.tahoetv/selftest.txt). Used by CI on Windows/macOS against the packaged app, and for
 *  support ("Kirim log") when something fails on one OS only. Every step is timed and its error kept, nothing stops it. */
object SelfTest {
    private val out = StringBuilder()
    private var failed = 0

    private fun line(s: String) { out.appendLine(s); println(s) }

    private fun <T> step(name: String, block: () -> T): T? {
        val t = System.currentTimeMillis()
        return try {
            val r = block()
            line("OK   $name (${System.currentTimeMillis() - t} ms): $r"); r
        } catch (e: Throwable) {
            failed++
            line("FAIL $name (${System.currentTimeMillis() - t} ms): ${e.javaClass.name}: ${e.message}")
            e.stackTrace.take(6).forEach { line("       at $it") }
            null
        }
    }

    fun run(reportPath: String?): Int {
        line("Tahoe TV selftest · ${System.getProperty("jpackage.app-version") ?: "dev"}")
        line("os=${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")} · java=${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
        line("java.home=${System.getProperty("java.home")}")
        line("user.home=${System.getProperty("user.home")} · user.dir=${System.getProperty("user.dir")} · charset=${java.nio.charset.Charset.defaultCharset()} · file.encoding=${System.getProperty("file.encoding")}")
        line("modules=${ModuleLayer.boot().modules().map { it.name }.sorted().joinToString(",")}")

        step("home writable") { File(System.getProperty("user.home"), ".tahoetv").apply { mkdirs() }.let { d -> File(d, "w.tmp").apply { writeText("x"); delete() }; d.path } }
        step("https GET (iptv-org)") { Web.get(tech.kousozo.tahoetv.core.M3u.DEFAULT, 15_000).length.toString() + " bytes" }
        step("TLS providers") { java.security.Security.getProviders().joinToString(",") { it.name } }
        val all = step("load channels") { Sources.loadAll("") }
        val entries = all?.let { step("arrange") { LocalTv.arrange(it, "") } }
        line("     channels=${all?.size} entries=${entries?.size}")
        all?.take(80)?.let { sample -> step("check status (80)") { Sources.checkStatus(sample, 3500).count { it.value }.toString() + " live of ${sample.size}" } }
        val dw = "https://dwamdstream102.akamaized.net/hls/live/2015525/dwstream102/index.m3u8"
        step("HlsProbe (DW)") { val (t, u) = HlsProbe.fetch(dw, emptyMap(), 10_000); "${HlsProbe.verdict(t, u, emptyMap(), 10_000, java.time.Instant.now())} ${t.length} chars" }
        val proxied = HlsProxy.wrap(dw, mapOf("User-Agent" to "TahoeTV"))
        step("HlsProxy fetch") { (URI(proxied).toURL().openConnection() as HttpURLConnection).run { connectTimeout = 8000; readTimeout = 15000; "HTTP $responseCode, ${inputStream.readBytes().decodeToString().lineSequence().first()}" } }
        step("IptvServer start + playlist") {
            IptvServer.start(0)
            val p = IptvServer.port.value
            val (code, size) = (URI("http://127.0.0.1:$p/playlist.m3u").toURL().openConnection() as HttpURLConnection).run { responseCode to inputStream.readBytes().size }
            "port=$p HTTP $code $size bytes, channels=${IptvServer.channelCount.value}"
        }
        step("JavaFX startup") { val f = CompletableFuture<String>(); Platform.setImplicitExit(false); Platform.startup { f.complete(System.getProperty("javafx.runtime.version") ?: "?") }; f.get(20, TimeUnit.SECONDS) }
        fun play(label: String, url: String) = step("JavaFX play $label") {
            val f = CompletableFuture<String>()
            var mp: MediaPlayer? = null
            Platform.runLater {
                try {
                    val p = MediaPlayer(Media(url)); mp = p
                    p.setOnReady { f.complete("READY ${p.media.width}x${p.media.height}") }
                    p.setOnError { f.completeExceptionally(IllegalStateException("${p.error?.type}: ${p.error?.message}")) }
                    p.play()
                } catch (e: Throwable) { f.completeExceptionally(e) }
            }
            try { f.get(30, TimeUnit.SECONDS) } finally { Platform.runLater { mp?.dispose() } }
        }
        play("https direct (DW)", dw)
        play("via HlsProxy (DW)", proxied)
        entries?.firstOrNull { it.channel.headers.isNotEmpty() }?.let { e -> play("relay channel ${e.name}", HlsProxy.wrap(e.channel.url, e.channel.headers)) }

        line("-- ${if (failed == 0) "ALL OK" else "$failed FAILED"} --")
        line("-- diag log --"); line(DiagLog.text())
        val text = out.toString()
        runCatching { File(System.getProperty("user.home"), ".tahoetv/selftest.txt").writeText(text) }
        reportPath?.let { runCatching { File(it).writeText(text) } }
        runCatching { IptvServer.stop() }
        return failed
    }
}
