package tech.kousozo.tahoetv.core

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class StreamSelectorTest {

    @Test
    fun fuzzyScorePrefersFastHighResAbrOverSlowLowRes() {
        val fast1080 = StreamSelector.fuzzyScore(
            latencyMs = 120,
            height = 1080,
            bandwidthBps = 5_000_000,
            hasAbr = true
        )
        val slow360 = StreamSelector.fuzzyScore(
            latencyMs = 1800,
            height = 360,
            bandwidthBps = 450_000,
            hasAbr = false
        )
        assertTrue("Expected fast 1080p ABR ($fast1080) > slow 360p ($slow360)", fast1080 > slow360 + 25.0)
    }

    @Test
    fun rankOnOpenSelectsBestFinalistAcrossMockServers() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            val port = server.address.port
            val mediaPlaylist = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4.0,\nseg0.ts\n#EXTINF:4.0,\nseg1.ts\n"
            server.createContext("/seg/live.m3u8") { ex ->
                val b = mediaPlaylist.toByteArray()
                ex.sendResponseHeaders(200, b.size.toLong())
                ex.responseBody.use { it.write(b) }
            }
            server.createContext("/dead.m3u8") { ex ->
                ex.sendResponseHeaders(404, -1)
                ex.close()
            }
            server.createContext("/slow360.m3u8") { ex ->
                Thread.sleep(260)
                val body = """
                    #EXTM3U
                    #EXT-X-STREAM-INF:BANDWIDTH=450000,RESOLUTION=640x360
                    http://127.0.0.1:$port/seg/live.m3u8
                """.trimIndent().toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            server.createContext("/fast1080.m3u8") { ex ->
                val body = """
                    #EXTM3U
                    #EXT-X-STREAM-INF:BANDWIDTH=5200000,RESOLUTION=1920x1080
                    http://127.0.0.1:$port/seg/live.m3u8
                    #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720
                    http://127.0.0.1:$port/seg/live.m3u8
                """.trimIndent().toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            server.start()

            val dead = M3u.Channel(name = "Test", url = "http://127.0.0.1:$port/dead.m3u8", logo = "", group = "")
            val slow = M3u.Channel(name = "Test", url = "http://127.0.0.1:$port/slow360.m3u8", logo = "", group = "")
            val fast = M3u.Channel(name = "Test", url = "http://127.0.0.1:$port/fast1080.m3u8", logo = "", group = "")

            val ranked = StreamSelector.rankOnOpen(listOf(dead, slow, fast), timeoutMs = 2500)
            assertEquals(3, ranked.size)
            assertEquals(fast.url, ranked[0].url)
            assertEquals(slow.url, ranked[1].url)
            assertEquals(dead.url, ranked[2].url)

            val stFast = SourceHealth.status(fast.url)!!
            val stSlow = SourceHealth.status(slow.url)!!
            assertTrue(stFast.live)
            assertEquals(1080, stFast.height)
            assertTrue(stFast.score > stSlow.score)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun fuzzySimilarityScoresRelatedChannelNamesHigher() {
        assertEquals(1.0, StreamSelector.fuzzySimilarity("RCTI", "rcti"), 0.001)
        val sim = StreamSelector.fuzzySimilarity("RCTI HD", "RCTI")
        val diff = StreamSelector.fuzzySimilarity("RCTI HD", "CNN International")
        assertTrue("Expected $sim > $diff", sim > diff)
        assertTrue(sim > 0.5)
    }

    @Test
    fun sourceHealthOrderRanksLiveByScoreDescending() {
        val u1 = "http://example.invalid/low-score.m3u8"
        val u2 = "http://example.invalid/high-score.m3u8"
        val u3 = "http://example.invalid/dead.m3u8"
        SourceHealth.reportMetrics(u1, StreamSelector.Metrics(live = true, latencyMs = 900, height = 480, bandwidthBps = 800_000, hasAbr = false, score = 55.0))
        SourceHealth.reportMetrics(u2, StreamSelector.Metrics(live = true, latencyMs = 150, height = 1080, bandwidthBps = 5_000_000, hasAbr = true, score = 92.0))
        SourceHealth.reportMetrics(u3, StreamSelector.Metrics(live = false, latencyMs = 120))

        val ordered = SourceHealth.order(
            listOf(
                M3u.Channel(name = "C", url = u1, logo = "", group = ""),
                M3u.Channel(name = "C", url = u3, logo = "", group = ""),
                M3u.Channel(name = "C", url = u2, logo = "", group = ""),
            )
        )
        assertEquals(listOf(u2, u1, u3), ordered.map { it.url })
    }
}
