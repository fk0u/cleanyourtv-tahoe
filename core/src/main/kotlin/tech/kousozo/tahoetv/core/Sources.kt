package tech.kousozo.tahoetv.core

import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Where TV Live channels come from, and the "free TV only" rule applied to every source. */
object Sources {
    /** `groups`: only these playlist groups are taken (null = all). */
    data class Source(val name: String, val url: String, val groups: Set<String>? = null)

    val DEFAULT = listOf(
        Source("iptv-org", M3u.DEFAULT),
        // dhanytv mixes free Indonesian TV with paid channels; only its Indonesian free-TV groups are used.
        Source("dhanytv", "https://raw.githubusercontent.com/dhasap/dhanytv/main/dhanytv.m3u",
            setOf("Indonesia Channels", "TVRI", "Local Channels", "Nasional", "TV HIBURAN")),
    )

    /** Guides in order of preference (schedules only, no streams). */
    val EPG = listOf("https://raw.githubusercontent.com/dhasap/dhanytv/main/epg.xml", Epg.DEFAULT)

    /** Restream hosts of paid operators / personal proxies, not the broadcaster's own stream. */
    private val RESTREAM = Regex("""workers\.dev|dens\.tv|masterplayer|sedot|maling|\.top(:|/|$)|\.pw(:|/|$)|cloudns|ngebutz|pages\.dev|tunnel\.my\.id""", RegexOption.IGNORE_CASE)
    /** Pay-TV brands: never part of free TV, whatever the group says. */
    private val PAID = Regex("""\b(hbo|cinemax|bein|spotv|champions tv|galaxy|hits|celestial|axn|dreamworks|cartoon network|disney|nat ?geo|fox|warner|vision ?prime|mola)\b""", RegexOption.IGNORE_CASE)

    fun free(c: M3u.Channel) = !c.drm && !RESTREAM.containsMatchIn(c.url) && !PAID.containsMatchIn(c.name)

    /** All sources merged, free TV only. A failing source is skipped, not fatal. */
    fun load(sources: List<Source> = DEFAULT): List<M3u.Channel> = sources.flatMap { s ->
        runCatching { M3u.load(s.url) }.getOrDefault(emptyList())
            .filter { s.groups == null || it.group in s.groups }
            .filter(::free)
    }.distinctBy { it.url }

    /** A user-supplied playlist URL replaces the built-in sources (still free TV only). */
    fun load(custom: String?): List<M3u.Channel> =
        if (custom.isNullOrBlank()) load() else load(listOf(Source("custom", custom.trim())))

    // ------------------------------------------------------------------ is it on air?

    /** URLs that answer with a playable playlist/stream within a few seconds. Parallel; ~5 s for a few hundred. */
    fun alive(channels: List<M3u.Channel>, timeoutMs: Int = 5000): Set<String> {
        val pool = Executors.newFixedThreadPool(32)
        val ok = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        channels.forEach { c -> pool.execute { if (check(c, timeoutMs)) ok += c.url } }
        pool.shutdown(); pool.awaitTermination(timeoutMs * 3L, TimeUnit.MILLISECONDS)
        pool.shutdownNow()
        return ok
    }

    private fun check(c: M3u.Channel, timeoutMs: Int): Boolean = runCatching {
        val con = URI(c.url).toURL().openConnection() as HttpURLConnection
        con.connectTimeout = timeoutMs; con.readTimeout = timeoutMs; con.instanceFollowRedirects = true
        con.setRequestProperty("User-Agent", "TahoeTV"); c.headers.forEach { (k, v) -> con.setRequestProperty(k, v) }
        try {
            if (con.responseCode !in 200..299) return@runCatching false
            val head = con.inputStream.use { s -> ByteArray(16).let { b -> String(b, 0, s.read(b).coerceAtLeast(0)) } }
            !c.url.contains(".m3u8", true) || head.startsWith("#EXTM3U")
        } finally { con.disconnect() }
    }.getOrDefault(false)
}
