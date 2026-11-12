package tech.kousozo.tahoetv.core

import org.json.JSONObject

/** M3U/M3U8 playlist parser for IPTV lists: #EXTINF attributes plus the per-stream option lines players use
 *  (#EXTVLCOPT / #EXTHTTP headers, #KODIPROP DRM licences). */
object M3u {
    /** `headers`: HTTP headers the stream needs (Referer, User-Agent…). `drm`: encrypted (has a licence key, or DASH), never played.
     *  A bare `license_type` on an HLS URL is not DRM: some playlists copy it onto plain streams. */
    data class Channel(val name: String, val url: String, val logo: String?, val group: String?,
                       val headers: Map<String, String> = emptyMap(), val drm: Boolean = false,
                       val tvgId: String? = null, val drmType: String? = null, val drmKey: String? = null)

    /** Public, community-maintained list of freely available Indonesian channels (iptv-org). */
    const val DEFAULT = "https://iptv-org.github.io/iptv/countries/id.m3u"

    private val attr = Regex("""([\w-]+)="([^"]*)"""")

    /** `"https://x/"` → `https://x/`: some playlists quote option values, and the quotes then went out in the header. */
    private fun unquote(v: String) = v.trim().removeSurrounding("\"").removeSurrounding("'").trim()

    fun parse(text: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var info: String? = null
        val headers = LinkedHashMap<String, String>()
        var drm = false
        var drmType: String? = null
        var drmKey: String? = null
        var group: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                // Options may come before #EXTINF (dhanytv) or between it and the URL (VLC style): both belong to the next URL.
                line.startsWith("#EXTINF") -> info = line
                line.startsWith("#EXTVLCOPT:") -> line.substringAfter(':').split('=', limit = 2).takeIf { it.size == 2 }?.let { (k, v) ->
                    when (k.trim().lowercase()) {
                        "http-referrer", "http-referer" -> headers["Referer"] = unquote(v)
                        "http-user-agent" -> headers["User-Agent"] = unquote(v)
                        "http-origin" -> headers["Origin"] = unquote(v)
                    }
                }
                line.startsWith("#EXTHTTP:") -> runCatching { JSONObject(line.substringAfter(':')).let { j -> j.keys().forEach { k -> headers[k] = j.getString(k) } } }
                line.startsWith("#KODIPROP:") -> {
                    val prop = line.substringAfter(':')
                    when {
                        prop.startsWith("inputstream.adaptive.license_type=", true) -> {
                            drmType = prop.substringAfter('=').trim()
                        }
                        prop.startsWith("inputstream.adaptive.license_key=", true) -> {
                            drmKey = prop.substringAfter('=').trim()
                            drm = true
                        }
                        prop.startsWith("inputstream.adaptive.stream_headers=", true) -> // "A=1&B=2" or "A=1|B=2"
                            prop.substringAfter('=').split('&', '|').forEach { kv ->
                                kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { (k, v) ->
                                    when (k.trim().lowercase()) { "referer", "referrer" -> headers["Referer"] = unquote(v); "user-agent" -> headers["User-Agent"] = unquote(v); "origin" -> headers["Origin"] = unquote(v) }
                                }
                            }
                    }
                }
                line.startsWith("#EXTGRP:") -> group = line.substringAfter(':').trim().ifEmpty { null }
                line.isEmpty() || line.startsWith("#") -> {}
                info != null -> {
                    val attrs = attr.findAll(info.substringBefore(',', info)).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    (attrs["http-referrer"] ?: attrs["http-referer"])?.takeIf { it.isNotBlank() }?.let { headers.putIfAbsent("Referer", it) }
                    attrs["http-user-agent"]?.takeIf { it.isNotBlank() }?.let { headers.putIfAbsent("User-Agent", it) }
                    val name = info.substringAfterLast(',').trim().ifEmpty { attrs["tvg-name"] ?: line }
                    val dash = line.substringBefore('?').endsWith(".mpd", ignoreCase = true)
                    out += Channel(name.replace(Regex("""\s*\(\d+[pi]\)"""), ""), line, attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                        (attrs["group-title"]?.takeIf { it.isNotBlank() } ?: group)?.substringBefore(';'), headers.toMap(), drm || dash,
                        attrs["tvg-id"]?.takeIf { it.isNotBlank() }, drmType, drmKey)
                    info = null; headers.clear(); drm = false; drmType = null; drmKey = null; group = null
                }
                else -> { headers.clear(); drm = false; drmType = null; drmKey = null; group = null } // stray URL without #EXTINF: its options don't leak into the next entry
            }
        }
        return out.distinctBy { it.url }
    }

    /** URL, or a local file path (playlists people share as .m3u/.txt files). */
    fun load(source: String) = parse(
        if (source.startsWith("http://") || source.startsWith("https://")) Web.get(source, 30_000)
        else java.io.File(source.removePrefix("file://")).readText())
}
