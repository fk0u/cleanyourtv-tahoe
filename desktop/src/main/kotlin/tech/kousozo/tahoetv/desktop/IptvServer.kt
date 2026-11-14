package tech.kousozo.tahoetv.desktop

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
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
                path == "/playlist.m3u" || path == "/all.m3u" -> servePlaylist(ex, query)
                path.endsWith(".m3u") && sourcesSnapshot.any { path == "/$it" } -> servePlaylist(ex, query, path.removePrefix("/"))
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
        val origin = ex.requestHeaders.getFirst("Origin")
        if (origin != null && isAllowedOrigin(origin)) {
            ex.responseHeaders.set("Access-Control-Allow-Origin", origin)
            ex.responseHeaders.set("Access-Control-Allow-Methods", "GET, HEAD, POST, DELETE, OPTIONS")
            ex.responseHeaders.set("Access-Control-Allow-Headers", "Range, User-Agent, Referer, Accept, Content-Type")
            ex.responseHeaders.set("Access-Control-Expose-Headers", "Content-Range, Content-Length, Accept-Ranges")
            ex.responseHeaders.set("Vary", "Origin")
        }
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
        val countries = Settings.viewer.countries
        val country = countries.joinToString(",")
        fun fresh() = epgCache?.takeIf { epgFor == country && System.currentTimeMillis() - epgAt < 6 * 3600_000L }
        // One download at a time: a burst of requests after the cache expires waits for it instead of each fetching the XML.
        // the country's first guide that answers (Indonesia: dhanytv, then epg.pw)
        val data = fresh() ?: synchronized(epgLock) { fresh() ?: countries.flatMap { tech.kousozo.tahoetv.core.Region.guides(it) }.distinct().firstNotNullOfOrNull { url -> runCatching {
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

