package tech.kousozo.tahoetv.desktop

import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HlsProxyTest {
    @Test fun trimDiscontinuityDropsPreRollSegmentsAndBumpsSequence() {
        val raw = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:100
            #EXT-X-PROGRAM-DATE-TIME:2026-11-14T00:00:00Z
            #EXTINF:6.0,
            ad0.ts
            #EXTINF:6.0,
            ad1.ts
            #EXT-X-DISCONTINUITY
            #EXT-X-PROGRAM-DATE-TIME:2026-11-14T01:00:00Z
            #EXTINF:6.0,
            live102.ts
            #EXTINF:6.0,
            live103.ts
        """.trimIndent()

        val trimmed = HlsProxy.trimDiscontinuity(raw)
        assertFalse("ad0.ts" in trimmed)
        assertFalse("ad1.ts" in trimmed)
        assertFalse("#EXT-X-DISCONTINUITY" in trimmed)
        assertFalse("2026-11-14T00:00:00Z" in trimmed)
        assertTrue("#EXT-X-MEDIA-SEQUENCE:102" in trimmed)
        assertTrue("2026-11-14T01:00:00Z" in trimmed)
        assertTrue("live102.ts" in trimmed && "live103.ts" in trimmed)
    }

    @Test fun trimDiscontinuityPreservesPlainOrTrailingDiscontinuityPlaylists() {
        val plain = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4.0,\na.ts\n"
        assertEquals(plain, HlsProxy.trimDiscontinuity(plain))

        val trailingDisc = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4.0,\na.ts\n#EXT-X-DISCONTINUITY\n#EXT-X-ENDLIST\n"
        assertEquals(trailingDisc, HlsProxy.trimDiscontinuity(trailingDisc))
    }

    @Test fun dashIsServedAsHlsThroughTheSignedRelay() {
        val mpd = """<?xml version="1.0"?>
            <MPD type="static" mediaPresentationDuration="PT8S"><Period>
              <AdaptationSet mimeType="video/mp4">
                <SegmentTemplate timescale="1000" duration="4000" startNumber="1" initialization="init-${'$'}RepresentationID$.mp4" media="seg-${'$'}RepresentationID$-${'$'}Number$.m4s"/>
                <Representation id="v" bandwidth="900000" width="1280" height="720" codecs="avc1.4d401f"/>
              </AdaptationSet>
            </Period></MPD>""".trimIndent()
        var seenReferer: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                seenReferer = ex.requestHeaders.getFirst("Referer")
                val body = when {
                    ex.requestURI.path.endsWith(".mpd") -> mpd.toByteArray()
                    else -> ByteArray(32) { 7 }
                }
                ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
            }
            start()
        }
        fun get(u: String): Pair<Int, ByteArray> = (URI(u).toURL().openConnection() as HttpURLConnection).let { c ->
            c.responseCode to (if (c.responseCode < 400) c.inputStream.readBytes() else ByteArray(0))
        }
        try {
            val headers = mapOf("Referer" to "https://ref.example/")
            val master = String(get(HlsProxy.wrapDash("http://127.0.0.1:${server.address.port}/live/a.mpd", headers, null)).second)
            assertTrue(master.startsWith("#EXTM3U"))
            assertTrue("RESOLUTION=1280x720" in master)
            val variant = master.lines().first { it.startsWith("http://127.0.0.1:") }
            val media = String(get(variant).second)
            assertTrue("#EXT-X-MAP:URI=\"http://127.0.0.1:" in media)
            assertTrue("#EXT-X-ENDLIST" in media)
            val seg = media.lines().first { it.startsWith("http://127.0.0.1:") }
            val (code, bytes) = get(seg)
            assertEquals(200, code); assertEquals(32, bytes.size)
            assertEquals("https://ref.example/", seenReferer)
            // a tampered link is refused
            assertEquals(403, get(seg.replace(Regex("s=[^&]+$"), "s=AAAA")).first)
        } finally {
            server.stop(0)
        }
    }

    @Test fun rewriteResolvesSegmentAndKeyUrisAndFollowsRedirectsOverRelay() {
        val playlistWithDisc = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:10
            #EXTINF:6.0,
            bumper.ts
            #EXT-X-DISCONTINUITY
            #EXT-X-KEY:METHOD=NONE,URI="key.bin"
            #EXTINF:6.0,
            seg11.ts
        """.trimIndent()

        val headers = mapOf("Referer" to "https://player.example/")
        val rewritten = HlsProxy.rewrite(playlistWithDisc, "https://cdn.example/live/index.m3u8", headers)
        assertFalse("bumper.ts" in rewritten)
        assertTrue("http://127.0.0.1:" in rewritten)
        assertTrue("URI=\"http://127.0.0.1:" in rewritten)

        // End-to-end check through HlsProxy HTTP endpoint with 302 redirect + custom header
        var seenReferer: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/edge/start.m3u8") { ex ->
                seenReferer = ex.requestHeaders.getFirst("Referer")
                ex.responseHeaders.add("Location", "/origin/live.m3u8")
                ex.sendResponseHeaders(302, -1)
                ex.close()
            }
            createContext("/origin/live.m3u8") { ex ->
                val body = playlistWithDisc.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val wrapped = HlsProxy.wrap("http://127.0.0.1:${server.address.port}/edge/start.m3u8", headers, force = true)
            val con = URI(wrapped).toURL().openConnection() as HttpURLConnection
            assertEquals(200, con.responseCode)
            val relayed = con.inputStream.bufferedReader().readText()
            assertEquals("https://player.example/", seenReferer)
            assertFalse("bumper.ts" in relayed)
            assertTrue("http://127.0.0.1:" in relayed)
        } finally {
            server.stop(0)
        }
    }
}
