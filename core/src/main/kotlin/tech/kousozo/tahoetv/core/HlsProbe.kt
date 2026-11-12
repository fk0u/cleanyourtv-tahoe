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
        UNKNOWN
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

    /** Fetches [url] (and its first variant if it is a master playlist) and says what kind of stream it is. Blocks. */
    fun probe(url: String, headers: Map<String, String>, timeoutMs: Int = 4000, now: Instant = Instant.now()): Kind = runCatching {
        if (!url.substringBefore('?').endsWith(".m3u8", true)) return Kind.UNKNOWN
        val (text, finalUrl) = fetch(url, headers, timeoutMs)
        verdict(text, finalUrl, headers, timeoutMs, now)
    }.getOrDefault(Kind.UNKNOWN)

    /** Like [probe] for a playlist already downloaded from [finalUrl]: only a master playlist costs one more request. */
    fun verdict(text: String, finalUrl: String, headers: Map<String, String>, timeoutMs: Int = 4000, now: Instant = Instant.now()): Kind = runCatching {
        val variant = firstVariant(text, finalUrl)
        if (variant != null) analyze(fetch(variant, headers, timeoutMs).first, now) else analyze(text, now)
    }.getOrDefault(Kind.UNKNOWN)

    /** Live playlists are a few KB; one this big is a long finished video (its ENDLIST would be past the cut). */
    private const val LIMIT = 4 * 1024 * 1024
    private const val TRUNCATED = "\n#EXT-X-ENDLIST\n"

    /** The playlist text (at most 4 MB; a bigger one is marked finished) and the URL it finally came from (after redirects). */
    fun fetch(url: String, headers: Map<String, String>, timeoutMs: Int): Pair<String, String> {
        val con = URI(url).toURL().openConnection() as HttpURLConnection
        con.connectTimeout = timeoutMs; con.readTimeout = timeoutMs; con.instanceFollowRedirects = true
        con.setRequestProperty("User-Agent", "TahoeTV"); headers.forEach { (k, v) -> con.setRequestProperty(k, v) }
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
