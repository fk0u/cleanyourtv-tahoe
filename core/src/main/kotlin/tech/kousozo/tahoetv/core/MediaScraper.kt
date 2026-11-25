package tech.kousozo.tahoetv.core

import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Scrapes missing channel logos and live programme preview thumbnails from the internet (Wikipedia pageimages
 *  with Bing image search fallback), backed by bounded LRU + atomic disk cache and `<= 2`-worker background
 *  prefetch. Shared across desktop, TV launcher, and mobile companion. */
object MediaScraper {
    private const val TTL_MS = 24 * 60 * 60_000L
    private const val MAX_CACHE_ENTRIES = 600
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val BING_MURL = Regex("""murl(?:&quot;|"):\s*(?:&quot;|")(https?://[^&"]+(?:&amp;[^&"]*)*)(?:&quot;|")""")

    private data class Cached(val url: String?, val at: Long)

    private fun newLru(): MutableMap<String, Cached> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Cached>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?): Boolean =
                size > MAX_CACHE_ENTRIES
        }
    )

    private val logoCache = newLru()
    private val thumbCache = newLru()
    private val posterCache = newLru()
    private val inflight = ConcurrentHashMap.newKeySet<String>()
    private val prefetchPool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "tahoe-media-prefetch").apply { isDaemon = true }
    }
    private val flushPool = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "tahoe-media-flush").apply { isDaemon = true }
    }
    private val pendingSaves = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var cacheDir: File? = null

    /** Optional override for the disk cache directory (e.g. Android `context.cacheDir`). */
    @JvmStatic fun initCache(dir: File) {
        cacheDir = File(dir, "media-scraper").also { runCatching { it.mkdirs() } }
        loadCacheFile("logos.tsv", logoCache)
        loadCacheFile("thumbs.tsv", thumbCache)
        loadCacheFile("posters3.tsv", posterCache)
        PopularityScraper.initCache(dir)
        Epg.initCache(dir)
    }

    private fun scheduleSave(name: String, source: MutableMap<String, Cached>) {
        if (!pendingSaves.add(name)) return
        flushPool.schedule({
            pendingSaves.remove(name)
            saveCacheFile(name, source)
        }, 750, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun dir(): File? = cacheDir ?: runCatching {
        File(System.getProperty("user.home"), ".tahoetv/media-scraper").apply { mkdirs() }
    }.getOrNull()?.takeIf { it.isDirectory && it.canWrite() }

    private fun escapeTsv(s: String): String = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "")
    private fun unescapeTsv(s: String): String = s.replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\")

    private fun loadCacheFile(name: String, target: MutableMap<String, Cached>) {
        val f = dir()?.let { File(it, name) } ?: return
        if (!f.isFile) return
        val now = System.currentTimeMillis()
        runCatching {
            f.forEachLine { line ->
                val p = line.split('\t')
                if (p.size == 3) {
                    val at = p[2].toLongOrNull() ?: return@forEachLine
                    if (now - at < TTL_MS) target[unescapeTsv(p[0])] = Cached(unescapeTsv(p[1]).ifBlank { null }, at)
                }
            }
        }
    }

    @Synchronized private fun saveCacheFile(name: String, source: MutableMap<String, Cached>) {
        val f = dir()?.let { File(it, name) } ?: return
        val snapshot = synchronized(source) { source.entries.map { it.key to it.value } }
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(snapshot.joinToString("\n") { (k, v) ->
                "${escapeTsv(k)}\t${escapeTsv(v.url.orEmpty())}\t${v.at}"
            })
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        }
    }

    /** Returns [existingLogo] if already present, otherwise scrapes a channel logo URL from the internet. */
    @JvmStatic @JvmOverloads
    fun channelLogo(channelName: String, existingLogo: String? = null): String? {
        if (!existingLogo.isNullOrBlank() && (existingLogo.startsWith("http://") || existingLogo.startsWith("https://"))) {
            return existingLogo
        }
        val clean = LocalTv.cleanName(channelName).ifBlank { channelName.trim() }
        if (clean.isBlank()) return null
        val key = clean.lowercase()
        val now = System.currentTimeMillis()
        logoCache[key]?.takeIf { now - it.at < TTL_MS }?.let { return it.url }

        val found = (scrapeWikiThumb(clean, 400, tvChannel = true) ?: scrapeBingImage("$clean tv channel logo png"))
            ?.takeIf(::verifyImageUrl)
        logoCache[key] = Cached(found, now)
        scheduleSave("logos.tsv", logoCache)
        return found
    }

    /** Scrapes a preview/thumbnail image URL for the currently airing [programmeTitle] on [channelName]. */
    @JvmStatic
    fun programmeThumbnail(programmeTitle: String, channelName: String): String? {
        val prog = programmeTitle.trim()
        val ch = LocalTv.cleanName(channelName).ifBlank { channelName.trim() }
        if (prog.isBlank()) return null
        val key = "${prog.lowercase()}|${ch.lowercase()}"
        val now = System.currentTimeMillis()
        thumbCache[key]?.takeIf { now - it.at < TTL_MS }?.let { return it.url }

        // Wikipedia only, and only for a page whose title matches: an image search returns unrelated pictures for programme names
        val found = scrapeWikiThumb(prog, 640)?.takeIf(::verifyImageUrl)
        thumbCache[key] = Cached(found, now)
        scheduleSave("thumbs.tsv", thumbCache)
        return found
    }

    /** Poster of a movie or series by its title (no API key): movies from the iTunes catalogue, series from TVMaze, then a Wikipedia
     *  page picture whose title really matches. Null when nothing matches: a wrong poster is worse than a placeholder. */
    @JvmStatic @JvmOverloads
    fun poster(title: String, year: Int? = null, series: Boolean = false): String? {
        val t = title.trim()
        if (t.isBlank()) return null
        val key = "${if (series) "s" else "m"}|${t.lowercase()}|${year ?: ""}"
        val now = System.currentTimeMillis()
        posterCache[key]?.takeIf { now - it.at < (if (it.url == null) TTL_MS / 24 else TTL_MS * 30) }?.let { return it.url }
        // TMDB first when a key is set (covers Indonesian films best), then the keyless sources; an image search is never used (it returns unrelated pictures)
        val found = tmdbPoster(t, year, series)
            ?: (if (series) tvmazePoster(t) else itunesPoster(t, year))
            ?: imdbPoster(t, year, series)
            ?: scrapeWikiThumb(if (series) "$t TV series" else if (year != null) "$t $year film" else "$t film", 500)
        val ok = found?.takeIf(::verifyImageUrl)
        posterCache[key] = Cached(ok, now)
        scheduleSave("posters3.tsv", posterCache)
        return ok
    }

    @JvmStatic @JvmOverloads
    fun prefetchPoster(title: String, year: Int? = null, series: Boolean = false, onReady: (String) -> Unit) {
        val key = "poster:${if (series) "s" else "m"}|${title.lowercase()}|${year ?: ""}"
        if (!inflight.add(key)) return
        prefetchPool.execute {
            try { poster(title, year, series)?.let(onReady) } finally { inflight.remove(key) }
        }
    }

    /** Optional TMDB key (the launcher passes its own); blank in public builds, which use the keyless sources instead. */
    @Volatile @JvmStatic var tmdbKey: String = ""

    internal fun parseTmdbPoster(json: String, title: String): String? = runCatching {
        val arr = JSONObject(json).optJSONArray("results") ?: return null
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val name = r.optString("title").ifBlank { r.optString("name") }
            val orig = r.optString("original_title").ifBlank { r.optString("original_name") }
            if (!titleMatches(title, name) && !titleMatches(title, orig)) continue
            val path = r.optString("poster_path").takeIf { it.startsWith("/") } ?: continue
            return "https://image.tmdb.org/t/p/w500$path"
        }
        null
    }.getOrNull()

    private fun tmdbPoster(title: String, year: Int?, series: Boolean): String? {
        val key = tmdbKey.trim().takeIf { it.isNotEmpty() } ?: return null
        val q = URLEncoder.encode(title, "UTF-8")
        val y = if (year != null) (if (series) "&first_air_date_year=$year" else "&year=$year") else ""
        val url = "https://api.themoviedb.org/3/search/${if (series) "tv" else "movie"}?api_key=${URLEncoder.encode(key, "UTF-8")}&query=$q$y"
        val body = runCatching { Web.get(url, 4000, mapOf("User-Agent" to "TahoeTV/3.8")) }.getOrNull() ?: return null
        return parseTmdbPoster(body, title)
    }

    internal fun parseItunesPoster(json: String, title: String, year: Int?): String? = runCatching {
        val arr = JSONObject(json).optJSONArray("results") ?: return null
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            if (!titleMatches(title, r.optString("trackName"))) continue
            val y = r.optString("releaseDate").take(4).toIntOrNull()
            if (year != null && y != null && kotlin.math.abs(y - year) > 1) continue
            return r.optString("artworkUrl100").takeIf { it.startsWith("https://") }?.replace(Regex("""/\d+x\d+bb"""), "/400x600bb")
        }
        null
    }.getOrNull()

    internal fun parseTvmazePoster(json: String, title: String): String? = runCatching {
        val arr = org.json.JSONArray(json)
        for (i in 0 until arr.length()) {
            val show = arr.optJSONObject(i)?.optJSONObject("show") ?: continue
            if (!titleMatches(title, show.optString("name"))) continue
            val img = show.optJSONObject("image") ?: continue
            return (img.optString("original").ifBlank { img.optString("medium") }).takeIf { it.startsWith("http") }?.replace("http://", "https://")
        }
        null
    }.getOrNull()

    /** IMDb's public search-suggestion feed (no key): title, year and poster. Only an exactly matching title counts. */
    internal fun parseImdbPoster(json: String, title: String, year: Int?, series: Boolean): String? = runCatching {
        val arr = JSONObject(json).optJSONArray("d") ?: return null
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val kind = r.optString("qid")
            if (series != kind.startsWith("tv") || kind in setOf("tvEpisode", "tvSpecial") || kind == "podcastSeries") continue
            if (!titleMatches(title, r.optString("l"))) continue
            val y = r.optInt("y", 0)
            if (year != null && y != 0 && kotlin.math.abs(y - year) > 1) continue
            val img = r.optJSONObject("i")?.optString("imageUrl").orEmpty()
            if (img.startsWith("https://")) return img.replace("._V1_.", "._V1_UX400_.")
        }
        null
    }.getOrNull()

    private fun imdbPoster(title: String, year: Int?, series: Boolean): String? {
        val key = title.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim().replace(' ', '_')
        if (key.isEmpty() || !key[0].isLetterOrDigit() || key[0].code > 127) return null
        val body = runCatching { Web.get("https://v2.sg.media-imdb.com/suggestion/${key[0]}/${URLEncoder.encode(key, "UTF-8")}.json", 4000, mapOf("User-Agent" to "TahoeTV/3.8")) }.getOrNull() ?: return null
        return parseImdbPoster(body, title, year, series)
    }

    private fun itunesPoster(title: String, year: Int?): String? {
        val q = URLEncoder.encode(title, "UTF-8")
        val body = runCatching { Web.get("https://itunes.apple.com/search?term=$q&media=movie&entity=movie&limit=8", 4000, mapOf("User-Agent" to "TahoeTV/3.8")) }.getOrNull() ?: return null
        return parseItunesPoster(body, title, year)
    }

    private fun tvmazePoster(title: String): String? {
        val q = URLEncoder.encode(title, "UTF-8")
        val body = runCatching { Web.get("https://api.tvmaze.com/search/shows?q=$q", 4000, mapOf("User-Agent" to "TahoeTV/3.8")) }.getOrNull() ?: return null
        return parseTvmazePoster(body, title)
    }

    /** Non-blocking background prefetch (<= 2 concurrent workers) for channel logo lookup. */
    @JvmStatic @JvmOverloads
    fun prefetchChannelLogo(channelName: String, existingLogo: String? = null, onReady: ((String) -> Unit)? = null) {
        if (!existingLogo.isNullOrBlank() && (existingLogo.startsWith("http://") || existingLogo.startsWith("https://"))) {
            onReady?.invoke(existingLogo)
            return
        }
        val clean = LocalTv.cleanName(channelName).ifBlank { channelName.trim() }
        if (clean.isBlank()) return
        val cacheKey = "logo:${clean.lowercase()}"
        logoCache[clean.lowercase()]?.takeIf { System.currentTimeMillis() - it.at < TTL_MS }?.url?.let {
            onReady?.invoke(it)
            return
        }
        if (!inflight.add(cacheKey)) return
        prefetchPool.execute {
            try {
                channelLogo(channelName, existingLogo)?.let { onReady?.invoke(it) }
            } finally {
                inflight.remove(cacheKey)
            }
        }
    }

    /** Non-blocking background prefetch (<= 2 concurrent workers) for programme thumbnail lookup. */
    @JvmStatic @JvmOverloads
    fun prefetchProgrammeThumbnail(programmeTitle: String, channelName: String, onReady: ((String) -> Unit)? = null) {
        val prog = programmeTitle.trim()
        if (prog.isBlank()) return
        val ch = LocalTv.cleanName(channelName).ifBlank { channelName.trim() }
        val mapKey = "${prog.lowercase()}|${ch.lowercase()}"
        val cacheKey = "thumb:$mapKey"
        thumbCache[mapKey]?.takeIf { System.currentTimeMillis() - it.at < TTL_MS }?.url?.let {
            onReady?.invoke(it)
            return
        }
        if (!inflight.add(cacheKey)) return
        prefetchPool.execute {
            try {
                programmeThumbnail(programmeTitle, channelName)?.let { onReady?.invoke(it) }
            } finally {
                inflight.remove(cacheKey)
            }
        }
    }

    internal fun parseBingFirstImage(html: String): String? =
        BING_MURL.find(html)?.groupValues?.get(1)
            ?.replace("&amp;", "&")
            ?.replace("&#38;", "&")
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }

    internal fun verifyImageUrl(url: String): Boolean = runCatching {
        val con = Web.openFollowingRedirects(url, 3000, mapOf("User-Agent" to UA), blockPrivateNetwork = true)
        try {
            if (con.responseCode !in 200..299) return@runCatching false
            val ct = con.contentType?.lowercase().orEmpty()
            ct.startsWith("image/")
        } finally {
            con.disconnect()
        }
    }.getOrDefault(false)

    /** The thumbnail of the best page. With [query], only a page whose title really matches it counts: a search for "Makan Receh"
     *  must not return the picture of whatever article merely mentions the words (a wrong photo is worse than none). */
    internal fun parseWikiPageImage(json: String, query: String? = null): String? = runCatching {
        val pages = JSONObject(json).optJSONObject("query")?.optJSONObject("pages") ?: return null
        val keys = pages.keys()
        while (keys.hasNext()) {
            val page = pages.optJSONObject(keys.next()) ?: continue
            if (query != null && !titleMatches(query, page.optString("title"))) continue
            val thumb = page.optJSONObject("thumbnail")?.optString("source")?.takeIf { it.startsWith("https://") }
            if (thumb != null) return thumb
        }
        null
    }.getOrNull()

    private val STOP = setOf("tv", "channel", "television", "televisi", "stasiun", "saluran", "the", "program", "acara", "show", "live", "hd")
    private fun tokens(s: String): Set<String> =
        s.lowercase().replace(Regex("\\(.*?\\)"), " ").split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() && it !in STOP }.toSet()

    /** The page title and the query share (nearly) all their meaningful words: "Trans TV" ~ "Trans TV", "TVRI" ~ "TVRI (stasiun televisi)", but not "Makan Receh" ~ "Air minum". */
    internal fun titleMatches(query: String, title: String): Boolean {
        val q = tokens(query); val t = tokens(title)
        if (q.isEmpty() || t.isEmpty()) return false
        val common = q.intersect(t).size
        return common.toDouble() / maxOf(q.size, t.size) >= 0.6 || (common == q.size && q.size >= 1 && t.size <= q.size + 2)
    }

    /** The Wikipedia search text: for a channel logo the name plus "television channel" in that wiki's language, so a station's article wins over a person or a place with the same name. */
    internal fun wikiQuery(lang: String, name: String, tvChannel: Boolean): String =
        if (!tvChannel) name else name + if (lang == "id") " stasiun televisi" else " television channel"

    private fun scrapeWikiThumb(query: String, size: Int, tvChannel: Boolean = false): String? {
        for (lang in listOf("id", "en")) {
            val q = URLEncoder.encode(wikiQuery(lang, query, tvChannel), "UTF-8")
            val api = "https://$lang.wikipedia.org/w/api.php?action=query&generator=search&gsrsearch=$q&gsrlimit=2&prop=pageimages&piprop=thumbnail&pithumbsize=$size&format=json"
            val body = runCatching { Web.get(api, 3500, mapOf("User-Agent" to "TahoeTV/3.3")) }.getOrNull() ?: continue
            parseWikiPageImage(body, query)?.let { return it }
        }
        return null
    }

    private fun scrapeBingImage(query: String): String? {
        val q = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.bing.com/images/search?q=$q&first=1"
        val html = runCatching { Web.get(url, 4000, mapOf("User-Agent" to UA)) }.getOrNull() ?: return null
        return parseBingFirstImage(html)
    }
}
