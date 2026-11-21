package tech.kousozo.tahoetv.core

import org.json.JSONObject

/** M3U/M3U8 playlist parser for IPTV lists: #EXTINF attributes plus the per-stream option lines players use
 *  (#EXTVLCOPT / #EXTHTTP headers, #KODIPROP DRM licences). */
object M3u {
    /** `headers`: HTTP headers the stream needs (Referer, User-Agent…). `drm`: encrypted (has a licence key).
     *  A bare `license_type` on an HLS URL is not DRM: some playlists copy it onto plain streams. */
    data class Channel(val name: String, val url: String, val logo: String?, val group: String?,
                       val headers: Map<String, String> = emptyMap(), val drm: Boolean = false,
                       val tvgId: String? = null, val drmType: String? = null, val drmKey: String? = null,
                       val manifestType: String? = null, val countryCode: String? = null,
                       val catchup: String? = null, val catchupSource: String? = null) {
        /** Stable identifier across stream URL rotations: normalized tvg-id when available, otherwise name slug. */
        val channelId: String
            get() = tvgId?.trim()?.lowercase()?.replace(Regex("[^a-z0-9._-]+"), "-")?.trim('-')?.takeIf { it.isNotEmpty() }
                ?: slug(name)

        /** True when this stream points to an MPEG-DASH (`.mpd`) manifest. */
        val isDash: Boolean get() = Drm.isDash(url, drmType, manifestType)
    }

    /** Metadata parsed from `#EXTM3U` header directives (EPG XMLTV guide URL, catchup mode, auto-refresh interval). */
    data class PlaylistMetadata(
        val epgUrl: String? = null,
        val catchup: String? = null,
        val refreshMinutes: Int? = null,
    )

    /** Extracts directives like `url-tvg="http://..."` or `x-tvg-url="..."` from `#EXTM3U` header lines. */
    @JvmStatic
    fun parseMetadata(text: String): PlaylistMetadata {
        val firstHeader = text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("#EXTM3U", ignoreCase = true) }
            ?: return PlaylistMetadata()
        val epg = Regex("""(?:url-tvg|x-tvg-url)="([^"]*)"""", RegexOption.IGNORE_CASE).find(firstHeader)?.groupValues?.get(1)?.trim()
        val catchup = Regex("""catchup="([^"]*)"""", RegexOption.IGNORE_CASE).find(firstHeader)?.groupValues?.get(1)?.trim()
        val refresh = Regex("""refresh="?(\d+)"?""", RegexOption.IGNORE_CASE).find(firstHeader)?.groupValues?.get(1)?.toIntOrNull()
        return PlaylistMetadata(epg?.ifBlank { null }, catchup?.ifBlank { null }, refresh)
    }

    /** Public, community-maintained list of freely available Indonesian channels (iptv-org). */
    const val DEFAULT = "https://iptv-org.github.io/iptv/countries/id.m3u"

    @JvmStatic
    fun slug(name: String): String =
        LocalTv.cleanName(name).lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "ch" }

    private val attr = Regex("""([\w-]+)="([^"]*)"""")

    /** `"https://x/"` → `https://x/`: some playlists quote option values, and the quotes then went out in the header. */
    private fun unquote(v: String) = v.trim().removeSurrounding("\"").removeSurrounding("'").trim()

    private fun normalizeHeaderKey(raw: String): String? = when (raw.trim().lowercase()) {
        "referer", "referrer", "http-referer", "http-referrer" -> "Referer"
        "user-agent", "http-user-agent" -> "User-Agent"
        "origin", "http-origin" -> "Origin"
        else -> null
    }

    fun parse(text: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var info: String? = null
        val headers = LinkedHashMap<String, String>()
        var drm = false
        var drmType: String? = null
        var drmKey: String? = null
        var manifestType: String? = null
        var group: String? = null

        fun resetPending() {
            info = null; headers.clear(); drm = false; drmType = null; drmKey = null; manifestType = null; group = null
        }

        fun applyKeyValues(raw: String, allowDrm: Boolean) {
            raw.split('&', '|').forEach { kv ->
                kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { (k, v) ->
                    val cleaned = unquote(v)
                    when (val hk = normalizeHeaderKey(k)) {
                        null -> if (allowDrm) when (k.trim().lowercase()) {
                            "license_type" -> drmType = cleaned.ifEmpty { drmType }
                            "license_key" -> if (cleaned.isNotEmpty()) { drmKey = cleaned; drm = true }
                            "manifest_type" -> manifestType = cleaned.ifEmpty { manifestType }
                        }
                        else -> if (cleaned.isNotEmpty()) headers[hk] = cleaned
                    }
                }
            }
        }

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                // Options may come before #EXTINF (dhanytv) or between it and the URL (VLC style): both belong to the next URL.
                // Only clear pending options when a previous #EXTINF had no URL line.
                line.startsWith("#EXTINF") -> {
                    if (info != null) resetPending()
                    info = line
                }
                line.startsWith("#EXTVLCOPT:") -> line.substringAfter(':').split('=', limit = 2).takeIf { it.size == 2 }?.let { (k, v) ->
                    normalizeHeaderKey(k)?.let { hk -> headers[hk] = unquote(v) }
                }
                line.startsWith("#EXTHTTP:") -> runCatching {
                    JSONObject(line.substringAfter(':')).let { j ->
                        j.keys().forEach { k ->
                            val hk = normalizeHeaderKey(k) ?: k
                            j.optString(k).takeIf { it.isNotEmpty() }?.let { headers[hk] = unquote(it) }
                        }
                    }
                }
                line.startsWith("#KODIPROP:") -> {
                    val prop = line.substringAfter(':')
                    when {
                        prop.startsWith("inputstream.adaptive.license_type=", true) -> {
                            drmType = unquote(prop.substringAfter('='))
                        }
                        prop.startsWith("inputstream.adaptive.license_key=", true) -> {
                            val keyVal = unquote(prop.substringAfter('='))
                            if (keyVal.isNotEmpty()) {
                                drmKey = keyVal
                                drm = true
                            }
                        }
                        prop.startsWith("inputstream.adaptive.manifest_type=", true) -> {
                            manifestType = unquote(prop.substringAfter('='))
                        }
                        prop.startsWith("inputstream.adaptive.stream_headers=", true) -> // "A=1&B=2" or "A=1|B=2"
                            applyKeyValues(prop.substringAfter('='), allowDrm = false)
                    }
                }
                line.startsWith("#EXTGRP:") -> group = line.substringAfter(':').trim().ifEmpty { null }
                line.isEmpty() || line.startsWith("#") -> {}
                info != null -> {
                    val curInfo = info!!
                    val attrs = attr.findAll(curInfo.substringBefore(',', curInfo)).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    (attrs["http-referrer"] ?: attrs["http-referer"])?.takeIf { it.isNotBlank() }?.let { headers.putIfAbsent("Referer", it) }
                    attrs["http-user-agent"]?.takeIf { it.isNotBlank() }?.let { headers.putIfAbsent("User-Agent", it) }
                    attrs["http-origin"]?.takeIf { it.isNotBlank() }?.let { headers.putIfAbsent("Origin", it) }
                    val cleanUrl = line.substringBefore('|').trim()
                    if ('|' in line) applyKeyValues(line.substringAfter('|'), allowDrm = true)
                    val name = curInfo.substringAfterLast(',').trim().ifEmpty { attrs["tvg-name"] ?: cleanUrl }
                    val catchup = attrs["catchup"]?.takeIf { it.isNotBlank() }
                    val catchupSource = attrs["catchup-source"]?.takeIf { it.isNotBlank() }
                    out += Channel(
                        name.replace(Regex("""\s*\(\d+[pi]\)"""), ""),
                        cleanUrl,
                        attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                        (attrs["group-title"]?.takeIf { it.isNotBlank() } ?: group)?.substringBefore(';'),
                        headers.toMap(),
                        drm || !drmKey.isNullOrBlank(),
                        attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                        drmType,
                        drmKey,
                        manifestType,
                        countryCode = null,
                        catchup = catchup,
                        catchupSource = catchupSource,
                    )
                    resetPending()
                }
                else -> resetPending() // stray URL without #EXTINF: its options don't leak into the next entry
            }
        }
        return out.distinctBy { it.url }
    }

    /** URL, or a local file path (playlists people share as .m3u/.txt files). */
    fun load(source: String) = parse(
        if (source.startsWith("http://") || source.startsWith("https://")) Web.get(source, 30_000)
        else java.io.File(source.removePrefix("file://")).readText())
}
