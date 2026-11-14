package tech.kousozo.tahoetv.desktop

import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject
import tech.kousozo.tahoetv.core.Protocol

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

            // Test /info
            val infoCon = URI("$baseUrl/info").toURL().openConnection() as HttpURLConnection
            assertEquals(200, infoCon.responseCode)
            val infoJson = JSONObject(infoCon.inputStream.bufferedReader().readText())
            assertEquals("desktop", infoJson.getString("role"))
            assertEquals("Tahoe Desktop", infoJson.getString("name"))
            assertEquals(Protocol.VERSION, infoJson.getInt("proto"))
            assertTrue(infoJson.getInt("channels") > 0)

            // Test GET /api/connect
            val getConnectCon = URI("$baseUrl/api/connect").toURL().openConnection() as HttpURLConnection
            assertEquals(200, getConnectCon.responseCode)
            val stateJson = JSONObject(getConnectCon.inputStream.bufferedReader().readText())
            assertEquals("device.state", stateJson.getString("type"))
            assertEquals("state", stateJson.getString("t"))
            assertTrue(stateJson.getJSONObject("body").has("session"))

            // Test POST /api/connect (valid stop envelope)
            val stopCon = (URI("$baseUrl/api/connect").toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                outputStream.use {
                    it.write(Protocol.encode(Protocol.SessionControl("stop"), id = "msg-1").toString().toByteArray(Charsets.UTF_8))
                }
            }
            assertEquals(200, stopCon.responseCode)
            val ackJson = JSONObject(stopCon.inputStream.bufferedReader().readText())
            assertEquals("ack", ackJson.getString("type"))
            assertEquals("msg-1", ackJson.getJSONObject("body").getString("ackId"))

            // Test POST /api/connect (invalid payload)
            val badCon = (URI("$baseUrl/api/connect").toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                outputStream.use { it.write("not-json".toByteArray(Charsets.UTF_8)) }
            }
            assertEquals(400, badCon.responseCode)
            val errJson = JSONObject(badCon.errorStream.bufferedReader().readText())
            assertEquals("error", errJson.getString("type"))
            assertEquals("bad_request", errJson.getJSONObject("body").getString("code"))
        } finally {
            IptvServer.stop()
            assertTrue(!IptvServer.isRunning.value)
        }
    }
}

