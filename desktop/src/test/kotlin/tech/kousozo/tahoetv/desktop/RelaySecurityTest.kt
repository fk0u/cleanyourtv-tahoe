package tech.kousozo.tahoetv.desktop

import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The relay serves only URLs it wrote itself, and the dashboard never trusts playlist text as HTML. */
class RelaySecurityTest {
    private fun get(url: String): Pair<Int, String> {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        val code = c.responseCode
        val body = runCatching { (if (code < 400) c.inputStream else c.errorStream)?.readBytes()?.decodeToString().orEmpty() }.getOrDefault("")
        return code to body
    }

    @Test fun relayAndDashboard() {
        // an upstream "CDN" with a media playlist and one segment
        val cdn = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/live/index.m3u8") { ex -> val b = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg1.ts\n".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
            createContext("/live/seg1.ts") { ex -> val b = ByteArray(188) { 0x47 }; ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
            createContext("/secret") { ex -> val b = "router admin".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) } }
            start()
        }
        val cdnBase = "http://127.0.0.1:${cdn.address.port}"
        val playlist = java.io.File.createTempFile("relay", ".m3u").apply {
            deleteOnExit()
            writeText("#EXTM3U\n#EXTINF:-1 group-title=\"<b>Grup</b>\" tvg-logo=\"javascript:alert(1)\",Evil <img src=x onerror=alert(1)>\n" +
                "#EXTVLCOPT:http-referrer=https://example.com/\n$cdnBase/live/index.m3u8\n")
        }
        IptvServer.start(bindPort = 8996, customFiles = listOf(playlist.path))
        val base = "http://127.0.0.1:${IptvServer.port.value}"
        try {
            val cid = org.json.JSONObject(get("$base/api/channels").second).getJSONArray("channels").getJSONObject(0).getString("id")

            // the playlist is rewritten with signed segment links, and they play
            val (code, pl) = get("$base/live/$cid.m3u8")
            assertEquals(200, code)
            val seg = pl.lines().first { it.contains("/segment?") }
            assertTrue("&s=" in seg)
            assertEquals(200, get(seg).first)

            // tampering or pointing it elsewhere: refused
            val evil = Base64.getUrlEncoder().withoutPadding().encodeToString("$cdnBase/secret".toByteArray())
            assertEquals(403, get("$base/segment?cid=$cid&u=$evil").first)
            assertEquals(403, get(seg.replace(Regex("u=[^&]+"), "u=$evil")).first)
            assertEquals(403, get("$base/live/$cid.m3u8?sub=$evil").first)

            // dashboard: playlist text is escaped, javascript: logos dropped
            val html = get("$base/").second
            assertFalse("<img src=x onerror" in html)
            assertFalse("javascript:alert" in html)
            assertFalse("<b>Grup</b>" in html)
            assertTrue("Evil &lt;img" in html)
        } finally {
            IptvServer.stop(); cdn.stop(0)
        }
    }
}
