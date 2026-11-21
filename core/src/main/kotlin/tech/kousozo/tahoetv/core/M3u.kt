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
        if (isOttJson(text)) return parseOttJson(text).first
        val firstHeader = text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("#EXTM3U", ignoreCase = true) }
            ?: return PlaylistMetadata()
        val epg = Regex("""(?:url-tvg|x-tvg-url)="([^"]*)"""", RegexOption.IGNORE_CASE).find(firstHeader)?.groupValues?.get(1)?.trim()
        val catchup = Regex("""catchup="([^"]*)"""", RegexOption.IGNORE_CASE).find(firstHeader)?.groupValues?.get(1)?.trim()
        val refresh = Regex("""refresh="?(\d+)"?""", RegexOption.IGNORE_CASE).find(firstHeader)?.groupValues?.get(1)?.toIntOrNull()
        return PlaylistMetadata(epg?.ifBlank { null }, catchup?.ifBlank { null }, refresh)
    }

    @JvmStatic
    fun isOttJson(text: String): Boolean {
        val trimmed = text.trimStart()
        return trimmed.startsWith("{") && trimmed.contains("\"channels\"")
    }

    private fun decodeBase64Safe(v: String?): String? {
        if (v == null || v.isBlank()) return null
        val trimmed = v.trim()
        return runCatching {
            String(java.util.Base64.getDecoder().decode(trimmed), Charsets.UTF_8).trim()
        }.recoverCatching {
            String(java.util.Base64.getUrlDecoder().decode(trimmed), Charsets.UTF_8).trim()
        }.getOrNull()?.ifEmpty { null }
    }

    /** Parses custom JSON channel payloads returned by OTT servers (e.g. plsemar, OTT Navigator schema). */
    @JvmStatic
    fun parseOttJson(text: String): Pair<PlaylistMetadata, List<Channel>> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return PlaylistMetadata() to emptyList()
        val providerObj = root.optJSONObject("provider")
        val epgUrl = providerObj?.optString("epg")?.takeIf { it.isNotBlank() }
        val meta = PlaylistMetadata(epgUrl = epgUrl)

        val catMap = mutableMapOf<String, String>()
        root.optJSONObject("categories")?.let { cats ->
            cats.keys().forEach { k ->
                val catObj = cats.optJSONObject(k)
                val catName = catObj?.optString("name")?.let { decodeBase64Safe(it) ?: it }
                if (!catName.isNullOrBlank()) catMap[k] = catName
            }
        }

        val channelsArr = root.optJSONArray("channels") ?: return meta to emptyList()
        val out = mutableListOf<Channel>()
        for (i in 0 until channelsArr.length()) {
            val chObj = channelsArr.optJSONObject(i) ?: continue
            val rawName = chObj.optString("name", "")
            val rawUrl = chObj.optString("url", "")
            val name = (decodeBase64Safe(rawName) ?: rawName).trim()
            val url = (decodeBase64Safe(rawUrl) ?: rawUrl).trim()
            if (url.isBlank() || name.isBlank()) continue

            val rawCategory = chObj.optString("category", "")
            val decodedCat = decodeBase64Safe(rawCategory) ?: rawCategory
            val group = catMap[decodedCat] ?: catMap[rawCategory] ?: decodedCat.takeIf { it.isNotBlank() }

            val rawIcon = chObj.optString("icon", "")
            val icon = decodeBase64Safe(rawIcon) ?: rawIcon.takeIf { it.isNotBlank() }

            val rawUa = chObj.optString("user_agent", "")
            val ua = decodeBase64Safe(rawUa) ?: rawUa.takeIf { it.isNotBlank() }

            val rawRef = chObj.optString("referrer", "").ifEmpty { chObj.optString("referer", "") }
            val ref = decodeBase64Safe(rawRef) ?: rawRef.takeIf { it.isNotBlank() }

            val rawFormat = chObj.optString("source_format", "")
            val format = (decodeBase64Safe(rawFormat) ?: rawFormat).lowercase().takeIf { it.isNotBlank() }

            val headers = mutableMapOf<String, String>()
            if (!ua.isNullOrBlank()) headers["User-Agent"] = ua
            if (!ref.isNullOrBlank()) headers["Referer"] = ref

            val isDash = format == "dash" || url.contains(".mpd", ignoreCase = true)
            val manifestType = if (isDash) "dash" else null

            out += Channel(
                name = name.replace(Regex("""\s*\(\d+[pi]\)"""), ""),
                url = url,
                logo = icon?.takeIf { it.isNotBlank() },
                group = group?.takeIf { it.isNotBlank() },
                headers = headers,
                drm = false,
                tvgId = null,
                drmType = null,
                drmKey = null,
                manifestType = manifestType,
                countryCode = null,
                catchup = null,
                catchupSource = null,
            )
        }
        return meta to out.distinctBy { it.url }
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
        if (isOttJson(text)) return parseOttJson(text).second
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
    fun load(source: String): List<Channel> {
        val text = if (source.startsWith("http://") || source.startsWith("https://")) {
            runCatching {
                Web.get(source, 30_000, headers = mapOf("User-Agent" to OttEngine.DEFAULT_OTT_UA))
            }.recoverCatching {
                Web.get(source, 30_000)
            }.getOrDefault("")
        } else {
            runCatching { java.io.File(source.removePrefix("file://")).readText() }.getOrDefault("")
        }
        return parse(text)
    }
}
