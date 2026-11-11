package tech.kousozo.tahoetv.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI

/** Records a live HLS channel to one file by following its playlist and appending every new segment, until [stopAt].
 *  MPEG-TS segments simply concatenate; fMP4 streams get their init section (`#EXT-X-MAP`) written before the first
 *  segment and again whenever it changes, which keeps the file playable. `#EXT-X-BYTERANGE` segments are fetched with
 *  HTTP Range. Encrypted streams (`#EXT-X-KEY` other than NONE) are refused: no keys are handled here.
 *  Each segment is downloaded fully before it is appended, so a failed download never leaves half a piece in the file;
 *  it is retried on the next playlist refresh. The file is started empty (a new run replaces what was there).
 *  Plain JVM (java.net), so the desktop app and the IPTV server can both use it. [run] blocks until done. */
class HlsRecorder(
    private val url: String,
    private val headers: Map<String, String>,
    val out: File,
    private val stopAt: Long,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val get: (String) -> Pair<String, String> = { u -> HlsProbe.fetch(u, headers, 10_000) },
    /** Downloads [url] (only bytes `range`, "start-end" inclusive, when given) into the stream; returns its size. */
    private val download: (url: String, range: String?, out: java.io.OutputStream) -> Long = { u, r, o -> copy(u, headers, r, o) },
) {
    @Volatile var bytes = 0L; private set
    @Volatile var segments = 0; private set
    /** Segments given up on after [RETRIES] failed downloads: the recording has a gap there. */
    @Volatile var skipped = 0; private set
    @Volatile var error: String? = null; private set
    @Volatile private var cancelled = false

    /** Stops after the segment being downloaded (at most one segment's timeout). */
    fun cancel() { cancelled = true }

    /** Returns true when it stopped normally (time reached, cancelled, or the stream ended), false on failure ([error]).
     *  A failure to write the file (disk full…) is a failure, not a skipped segment. */
    fun run(): Boolean {
        out.parentFile?.mkdirs()
        return try {
            FileOutputStream(out, false).use { o -> loop(o) }
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName; false
        }
    }

    private data class Seg(val url: String, val range: String?) { val key get() = if (range == null) url else "$url#$range" }

    private fun loop(o: FileOutputStream): Boolean {
        val (media, first) = pickMedia() ?: return false
        var pending = first // the playlist already downloaded while picking: its segments count too
        val done = LinkedHashSet<String>()      // written (or given up on): never fetched again
        val tries = HashMap<String, Int>()
        var writtenMap: String? = null
        var joined = false
        var failures = 0
        while (!cancelled && clock() < stopAt) {
            val (text, base) = pending?.also { pending = null } ?: try { get(media) } catch (e: Exception) {
                if (++failures >= 6) { error = "Siaran terputus: ${e.message}"; return false }
                sleep(3000); continue
            }
            failures = 0
            if (KEY.containsMatchIn(text)) { error = "Siaran terenkripsi, tidak bisa direkam."; return false }
            val list = parse(text, base)
            if (!joined) {
                // Start at the live edge, like a player: a server's window can hold minutes or hours of what already aired.
                val skip = if (text.contains("#EXT-X-ENDLIST")) 0 else (list.size - LIVE_EDGE).coerceAtLeast(0)
                list.take(skip).forEach { done += it.first.key }
                joined = true
            }
            // a segment still failing when it drops out of the window is lost: stop waiting for it
            val listed = list.mapTo(HashSet()) { it.first.key }
            tries.keys.filter { it !in listed }.forEach { tries -= it; skipped++ }
            for ((seg, map) in list) {
                if (seg.key in done) continue
                if (cancelled || clock() >= stopAt) break
                // Each piece is downloaded whole, then appended: a failed download leaves nothing behind.
                val piece = ByteArrayOutputStream()
                try {
                    if (map != null && map.key != writtenMap) download(map.url, map.range, piece)
                    download(seg.url, seg.range, piece)
                } catch (_: Exception) {
                    val n = (tries[seg.key] ?: 0) + 1
                    if (n >= RETRIES) { done += seg.key; tries -= seg.key; skipped++ } else tries[seg.key] = n
                    continue // retried on the next refresh while it's still listed
                }
                piece.writeTo(o) // an error here (disk full…) ends the recording as a failure
                bytes += piece.size(); segments++
                if (map != null) writtenMap = map.key
                done += seg.key; tries -= seg.key
                if (done.size > 2000) done.remove(done.first()) // only recent segments can reappear
            }
            if (text.contains("#EXT-X-ENDLIST") && tries.isEmpty()) break // the broadcast ended
            sleep((target(text) * 500).toLong().coerceIn(1000, 6000)) // half a segment: new pieces show up about that often
        }
        return true
    }

    /** Segments of a media playlist, each with the init section (`#EXT-X-MAP`) it needs. Lines that aren't valid URIs are skipped. */
    private fun parse(text: String, base: String): List<Pair<Seg, Seg?>> {
        val res = ArrayList<Pair<Seg, Seg?>>()
        var map: Seg? = null
        var range: String? = null
        val ends = HashMap<String, Long>() // a BYTERANGE without @offset continues where the last one of that file ended
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MAP:") -> map = URI_ATTR.find(line)?.groupValues?.get(1)?.let { resolve(base, it) }?.let { u ->
                    Seg(u, BR_ATTR.find(line)?.groupValues?.get(1)?.let { r -> toRange(r, 0) })
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> range = line.substringAfter(':').trim()
                line.isNotEmpty() && !line.startsWith("#") -> {
                    val u = resolve(base, line)
                    if (u != null) {
                        val r = range?.let { toRange(it, ends[u] ?: 0) }
                        r?.let { ends[u] = it.substringAfter('-').toLong() + 1 }
                        res += Seg(u, r) to map
                    }
                    range = null
                }
            }
        }
        return res
    }

    private fun target(text: String) = text.lineSequence().firstOrNull { it.startsWith("#EXT-X-TARGETDURATION:") }
        ?.substringAfter(':')?.trim()?.toDoubleOrNull() ?: 6.0

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
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }?.let { resolve(base, it) } ?: return@forEachIndexed
            if (b > bw) { bw = b; best = uri }
        }
        return (best ?: base) to null
    }

    companion object {
        /** Segments taken from the first playlist: about what a player buffers when it joins. */
        private const val LIVE_EDGE = 3
        /** Download attempts per segment before it's given up (one per playlist refresh, while it's listed). */
        private const val RETRIES = 3
        private val KEY = Regex("""#EXT-X-KEY:(?![^\n]*METHOD=NONE)""")
        private val URI_ATTR = Regex("""URI="([^"]+)"""")
        private val BR_ATTR = Regex("""BYTERANGE="([^"]+)"""")
        private val BANDWIDTH = Regex("""BANDWIDTH=(\d+)""")

        private fun resolve(base: String, ref: String) = runCatching { URI(base).resolve(ref).toString() }.getOrNull()

        /** "length[@offset]" → "start-end" (inclusive, as in an HTTP Range header); [next] when there's no offset. */
        internal fun toRange(spec: String, next: Long): String? {
            val n = spec.substringBefore('@').trim().toLongOrNull() ?: return null
            val at = if ('@' in spec) spec.substringAfter('@').trim().toLongOrNull() ?: return null else next
            return if (n <= 0) null else "$at-${at + n - 1}"
        }

        /** Writes one segment (or bytes [range] of it) to [o]; returns its size. */
        fun copy(url: String, headers: Map<String, String>, range: String?, o: java.io.OutputStream): Long {
            var u = url
            repeat(5) {
                val c = URI(u).toURL().openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 20_000; c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", "TahoeTV"); headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (range != null) c.setRequestProperty("Range", "bytes=$range")
                try {
                    if (c.responseCode in 300..399) { u = URI(u).resolve(c.getHeaderField("Location")).toString(); return@repeat }
                    if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
                    // A server that ignores Range sends the whole file: cut the requested part out of it.
                    if (range != null && c.responseCode == 200) {
                        val a = range.substringBefore('-').toLong(); val n = range.substringAfter('-').toLong() - a + 1
                        return c.inputStream.use { s ->
                            var pos = 0L; var left = n; val buf = ByteArray(64 * 1024)
                            while (left > 0) {
                                val r = s.read(buf); if (r < 0) break
                                val from = (a - pos).coerceIn(0, r.toLong()).toInt()
                                val take = minOf((r - from).toLong(), left).toInt()
                                if (take > 0) { o.write(buf, from, take); left -= take }
                                pos += r
                            }
                            n - left
                        }
                    }
                    return c.inputStream.use { it.copyTo(o) }
                } finally { c.disconnect() }
            }
            error("too many redirects")
        }
    }
}
