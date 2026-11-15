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

        val found = (scrapeWikiThumb(clean, 400) ?: scrapeBingImage("$clean tv channel logo png"))
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

        val found = (scrapeWikiThumb(prog, 640) ?: scrapeBingImage(if (ch.isNotBlank()) "$prog $ch tv" else "$prog tv"))
            ?.takeIf(::verifyImageUrl)
        thumbCache[key] = Cached(found, now)
        scheduleSave("thumbs.tsv", thumbCache)
        return found
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

    internal fun parseWikiPageImage(json: String): String? = runCatching {
        val pages = JSONObject(json).optJSONObject("query")?.optJSONObject("pages") ?: return null
        val keys = pages.keys()
        while (keys.hasNext()) {
            val page = pages.optJSONObject(keys.next()) ?: continue
            val thumb = page.optJSONObject("thumbnail")?.optString("source")?.takeIf { it.startsWith("https://") }
            if (thumb != null) return thumb
        }
        null
    }.getOrNull()

    private fun scrapeWikiThumb(query: String, size: Int): String? {
        val q = URLEncoder.encode(query, "UTF-8")
        for (lang in listOf("id", "en")) {
            val api = "https://$lang.wikipedia.org/w/api.php?action=query&generator=search&gsrsearch=$q&gsrlimit=2&prop=pageimages&piprop=thumbnail&pithumbsize=$size&format=json"
            val body = runCatching { Web.get(api, 3500, mapOf("User-Agent" to "TahoeTV/3.3")) }.getOrNull() ?: continue
            parseWikiPageImage(body)?.let { return it }
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
