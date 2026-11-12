package tech.kousozo.tahoetv.core

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
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
        // Indonesian channels re-tested daily for picture + sound; mostly broadcaster/free hosts. Adds backups.
        Source("yositapjani", "https://raw.githubusercontent.com/yositapjani/iptv-indonesia/main/indonesia.m3u"),
    )

    /** Guides in order of preference (schedules only, no streams). */
    val EPG = listOf("https://raw.githubusercontent.com/dhasap/dhanytv/main/epg.xml", Epg.DEFAULT)

    /** Restream hosts of paid operators / personal proxies, not the broadcaster's own stream. */
    private val RESTREAM = Regex("""workers\.dev|dens\.tv|masterplayer|sedot|maling|\.top(:|/|$)|\.pw(:|/|$)|cloudns|ngebutz|pages\.dev|tunnel\.my\.id|inisemar|beetv\.my\.id|biz\.id|nextgenz|ottc\.pro|lancartech|indihometv\.com|mikrofast|verspective""", RegexOption.IGNORE_CASE)
    /** Pay-TV brands: never part of free TV, whatever the group says. */
    private val PAID = Regex("""\b(hbo|cinemax|bein|spotv|champions tv|galaxy|hits|celestial|axn|dreamworks|cartoon network|disney|nat ?geo|fox(?! weather)|warner|vision ?prime|mola|nickelodeon|animax|sportstars|motogp|f1 tv|love nature)\b|\((v\+|r\+|maxstream|vidio|biz)\)""", RegexOption.IGNORE_CASE)

    /** Plain video/audio files (trailers, donation clips…), not live channels. */
    private val FILE = Regex("""\.(mp4|mkv|avi|mov|webm|mp3|m4a)(\?|$)""", RegexOption.IGNORE_CASE)

    fun free(c: M3u.Channel) = !c.drm && !RESTREAM.containsMatchIn(c.url) && !PAID.containsMatchIn(c.name) && !FILE.containsMatchIn(c.url)

    /** All sources merged, free TV only. A failing source is skipped, not fatal. */
    fun load(sources: List<Source> = DEFAULT): List<M3u.Channel> = sources.flatMap { s ->
        runCatching { M3u.load(s.url) }.getOrDefault(emptyList())
            .filter { s.groups == null || it.group in s.groups }
            .filter(::free)
    }.distinctBy { it.url }

    /** Built-in sources plus the user's own playlists (URLs or file paths, one per line), all free-TV filtered. */
    fun load(extra: String?): List<M3u.Channel> =
        load(DEFAULT + extra.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { Source("extra", it) })

    /** Loads channels from local files (koutv.m3u, anotheriptv.m3u, ~/.tahoetv/playlists/),
     *  plus user extra sources and built-in playlists. Local and user playlists are kept whole (premium, VOD, DRM).
     *  With nothing local, the built-in sources of [country] are used (see [Region.sources]): Indonesia's taken whole,
     *  another country's iptv-org list through the free-TV filter [free]. */
    @JvmOverloads
    fun loadAll(extra: String? = null, localFiles: List<String> = listOf("koutv.m3u", "anotheriptv.m3u"), country: String = Region.HOME): List<M3u.Channel> {
        val out = mutableListOf<M3u.Channel>()
        val seenUrls = mutableSetOf<String>()

        // 1. Local workspace files or packaged jar/apk resources
        localFiles.forEach { path ->
            val f = File(path)
            if (f.exists() && f.isFile) {
                runCatching { M3u.load(f.absolutePath) }.getOrNull()?.forEach { ch ->
                    if (seenUrls.add(ch.url)) out += ch
                }
            } else {
                val resName = "/" + path.substringAfterLast('/').removePrefix("/")
                val stream = Sources::class.java.getResourceAsStream(resName)
                    ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(resName.removePrefix("/"))
                stream?.use { s ->
                    val text = s.bufferedReader().readText()
                    runCatching { M3u.parse(text) }.getOrNull()?.forEach { ch ->
                        if (seenUrls.add(ch.url)) out += ch
                    }
                }
            }
        }

        // 2. ~/.tahoetv/playlists
        val dir = File(System.getProperty("user.home"), ".tahoetv/playlists")
        if (dir.exists()) {
            dir.listFiles()?.filter { it.extension.lowercase() in listOf("m3u", "m3u8", "txt") }?.forEach { f ->
                runCatching { M3u.load(f.absolutePath) }.getOrNull()?.forEach { ch ->
                    if (seenUrls.add(ch.url)) out += ch
                }
            }
        }

        // 3. Extra playlists specified in settings
        extra.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { src ->
            runCatching { M3u.load(src) }.getOrNull()?.forEach { ch ->
                if (seenUrls.add(ch.url)) out += ch
            }
        }

        // 4. Fallback to the country's built-in sources if no local channels found. Indonesia's are taken whole, as
        // before; another country's iptv-org list goes through the free-TV filter (it also lists paid/DRM/VOD entries).
        if (out.isEmpty()) {
            val home = country.equals(Region.HOME, true)
            val builtIn = if (home) Region.sources(country).flatMap { s -> runCatching { M3u.load(s.url) }.getOrDefault(emptyList()) }
                          else load(Region.sources(country))
            builtIn.forEach { ch -> if (seenUrls.add(ch.url)) out += ch }
        }

        return out
    }

    /** [loadAll] for the viewer's [country] (the usual call from the apps). */
    @JvmStatic fun loadFor(extra: String?, country: String?): List<M3u.Channel> =
        loadAll(extra, country = Region.country(country) ?: Region.HOME)

    // ------------------------------------------------------------------ is it on air?

    /** URLs that answer with a playable playlist/stream within a few seconds. Parallel; ~5 s for a few hundred. */
    fun alive(channels: List<M3u.Channel>, timeoutMs: Int = 5000): Set<String> {
        val pool = Executors.newFixedThreadPool(32)
        val ok = ConcurrentHashMap.newKeySet<String>()
        channels.forEach { c -> pool.execute { if (check(c, timeoutMs)) ok += c.url } }
        pool.shutdown(); pool.awaitTermination(budget(channels.size, timeoutMs), TimeUnit.MILLISECONDS)
        pool.shutdownNow()
        return ok
    }

    /** How long a parallel check of [n] channels may take: every channel gets its turn. The old fixed limit
     *  (3 × timeout ≈ 10 s) stopped after roughly the first 250 of ~1000 channels, so on the desktop three in four
     *  channels never got a status ("Memeriksa koneksi…" forever, missing from the LIVE filter). One check is at most
     *  ~3 requests (master playlist, variant, retry), each bounded by [timeoutMs]. */
    internal fun budget(n: Int, timeoutMs: Int, threads: Int = 32): Long =
        ((n + threads - 1) / threads).coerceAtLeast(1) * timeoutMs * 3L + 10_000L

    /** Detailed status check returning Map of url -> isLive (true=LIVE, false=OFFLINE).
     *  Calls [onProgress] on each checked channel so UI updates in real time. */
    fun checkStatus(
        channels: List<M3u.Channel>,
        timeoutMs: Int = 4000,
        onProgress: ((url: String, isLive: Boolean) -> Unit)? = null
    ): Map<String, Boolean> {
        val pool = Executors.newFixedThreadPool(32)
        val result = ConcurrentHashMap<String, Boolean>()
        channels.forEach { c ->
            pool.execute {
                val live = check(c, timeoutMs)
                result[c.url] = live
                onProgress?.invoke(c.url, live)
            }
        }
        pool.shutdown(); pool.awaitTermination(budget(channels.size, timeoutMs), TimeUnit.MILLISECONDS)
        pool.shutdownNow()
        return result
    }

    /** One channel: answers with a playable playlist/stream and, for live HLS, is not a recording. Blocks. */
    fun check(c: M3u.Channel, timeoutMs: Int): Boolean = runCatching {
        // HLS: download the playlist once and judge it (a master playlist costs one more request for its variant).
        // Answering is not enough: a finished playlist or a replayed old broadcast is a recording, not a live channel
        // (Trans7 played yesterday's show on a loop). Movies/VOD are meant to be finished playlists.
        if (c.url.substringBefore('?').endsWith(".m3u8", true)) {
            val (text, finalUrl) = HlsProbe.fetch(c.url, c.headers, timeoutMs)
            if (!text.trimStart().startsWith("#EXTM3U")) return@runCatching false
            return@runCatching LocalTv.isVod(c) || HlsProbe.verdict(text, finalUrl, c.headers, timeoutMs).let { it != HlsProbe.Kind.REPLAY && it != HlsProbe.Kind.STALE }
        }
        val con = URI(c.url).toURL().openConnection() as HttpURLConnection
        con.connectTimeout = timeoutMs; con.readTimeout = timeoutMs; con.instanceFollowRedirects = true
        con.setRequestProperty("User-Agent", "TahoeTV"); c.headers.forEach { (k, v) -> con.setRequestProperty(k, v) }
        try {
            if (con.responseCode !in 200..299) return@runCatching false
            val head = Web.body(con)!!.use { s -> ByteArray(16).let { b -> String(b, 0, s.read(b).coerceAtLeast(0)) } }
            !c.url.contains(".m3u8", true) || head.startsWith("#EXTM3U")
        } finally { con.disconnect() }
    }.getOrDefault(false)
}
