package tech.kousozo.tahoetv.desktop

import com.sun.net.httpserver.HttpExchange
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import tech.kousozo.tahoetv.core.HlsProbe
import tech.kousozo.tahoetv.core.Region
import tech.kousozo.tahoetv.core.UrlSigner
import tech.kousozo.tahoetv.core.Web

/**
 * Playlist, live HLS/DASH stream relay, EPG XML cache, and DVR recording HTTP handlers
 * extracted from [IptvServer].
 */
internal object ServerRelay {
    private val URI_ATTR = Regex("""URI="([^"]+)"""")
    private val RANGE = Regex("""bytes=(\d*)-(\d*)""")

    /** Signs the URLs this server writes into playlists; [serveSegment] and variant playlists only fetch signed ones,
     *  so the relay can't be pointed at arbitrary addresses (router pages, 127.0.0.1 services…). */
    private val signer = UrlSigner()

    /** Guide XML kept in memory for 6 h: every TV/phone/player asking used to download the whole file again. */
    @Volatile private var epgCache: ByteArray? = null
    @Volatile private var epgFor = "" // the country the cached guide is for
    @Volatile private var epgAt = 0L
    private val epgLock = Any()

    private fun relayParams(cid: String, url: String) =
        "cid=" + URLEncoder.encode(cid, "UTF-8") +
            "&u=" + Base64.getUrlEncoder().withoutPadding().encodeToString(url.toByteArray()) +
            "&s=" + signer.sign(cid, url)

    fun servePlaylist(ex: HttpExchange, query: Map<String, String>, sourceFilter: String? = null) {
        val base = IptvServer.baseUrl(ex)
        val group = query["group"]
        val raw = query["raw"] in listOf("1", "true")

        val sb = StringBuilder()
        sb.append("#EXTM3U url-tvg=\"$base/epg.xml\"\n")

        for (sc in IptvServer.currentChannels()) {
            if (sourceFilter != null && sc.source != sourceFilter) continue
            if (group != null && !sc.channel.group.equals(group, ignoreCase = true)) continue
            if (!IptvServer.wanted(sc, query)) continue

            val isMpd = sc.channel.url.contains(".mpd", ignoreCase = true)
            if (!sc.drmType.isNullOrBlank() || !sc.drmKey.isNullOrBlank() || isMpd) {
                sb.append("#KODIPROP:inputstreamaddon=inputstream.adaptive\n")
                if (isMpd) sb.append("#KODIPROP:inputstream.adaptive.manifest_type=mpd\n")
                sc.drmType?.let { sb.append("#KODIPROP:inputstream.adaptive.license_type=$it\n") }
                sc.drmKey?.let { sb.append("#KODIPROP:inputstream.adaptive.license_key=$it\n") }
            }

            if (sc.channel.headers.isNotEmpty()) {
                sc.channel.headers.forEach { (k, v) ->
                    when (k.lowercase()) {
                        "referer", "referrer" -> sb.append("#EXTVLCOPT:http-referrer=$v\n")
                        "user-agent" -> sb.append("#EXTVLCOPT:http-user-agent=$v\n")
                        "origin" -> sb.append("#EXTVLCOPT:http-origin=$v\n")
                    }
                }
            }

            val ext = if (isMpd) ".mpd" else ".m3u8"
            val streamUrl = if (raw) sc.channel.url else "$base/live/${sc.id}$ext"
            val tvgId = sc.channel.tvgId?.let { " tvg-id=\"$it\"" }.orEmpty()
            val logo = sc.channel.logo?.let { " tvg-logo=\"$it\"" }.orEmpty()
            val grp = sc.channel.group?.let { " group-title=\"$it\"" }.orEmpty()

            sb.append("#EXTINF:-1$tvgId$logo$grp,${sc.channel.name}\n")
            sb.append(streamUrl).append("\n")
        }

        val body = sb.toString().toByteArray(Charsets.UTF_8)
        IptvServer.sendCors(ex)
        ex.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl; charset=utf-8")
        ex.responseHeaders.add("Content-Disposition", "inline; filename=\"playlist.m3u\"")
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    fun serveLiveMpd(ex: HttpExchange, cid: String) {
        val sc = IptvServer.channelById(cid) ?: run { ex.sendResponseHeaders(404, -1); ex.close(); return }
        val con = open(sc.channel.url, sc.channel.headers)
        val body = Web.body(con)!!.use { it.readBytes() }
        IptvServer.sendCors(ex)
        ex.responseHeaders.add("Content-Type", "application/dash+xml")
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    fun serveLive(ex: HttpExchange, cid: String, subUrlB64: String?, sig: String?) {
        val sc = IptvServer.channelById(cid) ?: run { ex.sendResponseHeaders(404, -1); ex.close(); return }
        var targetUrl = sc.channel.url
        if (!subUrlB64.isNullOrEmpty()) {
            val sub = runCatching { String(Base64.getUrlDecoder().decode(subUrlB64), Charsets.UTF_8) }.getOrNull()
            if (sub == null || !signer.verify(cid, sub, sig)) { ex.sendResponseHeaders(403, -1); ex.close(); return }
            targetUrl = sub
        }

        val con = open(targetUrl, sc.channel.headers)
        if (con.responseCode !in 200..299) { con.disconnect(); ex.sendResponseHeaders(502, -1); ex.close(); return } // source refused: next source
        val fetched = Web.body(con)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val finalUrl = con.url.toString()
        // an HTML page with "200 OK", or a variant list whose variants all fail: tell the player now (it moves on to the
        // next source) instead of letting it time out; dead variants are dropped so it doesn't stop at the first one
        val text = fetched.takeIf { it.trimStart().startsWith("#EXTM3U") }
            ?.let { if (subUrlB64.isNullOrEmpty()) HlsProbe.dropDeadVariants(it, finalUrl, sc.channel.headers) else it }
            ?: run { ex.sendResponseHeaders(502, -1); ex.close(); return }

        val base = IptvServer.baseUrl(ex)
        val lines = text.lineSequence().map { line ->
            val sline = line.trim()
            when {
                sline.isEmpty() -> line
                sline.startsWith("#") -> {
                    if (sline.startsWith("#EXT-X-KEY")) {
                        URI_ATTR.replace(sline) { m ->
                            val resolved = URI(finalUrl).resolve(m.groupValues[1]).toString()
                            "URI=\"$base/segment?${relayParams(cid, resolved)}\""
                        }
                    } else line
                }
                else -> {
                    val resolved = URI(finalUrl).resolve(sline).toString()
                    val enc = Base64.getUrlEncoder().withoutPadding().encodeToString(resolved.toByteArray())
                    if (resolved.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) {
                        "$base/live/$cid.m3u8?sub=$enc&s=${signer.sign(cid, resolved)}"
                    } else {
                        "$base/segment?${relayParams(cid, resolved)}"
                    }
                }
            }
        }.joinToString("\n")

        val body = lines.toByteArray(Charsets.UTF_8)
        IptvServer.sendCors(ex)
        ex.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-cache, no-store")
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    fun serveSegment(ex: HttpExchange, uB64: String?, cid: String?, sig: String?) {
        if (uB64.isNullOrEmpty() || cid.isNullOrEmpty()) { ex.sendResponseHeaders(400, -1); ex.close(); return }
        val targetUrl = runCatching { String(Base64.getUrlDecoder().decode(uB64), Charsets.UTF_8) }.getOrNull()
            ?: run { ex.sendResponseHeaders(400, -1); ex.close(); return }
        // only URLs this server wrote into a playlist (see signer): not an open proxy for anyone on the LAN or any web page
        if (!signer.verify(cid, targetUrl, sig)) { ex.sendResponseHeaders(403, -1); ex.close(); return }

        val headers = IptvServer.channelById(cid)?.channel?.headers.orEmpty()
        val range = ex.requestHeaders.getFirst("Range")

        val con = open(targetUrl, headers, range)
        val code = con.responseCode
        val type = con.contentType ?: "video/mp2t"

        IptvServer.sendCors(ex)
        ex.responseHeaders.add("Content-Type", type)
        // decoded below: a compressed upstream's length no longer applies
        if (con.contentEncoding.isNullOrBlank()) con.getHeaderField("Content-Length")?.let { ex.responseHeaders.add("Content-Length", it) }
        con.getHeaderField("Content-Range")?.let { ex.responseHeaders.add("Content-Range", it) }
        con.getHeaderField("Accept-Ranges")?.let { ex.responseHeaders.add("Accept-Ranges", it) }

        ex.sendResponseHeaders(code, 0)
        ex.responseBody.use { out -> Web.body(con)!!.use { it.copyTo(out) } }
    }

    fun serveEpg(ex: HttpExchange) {
        val countries = Settings.viewer.countries
        val country = countries.joinToString(",")
        fun fresh() = epgCache?.takeIf { epgFor == country && System.currentTimeMillis() - epgAt < 6 * 3600_000L }
        // One download at a time: a burst of requests after the cache expires waits for it instead of each fetching the XML.
        // the country's first guide that answers (Indonesia: dhanytv, then epg.pw)
        val data = fresh() ?: synchronized(epgLock) {
            fresh() ?: countries.flatMap { Region.guides(it) }.distinct().firstNotNullOfOrNull { url ->
                runCatching {
                    val c = URI(url).toURL().openConnection() as HttpURLConnection
                    c.connectTimeout = 10000; c.readTimeout = 15000; c.setRequestProperty("User-Agent", "TahoeIPTV")
                    Web.body(c)!!.use { it.readBytes() }.also { epgCache = it; epgAt = System.currentTimeMillis(); epgFor = country }
                }.getOrNull()
            } ?: epgCache?.takeIf { epgFor == country } // a stale guide of the same country beats none
        }

        if (data == null) { ex.sendResponseHeaders(502, -1); ex.close(); return }
        IptvServer.sendCors(ex)
        ex.responseHeaders.add("Content-Type", "application/xml; charset=utf-8")
        ex.sendResponseHeaders(200, data.size.toLong())
        ex.responseBody.use { it.write(data) }
    }

    /** DVR. GET: every recording (with a /recordings/<id> URL once it has data), for any device on the LAN.
     *  POST ?cid=&start=&stop=&title= schedules (epoch ms; start/stop default to now / +1 h), POST ?cancel=<id> stops,
     *  DELETE ?id=<id> removes the file. Changes are accepted from this computer only: anyone on the Wi-Fi could
     *  otherwise fill its disk. Browsers are refused too (any request with an Origin header): a web page open on this
     *  computer could otherwise reach 127.0.0.1 and schedule or delete recordings. */
    fun serveRecordings(ex: HttpExchange, query: Map<String, String>) {
        Recordings.boot()
        when (ex.requestMethod.uppercase()) {
            "GET" -> IptvServer.sendJson(
                ex, 200,
                JSONObject().put("recordings", JSONArray(Recordings.jobs.toList().map { Recordings.json(it, IptvServer.baseUrl(ex)) }))
            )
            "POST", "DELETE" -> {
                if (!IptvServer.fromThisComputer(ex) || ex.requestHeaders.containsKey("Origin")) {
                    return IptvServer.sendJson(ex, 403, JSONObject().put("error", "rekaman hanya bisa diatur dari komputer ini"))
                }
                if (ex.requestMethod.equals("DELETE", true)) {
                    Recordings.delete(query["id"].orEmpty())
                    return IptvServer.sendJson(ex, 200, JSONObject().put("ok", true))
                }
                query["cancel"]?.let {
                    Recordings.cancel(it)
                    return IptvServer.sendJson(ex, 200, JSONObject().put("ok", true))
                }
                val sc = IptvServer.channelById(query["cid"].orEmpty())
                    ?: return IptvServer.sendJson(ex, 404, JSONObject().put("error", "saluran tidak ditemukan"))
                val now = System.currentTimeMillis()
                // a time that's there but unreadable is an error, not "now"
                val start = query["start"]?.let {
                    it.toLongOrNull() ?: return IptvServer.sendJson(ex, 400, JSONObject().put("error", "start bukan angka (epoch ms)"))
                } ?: now
                val stop = query["stop"]?.let {
                    it.toLongOrNull() ?: return IptvServer.sendJson(ex, 400, JSONObject().put("error", "stop bukan angka (epoch ms)"))
                }
                // exact arithmetic: absurd epoch values must not wrap around into a "valid" duration
                val length = runCatching { Math.subtractExact(stop ?: Math.addExact(start, 3600_000L), start) }.getOrNull()
                if (length == null || length < 60_000L || length > 6 * 3600_000L) {
                    return IptvServer.sendJson(ex, 400, JSONObject().put("error", "durasi 1 menit sampai 6 jam"))
                }
                val end = start + length
                val job = Recordings.schedule(sc.channel.name, query["title"]?.takeIf { it.isNotBlank() } ?: "Rekaman", listOf(sc.channel), start, end)
                    ?: return IptvServer.sendJson(ex, 400, JSONObject().put("error", "saluran ini (DRM/DASH) tidak bisa direkam"))
                IptvServer.sendJson(ex, 200, Recordings.json(job, IptvServer.baseUrl(ex)))
            }
            else -> { ex.sendResponseHeaders(405, -1); ex.close() }
        }
    }

    /** A recording's file, with Range support so players (TV, VLC, browser) can seek in it. */
    fun serveRecordingFile(ex: HttpExchange, id: String) {
        val f = Recordings.jobs.firstOrNull { it.id == id }?.let { File(it.file) }?.takeIf { it.isFile }
            ?: run { ex.sendResponseHeaders(404, -1); ex.close(); return }
        val len = f.length()
        val range = RANGE.find(ex.requestHeaders.getFirst("Range").orEmpty())
        var from = 0L
        var to = len - 1
        if (range != null) {
            val (a, b) = range.destructured
            // numbers too big for a Long: a start past the end (416), an end clamped to the file
            if (a.isNotEmpty()) { from = a.toLongOrNull() ?: Long.MAX_VALUE; if (b.isNotEmpty()) to = minOf(b.toLongOrNull() ?: Long.MAX_VALUE, len - 1) }
            else if (b.isNotEmpty()) from = maxOf(0L, len - (b.toLongOrNull() ?: Long.MAX_VALUE))
            if (from > to || from >= len) { ex.responseHeaders.add("Content-Range", "bytes */$len"); ex.sendResponseHeaders(416, -1); ex.close(); return }
        }
        IptvServer.sendCors(ex)
        ex.responseHeaders.add("Content-Type", if (f.name.endsWith(".mp4")) "video/mp4" else "video/mp2t")
        ex.responseHeaders.add("Accept-Ranges", "bytes")
        if (range != null) ex.responseHeaders.add("Content-Range", "bytes $from-$to/$len")
        val n = to - from + 1
        if (ex.requestMethod.equals("HEAD", true)) {
            ex.responseHeaders.set("Content-Length", "$n") // the size players need to seek; HEAD sends no body
            ex.sendResponseHeaders(if (range != null) 206 else 200, -1); ex.close(); return
        }
        ex.sendResponseHeaders(if (range != null) 206 else 200, n)
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(from)
            ex.responseBody.use { out ->
                val buf = ByteArray(64 * 1024)
                var left = n
                while (left > 0) {
                    val r = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (r < 0) break
                    out.write(buf, 0, r); left -= r
                }
            }
        }
    }

    private fun open(url: String, headers: Map<String, String>, range: String? = null): HttpURLConnection {
        var u = url
        repeat(5) {
            val c = URI(u).toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 18000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", Web.STREAM_UA)
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (!range.isNullOrEmpty()) c.setRequestProperty("Range", range)
            if (c.responseCode in 300..399) {
                u = URI(u).resolve(c.getHeaderField("Location")).toString()
                c.disconnect()
            } else return c
        }
        error("Too many redirects: $url")
    }
}
