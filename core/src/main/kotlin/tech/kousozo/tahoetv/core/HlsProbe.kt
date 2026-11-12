package tech.kousozo.tahoetv.core

import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Tells a real live HLS channel from a recording that merely answers "200 OK": a playlist that ends
 *  (`#EXT-X-ENDLIST`, `PLAYLIST-TYPE:VOD`) is a finished video that plays from the top and loops, and a live-looking
 *  playlist whose newest segment is stamped far in the past is yesterday's broadcast being replayed. Both used to count as
 *  "LIVE" because the check only looked at the first bytes of the file. Shared by the TV, phone and desktop apps. */
object HlsProbe {
    enum class Kind {
        /** Moving live window (or not enough to tell). */
        LIVE,
        /** Finished playlist: a recording that plays from the start. */
        REPLAY,
        /** Looks live, but the newest segment is old: a replayed broadcast. */
        STALE,
        /** Not an HLS playlist, or not reachable. */
        UNKNOWN,
        /** Answers, but isn't playable: an HTML page instead of a playlist, or a variant list whose variants all fail
         *  (the master playlist was up while its 720p/1080p playlists gave 403/404, so the channel looked LIVE). */
        DEAD
    }

    /** A live window whose newest segment is older than this is not "now". */
    private const val STALE_MS = 15 * 60_000L
    private val VOD_TYPE = Regex("""#EXT-X-PLAYLIST-TYPE:\s*VOD""", RegexOption.IGNORE_CASE)

    /** Verdict for one media playlist's text. A master playlist (variant list) gives [Kind.UNKNOWN]: follow [firstVariant]. */
    fun analyze(text: String, now: Instant = Instant.now()): Kind {
        if (!text.trimStart().startsWith("#EXTM3U")) return Kind.UNKNOWN
        if (text.contains("#EXT-X-STREAM-INF")) return Kind.UNKNOWN
        if (text.contains("#EXT-X-ENDLIST") || VOD_TYPE.containsMatchIn(text)) return Kind.REPLAY
        // Wall-clock end of the newest segment, from #EXT-X-PROGRAM-DATE-TIME (later segments continue from the last stamp).
        var clock: Instant? = null
        var dur = 0.0
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> clock = parseTime(line.substringAfter(':'))
                line.startsWith("#EXTINF:") -> dur = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                line.isNotEmpty() && !line.startsWith("#") -> clock = clock?.plusMillis((dur * 1000).toLong())
            }
        }
        val newest = clock ?: return Kind.LIVE
        return if (now.toEpochMilli() - newest.toEpochMilli() > STALE_MS) Kind.STALE else Kind.LIVE
    }

    /** URL of the first variant of a master playlist, resolved against [base]; null if [text] is not a master playlist. */
    fun firstVariant(text: String, base: String): String? {
        val lines = text.lines().map { it.trim() }
        val i = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
        if (i < 0) return null
        val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
        return runCatching { URI(base).resolve(uri).toString() }.getOrNull()
    }

    data class Variant(val url: String, val height: Int, val inf: String)

    private val HEIGHT = Regex("""RESOLUTION=\d+x(\d+)""")

    /** Variants of a master playlist (in playlist order), resolved against [base]; empty for a media playlist. */
    fun variants(text: String, base: String): List<Variant> {
        val lines = text.lines().map { it.trim() }
        return lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF") }.mapNotNull { i ->
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return@mapNotNull null
            val url = runCatching { URI(base).resolve(uri.replace(" ", "%20")).toString() }.getOrNull() ?: return@mapNotNull null
            Variant(url, HEIGHT.find(lines[i])?.groupValues?.get(1)?.toIntOrNull() ?: 0, lines[i])
        }
    }

    /** Order to try variants in: with a cap, the best one at or under it first; then the rest, smallest first. */
    fun preferred(variants: List<Variant>, maxHeight: Int = 0): List<Variant> {
        if (maxHeight <= 0) return variants
        val (fit, over) = variants.partition { it.height in 1..maxHeight }
        return fit.sortedByDescending { it.height } + (over + variants.filter { it.height == 0 && it !in fit }).distinct().sortedBy { if (it.height == 0) Int.MAX_VALUE else it.height }
    }

    /** The first variant (in [preferred] order, at most [tries]) whose playlist really answers, with that playlist's
     *  text and final URL. Null if none does. Blocks. */
    fun liveVariant(text: String, base: String, headers: Map<String, String>, timeoutMs: Int = 4000, maxHeight: Int = 0, tries: Int = 4): Pair<Variant, Pair<String, String>>? =
        preferred(variants(text, base), maxHeight).take(tries).firstNotNullOfOrNull { v ->
            runCatching { fetch(v.url, headers, timeoutMs) }.getOrNull()?.takeIf { it.first.trimStart().startsWith("#EXTM3U") }?.let { v to it }
        }

    /** A master playlist without its dead variants (checked in parallel), for adaptive players that would otherwise
     *  give up on the first variant that fails (hls.js, LAN players). Media playlists come back unchanged; null when
     *  no variant answers. Blocks for about one [timeoutMs]. */
    fun dropDeadVariants(text: String, base: String, headers: Map<String, String>, timeoutMs: Int = 4000): String? {
        val all = variants(text, base)
        if (all.isEmpty()) return text
        val pool = java.util.concurrent.Executors.newFixedThreadPool(all.size.coerceAtMost(8))
        val alive = try {
            all.map { v -> pool.submit<Boolean> { runCatching { fetch(v.url, headers, timeoutMs).first.trimStart().startsWith("#EXTM3U") }.getOrDefault(false) } }
                .map { runCatching { it.get(timeoutMs * 2L, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrDefault(false) }
        } finally { pool.shutdownNow() }
        if (alive.none { it }) return null
        if (alive.all { it }) return text
        val dead = all.filterIndexed { i, _ -> !alive[i] }.map { it.inf }.toSet()
        val lines = text.lines()
        val out = ArrayList<String>()
        var skipUri = false
        for (l in lines) {
            when {
                l.trim() in dead -> skipUri = true // this variant's #EXT-X-STREAM-INF … and its URI line below
                skipUri && l.isNotBlank() && !l.startsWith("#") -> skipUri = false
                !skipUri -> out += l
            }
        }
        return out.joinToString("\n")
    }

    /** Fetches [url] (and a working variant if it is a master playlist) and says what kind of stream it is. Blocks. */
    fun probe(url: String, headers: Map<String, String>, timeoutMs: Int = 4000, now: Instant = Instant.now()): Kind = runCatching {
        if (!url.substringBefore('?').endsWith(".m3u8", true)) return Kind.UNKNOWN
        val (text, finalUrl) = fetch(url, headers, timeoutMs)
        verdict(text, finalUrl, headers, timeoutMs, now)
    }.getOrDefault(Kind.UNKNOWN)

    /** Like [probe] for a playlist already downloaded from [finalUrl]: a master playlist costs a request per variant
     *  tried (the first that answers decides). */
    fun verdict(text: String, finalUrl: String, headers: Map<String, String>, timeoutMs: Int = 4000, now: Instant = Instant.now()): Kind = runCatching {
        if (!text.trimStart().startsWith("#EXTM3U")) return Kind.DEAD // e.g. a server's HTML page with "200 OK"
        if (!text.contains("#EXT-X-STREAM-INF")) return analyze(text, now)
        val live = liveVariant(text, finalUrl, headers, timeoutMs) ?: return Kind.DEAD
        analyze(live.second.first, now)
    }.getOrDefault(Kind.UNKNOWN)

    /** Live playlists are a few KB; one this big is a long finished video (its ENDLIST would be past the cut). */
    private const val LIMIT = 4 * 1024 * 1024
    private const val TRUNCATED = "\n#EXT-X-ENDLIST\n"

    /** The playlist text (at most 4 MB; a bigger one is marked finished) and the URL it finally came from (after redirects). */
    fun fetch(url: String, headers: Map<String, String>, timeoutMs: Int): Pair<String, String> {
        val con = URI(url).toURL().openConnection() as HttpURLConnection
        con.connectTimeout = timeoutMs; con.readTimeout = timeoutMs; con.instanceFollowRedirects = true
        con.setRequestProperty("User-Agent", Web.STREAM_UA); headers.forEach { (k, v) -> con.setRequestProperty(k, v) }
        try {
            if (con.responseCode !in 200..299) error("HTTP ${con.responseCode}")
            // manual read: InputStream.readNBytes(int) needs Android 13, and this runs on Android 9 TVs too
            val out = java.io.ByteArrayOutputStream()
            Web.body(con)!!.use { s ->
                val buf = ByteArray(8192)
                while (out.size() < LIMIT) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n) }
            }
            val text = out.toString("UTF-8")
            return (if (out.size() >= LIMIT) text + TRUNCATED else text) to con.url.toString()
        } finally { con.disconnect() }
    }

    /** ISO-8601 with or without an offset ("+0700" and "+07:00" both occur); no offset means UTC. */
    private fun parseTime(s: String): Instant? {
        val t = s.trim().replace(Regex("""([+-]\d{2})(\d{2})$"""), "$1:$2")
        return runCatching { OffsetDateTime.parse(t).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(t).toInstant(ZoneOffset.UTC) }.getOrNull()
    }
}
