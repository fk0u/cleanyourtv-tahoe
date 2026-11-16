package tech.kousozo.tahoetv.core

import java.io.File
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject

/**
 * Free Photo API Wallpaper Provider (#39):
 * fetches resolution-appropriate daily wallpapers from Wikimedia Commons Picture of the Day (no API key required),
 * Unsplash API (with photographer attribution & download ping), or Pexels API, caches them locally,
 * enforces safe Kids themes, and falls back gracefully to Tahoe gradients when offline.
 */
object WallpaperProvider {
    enum class Source(val key: String) {
        AUTO("auto"),
        WIKIMEDIA("wikimedia"),
        UNSPLASH("unsplash"),
        PEXELS("pexels"),
        GRADIENT("gradient");

        companion object {
            @JvmStatic
            fun fromKey(raw: String?): Source =
                entries.firstOrNull { it.key.equals(raw?.trim(), ignoreCase = true) } ?: AUTO
        }
    }

    enum class Theme(val key: String, val query: String) {
        NATURE("nature", "landscape nature scenic dark"),
        CITY("city", "night city skyline architecture"),
        INDONESIA("indonesia", "indonesia tropical landscape mountain"),
        NIGHT("night", "minimal dark night sky aurora"),
        KIDS_SAFE("kids_safe", "illustration space stars nature animals");

        companion object {
            @JvmStatic
            fun fromKey(raw: String?): Theme =
                entries.firstOrNull { it.key.equals(raw?.trim(), ignoreCase = true) } ?: NATURE
        }
    }

    data class PhotoMeta(
        val id: String,
        val imageUrl: String,
        val downloadUrl: String = "",
        val author: String = "",
        val providerName: String = "",
        val attribution: String = "",
    )

    private const val META_FILE = "wall_meta.tsv"
    private const val MAX_CACHED_FILES = 10

    @JvmStatic
    fun targetWidth(tier: DeviceProfile.Tier): Int = when (tier) {
        DeviceProfile.Tier.FULL -> 3840
        DeviceProfile.Tier.STANDARD -> 1920
        DeviceProfile.Tier.LIGHT -> 1280
    }

    @JvmStatic
    fun effectiveTheme(theme: Theme, kidsProfile: Boolean): Theme =
        if (kidsProfile) Theme.KIDS_SAFE else theme

    @JvmStatic
    fun effectiveSource(source: Source, unsplashKey: String, pexelsKey: String): Source = when (source) {
        Source.UNSPLASH -> if (unsplashKey.isNotBlank()) Source.UNSPLASH else Source.WIKIMEDIA
        Source.PEXELS -> if (pexelsKey.isNotBlank()) Source.PEXELS else Source.WIKIMEDIA
        Source.AUTO -> when {
            unsplashKey.isNotBlank() -> Source.UNSPLASH
            pexelsKey.isNotBlank() -> Source.PEXELS
            else -> Source.WIKIMEDIA
        }
        else -> source
    }

    @JvmStatic
    fun formatAttribution(author: String, provider: String, lang: String): String {
        val cleanAuthor = author.trim()
        val cleanProv = provider.trim()
        if (cleanAuthor.isEmpty() && cleanProv.isEmpty()) return ""
        val who = cleanAuthor.ifEmpty { cleanProv }
        val on = if (cleanProv.isNotEmpty() && !who.equals(cleanProv, ignoreCase = true)) " · $cleanProv" else ""
        return I18n.get(lang, "wall.attribution", "$who$on")
    }

    @JvmStatic
    @JvmOverloads
    fun parseWikimediaPotd(jsonText: String, targetWidth: Int = 1920, lang: String = "id"): PhotoMeta? {
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        val pages = root.optJSONObject("query")?.optJSONObject("pages") ?: return null
        val keys = pages.keys()
        while (keys.hasNext()) {
            val page = pages.optJSONObject(keys.next()) ?: continue
            val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
            val thumbUrl = info.optString("thumburl").ifBlank { info.optString("url") }.trim()
            if (thumbUrl.isBlank()) continue
            val mime = info.optString("mime", "image/jpeg")
            if (!mime.startsWith("image/") || mime.contains("svg")) continue
            val meta = info.optJSONObject("extmetadata")
            val rawArtist = meta?.optJSONObject("Artist")?.optString("value").orEmpty()
            val cleanArtist = rawArtist.replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), " ").trim().take(48)
            val title = page.optString("title", "potd").replace("File:", "").take(48)
            val id = "wm_${title.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
            val resolvedUrl = thumbUrl.replace(Regex("/\\d+px-"), "/${targetWidth}px-")
            return PhotoMeta(
                id = id,
                imageUrl = resolvedUrl,
                author = cleanArtist.ifBlank { "Wikimedia Commons" },
                providerName = "Wikimedia Commons",
                attribution = formatAttribution(cleanArtist, "Wikimedia Commons", lang),
            )
        }
        return null
    }

    @JvmStatic
    @JvmOverloads
    fun parseUnsplashPhoto(jsonText: String, targetWidth: Int = 1920, lang: String = "id"): PhotoMeta? {
        val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        val urls = obj.optJSONObject("urls") ?: return null
        val rawUrl = urls.optString("raw").ifBlank { urls.optString("full") }.ifBlank { urls.optString("regular") }.trim()
        if (rawUrl.isBlank()) return null
        val sep = if ('?' in rawUrl) '&' else '?'
        val sizedUrl = if (urls.has("raw") && urls.optString("raw").isNotBlank()) {
            "$rawUrl${sep}w=$targetWidth&auto=format&fit=crop&q=82"
        } else {
            rawUrl
        }
        val author = obj.optJSONObject("user")?.optString("name").orEmpty().trim()
        val id = "un_" + obj.optString("id", System.currentTimeMillis().toString()).replace(Regex("[^A-Za-z0-9_-]"), "_")
        val dl = obj.optJSONObject("links")?.optString("download_location").orEmpty().trim()
        return PhotoMeta(
            id = id,
            imageUrl = sizedUrl,
            downloadUrl = dl,
            author = author,
            providerName = "Unsplash",
            attribution = formatAttribution(author, "Unsplash", lang),
        )
    }

    @JvmStatic
    @JvmOverloads
    fun parsePexelsPhoto(jsonText: String, targetWidth: Int = 1920, lang: String = "id"): PhotoMeta? {
        val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        val photos = obj.optJSONArray("photos") ?: return null
        for (i in 0 until photos.length()) {
            val p = photos.optJSONObject(i) ?: continue
            val src = p.optJSONObject("src") ?: continue
            val url = (if (targetWidth >= 2560) src.optString("original") else "")
                .ifBlank { src.optString("landscape") }
                .ifBlank { src.optString("large2x") }
                .ifBlank { src.optString("large") }
                .trim()
            if (url.isBlank()) continue
            val author = p.optString("photographer").trim()
            val id = "px_${p.optLong("id", i.toLong())}"
            return PhotoMeta(
                id = id,
                imageUrl = url,
                author = author,
                providerName = "Pexels",
                attribution = formatAttribution(author, "Pexels", lang),
            )
        }
        return null
    }

    @JvmStatic
    @JvmOverloads
    fun fetchDailyPhoto(
        source: Source,
        theme: Theme,
        unsplashKey: String,
        pexelsKey: String,
        tier: DeviceProfile.Tier,
        lang: String,
        kidsProfile: Boolean = false,
    ): PhotoMeta? {
        if (source == Source.GRADIENT) return null
        val effSource = effectiveSource(source, unsplashKey, pexelsKey)
        val effTheme = effectiveTheme(theme, kidsProfile)
        val width = targetWidth(tier)
        return when (effSource) {
            Source.UNSPLASH -> fetchUnsplash(effTheme, unsplashKey, width, lang)
                ?: fetchWikimedia(width, lang)
            Source.PEXELS -> fetchPexels(effTheme, pexelsKey, width, lang)
                ?: fetchWikimedia(width, lang)
            Source.WIKIMEDIA, Source.AUTO -> fetchWikimedia(width, lang)
            Source.GRADIENT -> null
        }
    }

    private fun fetchWikimedia(width: Int, lang: String): PhotoMeta? = runCatching {
        val date = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_LOCAL_DATE)
        val url = "https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=images" +
            "&titles=" + URLEncoder.encode("Template:Potd/$date", "UTF-8") +
            "&prop=imageinfo&iiprop=url|extmetadata|mime&iiurlwidth=$width"
        parseWikimediaPotd(Web.get(url, timeoutMs = 10_000), width, lang)
    }.getOrNull()

    private fun fetchUnsplash(theme: Theme, key: String, width: Int, lang: String): PhotoMeta? = runCatching {
        if (key.isBlank()) return null
        val url = "https://api.unsplash.com/photos/random?orientation=landscape&content_filter=high&query=" +
            URLEncoder.encode(theme.query, "UTF-8")
        val json = Web.get(url, timeoutMs = 10_000, headers = mapOf("Authorization" to "Client-ID ${key.trim()}"))
        val photo = parseUnsplashPhoto(json, width, lang) ?: return null
        if (photo.downloadUrl.isNotBlank()) {
            runCatching {
                Web.get(photo.downloadUrl, timeoutMs = 6_000, headers = mapOf("Authorization" to "Client-ID ${key.trim()}"))
            }
        }
        photo
    }.getOrNull()

    private fun fetchPexels(theme: Theme, key: String, width: Int, lang: String): PhotoMeta? = runCatching {
        if (key.isBlank()) return null
        val page = ((System.currentTimeMillis() / 3_600_000L) % 5L + 1L).toInt()
        val url = "https://api.pexels.com/v1/search?orientation=landscape&per_page=5&page=$page&query=" +
            URLEncoder.encode(theme.query, "UTF-8")
        val json = Web.get(url, timeoutMs = 10_000, headers = mapOf("Authorization" to key.trim()))
        parsePexelsPhoto(json, width, lang)
    }.getOrNull()

    /**
     * Downloads [meta] into [dir] if not yet cached, updates attribution index (`wall_meta.tsv`),
     * and prunes oldest files above [MAX_CACHED_FILES]. Returns cached image file or null on failure.
     */
    @JvmStatic
    @JvmOverloads
    fun cachePhoto(
        dir: File,
        meta: PhotoMeta,
        downloader: (String) -> ByteArray = { u -> Web.stream(u, 20_000).use { it.readBytes() } },
    ): File? = runCatching {
        if (!dir.exists()) dir.mkdirs()
        val safeId = meta.id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
        val out = File(dir, "$safeId.jpg")
        if (!out.exists() || out.length() < 1024L) {
            val bytes = downloader(meta.imageUrl)
            if (bytes.size < 1024) return null
            val tmp = File(dir, "$safeId.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(out)) {
                out.writeBytes(bytes)
                tmp.delete()
            }
        }
        saveAttribution(dir, out.name, meta.attribution)
        pruneOld(dir, out.name)
        out
    }.getOrNull()

    @JvmStatic
    fun loadAttributionMap(dir: File): Map<String, String> = runCatching {
        val f = File(dir, META_FILE)
        if (!f.exists()) return emptyMap()
        f.readLines().mapNotNull { line ->
            val parts = line.split("\t", limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank()) parts[0] to parts[1] else null
        }.toMap()
    }.getOrDefault(emptyMap())

    @JvmStatic
    fun attributionFor(dir: File?, imagePath: String?): String {
        if (dir == null || imagePath.isNullOrBlank()) return ""
        val name = File(imagePath).name
        return loadAttributionMap(dir)[name].orEmpty()
    }

    private fun saveAttribution(dir: File, fileName: String, attribution: String) {
        val map = loadAttributionMap(dir).toMutableMap()
        if (attribution.isNotBlank()) map[fileName] = attribution
        val f = File(dir, META_FILE)
        val content = map.entries.joinToString("\n") { "${it.key}\t${it.value.replace('\n', ' ')}" }
        f.writeText(content)
    }

    private fun pruneOld(dir: File, keepName: String) {
        val imgs = dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() } ?: return
        if (imgs.size <= MAX_CACHED_FILES) return
        val attrMap = loadAttributionMap(dir).toMutableMap()
        for (stale in imgs.drop(MAX_CACHED_FILES)) {
            if (stale.name == keepName) continue
            stale.delete()
            attrMap.remove(stale.name)
        }
        val f = File(dir, META_FILE)
        f.writeText(attrMap.entries.joinToString("\n") { "${it.key}\t${it.value}" })
    }
}
