package tech.kousozo.tahoetv.desktop

import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject

class IptvServerTest {
    @Test
    fun testServerLifecycleAndEndpoints() {
        // Its own small playlist, so the test doesn't depend on a koutv.m3u lying around in the working directory
        val playlist = java.io.File.createTempFile("test-channels", ".m3u").apply {
            deleteOnExit()
            writeText("#EXTM3U\n#EXTINF:-1 group-title=\"Nasional\",Test Satu\nhttp://127.0.0.1:9/satu/index.m3u8\n" +
                "#EXTINF:-1 group-title=\"Berita\",Test Dua\nhttp://127.0.0.1:9/dua/index.m3u8\n")
        }
        // Start server on an open port
        IptvServer.start(bindPort = 8997, customFiles = listOf(playlist.path))
        assertTrue(IptvServer.isRunning.value)
        assertTrue(IptvServer.channelCount.value > 0)

        val port = IptvServer.port.value
        val baseUrl = "http://127.0.0.1:$port"

        try {
            // Test /api/status
            val statusCon = URI("$baseUrl/api/status").toURL().openConnection() as HttpURLConnection
            assertEquals(200, statusCon.responseCode)
            val statusJson = JSONObject(statusCon.inputStream.bufferedReader().readText())
            assertEquals("online", statusJson.getString("status"))
            assertTrue(statusJson.getInt("total_channels") > 0)

            // Test /playlist.m3u
            val m3uCon = URI("$baseUrl/playlist.m3u").toURL().openConnection() as HttpURLConnection
            assertEquals(200, m3uCon.responseCode)
            val m3uText = m3uCon.inputStream.bufferedReader().readText()
            assertTrue(m3uText.startsWith("#EXTM3U"))
            assertTrue(m3uText.contains("/live/"))

            // Test /api/channels
            val chanCon = URI("$baseUrl/api/channels").toURL().openConnection() as HttpURLConnection
            assertEquals(200, chanCon.responseCode)
            val chanJson = JSONObject(chanCon.inputStream.bufferedReader().readText())
            assertTrue(chanJson.getInt("count") > 0)

            // Test Dashboard /
            val dashCon = URI("$baseUrl/").toURL().openConnection() as HttpURLConnection
            assertEquals(200, dashCon.responseCode)
            val html = dashCon.inputStream.bufferedReader().readText()
            assertTrue(html.contains("Tahoe IPTV Server"))
        } finally {
            IptvServer.stop()
            assertTrue(!IptvServer.isRunning.value)
        }
    }
}

