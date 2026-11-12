package tech.kousozo.tahoetv.desktop

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import tech.kousozo.tahoetv.core.HlsProbe
import tech.kousozo.tahoetv.core.LocalTv
import tech.kousozo.tahoetv.core.M3u
import tech.kousozo.tahoetv.core.Sources

/** LAN IPTV Server & Streaming Relay:
 *  Aggregates local M3U playlists (koutv.m3u, anotheriptv.m3u, ~/.tahoetv/playlists),
 *  serves master playlist (/playlist.m3u) & EPG (/epg.xml), and acts as a header-injecting
 *  HLS reverse proxy (/live/{id}.m3u8) so any external player or TV can play channels
 *  requiring Referer, User-Agent, or Origin headers. */
object IptvServer {
    var isRunning = mutableStateOf(false); private set
    var port = mutableStateOf(8088); private set
    var channelCount = mutableStateOf(0); private set
    var relayCount = mutableStateOf(0); private set
    val loadedSources = mutableStateListOf<String>()

    data class ServerChannel(
        val id: String,
        val channel: M3u.Channel,
        val source: String,
        val drmType: String? = null,
        val drmKey: String? = null
    )

    private var server: HttpServer? = null
    // Replaced as a whole on reload (never changed in place): request handlers can read them while a reload runs.
    @Volatile private var channels: List<ServerChannel> = emptyList()
    @Volatile private var byId: Map<String, ServerChannel> = emptyMap()
    @Volatile private var idByUrl: Map<String, String> = emptyMap()

    /** The server's id for a stream URL (for the web player), or null if it isn't in the list. */
    fun idFor(url: String): String? = idByUrl[url]

    /** Playlist + country the list was loaded for: a change to either reloads it. */
    private fun loadKey() = Settings.playlist + "|" + Settings.countryCode

    /** The playlist setting the channel list was loaded for (null = custom files, e.g. tests). */
    @Volatile private var loadedFor: String? = null

    /** Starts the server if needed (on the configured port, or any free port if that one is taken), and reloads the
     *  channel list if the playlist setting changed since; returns the port. Blocks: call off the UI thread. */
    @Synchronized fun ensureRunning(): Int {
        if (!isRunning.value) runCatching { start(Settings.iptvPort) }.onFailure { runCatching { start(0) } }
        else if (loadedFor != null && loadedFor != loadKey()) loadPlaylists(emptyList())
        return port.value
    }
    private val executor = Executors.newFixedThreadPool(16)
    private val URI_ATTR = Regex("""URI="([^"]+)"""")

    // ------------------------------------------------------------------ health: is each channel really live?
    /** id → "live" | "replay" (finished playlist) | "stale" (old broadcast replayed) | "dead", refreshed in the background. */
    private val health = ConcurrentHashMap<String, String>()
    @Volatile private var healthAt = 0L
    /** One scan at a time, started atomically (two /api/health?refresh=1 at once must not start two scans). */
    private val healthRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Bumped when playlists reload: verdicts of an older scan are dropped instead of landing on reused ids. */
    private val healthGen = java.util.concurrent.atomic.AtomicInteger(0)

    private fun verdict(c: M3u.Channel): String {
        if (!c.url.contains(".m3u8", true)) return if (Sources.check(c, 4000)) "live" else "dead"
        if (LocalTv.isVod(c)) return if (Sources.check(c, 4000)) "live" else "dead"
        return when (HlsProbe.probe(c.url, c.headers, 4000)) {
            HlsProbe.Kind.REPLAY -> "replay"
            HlsProbe.Kind.STALE -> "stale"
            HlsProbe.Kind.LIVE -> "live"
            HlsProbe.Kind.DEAD -> "dead"
            HlsProbe.Kind.UNKNOWN -> if (Sources.check(c, 4000)) "live" else "dead"
        }
    }

    /** Checks every channel (16 at a time) in the background, at most once per 15 min unless [force]. */
    private fun refreshHealth(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - healthAt < 15 * 60_000L) return
        if (!healthRunning.compareAndSet(false, true)) return
        val gen = healthGen.get()
        Thread({
            val pool = Executors.newFixedThreadPool(16)
            try {
                channels.toList().forEach { sc -> pool.execute { val v = verdict(sc.channel); if (healthGen.get() == gen) health[sc.id] = v } }
                pool.shutdown()
                // Every probe has its own timeouts; this only guards against a hang. A scan that didn't finish isn't "done".
                val finished = pool.awaitTermination(10, java.util.concurrent.TimeUnit.MINUTES)
                if (!finished) pool.shutdownNow()
                if (finished && healthGen.get() == gen) healthAt = System.currentTimeMillis()
            } finally { healthRunning.set(false) }
        }, "tahoe-health").apply { isDaemon = true }.start()
    }

    /** `?live=1` drops channels known to be recordings or dead; unchecked ones stay. */
    private fun wanted(sc: ServerChannel, query: Map<String, String>) = query["live"] !in listOf("1", "true") || health[sc.id].let { it == null || it == "live" }

    fun lanIp(): String {
        try {
            for (ni in java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (a in java.util.Collections.list(ni.inetAddresses)) {
                    if (!a.isLoopbackAddress && a is java.net.Inet4Address) return a.hostAddress
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    fun webUrl(): String = "http://${lanIp()}:${port.value}/"
    fun masterPlaylistUrl(): String = "http://${lanIp()}:${port.value}/playlist.m3u"
    fun epgUrl(): String = "http://${lanIp()}:${port.value}/epg.xml"

    /** Blocks while the channel list loads: call off the UI thread. A second call while one runs waits, then does nothing. */
    @Synchronized fun start(bindPort: Int = 8088, customFiles: List<String> = emptyList()) {
        if (isRunning.value) return
        loadPlaylists(customFiles)

        val s = HttpServer.create(InetSocketAddress("0.0.0.0", bindPort), 0)
        s.executor = executor

        s.createContext("/") { ex -> handleRequest(ex) }
        s.start()

        server = s
        port.value = s.address.port
        isRunning.value = true
        refreshHealth()
        Recordings.boot() // scheduled recordings run while the server does
    }

    fun stop() { // not under the lock: a reload in progress must not freeze the Stop button
        server?.stop(0)
        server = null
        isRunning.value = false
    }

    private fun loadPlaylists(customFiles: List<String>) {
        healthGen.incrementAndGet(); health.clear(); healthAt = 0L // new channel list: old verdicts no longer apply
        val channels = ArrayList<ServerChannel>()
        val byId = HashMap<String, ServerChannel>()
        val idByUrl = HashMap<String, String>()
        loadedSources.clear()

        val filesToLoad = LinkedHashSet<File>()
        customFiles.forEach { filesToLoad.add(File(it)) }

        // Workspace files
        listOf("koutv.m3u", "anotheriptv.m3u").forEach { name ->
            val f = File(name)
            if (f.exists()) filesToLoad.add(f)
        }

        // ~/.tahoetv/playlists/
        val dir = File(System.getProperty("user.home"), ".tahoetv/playlists")
        if (dir.exists()) {
            dir.listFiles()?.filter { it.extension.lowercase() in listOf("m3u", "m3u8", "txt") }?.forEach {
                filesToLoad.add(it)
            }
        }

        val existingIds = HashSet<String>()
        var relays = 0

        // The same channels as the app's TV Live: local files, ~/.tahoetv/playlists, the user's playlists, and the
        // built-in free-TV sources when there's nothing local. Before, only local files were read, so for almost everyone
        // (no koutv.m3u) the server, the dashboard, the LAN playlist and the web player had 0 channels.
        val groups: List<Pair<String, List<M3u.Channel>>> =
            if (customFiles.isNotEmpty()) filesToLoad.filter { it.isFile }.map { f -> f.name to runCatching { M3u.load(f.absolutePath) }.getOrDefault(emptyList()) }
            else loadKey().let { key -> // taken before the slow load: a change meanwhile still triggers the next reload
                listOf("Tahoe" to runCatching { Sources.loadFor(Settings.playlist, Settings.countryCode) }.getOrDefault(emptyList())).also { loadedFor = key } }
        for ((sourceName, list) in groups) {
            if (list.isEmpty()) continue

            loadedSources.add(sourceName)
            for (ch in list) {
                val clean = ch.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "ch" }
                var id = clean
                var idx = 1
                while (id in existingIds) {
                    idx++
                    id = "$clean-$idx"
                }
                existingIds.add(id)

                val sc = ServerChannel(id, ch, sourceName, ch.drmType, ch.drmKey)
                channels.add(sc)
                byId[id] = sc
                idByUrl.putIfAbsent(ch.url, id)
                if (ch.headers.isNotEmpty()) relays++
            }
        }

        this.channels = channels; this.byId = byId; this.idByUrl = idByUrl
        channelCount.value = channels.size
        relayCount.value = relays
    }

    private fun handleRequest(ex: HttpExchange) {
        val path = ex.requestURI.path
        val query = ex.requestURI.rawQuery.orEmpty().split('&').filter { it.contains('=') }
            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

        if (ex.requestMethod.equals("OPTIONS", ignoreCase = true)) {
            sendCors(ex)
            ex.sendResponseHeaders(204, -1)
            ex.close()
            return
        }

        try {
            when {
                path == "/" || path == "/index.html" -> serveDashboard(ex)
                path == "/watch" -> serveWatch(ex, query)
                path == "/api/watch" -> serveWatchApi(ex, query)
                path == "/playlist.m3u" || path == "/all.m3u" -> servePlaylist(ex, query)
                path.endsWith(".m3u") && loadedSources.any { path == "/$it" } -> servePlaylist(ex, query, path.removePrefix("/"))
                path == "/epg.xml" -> serveEpg(ex)
                path.startsWith("/live/") && (path.endsWith(".m3u8") || path.endsWith(".mpd")) -> {
                    if (path.endsWith(".mpd")) {
                        val cid = path.removePrefix("/live/").removeSuffix(".mpd")
                        serveLiveMpd(ex, cid)
                    } else {
                        val cid = path.removePrefix("/live/").removeSuffix(".m3u8")
                        serveLive(ex, cid, query["sub"], query["s"])
                    }
                }
                path == "/segment" -> serveSegment(ex, query["u"], query["cid"], query["s"])
                path == "/api/status" -> serveStatus(ex)
                path == "/api/channels" -> serveChannels(ex, query)
                path == "/api/health" -> serveHealth(ex, query)
                path == "/api/recordings" -> serveRecordings(ex, query)
                path.startsWith("/recordings/") -> serveRecordingFile(ex, path.removePrefix("/recordings/"))
                else -> {
                    ex.sendResponseHeaders(404, -1)
                    ex.close()
                }
            }
        } catch (e: Exception) {
            runCatching { ex.sendResponseHeaders(500, -1) }
            ex.close()
        }
    }

    private fun sendCors(ex: HttpExchange) {
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        ex.responseHeaders.add("Access-Control-Allow-Methods", "GET, HEAD, POST, DELETE, OPTIONS")
        ex.responseHeaders.add("Access-Control-Allow-Headers", "Range, User-Agent, Referer, Accept, Content-Type")
        ex.responseHeaders.add("Access-Control-Expose-Headers", "Content-Range, Content-Length, Accept-Ranges")
    }

    private fun baseUrl(ex: HttpExchange): String {
        val h = ex.requestHeaders.getFirst("Host")
        return if (!h.isNullOrBlank()) "http://$h" else "http://${lanIp()}:${port.value}"
    }

    private fun servePlaylist(ex: HttpExchange, query: Map<String, String>, sourceFilter: String? = null) {
        val base = baseUrl(ex)
        val group = query["group"]
        val raw = query["raw"] in listOf("1", "true")

        val sb = StringBuilder()
        sb.append("#EXTM3U url-tvg=\"$base/epg.xml\"\n")

        for (sc in channels) {
            if (sourceFilter != null && sc.source != sourceFilter) continue
            if (group != null && !sc.channel.group.equals(group, ignoreCase = true)) continue
            if (!wanted(sc, query)) continue

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
        sendCors(ex)
        ex.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl; charset=utf-8")
        ex.responseHeaders.add("Content-Disposition", "inline; filename=\"playlist.m3u\"")
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    private fun serveLiveMpd(ex: HttpExchange, cid: String) {
        val sc = byId[cid] ?: run { ex.sendResponseHeaders(404, -1); ex.close(); return }
        val con = open(sc.channel.url, sc.channel.headers)
        val body = tech.kousozo.tahoetv.core.Web.body(con)!!.use { it.readBytes() }
        sendCors(ex)
        ex.responseHeaders.add("Content-Type", "application/dash+xml")
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    /** Signs the URLs this server writes into playlists; [serveSegment] and variant playlists only fetch signed ones,
     *  so the relay can't be pointed at arbitrary addresses (router pages, 127.0.0.1 services…). */
    private val signer = tech.kousozo.tahoetv.core.UrlSigner()
    private fun relayParams(cid: String, url: String) =
        "cid=" + URLEncoder.encode(cid, "UTF-8") + "&u=" + Base64.getUrlEncoder().withoutPadding().encodeToString(url.toByteArray()) + "&s=" + signer.sign(cid, url)

    private fun serveLive(ex: HttpExchange, cid: String, subUrlB64: String?, sig: String?) {
        val sc = byId[cid] ?: run { ex.sendResponseHeaders(404, -1); ex.close(); return }
        var targetUrl = sc.channel.url
        if (!subUrlB64.isNullOrEmpty()) {
            val sub = runCatching { String(Base64.getUrlDecoder().decode(subUrlB64), Charsets.UTF_8) }.getOrNull()
            if (sub == null || !signer.verify(cid, sub, sig)) { ex.sendResponseHeaders(403, -1); ex.close(); return }
            targetUrl = sub
        }

        val con = open(targetUrl, sc.channel.headers)
        if (con.responseCode !in 200..299) { con.disconnect(); ex.sendResponseHeaders(502, -1); ex.close(); return } // source refused: next source
        val fetched = tech.kousozo.tahoetv.core.Web.body(con)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val finalUrl = con.url.toString()
        // an HTML page with "200 OK", or a variant list whose variants all fail: tell the player now (it moves on to the
        // next source) instead of letting it time out; dead variants are dropped so it doesn't stop at the first one
        val text = fetched.takeIf { it.trimStart().startsWith("#EXTM3U") }
            ?.let { if (subUrlB64.isNullOrEmpty()) tech.kousozo.tahoetv.core.HlsProbe.dropDeadVariants(it, finalUrl, sc.channel.headers) else it }
            ?: run { ex.sendResponseHeaders(502, -1); ex.close(); return }

        val base = baseUrl(ex)
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
        sendCors(ex)
        ex.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-cache, no-store")
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    private fun serveSegment(ex: HttpExchange, uB64: String?, cid: String?, sig: String?) {
        if (uB64.isNullOrEmpty() || cid.isNullOrEmpty()) { ex.sendResponseHeaders(400, -1); ex.close(); return }
        val targetUrl = runCatching { String(Base64.getUrlDecoder().decode(uB64), Charsets.UTF_8) }.getOrNull()
            ?: run { ex.sendResponseHeaders(400, -1); ex.close(); return }
        // only URLs this server wrote into a playlist (see signer): not an open proxy for anyone on the LAN or any web page
        if (!signer.verify(cid, targetUrl, sig)) { ex.sendResponseHeaders(403, -1); ex.close(); return }

        val headers = cid?.let { byId[it]?.channel?.headers }.orEmpty()
        val range = ex.requestHeaders.getFirst("Range")

        val con = open(targetUrl, headers, range)
        val code = con.responseCode
        val type = con.contentType ?: "video/mp2t"

        sendCors(ex)
        ex.responseHeaders.add("Content-Type", type)
        // decoded below: a compressed upstream's length no longer applies
        if (con.contentEncoding.isNullOrBlank()) con.getHeaderField("Content-Length")?.let { ex.responseHeaders.add("Content-Length", it) }
        con.getHeaderField("Content-Range")?.let { ex.responseHeaders.add("Content-Range", it) }
        con.getHeaderField("Accept-Ranges")?.let { ex.responseHeaders.add("Accept-Ranges", it) }

        ex.sendResponseHeaders(code, 0)
        ex.responseBody.use { out -> tech.kousozo.tahoetv.core.Web.body(con)!!.use { it.copyTo(out) } }
    }

    /** Guide XML kept in memory for 6 h: every TV/phone/player asking used to download the whole file again. */
    @Volatile private var epgCache: ByteArray? = null
    @Volatile private var epgFor = "" // the country the cached guide is for
    @Volatile private var epgAt = 0L
    private val epgLock = Any()

    private fun serveEpg(ex: HttpExchange) {
        val country = Settings.countryCode
        fun fresh() = epgCache?.takeIf { epgFor == country && System.currentTimeMillis() - epgAt < 6 * 3600_000L }
        // One download at a time: a burst of requests after the cache expires waits for it instead of each fetching the XML.
        // the country's first guide that answers (Indonesia: dhanytv, then epg.pw)
        val data = fresh() ?: synchronized(epgLock) { fresh() ?: tech.kousozo.tahoetv.core.Region.guides(country).firstNotNullOfOrNull { url -> runCatching {
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 15000; c.setRequestProperty("User-Agent", "TahoeIPTV")
            tech.kousozo.tahoetv.core.Web.body(c)!!.use { it.readBytes() }.also { epgCache = it; epgAt = System.currentTimeMillis(); epgFor = country }
        }.getOrNull() } ?: epgCache?.takeIf { epgFor == country } } // a stale guide of the same country beats none

        if (data == null) { ex.sendResponseHeaders(502, -1); ex.close(); return }
        sendCors(ex)
        ex.responseHeaders.add("Content-Type", "application/xml; charset=utf-8")
        ex.sendResponseHeaders(200, data.size.toLong())
        ex.responseBody.use { it.write(data) }
    }

    private fun sendJson(ex: HttpExchange, code: Int, j: Any) {
        val body = j.toString().toByteArray(Charsets.UTF_8)
        sendCors(ex)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    /** DVR. GET: every recording (with a /recordings/<id> URL once it has data), for any device on the LAN.
     *  POST ?cid=&start=&stop=&title= schedules (epoch ms; start/stop default to now / +1 h), POST ?cancel=<id> stops,
     *  DELETE ?id=<id> removes the file. Changes are accepted from this computer only: anyone on the Wi-Fi could
     *  otherwise fill its disk. Browsers are refused too (any request with an Origin header): a web page open on this
     *  computer could otherwise reach 127.0.0.1 and schedule or delete recordings. */
    private fun serveRecordings(ex: HttpExchange, query: Map<String, String>) {
        Recordings.boot()
        when (ex.requestMethod.uppercase()) {
            "GET" -> sendJson(ex, 200, JSONObject()
                .put("recordings", JSONArray(Recordings.jobs.toList().map { Recordings.json(it, baseUrl(ex)) })))
            "POST", "DELETE" -> {
                if (!fromThisComputer(ex) || ex.requestHeaders.containsKey("Origin"))
                    return sendJson(ex, 403, JSONObject().put("error", "rekaman hanya bisa diatur dari komputer ini"))
                if (ex.requestMethod.equals("DELETE", true)) { Recordings.delete(query["id"].orEmpty()); return sendJson(ex, 200, JSONObject().put("ok", true)) }
                query["cancel"]?.let { Recordings.cancel(it); return sendJson(ex, 200, JSONObject().put("ok", true)) }
                val sc = byId[query["cid"].orEmpty()] ?: return sendJson(ex, 404, JSONObject().put("error", "saluran tidak ditemukan"))
                val now = System.currentTimeMillis()
                // a time that's there but unreadable is an error, not "now"
                val start = query["start"]?.let { it.toLongOrNull() ?: return sendJson(ex, 400, JSONObject().put("error", "start bukan angka (epoch ms)")) } ?: now
                val stop = query["stop"]?.let { it.toLongOrNull() ?: return sendJson(ex, 400, JSONObject().put("error", "stop bukan angka (epoch ms)")) }
                // exact arithmetic: absurd epoch values must not wrap around into a "valid" duration
                val length = runCatching { Math.subtractExact(stop ?: Math.addExact(start, 3600_000L), start) }.getOrNull()
                if (length == null || length < 60_000L || length > 6 * 3600_000L) return sendJson(ex, 400, JSONObject().put("error", "durasi 1 menit sampai 6 jam"))
                val end = start + length
                val job = Recordings.schedule(sc.channel.name, query["title"]?.takeIf { it.isNotBlank() } ?: "Rekaman", listOf(sc.channel), start, end)
                    ?: return sendJson(ex, 400, JSONObject().put("error", "saluran ini (DRM/DASH) tidak bisa direkam"))
                sendJson(ex, 200, Recordings.json(job, baseUrl(ex)))
            }
            else -> { ex.sendResponseHeaders(405, -1); ex.close() }
        }
    }

    /** Loopback, or one of this computer's own addresses (a request to its LAN IP from itself). */
    private fun fromThisComputer(ex: HttpExchange): Boolean {
        val a = ex.remoteAddress.address
        return a.isLoopbackAddress || runCatching { java.net.NetworkInterface.getByInetAddress(a) != null }.getOrDefault(false)
    }

    private val RANGE = Regex("""bytes=(\d*)-(\d*)""")

    /** A recording's file, with Range support so players (TV, VLC, browser) can seek in it. */
    private fun serveRecordingFile(ex: HttpExchange, id: String) {
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
        sendCors(ex)
        ex.responseHeaders.add("Content-Type", if (f.name.endsWith(".mp4")) "video/mp4" else "video/mp2t")
        ex.responseHeaders.add("Accept-Ranges", "bytes")
        if (range != null) ex.responseHeaders.add("Content-Range", "bytes $from-$to/$len")
        val n = to - from + 1
        if (ex.requestMethod.equals("HEAD", true)) {
            ex.responseHeaders.set("Content-Length", "$n") // the size players need to seek; HEAD sends no body
            ex.sendResponseHeaders(if (range != null) 206 else 200, -1); ex.close(); return
        }
        ex.sendResponseHeaders(if (range != null) 206 else 200, n)
        java.io.RandomAccessFile(f, "r").use { raf ->
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

    /** What the player can't tell from a plain "200 OK": which channels are live, which are recordings, which are down.
     *  `?refresh=1` re-checks now. Checking runs in the background; call again in a minute for complete numbers. */
    private fun serveHealth(ex: HttpExchange, query: Map<String, String>) {
        refreshHealth(force = query["refresh"] in listOf("1", "true"))
        val counts = health.values.groupingBy { it }.eachCount()
        val j = JSONObject().apply {
            put("checking", healthRunning.get()); put("checked_at", healthAt); put("total", channels.size); put("checked", health.size)
            put("live", counts["live"] ?: 0); put("replay", counts["replay"] ?: 0); put("stale", counts["stale"] ?: 0); put("dead", counts["dead"] ?: 0)
            put("channels", JSONObject(health.toMap()))
        }
        val body = j.toString(2).toByteArray(Charsets.UTF_8)
        sendCors(ex)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    private fun serveStatus(ex: HttpExchange) {
        val base = baseUrl(ex)
        val j = JSONObject().apply {
            put("status", "online")
            put("port", port.value)
            put("lan_ip", lanIp())
            put("base_url", base)
            put("master_playlist", "$base/playlist.m3u")
            put("epg_url", "$base/epg.xml")
            put("sources", JSONArray(loadedSources))
            put("total_channels", channelCount.value)
            put("relay_channels", relayCount.value)
        }
        val body = j.toString(2).toByteArray(Charsets.UTF_8)
        sendCors(ex)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    private fun serveChannels(ex: HttpExchange, query: Map<String, String>) {
        val base = baseUrl(ex)
        val grp = query["group"]
        val filtered = channels.filter { (grp == null || it.channel.group.equals(grp, ignoreCase = true)) && wanted(it, query) }
        val arr = JSONArray()
        for (sc in filtered) {
            val isMpd = sc.channel.url.contains(".mpd", ignoreCase = true)
            val ext = if (isMpd) ".mpd" else ".m3u8"
            val isVod = sc.channel.group?.contains("vod", ignoreCase = true) == true ||
                    sc.channel.url.endsWith(".mp4", ignoreCase = true) ||
                    sc.channel.url.endsWith(".mkv", ignoreCase = true)
            arr.put(JSONObject().apply {
                put("id", sc.id)
                put("name", sc.channel.name)
                put("stream_url", "$base/live/${sc.id}$ext")
                put("raw_url", sc.channel.url)
                put("is_mpd", isMpd)
                put("is_vod", isVod)
                put("drm_type", sc.drmType)
                put("drm_key", sc.drmKey)
                put("logo", sc.channel.logo)
                put("group", sc.channel.group)
                put("tvg_id", sc.channel.tvgId)
                put("has_headers", sc.channel.headers.isNotEmpty())
                put("source", sc.source)
                put("health", health[sc.id] ?: "unknown")
            })
        }
        val j = JSONObject().put("count", filtered.size).put("channels", arr)
        val body = j.toString(2).toByteArray(Charsets.UTF_8)
        sendCors(ex)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    /** The web player's session with the app (see [WebPlayer]): GET = what to play now; POST = the page's reports
     *  (event=playing|failed|closed for session s, or zap=±1). This computer only, and only from this server's own page
     *  (or no Origin): another site in the browser can't drive it. */
    private fun serveWatchApi(ex: HttpExchange, query: Map<String, String>) {
        val origin = ex.requestHeaders.getFirst("Origin")
        val ownPage = origin == null || origin == "http://127.0.0.1:${port.value}" || origin == "http://localhost:${port.value}"
        if (!fromThisComputer(ex) || !ownPage) return sendJson(ex, 403, JSONObject().put("error", "forbidden"))
        if (ex.requestMethod.equals("POST", true)) {
            query["zap"]?.toIntOrNull()?.let { WebPlayer.zap(query["s"]?.toIntOrNull() ?: -1, it.coerceIn(-1, 1)) }
            query["event"]?.let { WebPlayer.report(query["s"]?.toIntOrNull() ?: -1, it, query["msg"]) }
            return sendJson(ex, 200, JSONObject().put("ok", true))
        }
        sendJson(ex, 200, WebPlayer.state())
    }

    /** One channel, full window, for the web player (Windows, where JavaFX can't play HLS): hls.js plays the server's
     *  relay of each source in turn, moving on when one fails or doesn't start in 20 s; follows the app's session. */
    private fun serveWatch(ex: HttpExchange, query: Map<String, String>) {
        val ids = query["ids"].orEmpty().split(",").map { it.trim() }.filter { byId.containsKey(it) }
        val title = query["t"].orEmpty().ifBlank { ids.firstOrNull()?.let { byId[it]?.channel?.name } ?: "Tahoe TV" }
        val html = WATCH_HTML.replace("__TITLE__", esc(title)).replace("__IDS__", esc(JSONArray(ids).toString()))
            .replace("__SEQ__", (query["s"]?.toIntOrNull() ?: -1).toString())
        val body = html.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    private val WATCH_HTML = """<!DOCTYPE html>
<html lang="id"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>__TITLE__ — Tahoe TV</title>
<script src="https://cdn.jsdelivr.net/npm/hls.js@1.5.17/dist/hls.min.js"></script>
<style>
  html,body{margin:0;height:100%;background:#000;color:#f5f5f7;font-family:"Segoe UI",-apple-system,system-ui,sans-serif;overflow:hidden}
  video{position:fixed;inset:0;width:100%;height:100%;background:#000}
  #top{position:fixed;left:0;right:0;top:0;padding:14px 18px;display:flex;align-items:center;gap:10px;background:linear-gradient(#000b,#0000);transition:opacity .4s;z-index:2}
  .live{background:#ff453a;color:#fff;font-weight:700;font-size:12px;padding:2px 7px;border-radius:5px}
  #name{font-weight:600;font-size:17px}
  #fmt{margin-left:auto;font:12px ui-monospace,Consolas,monospace;opacity:.75}
  .btn{background:#ffffff26;color:#fff;border:0;border-radius:99px;padding:7px 13px;font-size:14px;cursor:pointer}
  .btn:hover{background:#ffffff40}
  #veil{position:fixed;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:16px;background:#07080dcc;transition:opacity .3s;z-index:1;pointer-events:none}
  #veil.hide{opacity:0}
  .spin{width:34px;height:34px;border:3px solid #fff3;border-top-color:#fff;border-radius:50%;animation:s 1s linear infinite}
  @keyframes s{to{transform:rotate(360deg)}}
  #msg{max-width:520px;text-align:center;font-size:15px;line-height:1.5;opacity:.9}
  #sound{position:fixed;bottom:70px;left:50%;transform:translateX(-50%);display:none;background:#f5f5f7;color:#111;border:0;border-radius:99px;padding:10px 18px;font-weight:600;cursor:pointer;z-index:3}
  .idle #top{opacity:0}
</style></head><body>
<video id="v" playsinline controls></video>
<div id="top"><span class="live">LIVE</span><span id="name">__TITLE__</span>
  <button class="btn" id="prev" title="Saluran sebelumnya (↑)">◀</button><button class="btn" id="next" title="Saluran berikutnya (↓)">▶</button>
  <span id="fmt"></span></div>
<div id="veil"><div class="spin" id="spin"></div><div id="msg">Memuat __TITLE__…</div></div>
<button id="sound">🔊 Klik untuk menyalakan suara</button>
<script>
const v = document.getElementById('v'), veil = document.getElementById('veil'), msg = document.getElementById('msg'), spin = document.getElementById('spin');
let ids = JSON.parse("__IDS__".replace(/&quot;/g,'"')), seq = __SEQ__, maxH = 0, lat = 20, i = 0, hls = null, timer = null, reported = false, session = false;
function post(q){ return fetch('/api/watch?' + q, { method: 'POST' }).catch(() => {}); }
function fail(t){ spin.style.display='none'; msg.textContent=t; veil.classList.remove('hide'); post('event=failed&s=' + seq + '&msg=' + encodeURIComponent(t)); }
function next(){ i++; start(); }
function tryPlay(){
  v.play().catch(() => { v.muted = true; v.play().catch(()=>{}); document.getElementById('sound').style.display='block'; });
}
function start(){
  clearTimeout(timer); reported = false;
  if (hls) { hls.destroy(); hls = null; }
  if (i >= ids.length) { fail('Saluran ini sedang tidak bisa diputar. Coba saluran lain (◀ ▶).'); return; }
  spin.style.display=''; msg.textContent='Memuat…'; veil.classList.remove('hide');
  const src = '/live/' + encodeURIComponent(ids[i]) + '.m3u8';
  if (window.Hls && Hls.isSupported()) {
    hls = new Hls({ liveSyncDuration: lat, liveMaxLatencyDuration: lat * 3, enableWorker: true });
    hls.on(Hls.Events.ERROR, (e, d) => { if (d.fatal) { if (d.type === Hls.ErrorTypes.MEDIA_ERROR) hls.recoverMediaError(); else next(); } });
    // start playing once the manifest is in (calling play() earlier can be rejected before there's a source)
    hls.on(Hls.Events.MANIFEST_PARSED, () => {
      if (maxH) {
        let cap = -1, smallest = 0;
        hls.levels.forEach((l, k) => { if (l.height && l.height <= maxH && (cap < 0 || l.height >= hls.levels[cap].height)) cap = k;
                                       if ((l.height || 1e9) < (hls.levels[smallest].height || 1e9)) smallest = k; });
        hls.autoLevelCapping = cap >= 0 ? cap : smallest;
      }
      tryPlay();
    });
    hls.on(Hls.Events.LEVEL_SWITCHED, (e, d) => { const l = hls.levels[d.level]; if (l) document.getElementById('fmt').textContent = (l.height ? l.height + 'p · ' : '') + (l.videoCodec || '').split('.')[0].replace('avc1','H.264').replace('hvc1','H.265'); });
    hls.loadSource(src); hls.attachMedia(v);
  } else { v.src = src; v.addEventListener('loadedmetadata', tryPlay, { once: true }); }
  timer = setTimeout(() => { if (v.readyState < 3) next(); }, 20000);
}
function load(s){ session = true; seq = s.seq; ids = s.ids; maxH = s.q || 0; lat = Math.min(60, Math.max(4, s.lat || 20)); i = 0;
  document.getElementById('name').textContent = s.title; document.title = s.title + ' — Tahoe TV'; start(); }
// follow the app: a new channel replaces this one; Stop stops and closes the window
async function poll(){
  try {
    const s = await (await fetch('/api/watch', { cache: 'no-store' })).json();
    if (s.stopped && session) { if (hls) { hls.destroy(); hls = null; } v.pause(); v.removeAttribute('src'); fail('Pemutar dihentikan.'); window.close(); }
    else if (!s.stopped && s.seq !== seq && s.ids.length) load(s);
  } catch (e) {}
}
v.addEventListener('playing', () => { clearTimeout(timer); veil.classList.add('hide'); if (!reported) { reported = true; post('event=playing&s=' + seq); } });
v.addEventListener('error', () => { if (!hls) next(); });
document.getElementById('sound').onclick = (e) => { v.muted = false; e.target.style.display='none'; };
document.getElementById('prev').onclick = () => post('zap=-1&s=' + seq);
document.getElementById('next').onclick = () => post('zap=1&s=' + seq);
let idle; document.addEventListener('mousemove', () => { document.body.classList.remove('idle'); clearTimeout(idle); idle = setTimeout(()=>document.body.classList.add('idle'), 3000); });
document.addEventListener('keydown', (e) => {
  if (e.key === ' ') { v.paused ? v.play() : v.pause(); e.preventDefault(); }
  if (e.key === 'f' || e.key === 'F') { document.fullscreenElement ? document.exitFullscreen() : document.documentElement.requestFullscreen(); }
  if (e.key === 'm' || e.key === 'M') { v.muted = !v.muted; }
  if (e.key === 'ArrowUp' || e.key === 'PageUp') { post('zap=-1&s=' + seq); e.preventDefault(); }
  if (e.key === 'ArrowDown' || e.key === 'PageDown') { post('zap=1&s=' + seq); e.preventDefault(); }
});
window.addEventListener('pagehide', () => navigator.sendBeacon('/api/watch?event=closed&s=' + seq));
// opened by the app (?s=…) but already stopped: don't start playing; opened by hand: play the URL's channel
const fromApp = new URLSearchParams(location.search).has('s');
fetch('/api/watch', { cache: 'no-store' }).then(r => r.json()).then(s => {
  if (s.ids && s.ids.length && !s.stopped) load(s);
  else if (fromApp && s.stopped) { spin.style.display = 'none'; msg.textContent = 'Pemutar dihentikan.'; window.close(); }
  else if (ids.length) start(); else fail('Tidak ada saluran yang dipilih.'); })
  .catch(() => { if (ids.length) start(); });
setInterval(poll, 1500);
</script></body></html>"""

    /** Text from playlists (third-party files) goes into this page: escaped, never trusted as HTML. */
    internal fun esc(s: String?): String = s.orEmpty().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    /** Only http(s) logos: no javascript:/data: URLs in an <img src>. */
    internal fun safeUrl(u: String?): String? = u?.trim()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }

    private fun serveDashboard(ex: HttpExchange) {
        val base = baseUrl(ex)
        val groups = channels.mapNotNull { it.channel.group }.distinct().sorted()
        val total = channels.size
        val relays = relayCount.value

        val channelCardsHtml = channels.joinToString("\n") { sc ->
            val isMpd = sc.channel.url.contains(".mpd", ignoreCase = true)
            val ext = if (isMpd) ".mpd" else ".m3u8"
            val streamUrl = "$base/live/${sc.id}$ext"
            val isVod = sc.channel.group?.contains("vod", ignoreCase = true) == true ||
                    sc.channel.url.endsWith(".mp4", ignoreCase = true) ||
                    sc.channel.url.endsWith(".mkv", ignoreCase = true)
            val logo = safeUrl(sc.channel.logo)?.let { "<img src=\"${esc(it)}\" onerror=\"this.style.display='none'; this.nextElementSibling.style.display='block';\">" }.orEmpty()
            val initial = esc((sc.channel.name.firstOrNull() ?: '?').uppercaseChar().toString())
            val placeholderStyle = if (sc.channel.logo.isNullOrBlank()) "" else "style=\"display:none;\""
            val relayBadge = if (sc.channel.headers.isNotEmpty()) "<span class=\"tag relay\">Relay</span>" else ""
            val dashBadge = if (isMpd) "<span class=\"tag dash\">DASH</span>" else ""
            val drmBadge = if (sc.channel.drm) "<span class=\"tag drm\">DRM</span>" else ""
            val vodBadge = if (isVod) "<span class=\"tag vod\">VOD</span>" else ""

            val infoJson = JSONObject().apply {
                put("id", sc.id)
                put("name", sc.channel.name)
                put("stream_url", streamUrl)
                put("group", sc.channel.group.orEmpty())
                put("is_mpd", isMpd)
                put("is_vod", isVod)
                put("drm_type", sc.drmType)
                put("drm_key", sc.drmKey)
                put("has_headers", sc.channel.headers.isNotEmpty())
            }.toString().let(::esc)

            """<div class="card" data-id="${esc(sc.id)}" data-name="${esc(sc.channel.name.lowercase())}" data-group="${esc(sc.channel.group)}" data-info="$infoJson" onclick="playCard(this)">
  <div class="logo">
    $logo
    <div class="placeholder" $placeholderStyle>$initial</div>
  </div>
  <div style="min-width: 0; flex: 1;">
    <div class="name">${esc(sc.channel.name)}</div>
    <div class="sub">
      <span>${esc(sc.channel.group)}</span>
      $relayBadge $dashBadge $drmBadge $vodBadge
    </div>
  </div>
</div>"""
        }

        val groupPillsHtml = groups.joinToString("\n") { g ->
            val count = channels.count { it.channel.group == g }
            """<button class="pill" data-group="${esc(g)}" onclick="selectGroup(this.dataset.group, this)">${esc(g)} ($count)</button>"""
        }

        val html = """
<!DOCTYPE html>
<html lang="id">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Tahoe IPTV Server</title>
<script src="https://cdnjs.cloudflare.com/ajax/libs/shaka-player/4.7.11/shaka-player.compiled.js"></script>
<style>
  :root { --bg: #07080d; --surface: rgba(255,255,255,0.06); --hover: rgba(255,255,255,0.12); --border: rgba(255,255,255,0.1); --text: #f5f5f7; --dim: #86868b; --accent: #0a84ff; }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { background: var(--bg); color: var(--text); font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", sans-serif; padding: 24px; }
  header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid var(--border); padding-bottom: 16px; margin-bottom: 16px; flex-wrap: wrap; gap: 12px; }
  h1 { font-size: 24px; font-weight: 700; display: flex; align-items: center; gap: 10px; }
  .badge { background: #30d158; color: #000; font-size: 11px; font-weight: 700; padding: 2px 7px; border-radius: 5px; }
  .btn { background: var(--surface); color: var(--text); border: 1px solid var(--border); border-radius: 8px; padding: 8px 14px; font-size: 13px; cursor: pointer; text-decoration: none; display: inline-flex; align-items: center; gap: 6px; }
  .btn:hover { background: var(--hover); }
  .btn.primary { background: var(--accent); color: white; border-color: var(--accent); }
  .layout { display: grid; grid-template-columns: 1fr 440px; gap: 20px; }
  @media(max-width: 900px) { .layout { grid-template-columns: 1fr; } }
  .player-box { background: #000; border: 1px solid var(--border); border-radius: 14px; overflow: hidden; position: sticky; top: 20px; }
  video { width: 100%; aspect-ratio: 16/9; background: #020204; }
  .p-info { padding: 16px; }
  .pills { display: flex; gap: 6px; overflow-x: auto; padding-bottom: 8px; margin-bottom: 12px; }
  .pill { background: var(--surface); border: 1px solid var(--border); color: var(--dim); padding: 5px 12px; border-radius: 20px; font-size: 12px; cursor: pointer; white-space: nowrap; }
  .pill.active { background: var(--accent); color: white; border-color: var(--accent); font-weight: 600; }
  .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 10px; max-height: 72vh; overflow-y: auto; }
  .card { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 10px 14px; display: flex; align-items: center; gap: 12px; cursor: pointer; }
  .card:hover { background: var(--hover); }
  .card.active { border-color: var(--accent); background: rgba(10,132,255,0.18); }
  .logo { width: 40px; height: 40px; border-radius: 8px; background: #1c1c1e; display: flex; align-items: center; justify-content: center; overflow: hidden; flex-shrink: 0; }
  .logo img { width: 100%; height: 100%; object-fit: contain; }
  .placeholder { font-weight: bold; color: var(--dim); font-size: 14px; }
  .name { font-size: 14px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .sub { font-size: 11px; color: var(--dim); margin-top: 4px; display: flex; gap: 4px; align-items: center; flex-wrap: wrap; }
  .tag { font-size: 9px; font-weight: 700; padding: 1px 5px; border-radius: 3px; }
  .tag.relay { background: rgba(48,209,88,0.2); color: #30d158; }
  .tag.dash { background: rgba(191,90,242,0.2); color: #bf5af2; }
  .tag.drm { background: rgba(255,159,10,0.2); color: #ff9f0a; }
  .tag.vod { background: rgba(255,45,85,0.2); color: #ff2d55; }
  .search { width: 100%; background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 10px 14px; color: white; margin-bottom: 10px; font-size: 14px; }
  .toast { position: fixed; bottom: 20px; right: 20px; background: #222; border: 1px solid var(--border); color: white; padding: 10px 16px; border-radius: 8px; opacity: 0; transition: opacity 0.2s; z-index: 999; }
  .toast.show { opacity: 1; }
</style>
</head>
<body>
<header>
  <div>
    <h1>Tahoe IPTV Server <span class="badge">ONLINE</span></h1>
    <div style="font-size: 13px; color: var(--dim); margin-top: 4px;">$total saluran · $relays relay header · Sumber: ${esc(loadedSources.joinToString(", "))}</div>
  </div>
  <div style="display: flex; gap: 8px; flex-wrap: wrap;">
    <button class="btn primary" onclick="copy('$base/playlist.m3u', 'Playlist Master disalin!')">📋 Salin M3U Master</button>
    <a href="$base/playlist.m3u" class="btn" target="_blank">⬇️ Download</a>
    <button class="btn" onclick="copy('$base/epg.xml', 'EPG disalin!')">📅 Salin EPG</button>
  </div>
</header>
<div class="layout">
  <div>
    <input type="text" id="q" class="search" placeholder="Cari saluran (contoh: RCTI, Trans TV, beIN, Sports, HBO, Movies, VOD)..." oninput="filter()">
    <div class="pills" id="pills">
      <button class="pill active" onclick="selectGroup(null, this)">Semua ($total)</button>
      $groupPillsHtml
    </div>
    <div class="grid" id="grid">
      $channelCardsHtml
    </div>
  </div>
  <div class="player-box">
    <video id="v" controls autoplay playsinline></video>
    <div class="p-info">
      <h3 id="pName">Pilih saluran untuk menonton</h3>
      <p id="pSub" style="font-size: 12px; color: var(--dim); margin-top: 4px;">Klik salah satu saluran di sebelah kiri.</p>
      <div id="drmBox" style="display:none; margin-top:10px; font-size:12px; background:rgba(255,255,255,0.06); padding:8px 10px; border-radius:8px;"></div>
      <div style="margin-top: 14px; display: flex; gap: 8px;">
        <button id="copyBtn" class="btn" style="display: none;" onclick="copyCurrent()">🔗 Salin Link Stream Ini</button>
      </div>
    </div>
  </div>
</div>
<div id="t" class="toast"></div>
<script>
  let shakaPlayer = null;
  let currentCh = null;
  let activeGroup = null;
  const v = document.getElementById('v');

  document.addEventListener('DOMContentLoaded', async () => {
    shaka.polyfill.installAll();
    if (shaka.Player.isBrowserSupported()) {
      shakaPlayer = new shaka.Player(v);
      shakaPlayer.addEventListener('error', (e) => {
        console.error('Shaka error:', e.detail);
        document.getElementById('pSub').innerText = (currentCh?.group || '') + ' · Gagal memuat siaran (Error ' + e.detail.code + ')';
      });
    }
    const first = document.querySelector('.card');
    if (first) playCard(first);
  });

  function selectGroup(grp, el) {
    activeGroup = grp;
    document.querySelectorAll('.pill').forEach(p => p.classList.remove('active'));
    el.classList.add('active');
    filter();
  }

  function filter() {
    const q = document.getElementById('q').value.toLowerCase().trim();
    document.querySelectorAll('.card').forEach(c => {
      const matchQ = !q || c.dataset.name.includes(q);
      const matchG = !activeGroup || c.dataset.group === activeGroup;
      c.style.display = (matchQ && matchG) ? 'flex' : 'none';
    });
  }

  async function playCard(el) {
    const info = JSON.parse(el.dataset.info);
    currentCh = info;

    document.querySelectorAll('.card').forEach(c => c.classList.toggle('active', c.dataset.id === info.id));
    document.getElementById('pName').innerText = info.name;
    document.getElementById('pSub').innerText = info.group + ' · Memuat siaran...';
    document.getElementById('copyBtn').style.display = 'inline-flex';

    const drmBox = document.getElementById('drmBox');
    if (info.drm_type || info.drm_key) {
      drmBox.style.display = 'block';
      drmBox.innerHTML = '<strong>DRM:</strong> ' + (info.drm_type || 'ClearKey') + (info.drm_key ? '<br><code>' + info.drm_key + '</code>' : '');
    } else {
      drmBox.style.display = 'none';
    }

    if (!shakaPlayer) return;
    shakaPlayer.resetConfiguration();
    shakaPlayer.getNetworkingEngine().clearAllRequestFilters();

    // Auto-configure ClearKey DRM
    if (info.drm_type && (info.drm_type.includes('clearkey') || info.drm_type.includes('org.w3.clearkey')) && info.drm_key) {
      const cleanKey = info.drm_key.split('&')[0];
      const parts = cleanKey.split(':');
      if (parts.length === 2) {
        shakaPlayer.configure({ drm: { clearKeys: { [parts[0].trim()]: parts[1].trim() } } });
      }
    } else if (info.drm_type && info.drm_type.includes('widevine') && info.drm_key) {
      shakaPlayer.configure({ drm: { servers: { 'com.widevine.alpha': info.drm_key } } });
    }

    try {
      await shakaPlayer.load(info.stream_url);
      v.play().catch(()=>{});
      document.getElementById('pSub').innerText = info.group + ' · Live';
    } catch (err) {
      console.warn('Playback error:', err);
    }
  }

  function copyCurrent() {
    if (currentCh) copy(currentCh.stream_url, 'Link stream disalin!');
  }

  function copy(t, msg) {
    navigator.clipboard.writeText(t).then(() => {
      const el = document.getElementById('t'); el.innerText = msg; el.classList.add('show');
      setTimeout(() => el.classList.remove('show'), 2000);
    });
  }
</script>
</body>
</html>
""".trimIndent()

        val body = html.toByteArray(Charsets.UTF_8)
        sendCors(ex)
        ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    private fun open(url: String, headers: Map<String, String>, range: String? = null): HttpURLConnection {
        var u = url
        repeat(5) {
            val c = URI(u).toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 18000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", tech.kousozo.tahoetv.core.Web.STREAM_UA)
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

