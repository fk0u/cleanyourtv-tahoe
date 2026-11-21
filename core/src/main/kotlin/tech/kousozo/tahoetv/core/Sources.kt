package tech.kousozo.tahoetv.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Where TV Live channels come from, and the "free TV only" rule applied to every source. */
object Sources {
    /** `groups`: only these playlist groups are taken (null = all). */
    data class Source(val name: String, val url: String, val groups: Set<String>? = null)

    val DEFAULT = listOf(
        Source("iptv-org", M3u.DEFAULT),
        Source("dhanytv", "https://raw.githubusercontent.com/dhasap/dhanytv/main/dhanytv.m3u"),
        Source("dhanytv-ott", "https://raw.githubusercontent.com/dhasap/dhanytv/main/dhanytv-ott.m3u"),
        Source("windozalmi", "https://raw.githubusercontent.com/windozalmi/Playlist_IPTV_Pribadi/m3u-ipvt/IPTV%20Indonesia%20by%20WINDO%20ZALMI"),
        Source("yositapjani", "https://raw.githubusercontent.com/yositapjani/iptv-indonesia/main/indonesia.m3u"),
    )

    /** Worldwide iptv-org index, warmed in background as non-priority cache so selected country lists stay focused. */
    @JvmField
    val GLOBAL_INDEX = Source("iptv-org-all", "https://iptv-org.github.io/iptv/index.m3u")

    /** Guides in order of preference (schedules only, no streams). */
    val EPG = listOf("https://raw.githubusercontent.com/dhasap/dhanytv/main/epg.xml", Epg.DEFAULT)

    /** Restream hosts of paid operators / personal proxies, not the broadcaster's own stream. */
    private val RESTREAM = Regex("""workers\.dev|dens\.tv|masterplayer|sedot|maling|\.top(:|/|$)|\.pw(:|/|$)|cloudns|ngebutz|pages\.dev|tunnel\.my\.id|inisemar|beetv\.my\.id|biz\.id|nextgenz|ottc\.pro|lancartech|indihometv\.com|mikrofast|verspective""", RegexOption.IGNORE_CASE)
    /** Pay-TV brands: never part of free TV, whatever the group says. */
    private val PAID = Regex("""\b(hbo|cinemax|bein|spotv|champions tv|galaxy|hits|celestial|axn|dreamworks|cartoon network|disney|nat ?geo|fox(?! weather)|warner|vision ?prime|mola|nickelodeon|animax|sportstars|motogp|f1 tv|love nature)\b|\((v\+|r\+|maxstream|vidio|biz)\)""", RegexOption.IGNORE_CASE)

    /** Plain video/audio files (trailers, donation clips…), not live channels. */
    private val FILE = Regex("""\.(mp4|mkv|avi|mov|webm|mp3|m4a)(\?|$)""", RegexOption.IGNORE_CASE)

    fun free(c: M3u.Channel): Boolean {
        if (FILE.containsMatchIn(c.url)) return false
        if (OttEngine.isPrimaryConfigured() && OttEngine.status == OttEngine.ServerStatus.ONLINE) return true
        return !c.drm && !RESTREAM.containsMatchIn(c.url) && !PAID.containsMatchIn(c.name)
    }

    private const val DAILY_MS = 24 * 60 * 60_000L
    private const val MEM_TTL_MS = 15 * 60_000L
    private data class MemPlaylist(val channels: List<M3u.Channel>, val at: Long)

    @Volatile private var cacheDir: File? = null
    @Volatile private var dailyTimer: java.util.concurrent.ScheduledExecutorService? = null
    private val memCache = ConcurrentHashMap<String, MemPlaylist>()
    private val swrInFlight = ConcurrentHashMap.newKeySet<String>()
    private val fetchPool = Executors.newFixedThreadPool(4) { r -> Thread(r, "tahoe-sources").apply { isDaemon = true } }

    /** Sets the directory used to cache remote M3U playlists, EPG guides, and country popularity data. */
    @JvmStatic fun initCache(dir: File) {
        cacheDir = dir.also { runCatching { it.mkdirs() } }
        PopularityScraper.initCache(dir)
        Epg.initCache(dir)
    }

    private fun playlistCacheDir(): File? = cacheDir ?: runCatching {
        File(System.getProperty("user.home"), ".tahoetv/playlist-cache").apply { mkdirs() }
    }.getOrNull()?.takeIf { it.isDirectory && it.canWrite() }

    private fun cacheFileFor(dir: File, url: String): File {
        val key = url.hashCode().toUInt().toString(16) + "-" + url.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "_").take(32)
        return File(dir, "$key.m3u")
    }

    private fun writeCacheAtomic(target: File, content: String) {
        runCatching {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(content)
            if (!tmp.renameTo(target)) {
                target.writeText(content)
                tmp.delete()
            }
        }
    }

    private fun refreshSourceAsync(url: String, cached: File) {
        if (!swrInFlight.add(url)) return
        fetchPool.execute {
            try {
                val fetched = runCatching { Web.get(url, 30_000) }
            .recoverCatching { Web.get(url, 30_000) }
            .onFailure { DiagLog.error("sources", "gagal memuat playlist $url", it) }
            .getOrNull()
                if (!fetched.isNullOrBlank()) {
                    writeCacheAtomic(cached, fetched)
                    memCache[url] = MemPlaylist(M3u.parse(fetched), System.currentTimeMillis())
                }
            } finally {
                swrInFlight.remove(url)
            }
        }
    }

    private fun loadSource(url: String, forceRefresh: Boolean = false): List<M3u.Channel> {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return M3u.load(url)
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            memCache[url]?.takeIf { now - it.at < MEM_TTL_MS }?.let { return it.channels }
        }
        val dir = playlistCacheDir()
        val cached = dir?.let { cacheFileFor(it, url) }
        if (cached != null && cached.isFile && !forceRefresh) {
            val age = now - cached.lastModified()
            val parsed = runCatching { M3u.parse(cached.readText()) }.getOrNull()
            if (parsed != null && parsed.isNotEmpty()) {
                memCache[url] = MemPlaylist(parsed, now)
                if (age >= DAILY_MS) refreshSourceAsync(url, cached) // stale-while-revalidate in background
                return parsed
            }
        }
        val fetched = runCatching { Web.get(url, 30_000) }
            .recoverCatching { Web.get(url, 30_000) }
            .onFailure { DiagLog.error("sources", "gagal memuat playlist $url", it) }
            .getOrNull()
        if (!fetched.isNullOrBlank()) {
            if (cached != null) writeCacheAtomic(cached, fetched)
            return M3u.parse(fetched).also { memCache[url] = MemPlaylist(it, System.currentTimeMillis()) }
        }
        if (cached != null && cached.isFile) {
            return M3u.parse(cached.readText()).also { memCache[url] = MemPlaylist(it, System.currentTimeMillis()) }
        }
        return emptyList()
    }

    /** Schedules a daily (24h) background refresh of built-in playlists; [onRefresh] runs after each update. */
    @JvmStatic @Synchronized fun scheduleDailyRefresh(onRefresh: Runnable? = null) {
        if (dailyTimer != null) return
        val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tahoe-daily-sources").apply { isDaemon = true } }
        dailyTimer = exec
        exec.scheduleAtFixedRate({
            runCatching {
                DEFAULT.forEach { loadSource(it.url, forceRefresh = true) }
                onRefresh?.run()
            }
        }, 24, 24, TimeUnit.HOURS)
    }

    /** All sources merged in order using concurrent fetching, free TV only. A failing source is skipped, not fatal. */
    fun load(sources: List<Source> = DEFAULT, forceRefresh: Boolean = false): List<M3u.Channel> {
        if (sources.size <= 1) {
            return sources.flatMap { s ->
                runCatching { loadSource(s.url, forceRefresh) }.getOrDefault(emptyList())
                    .filter { s.groups == null || it.group in s.groups }
                    .filter(::free)
            }.distinctBy { it.url }
        }
        val futures = sources.map { s ->
            fetchPool.submit<List<M3u.Channel>> {
                runCatching { loadSource(s.url, forceRefresh) }.getOrDefault(emptyList())
                    .filter { s.groups == null || it.group in s.groups }
                    .filter(::free)
            }
        }
        return futures.flatMap { f -> runCatching { f.get() }.getOrDefault(emptyList()) }.distinctBy { it.url }
    }

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
        if (out.isEmpty()) builtIn(country).forEach { ch -> if (seenUrls.add(ch.url)) out += ch }

        return out.filterNot { ControlPlane.isChannelBlocked(it) || LocalTv.isMismatchedSource(it.name, it.url) }
    }

    /** A country's built-in channels tagged with [country]: Indonesia keeps all channels (including premium/DASH/DRM); other countries use [load]. */
    private fun builtIn(country: String): List<M3u.Channel> {
        val cc = country.uppercase()
        val list = if (cc == Region.HOME) {
            val sources = Region.sources(cc)
            val futures = sources.map { s ->
                fetchPool.submit<List<M3u.Channel>> {
                    runCatching { loadSource(s.url) }.getOrDefault(emptyList())
                        .filter { (s.groups == null || it.group in s.groups) && !FILE.containsMatchIn(it.url) && !LocalTv.isMismatchedSource(it.name, it.url) }
                }
            }
            futures.flatMap { f -> runCatching { f.get() }.getOrDefault(emptyList()) }.distinctBy { it.url }
        } else {
            load(Region.sources(cc))
        }
        return list.map { if (it.countryCode == null) it.copy(countryCode = cc) else it }
    }

    private fun warmBackgroundAsync(countries: List<String>, priorityNames: List<String>) {
        if (!swrInFlight.add("__global_bg__")) return
        fetchPool.execute {
            try {
                // Warm internet-scraped popularity rankings for chosen countries in background
                PopularityScraper.prefetchAsync(countries, priorityNames)
                // Warm global index into disk/memory cache in background as non-priority (never pollutes filtered lists)
                val cached = playlistCacheDir()?.let { cacheFileFor(it, GLOBAL_INDEX.url) }
                if (cached == null || !cached.isFile || System.currentTimeMillis() - cached.lastModified() >= DAILY_MS) {
                    runCatching { loadSource(GLOBAL_INDEX.url) }
                }
            } finally {
                swrInFlight.remove("__global_bg__")
            }
        }
    }

    /**
     * Loads channels for [countries] in priority order:
     * 1) Immediately loads the priority country (`countries.first()` + [extra]/local playlists) and invokes [onPriorityReady].
     * 2) Loads remaining selected countries in parallel as non-priority and returns the combined, deduplicated filtered list,
     *    while asynchronously warming global caches & popularity rankings in background.
     */
    @JvmStatic
    @JvmOverloads
    fun loadCountriesPriority(
        extra: String?,
        countries: List<String>,
        onPriorityReady: ((List<M3u.Channel>) -> Unit)? = null,
    ): List<M3u.Channel> = loadChannels(extra, countries, emptyList(), onPriorityReady)

    /** [loadCountriesPriority] plus extra channel [languages] (ISO 639-1, see [Region.languagePlaylist]), loaded
     *  after the countries through the free-TV filter (#71). If a primary OTT server is configured, routes through
     *  [OttEngine] with automatic fallback to built-in sources. */
    @JvmStatic
    @JvmOverloads
    fun loadChannels(
        extra: String?,
        countries: List<String>,
        languages: List<String>,
        onPriorityReady: ((List<M3u.Channel>) -> Unit)? = null,
    ): List<M3u.Channel> {
        if (OttEngine.isPrimaryConfigured()) {
            return OttEngine.loadChannels(extra, countries, languages, onPriorityReady)
        }
        return loadBuiltInFallback(countries, languages, extra, onPriorityReady)
    }

    /**
     * Loads the built-in channel pool for [countries] and [languages], used as the primary source when no OTT server
     * is configured, or as the automatic fallback pool when the primary OTT server is offline.
     */
    @JvmStatic
    @JvmOverloads
    fun loadBuiltInFallback(
        countries: List<String>,
        languages: List<String> = emptyList(),
        extra: String? = null,
        onPriorityReady: ((List<M3u.Channel>) -> Unit)? = null,
    ): List<M3u.Channel> {
        val list = countries.mapNotNull { Region.country(it) }.distinct().ifEmpty { listOf(Region.HOME) }
        val primary = list.first()
        val seen = HashSet<String>()
        val priorityChannels = loadAll(extra, country = primary)
            .map { if (it.countryCode.isNullOrBlank()) it.copy(countryCode = primary) else it }
            .filter { seen.add(it.url) && !ControlPlane.isChannelBlocked(it) }
        onPriorityReady?.invoke(priorityChannels)
        warmBackgroundAsync(list, priorityChannels.map { it.name })
        val langSources = Region.languageSources(languages)
        if (list.size == 1 && langSources.isEmpty()) return priorityChannels

        val secondaryFutures = list.drop(1).map { cc ->
            fetchPool.submit<List<M3u.Channel>> { builtIn(cc) }
        } + langSources.map { s -> fetchPool.submit<List<M3u.Channel>> { load(listOf(s)) } }
        val extraCountries = secondaryFutures.flatMap { f -> runCatching { f.get() }.getOrDefault(emptyList()) }
            .filter { seen.add(it.url) && !ControlPlane.isChannelBlocked(it) }
        return priorityChannels + extraCountries
    }

    /** Channels of several countries (the onboarding's "channel countries"), merged without duplicates: the first as
     *  [loadAll] (local files, [extra], or its built-ins), then every other country's built-in channels. */
    @JvmStatic fun loadCountries(extra: String?, countries: List<String>): List<M3u.Channel> =
        loadCountriesPriority(extra, countries, null)

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
     *  ~4 timeouts: the master playlist, one parallel round over its variants (up to 2 × timeout), and a retry. */
    internal fun budget(n: Int, timeoutMs: Int, threads: Int = 32): Long =
        ((n + threads - 1) / threads).coerceAtLeast(1) * timeoutMs * 4L + 10_000L

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
            return@runCatching LocalTv.isVod(c) || HlsProbe.verdict(text, finalUrl, c.headers, timeoutMs).let { it == HlsProbe.Kind.LIVE || it == HlsProbe.Kind.UNKNOWN }
        }
        val con = Web.openFollowingRedirects(c.url, timeoutMs, c.headers, connectTimeoutMs = timeoutMs)
        try {
            if (con.responseCode !in 200..299) return@runCatching false
            val buf = ByteArray(512)
            val head = Web.body(con)!!.use { s ->
                var total = 0
                while (total < buf.size) {
                    val n = s.read(buf, total, buf.size - total)
                    if (n < 0) break
                    total += n
                }
                String(buf, 0, total).trimStart()
            }
            if (c.isDash) {
                val low = head.lowercase()
                return@runCatching ("<mpd" in low || "<?xml" in low) && !low.startsWith("<html") && !low.startsWith("<!doctype html")
            }
            !c.url.contains(".m3u8", true) || head.startsWith("#EXTM3U")
        } finally { con.disconnect() }
    }.getOrDefault(false)
}
