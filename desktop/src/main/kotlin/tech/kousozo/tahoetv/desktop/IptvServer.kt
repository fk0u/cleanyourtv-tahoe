package tech.kousozo.tahoetv.desktop

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import tech.kousozo.tahoetv.core.HlsProbe
import tech.kousozo.tahoetv.core.LocalTv
import tech.kousozo.tahoetv.core.M3u
import tech.kousozo.tahoetv.core.Protocol
import tech.kousozo.tahoetv.core.Sources
import tech.kousozo.tahoetv.core.TahoeVersion

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
    @Volatile private var sourcesSnapshot: List<String> = emptyList()

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
    internal fun currentChannels(): List<ServerChannel> = channels
    internal fun channelById(id: String): ServerChannel? = byId[id]

    /** Playlist + country the list was loaded for: a change to either reloads it. */
    private fun loadKey() = Settings.playlist + "|" + Settings.viewer.countries.joinToString(",")

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
    internal fun wanted(sc: ServerChannel, query: Map<String, String>) = query["live"] !in listOf("1", "true") || health[sc.id].let { it == null || it == "live" }

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

    fun localIps(): Set<String> {
        val ips = LinkedHashSet<String>()
        ips.add("127.0.0.1")
        ips.add("localhost")
        ips.add("::1")
        ips.add("0.0.0.0")
        try {
            for (ni in java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (a in java.util.Collections.list(ni.inetAddresses)) {
                    a.hostAddress?.let { ips.add(it.substringBefore('%').lowercase()) }
                }
            }
        } catch (_: Exception) {}
        return ips
    }

    internal fun isAllowedHost(hostHeader: String?): Boolean {
        if (hostHeader.isNullOrBlank()) return true
        val host = hostHeader.substringBefore(':').trim().lowercase()
        return host in localIps() || host.endsWith(".local")
    }

    internal fun isAllowedOrigin(origin: String?): Boolean {
        if (origin.isNullOrBlank()) return false
        val uri = runCatching { URI(origin) }.getOrNull() ?: return false
        val host = uri.host?.lowercase().orEmpty()
        val p = uri.port.takeIf { it != -1 } ?: if (uri.scheme.equals("https", true)) 443 else 80
        return isAllowedHost(host) && (p == port.value || p == 80 || p == 443)
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
        Snapshot.withMutableSnapshot {
            port.value = s.address.port
            isRunning.value = true
        }
        refreshHealth()
        Recordings.boot() // scheduled recordings run while the server does
    }

    fun stop() { // not under the lock: a reload in progress must not freeze the Stop button
        server?.stop(0)
        server = null
        Snapshot.withMutableSnapshot {
            isRunning.value = false
        }
    }

    private fun loadPlaylists(customFiles: List<String>) {
        healthGen.incrementAndGet(); health.clear(); healthAt = 0L // new channel list: old verdicts no longer apply
        val channels = ArrayList<ServerChannel>()
        val byId = HashMap<String, ServerChannel>()
        val idByUrl = HashMap<String, String>()
        val newSources = ArrayList<String>()

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
                listOf("Tahoe" to runCatching { Sources.loadCountries(Settings.playlist, Settings.viewer.countries) }.getOrDefault(emptyList())).also { loadedFor = key } }
        for ((sourceName, list) in groups) {
            if (list.isEmpty()) continue

            newSources.add(sourceName)
            for (ch in list) {
                val clean = ch.channelId.replace('.', '-').ifEmpty { "ch" }
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
        this.sourcesSnapshot = newSources.toList()
        Snapshot.withMutableSnapshot {
            loadedSources.clear()
            loadedSources.addAll(newSources)
            channelCount.value = channels.size
            relayCount.value = relays
        }
    }

    private fun handleRequest(ex: HttpExchange) {
        val host = ex.requestHeaders.getFirst("Host")
        if (!isAllowedHost(host)) {
            ex.sendResponseHeaders(400, -1)
            ex.close()
            return
        }

        val path = ex.requestURI.path
        val query = ex.requestURI.rawQuery.orEmpty().split('&').filter { it.contains('=') }
            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }

        if (ex.requestMethod.equals("OPTIONS", ignoreCase = true)) {
            val origin = ex.requestHeaders.getFirst("Origin")
            if (origin != null && !isAllowedOrigin(origin)) {
                ex.sendResponseHeaders(403, -1)
                ex.close()
                return
            }
            sendCors(ex)
            ex.sendResponseHeaders(204, -1)
            ex.close()
            return
        }

        try {
            when {
                path == "/" || path == "/index.html" -> serveDashboard(ex)
                path == "/assets/hls.min.js" || path == "/assets/shaka-player.compiled.js" -> serveAsset(ex, path.removePrefix("/"))
                path == "/watch" -> serveWatch(ex, query)
                path == "/api/watch" -> serveWatchApi(ex, query)
                path == "/playlist.m3u" || path == "/all.m3u" -> ServerRelay.servePlaylist(ex, query)
                path.endsWith(".m3u") && sourcesSnapshot.any { path == "/$it" } -> ServerRelay.servePlaylist(ex, query, path.removePrefix("/"))
                path == "/epg.xml" -> ServerRelay.serveEpg(ex)
                path.startsWith("/live/") && (path.endsWith(".m3u8") || path.endsWith(".mpd")) -> {
                    if (path.endsWith(".mpd")) {
                        val cid = path.removePrefix("/live/").removeSuffix(".mpd")
                        ServerRelay.serveLiveMpd(ex, cid)
                    } else {
                        val cid = path.removePrefix("/live/").removeSuffix(".m3u8")
                        ServerRelay.serveLive(ex, cid, query["sub"], query["s"])
                    }
                }
                path == "/segment" -> ServerRelay.serveSegment(ex, query["u"], query["cid"], query["s"])
                path == "/info" -> serveInfo(ex)
                path == "/api/connect" -> serveConnect(ex)
                path == "/api/status" -> serveStatus(ex)
                path == "/api/channels" -> serveChannels(ex, query)
                path == "/api/health" -> serveHealth(ex, query)
                path == "/api/recordings" -> ServerRelay.serveRecordings(ex, query)
                path.startsWith("/recordings/") -> ServerRelay.serveRecordingFile(ex, path.removePrefix("/recordings/"))
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

    internal fun sendCors(ex: HttpExchange) {
        val origin = ex.requestHeaders.getFirst("Origin")
        if (origin != null && isAllowedOrigin(origin)) {
            ex.responseHeaders.set("Access-Control-Allow-Origin", origin)
            ex.responseHeaders.set("Access-Control-Allow-Methods", "GET, HEAD, POST, DELETE, OPTIONS")
            ex.responseHeaders.set("Access-Control-Allow-Headers", "Range, User-Agent, Referer, Accept, Content-Type")
            ex.responseHeaders.set("Access-Control-Expose-Headers", "Content-Range, Content-Length, Accept-Ranges")
            ex.responseHeaders.set("Vary", "Origin")
        }
    }

    internal fun baseUrl(ex: HttpExchange): String {
        val h = ex.requestHeaders.getFirst("Host")
        return if (!h.isNullOrBlank()) "http://$h" else "http://${lanIp()}:${port.value}"
    }

    internal fun sendJson(ex: HttpExchange, code: Int, j: Any) {
        val body = j.toString().toByteArray(Charsets.UTF_8)
        sendCors(ex)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    /** Loopback, or one of this computer's own addresses (a request to its LAN IP from itself). */
    internal fun fromThisComputer(ex: HttpExchange): Boolean {
        val a = ex.remoteAddress.address
        return a.isLoopbackAddress || runCatching { java.net.NetworkInterface.getByInetAddress(a) != null }.getOrDefault(false)
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
            put("sources", JSONArray(sourcesSnapshot))
            put("total_channels", channels.size)
            put("relay_channels", relayCount.value)
        }
        val body = j.toString(2).toByteArray(Charsets.UTF_8)
        sendCors(ex)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    /** Device identity and Tahoe Connect capability advertisement (`GET /info`), matching TV Companion's handshake shape. */
    private fun serveInfo(ex: HttpExchange) {
        val info = JSONObject()
            .put("id", "desktop-${lanIp()}")
            .put("name", "Tahoe Desktop")
            .put("role", "desktop")
            .put("proto", Protocol.VERSION)
            .put("ver", TahoeVersion.VERSION)
            .put("port", port.value)
            .put("channels", channels.size)
            .put("caps", JSONArray(listOf("live", "handoff", "session")))
        sendJson(ex, 200, info)
    }

    /** Tahoe Connect LAN receiver endpoint: GET returns device + playback session state; POST accepts Protocol v2 messages. */
    private fun serveConnect(ex: HttpExchange) {
        when (ex.requestMethod.uppercase()) {
            "GET" -> {
                val st = WebPlayer.state()
                val ids = st.optJSONArray("ids")
                val firstId = if (ids != null && ids.length() > 0) ids.optString(0, "") else ""
                val stopped = st.optBoolean("stopped", true)
                val title = st.optString("title", "")
                val session = Protocol.SessionState(
                    rev = st.optLong("seq", 0L),
                    channelId = firstId,
                    channel = title,
                    number = 0,
                    playing = !stopped && title.isNotEmpty(),
                    paused = false,
                )
                val payload = Protocol.toLegacyStateJson(
                    Protocol.DeviceState(volume = 100, muted = false, sleep = 0, session = session),
                    session
                )
                sendJson(ex, 200, payload)
            }
            "POST" -> {
                val origin = ex.requestHeaders.getFirst("Origin")
                if (origin != null && !isAllowedOrigin(origin)) {
                    return sendJson(ex, 403, Protocol.error("", "forbidden", "forbidden origin"))
                }
                val rawBytes = ex.requestBody.readNBytes(8193)
                if (rawBytes.size > 8192) {
                    return sendJson(ex, 413, Protocol.error("", "too_large", "payload too large"))
                }
                val decoded = Protocol.decode(String(rawBytes, Charsets.UTF_8))
                    ?: return sendJson(ex, 400, Protocol.error("", "bad_request", "invalid message"))
                when (val msg = decoded.message) {
                    is Protocol.SessionLoad -> {
                        val match = byId[msg.channelId] ?: channels.firstOrNull {
                            it.channel.channelId.equals(msg.channelId, ignoreCase = true) ||
                                it.channel.name.equals(msg.name.ifBlank { msg.channelId }, ignoreCase = true)
                        }
                        val stream = match?.channel ?: if (msg.url.isNotBlank()) {
                            M3u.Channel(name = msg.name.ifBlank { msg.channelId.ifBlank { "Tahoe TV" } }, url = msg.url, logo = null, group = null)
                        } else null
                        if (stream == null) {
                            return sendJson(ex, 404, Protocol.error(decoded.envelope.id, "not_found", "channel not found"))
                        }
                        val title = msg.name.ifBlank { stream.name }
                        val err = WebPlayer.open(listOf(stream), title, {}, {})
                        val rev = WebPlayer.state().optLong("seq", 0L)
                        if (err != null) sendJson(ex, 409, Protocol.error(decoded.envelope.id, "playback_error", err))
                        else sendJson(ex, 200, Protocol.ack(decoded.envelope.id, rev))
                    }
                    is Protocol.SessionControl -> when (msg.action.lowercase()) {
                        "stop" -> {
                            WebPlayer.stop()
                            sendJson(ex, 200, Protocol.ack(decoded.envelope.id, WebPlayer.state().optLong("seq", 0L)))
                        }
                        "play" -> {
                            WebPlayer.reopen()
                            sendJson(ex, 200, Protocol.ack(decoded.envelope.id, WebPlayer.state().optLong("seq", 0L)))
                        }
                        "pause" -> sendJson(ex, 200, Protocol.ack(decoded.envelope.id, WebPlayer.state().optLong("seq", 0L)))
                        else -> sendJson(ex, 400, Protocol.error(decoded.envelope.id, "unsupported", "unsupported action: ${msg.action}"))
                    }
                    is Protocol.Ping -> sendJson(ex, 200, Protocol.encode(Protocol.Pong(msg.nonce), id = decoded.envelope.id))
                    else -> sendJson(ex, 400, Protocol.error(decoded.envelope.id, "unsupported", "unsupported type: ${decoded.envelope.type}"))
                }
            }
            else -> {
                ex.sendResponseHeaders(405, -1)
                ex.close()
            }
        }
    }

    private fun serveChannels(ex: HttpExchange, query: Map<String, String>) {
        val base = baseUrl(ex)
        val grp = query["group"]
        val filtered = channels.filter { (grp == null || it.channel.group.equals(grp, ignoreCase = true)) && wanted(it, query) }
        val origin = ex.requestHeaders.getFirst("Origin")
        val includeSecrets = fromThisComputer(ex) && (origin == null || isAllowedOrigin(origin))
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
                if (includeSecrets) put("raw_url", sc.channel.url)
                put("is_mpd", isMpd)
                put("is_vod", isVod)
                put("drm_type", sc.drmType)
                if (includeSecrets) put("drm_key", sc.drmKey)
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

    /** The slate's texts (#76) for the watch page, in the app's language order (org.json escapes "</" for a script tag). */
    private fun slateJson(): String {
        val S = tech.kousozo.tahoetv.core.Slate
        val lang = Settings.lang
        val order = S.order(lang)
        return JSONObject().put("order", JSONArray(order)).put("msg", JSONObject().apply { order.forEach { put(it, S.message(it)) } })
            .put("reason", S.reason(order[0], tech.kousozo.tahoetv.core.Slate.Reason.Offline))
            .put("retrying", S.label(lang, "retrying")).put("waiting", S.label(lang, "waiting"))
            .put("secs", S.RETRY_SECONDS).put("max", S.MAX_RETRIES).toString()
    }

    /** One channel, full window, for the web player (Windows, where JavaFX can't play HLS): hls.js plays the server's
     *  relay of each source in turn, moving on when one fails or doesn't start in 20 s; follows the app's session. */
    private fun serveWatch(ex: HttpExchange, query: Map<String, String>) {
        val ids = query["ids"].orEmpty().split(",").map { it.trim() }.filter { byId.containsKey(it) }
        val title = query["t"].orEmpty().ifBlank { ids.firstOrNull()?.let { byId[it]?.channel?.name } ?: "Tahoe TV" }
        val html = ServerPages.renderWatch(title, ids, query["s"]?.toIntOrNull() ?: -1, slateJson())
        val body = html.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        ex.responseHeaders.add("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' http: https: data:; media-src 'self' blob: http: https:; connect-src 'self' blob: http: https:;")
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    private fun serveAsset(ex: HttpExchange, resourcePath: String) {
        val body = IptvServer::class.java.getResourceAsStream("/$resourcePath")?.use { it.readBytes() }
            ?: run { ex.sendResponseHeaders(404, -1); ex.close(); return }
        sendCors(ex)
        ex.responseHeaders.add("Content-Type", "application/javascript; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "public, max-age=86400")
        if (ex.requestMethod.equals("HEAD", true)) {
            ex.responseHeaders.set("Content-Length", "${body.size}")
            ex.sendResponseHeaders(200, -1)
            ex.close()
            return
        }
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }

    /** Text from playlists (third-party files) goes into this page: escaped, never trusted as HTML. */
    internal fun esc(s: String?): String = s.orEmpty().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    /** Only http(s) logos: no javascript:/data: URLs in an <img src>. */
    internal fun safeUrl(u: String?): String? = u?.trim()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }

    private fun serveDashboard(ex: HttpExchange) {
        val body = ServerPages.renderDashboard(baseUrl(ex), channels, sourcesSnapshot).toByteArray(Charsets.UTF_8)
        sendCors(ex)
        ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        ex.responseHeaders.add("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' http: https: data:; media-src 'self' blob: http: https:; connect-src 'self' blob: http: https:;")
        ex.sendResponseHeaders(200, body.size.toLong())
        ex.responseBody.use { it.write(body) }
    }
}
