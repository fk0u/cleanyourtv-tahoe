package tech.kousozo.tahoetv.desktop

import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.Executors
import org.json.JSONObject

/** Local HTTP relay (127.0.0.1 only) so the player can open streams that need request headers (Referer,
 *  User-Agent…): JavaFX's media stack can't send them. Playlists are rewritten so every segment/sub-playlist
 *  also goes through the relay with the same headers. */
object HlsProxy {
    private val signer = tech.kousozo.tahoetv.core.UrlSigner()
    private val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newFixedThreadPool(8)
            createContext("/s") { ex ->
                try {
                    val q = ex.requestURI.rawQuery.orEmpty().split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                    val url = String(Base64.getUrlDecoder().decode(q.getValue("u")))
                    // only URLs this proxy handed out: a web page can't use it to reach other addresses
                    if (!signer.verify(q["h"].orEmpty() + "\n" + q["x"].orEmpty(), url, q["s"])) { ex.sendResponseHeaders(403, -1); return@createContext }
                    val headers = q["h"]?.let { JSONObject(String(Base64.getUrlDecoder().decode(it))).toMap().mapValues { (_, v) -> "$v" } }.orEmpty()
                    val extra = q["x"]?.takeIf { it.isNotEmpty() }?.let { JSONObject(String(Base64.getUrlDecoder().decode(it))) }
                    if (extra != null) { dash(ex, url, headers, extra); return@createContext }
                    val con = open(url, headers)
                    val type = con.contentType.orEmpty()
                    val playlist = url.substringBefore('?').endsWith(".m3u8", true) || "mpegurl" in type.lowercase()
                    if (playlist) {
                        if (con.responseCode !in 200..299) { con.disconnect(); ex.sendResponseHeaders(502, -1); return@createContext }
                        val raw = tech.kousozo.tahoetv.core.Web.body(con)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                        // "200 OK" with an HTML page: fail now, so the player moves on instead of waiting out its timeout
                        if (!raw.trimStart().startsWith("#EXTM3U")) { ex.sendResponseHeaders(502, -1); return@createContext }
                        val text = playable(raw, con.url.toString(), headers) ?: run { ex.sendResponseHeaders(502, -1); return@createContext }
                        val body = rewrite(text, con.url.toString(), headers).toByteArray()
                        ex.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
                        ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
                    } else if ("text/html" in type.lowercase() || con.responseCode >= 400) {
                        con.disconnect(); ex.sendResponseHeaders(502, -1) // an error page or a refused segment: the player moves on
                    } else {
                        if (type.isNotEmpty()) ex.responseHeaders.add("Content-Type", type)
                        // decoded here, so no Content-Encoding goes to the player
                        ex.sendResponseHeaders(con.responseCode, 0); ex.responseBody.use { out -> tech.kousozo.tahoetv.core.Web.body(con)!!.use { it.copyTo(out) } }
                    }
                } catch (e: Exception) {
                    runCatching { ex.sendResponseHeaders(502, -1) }
                } finally { ex.close() }
            }
            start()
        }
    }

    private fun open(url: String, headers: Map<String, String>): HttpURLConnection =
        tech.kousozo.tahoetv.core.Web.openFollowingRedirects(url, 15_000, headers, connectTimeoutMs = 8000)

    private val URI_ATTR = Regex("""URI="([^"]+)"""")

    /** Strip pre-roll segments before the last `#EXT-X-DISCONTINUITY` (that has trailing segments) so
     *  JavaFX MediaPlayer does not stall or fail on codec/timestamp switches in live media playlists. */
    internal fun trimDiscontinuity(playlist: String): String {
        val lines = playlist.replace("\r\n", "\n").lines()
        val discIdx = lines.indices.lastOrNull { i ->
            lines[i].trim().equals("#EXT-X-DISCONTINUITY", ignoreCase = true) &&
                lines.drop(i + 1).any { it.trim().startsWith("#EXTINF", ignoreCase = true) }
        } ?: return playlist
        val firstSeg = lines.indexOfFirst { it.trim().startsWith("#EXTINF", ignoreCase = true) }
        if (firstSeg < 0 || discIdx < firstSeg) return playlist
        val droppedSegs = lines.subList(firstSeg, discIdx).count { it.trim().startsWith("#EXTINF", ignoreCase = true) }
        val header = lines.take(firstSeg).mapNotNull { line ->
            val t = line.trim()
            when {
                t.startsWith("#EXT-X-DISCONTINUITY", ignoreCase = true) -> null
                t.startsWith("#EXT-X-PROGRAM-DATE-TIME", ignoreCase = true) -> null
                t.startsWith("#EXT-X-MEDIA-SEQUENCE:", ignoreCase = true) -> {
                    val seq = t.substringAfter(':').trim().toLongOrNull()
                    if (seq != null) "#EXT-X-MEDIA-SEQUENCE:${seq + droppedSegs}" else line
                }
                else -> line
            }
        }
        val tail = lines.drop(discIdx + 1).dropWhile { it.trim().equals("#EXT-X-DISCONTINUITY", ignoreCase = true) }
        return (header + tail).joinToString("\n")
    }

    /** What JavaFX gets instead of the source's playlist: a master playlist without its dead variants (JavaFX plays the
     *  first variant and never switches; sources often keep the master up while a variant gives 403/404), capped to the
     *  chosen quality. Null when no variant answers. */
    internal fun playable(text: String, base: String, headers: Map<String, String>): String? =
        tech.kousozo.tahoetv.core.HlsProbe.dropDeadVariants(text, base, headers, 5000)
            ?.let { tech.kousozo.tahoetv.core.PlayPrefs.capVariants(it, Settings.quality.maxHeight) }

    /** Absolute-ise every URL in a playlist (after trimming discontinuity pre-rolls) and point it back at the relay. */
    fun rewrite(text: String, base: String, headers: Map<String, String>): String = trimDiscontinuity(text).lineSequence().joinToString("\n") { line ->
        when {
            line.isBlank() -> line
            line.startsWith("#") -> URI_ATTR.replace(line) { m -> "URI=\"" + wrap(URI(base).resolve(m.groupValues[1]).toString(), headers) + "\"" }
            else -> wrap(URI(base).resolve(line.trim()).toString(), headers)
        }
    }

    /** URL the player should open. Streams without special headers are played directly (measured: going through the
     *  relay made ad-supported channels that play directly time out in JavaFX). */
    fun wrap(url: String, headers: Map<String, String>, force: Boolean = false): String {
        // straight to the source unless headers are needed, a quality cap has to be applied, or [force] (its first
        // variant is dead: the relay hands JavaFX a master without it)
        if (!force && headers.isEmpty() && Settings.quality.maxHeight == 0) return url
        return signed(url, headers, null)
    }

    /** A DASH (`.mpd`) stream for JavaFX: the relay serves it as HLS (fMP4) and, with ClearKey keys in [drmKey],
     *  decrypts every init and media segment on the way, so the picture plays inside the app window. */
    fun wrapDash(url: String, headers: Map<String, String>, drmKey: String?): String =
        signed(url, headers, JSONObject().put("t", "mpd").apply { drmKey?.takeIf { it.isNotBlank() }?.let { put("k", it) } })

    private fun signed(url: String, headers: Map<String, String>, extra: JSONObject?): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val h = enc.encodeToString(JSONObject(headers).toString().toByteArray())
        val x = extra?.let { enc.encodeToString(it.toString().toByteArray()) }.orEmpty()
        return "http://127.0.0.1:${server.address.port}/s?u=" + enc.encodeToString(url.toByteArray()) +
            "&h=" + URLEncoder.encode(h, "UTF-8") + (if (x.isNotEmpty()) "&x=" + URLEncoder.encode(x, "UTF-8") else "") +
            "&s=" + signer.sign("$h\n$x", url)
    }

    /** Init segments' encryption info (track → KID), so media segments of multi-key streams use the right key. */
    private val initInfo = java.util.concurrent.ConcurrentHashMap<String, Map<Int, tech.kousozo.tahoetv.core.CencDecrypt.TrackCrypto>>()

    private fun fetchBytes(url: String, headers: Map<String, String>): ByteArray? {
        val con = open(url, headers)
        if (con.responseCode !in 200..299 || "text/html" in con.contentType.orEmpty().lowercase()) { con.disconnect(); return null }
        return tech.kousozo.tahoetv.core.Web.body(con)!!.use { it.readBytes() }
    }

    private fun send(ex: com.sun.net.httpserver.HttpExchange, type: String, body: ByteArray?) {
        if (body == null) { ex.sendResponseHeaders(502, -1); return }
        ex.responseHeaders.add("Content-Type", type)
        ex.responseHeaders.add("Cache-Control", "no-cache")
        ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
    }

    /** `t` = mpd (HLS master), rep (HLS media playlist of one representation, `r`), init / seg (fMP4 pieces, decrypted
     *  with the ClearKey keys `k` when given; `i` = the init segment a media segment belongs to). */
    private fun dash(ex: com.sun.net.httpserver.HttpExchange, url: String, headers: Map<String, String>, x: JSONObject) {
        val key = x.optString("k").ifEmpty { null }
        val keys = tech.kousozo.tahoetv.core.Drm.clearKeyMap(key)
        fun child(t: String, u: String, more: JSONObject.() -> Unit = {}) =
            signed(u, headers, JSONObject().put("t", t).apply { key?.let { put("k", it) }; more() })
        when (x.optString("t")) {
            "mpd", "rep" -> {
                val con = open(url, headers)
                if (con.responseCode !in 200..299) { con.disconnect(); ex.sendResponseHeaders(502, -1); return }
                val xml = tech.kousozo.tahoetv.core.Web.body(con)!!.use { it.readBytes().toString(Charsets.UTF_8) }
                val base = con.url.toString()
                if (!xml.contains("<MPD", ignoreCase = true)) { ex.sendResponseHeaders(502, -1); return }
                val out = if (x.optString("t") == "mpd") {
                    tech.kousozo.tahoetv.core.DashToHls.toMasterM3u8(xml, base, Settings.quality.maxHeight) { rep -> child("rep", url) { put("r", rep) } }
                } else {
                    var init: String? = null
                    tech.kousozo.tahoetv.core.DashToHls.toMediaM3u8(xml, base, x.optString("r")) { seg, isInit ->
                        if (isInit) { init = seg; child("init", seg) } else child("seg", seg) { init?.let { put("i", it) } }
                    }
                }
                send(ex, "application/vnd.apple.mpegurl", out?.toByteArray())
            }
            "init" -> {
                val data = fetchBytes(url, headers)
                if (data != null && keys.isNotEmpty()) initInfo[url] = tech.kousozo.tahoetv.core.CencDecrypt.parseInitInfo(data)
                send(ex, "video/mp4", if (data != null && keys.isNotEmpty()) tech.kousozo.tahoetv.core.CencDecrypt.decryptInit(data) else data)
            }
            "seg" -> {
                val data = fetchBytes(url, headers)
                val tracks = x.optString("i").ifEmpty { null }?.let { i ->
                    initInfo[i] ?: fetchBytes(i, headers)?.let { tech.kousozo.tahoetv.core.CencDecrypt.parseInitInfo(it) }?.also { initInfo[i] = it }
                }.orEmpty()
                send(ex, "video/mp4", if (data != null && keys.isNotEmpty()) runCatching {
                    tech.kousozo.tahoetv.core.CencDecrypt.decryptMedia(data, keys, initTracks = tracks)
                }.getOrNull() else data)
            }
            else -> ex.sendResponseHeaders(400, -1)
        }
    }
}
