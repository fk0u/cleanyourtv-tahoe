package tech.kousozo.tahoetv.desktop

import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject

class IptvServerSecurityTest {
    @Test
    fun testHostHeaderAndCorsAndSecrets() {
        val playlist = java.io.File.createTempFile("sec-test", ".m3u").apply {
            deleteOnExit()
            writeText("""
                #EXTM3U
                #EXTINF:-1 group-title="SecGroup" tvg-id="id1" tvg-logo="https://example.com/logo.png",Sec Channel
                #KODIPROP:inputstream.adaptive.license_type=clearkey
                #KODIPROP:inputstream.adaptive.license_key=secret_key_12345
                http://127.0.0.1:9999/stream.m3u8
            """.trimIndent())
        }

        IptvServer.start(bindPort = 8998, customFiles = listOf(playlist.path))
        val port = IptvServer.port.value
        val baseUrl = "http://127.0.0.1:$port"

        try {
            // 1. DNS Rebinding: Host header attacker.com must be rejected (test with raw socket since HttpURLConnection restricts Host header)
            val socket = java.net.Socket("127.0.0.1", port)
            socket.getOutputStream().write("GET /api/status HTTP/1.1\r\nHost: evil-attacker.com:$port\r\nConnection: close\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
            val statusLine = socket.getInputStream().bufferedReader().readLine().orEmpty()
            socket.close()
            assertTrue(statusLine.startsWith("HTTP/1.1 400") || statusLine.startsWith("HTTP/1.0 400"), "Expected 400 for bad Host header, got: $statusLine")

            // Valid host (localhost / 127.0.0.1) must succeed
            val conGoodHost = URI("$baseUrl/api/status").toURL().openConnection() as HttpURLConnection
            conGoodHost.setRequestProperty("Host", "127.0.0.1:$port")
            assertEquals(200, conGoodHost.responseCode)

            // 2. CORS: Cross-origin request from evil.com must NOT receive Access-Control-Allow-Origin
            val corsSocket = java.net.Socket("127.0.0.1", port)
            corsSocket.getOutputStream().write("GET /api/channels HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nOrigin: https://evil.com\r\nConnection: close\r\n\r\n".toByteArray())
            corsSocket.getOutputStream().flush()
            val corsResp = corsSocket.getInputStream().bufferedReader().readText()
            corsSocket.close()

            assertFalse(corsResp.contains("Access-Control-Allow-Origin"), "Must not grant CORS to evil.com")
            // And secrets (raw_url, drm_key) must NOT be leaked when Origin is evil.com
            val evilBody = corsResp.substringAfter("\r\n\r\n")
            val evilJson = JSONObject(evilBody)
            val chEvil = evilJson.getJSONArray("channels").getJSONObject(0)
            assertFalse(chEvil.has("raw_url"), "raw_url must be omitted for untrusted origins")
            assertFalse(chEvil.has("drm_key"), "drm_key must be omitted for untrusted origins")

            // 3. Same-origin or local requests without cross-origin Origin header should contain channels
            val conLocal = URI("$baseUrl/api/channels").toURL().openConnection() as HttpURLConnection
            assertEquals(200, conLocal.responseCode)
            val localJson = JSONObject(conLocal.inputStream.bufferedReader().readText())
            val chLocal = localJson.getJSONArray("channels").getJSONObject(0)
            assertTrue(chLocal.has("raw_url"))
            assertTrue(chLocal.has("drm_key"))

            // 4. Dashboard and watch pages must enforce strict CSP and serve bundled local player scripts (no CDN)
            val conDash = URI("$baseUrl/").toURL().openConnection() as HttpURLConnection
            assertEquals(200, conDash.responseCode)
            val csp = conDash.getHeaderField("Content-Security-Policy")
            assertTrue(csp != null && csp.contains("default-src 'self'"), "Dashboard must enforce CSP")
            assertFalse(csp.contains("cloudflare.com"), "Dashboard CSP must not depend on external CDN")
            val dashHtml = conDash.inputStream.bufferedReader().readText()
            assertTrue(dashHtml.contains("/assets/shaka-player.compiled.js"))
            assertFalse(dashHtml.contains("cdnjs.cloudflare.com"))

            val conWatch = URI("$baseUrl/watch").toURL().openConnection() as HttpURLConnection
            assertEquals(200, conWatch.responseCode)
            val watchCsp = conWatch.getHeaderField("Content-Security-Policy")
            assertTrue(watchCsp != null && !watchCsp.contains("jsdelivr.net"), "Watch CSP must not depend on external CDN")
            val watchHtml = conWatch.inputStream.bufferedReader().readText()
            assertTrue(watchHtml.contains("/assets/hls.min.js"))
            assertFalse(watchHtml.contains("cdn.jsdelivr.net"))

            for (asset in listOf("/assets/hls.min.js", "/assets/shaka-player.compiled.js")) {
                val assetCon = URI("$baseUrl$asset").toURL().openConnection() as HttpURLConnection
                assertEquals(200, assetCon.responseCode)
                assertTrue(assetCon.contentType.orEmpty().contains("javascript"), "Expected JS content-type for $asset")
                assertTrue(assetCon.inputStream.readBytes().size > 10_000, "Asset $asset should be non-trivial size")
            }
        } finally {
            IptvServer.stop()
        }
    }
}
