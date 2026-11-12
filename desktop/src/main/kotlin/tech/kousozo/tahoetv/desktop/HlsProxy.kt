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
                    if (!signer.verify(q["h"].orEmpty(), url, q["s"])) { ex.sendResponseHeaders(403, -1); return@createContext }
                    val headers = q["h"]?.let { JSONObject(String(Base64.getUrlDecoder().decode(it))).toMap().mapValues { (_, v) -> "$v" } }.orEmpty()
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

    private fun open(url: String, headers: Map<String, String>): HttpURLConnection {
        var u = url
        repeat(5) { // follow redirects by hand: HttpURLConnection won't cross http↔https, which CDNs do
            val c = URI(u).toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 15000; c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", tech.kousozo.tahoetv.core.Web.STREAM_UA); headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (c.responseCode in 300..399) { u = URI(u).resolve(c.getHeaderField("Location")).toString(); c.disconnect() } else return c
        }
        error("too many redirects")
    }

    private val URI_ATTR = Regex("""URI="([^"]+)"""")

    /** What JavaFX gets instead of the source's playlist: a master playlist without its dead variants (JavaFX plays the
     *  first variant and never switches; sources often keep the master up while a variant gives 403/404), capped to the
     *  chosen quality. Null when no variant answers. */
    internal fun playable(text: String, base: String, headers: Map<String, String>): String? =
        tech.kousozo.tahoetv.core.HlsProbe.dropDeadVariants(text, base, headers, 5000)
            ?.let { tech.kousozo.tahoetv.core.PlayPrefs.capVariants(it, Settings.quality.maxHeight) }

    /** Absolute-ise every URL in a playlist and point it back at the relay. */
    fun rewrite(text: String, base: String, headers: Map<String, String>): String = text.lineSequence().joinToString("\n") { line ->
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
        val enc = Base64.getUrlEncoder().withoutPadding()
        val h = enc.encodeToString(JSONObject(headers).toString().toByteArray())
        return "http://127.0.0.1:${server.address.port}/s?u=" + enc.encodeToString(url.toByteArray()) +
            "&h=" + URLEncoder.encode(h, "UTF-8") + "&s=" + signer.sign(h, url)
    }
}
