package tech.kousozo.tahoetv.core

import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** Scrapes missing channel logos and live programme preview thumbnails from the internet (Wikipedia pageimages
 *  with Bing image search fallback), backed by a lightweight in-memory and disk cache so lookups happen at most once
 *  per day/session. Shared across desktop, TV launcher, and mobile companion. */
object MediaScraper {
    private const val TTL_MS = 24 * 60 * 60_000L
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val BING_MURL = Regex("""murl&quot;:&quot;(https://[^&]+)""")

    private data class Cached(val url: String?, val at: Long)

    private val logoCache = ConcurrentHashMap<String, Cached>()
    private val thumbCache = ConcurrentHashMap<String, Cached>()
    @Volatile private var cacheDir: File? = null

    /** Optional override for the disk cache directory (e.g. Android `context.cacheDir`). */
    @JvmStatic fun initCache(dir: File) {
        cacheDir = File(dir, "media-scraper").also { runCatching { it.mkdirs() } }
        loadCacheFile("logos.tsv", logoCache)
        loadCacheFile("thumbs.tsv", thumbCache)
    }

    private fun dir(): File? = cacheDir ?: runCatching {
        File(System.getProperty("user.home"), ".tahoetv/media-scraper").apply { mkdirs() }
    }.getOrNull()?.takeIf { it.isDirectory && it.canWrite() }

    private fun loadCacheFile(name: String, target: ConcurrentHashMap<String, Cached>) {
        val f = dir()?.let { File(it, name) } ?: return
        if (!f.isFile) return
        val now = System.currentTimeMillis()
        runCatching {
            f.forEachLine { line ->
                val p = line.split('\t')
                if (p.size == 3) {
                    val at = p[2].toLongOrNull() ?: return@forEachLine
                    if (now - at < TTL_MS) target[p[0]] = Cached(p[1].ifBlank { null }, at)
                }
            }
        }
    }

    @Synchronized private fun saveCacheFile(name: String, source: ConcurrentHashMap<String, Cached>) {
        val f = dir()?.let { File(it, name) } ?: return
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(source.entries.joinToString("\n") { (k, v) -> "$k\t${v.url.orEmpty()}\t${v.at}" })
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

        val found = scrapeWikiThumb(clean, 400)
            ?: scrapeBingImage("$clean tv channel logo png")
        logoCache[key] = Cached(found, now)
        saveCacheFile("logos.tsv", logoCache)
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

        val found = scrapeWikiThumb(prog, 640)
            ?: scrapeBingImage(if (ch.isNotBlank()) "$prog $ch tv" else "$prog tv")
        thumbCache[key] = Cached(found, now)
        saveCacheFile("thumbs.tsv", thumbCache)
        return found
    }

    internal fun parseBingFirstImage(html: String): String? =
        BING_MURL.find(html)?.groupValues?.get(1)?.takeIf { it.startsWith("https://") }

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
