package tech.kousozo.tahoetv.core

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI

/** Records a live HLS channel to one file by following its playlist and appending every new segment, until [stopAt].
 *  MPEG-TS segments simply concatenate; fMP4 streams get their init section (`#EXT-X-MAP`) written once first, which
 *  also gives a playable file. Encrypted streams (`#EXT-X-KEY` other than NONE) are refused: no keys are handled here.
 *  Plain JVM (java.net), so the desktop app and the IPTV server can both use it. [run] blocks until done. */
class HlsRecorder(
    private val url: String,
    private val headers: Map<String, String>,
    val out: File,
    private val stopAt: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val get: (String) -> Pair<String, String> = { u -> HlsProbe.fetch(u, headers, 10_000) },
    private val download: (String, java.io.OutputStream) -> Long = { u, o -> copy(u, headers, o) },
) {
    @Volatile var bytes = 0L; private set
    @Volatile var segments = 0; private set
    @Volatile var error: String? = null; private set
    @Volatile private var cancelled = false

    fun cancel() { cancelled = true }

    /** Returns true when it stopped normally (time reached, cancelled, or the stream ended), false on failure ([error]). */
    fun run(): Boolean {
        out.parentFile?.mkdirs()
        return try {
            FileOutputStream(out, true).use { o -> loop(o) }
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName; false
        }
    }

    private fun loop(o: FileOutputStream): Boolean {
        val (media, first) = pickMedia() ?: return false
        var pending = first // the playlist already downloaded while picking: its segments count too
        val seen = LinkedHashSet<String>()
        var mapDone = false
        var failures = 0
        while (!cancelled && clock() < stopAt) {
            val (text, base) = pending?.also { pending = null } ?: try { get(media) } catch (e: Exception) {
                if (++failures >= 6) { error = "Siaran terputus: ${e.message}"; return false }
                sleep(3000); continue
            }
            failures = 0
            if (KEY.containsMatchIn(text)) { error = "Siaran terenkripsi, tidak bisa direkam."; return false }
            if (seen.isEmpty()) {
                // Start at the live edge, like a player: a server's window can hold minutes or hours of what already aired.
                val segs = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
                if (!text.contains("#EXT-X-ENDLIST")) segs.dropLast(LIVE_EDGE).forEach { seen += URI(base).resolve(it).toString() }
            }
            var target = 6.0
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                when {
                    line.startsWith("#EXT-X-TARGETDURATION:") -> target = line.substringAfter(':').trim().toDoubleOrNull() ?: target
                    line.startsWith("#EXT-X-MAP:") && !mapDone -> {
                        val uri = URI_ATTR.find(line)?.groupValues?.get(1) ?: continue
                        bytes += download(URI(base).resolve(uri).toString(), o); mapDone = true
                    }
                    line.isNotEmpty() && !line.startsWith("#") -> {
                        val seg = URI(base).resolve(line).toString()
                        if (seen.add(seg)) {
                            if (cancelled || clock() >= stopAt) break
                            try { bytes += download(seg, o); segments++ } catch (_: Exception) { /* one lost piece: keep going */ }
                            if (seen.size > 2000) seen.remove(seen.first()) // only recent segments can reappear
                        }
                    }
                }
            }
            if (text.contains("#EXT-X-ENDLIST")) break // the broadcast ended
            sleep((target * 500).toLong().coerceIn(1000, 6000)) // half a segment: new pieces show up about that often
        }
        return true
    }

    /** The media playlist to follow: the best (highest BANDWIDTH) variant of a master playlist, or [url] itself
     *  (then with its already downloaded text, so the first window isn't fetched twice). */
    private fun pickMedia(): Pair<String, Pair<String, String>?>? {
        val (text, base) = try { get(url) } catch (e: Exception) { error = "Siaran tidak bisa dibuka: ${e.message}"; return null }
        if (!text.trimStart().startsWith("#EXTM3U")) { error = "Bukan siaran HLS."; return null }
        if (!text.contains("#EXT-X-STREAM-INF")) return base to (text to base)
        val lines = text.lines().map { it.trim() }
        var best: String? = null; var bw = -1L
        lines.forEachIndexed { i, l ->
            if (!l.startsWith("#EXT-X-STREAM-INF")) return@forEachIndexed
            val b = BANDWIDTH.find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@forEachIndexed
            if (b > bw) { bw = b; best = URI(base).resolve(uri).toString() }
        }
        return (best ?: base) to null
    }

    companion object {
        /** Segments taken from the first playlist: about what a player buffers when it joins. */
        private const val LIVE_EDGE = 3
        private val KEY = Regex("""#EXT-X-KEY:(?![^\n]*METHOD=NONE)""")
        private val URI_ATTR = Regex("""URI="([^"]+)"""")
        private val BANDWIDTH = Regex("""BANDWIDTH=(\d+)""")

        /** Appends one segment to [o]; returns its size. */
        fun copy(url: String, headers: Map<String, String>, o: java.io.OutputStream): Long {
            var u = url
            repeat(5) {
                val c = URI(u).toURL().openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 20_000; c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", "TahoeTV"); headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                try {
                    if (c.responseCode in 300..399) { u = URI(u).resolve(c.getHeaderField("Location")).toString(); return@repeat }
                    if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
                    return c.inputStream.use { it.copyTo(o) }
                } finally { c.disconnect() }
            }
            error("too many redirects")
        }
    }
}
