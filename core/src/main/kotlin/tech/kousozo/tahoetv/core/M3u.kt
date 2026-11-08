package tech.kousozo.tahoetv.core

import org.json.JSONObject

/** M3U/M3U8 playlist parser for IPTV lists: #EXTINF attributes plus the per-stream option lines players use
 *  (#EXTVLCOPT / #EXTHTTP headers, #KODIPROP DRM licences). */
object M3u {
    /** `headers`: HTTP headers the stream needs (Referer, User-Agent…). `drm`: encrypted (licence key / DASH), never played. */
    data class Channel(val name: String, val url: String, val logo: String?, val group: String?,
                       val headers: Map<String, String> = emptyMap(), val drm: Boolean = false)

    /** Public, community-maintained list of freely available Indonesian channels (iptv-org). */
    const val DEFAULT = "https://iptv-org.github.io/iptv/countries/id.m3u"

    private val attr = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(text: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var info: String? = null
        val headers = LinkedHashMap<String, String>()
        var drm = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                // Options may come before #EXTINF (dhanytv) or between it and the URL (VLC style): both belong to the next URL.
                line.startsWith("#EXTINF") -> info = line
                line.startsWith("#EXTVLCOPT:") -> line.substringAfter(':').split('=', limit = 2).takeIf { it.size == 2 }?.let { (k, v) ->
                    when (k.trim().lowercase()) {
                        "http-referrer", "http-referer" -> headers["Referer"] = v.trim()
                        "http-user-agent" -> headers["User-Agent"] = v.trim().trim('"')
                        "http-origin" -> headers["Origin"] = v.trim()
                    }
                }
                line.startsWith("#EXTHTTP:") -> runCatching { JSONObject(line.substringAfter(':')).let { j -> j.keys().forEach { k -> headers[k] = j.getString(k) } } }
                line.startsWith("#KODIPROP:") -> if ("license" in line.lowercase() || "drm" in line.lowercase()) drm = true
                line.isEmpty() || line.startsWith("#") -> {}
                info != null -> {
                    val attrs = attr.findAll(info.substringBefore(',', info)).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    (attrs["http-referrer"] ?: attrs["http-referer"])?.takeIf { it.isNotBlank() }?.let { headers.putIfAbsent("Referer", it) }
                    attrs["http-user-agent"]?.takeIf { it.isNotBlank() }?.let { headers.putIfAbsent("User-Agent", it) }
                    val name = info.substringAfterLast(',').trim().ifEmpty { attrs["tvg-name"] ?: line }
                    val dash = line.substringBefore('?').endsWith(".mpd", ignoreCase = true)
                    out += Channel(name.replace(Regex("""\s*\(\d+[pi]\)"""), ""), line, attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                        attrs["group-title"]?.takeIf { it.isNotBlank() }?.substringBefore(';'), headers.toMap(), drm || dash)
                    info = null; headers.clear(); drm = false
                }
                else -> { headers.clear(); drm = false } // stray URL without #EXTINF: its options don't leak into the next entry
            }
        }
        return out.distinctBy { it.url }
    }

    fun load(url: String) = parse(Web.get(url, 30_000))
}
