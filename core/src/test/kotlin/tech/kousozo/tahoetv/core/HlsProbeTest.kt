package tech.kousozo.tahoetv.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HlsProbeTest {
    private val now = Instant.parse("2026-11-11T08:00:00Z")

    @Test fun endedPlaylistIsARecording() {
        val t = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\na.ts\n#EXTINF:6,\nb.ts\n#EXT-X-ENDLIST\n"
        assertEquals(HlsProbe.Kind.REPLAY, HlsProbe.analyze(t, now))
    }

    @Test fun vodPlaylistType() {
        assertEquals(HlsProbe.Kind.REPLAY, HlsProbe.analyze("#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXTINF:6,\na.ts\n", now))
    }

    @Test fun movingWindowWithoutStampsIsLive() {
        assertEquals(HlsProbe.Kind.LIVE, HlsProbe.analyze("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:900\n#EXTINF:6,\na.ts\n#EXTINF:6,\nb.ts\n", now))
    }

    @Test fun freshStampIsLiveOldStampIsStale() {
        fun pl(stamp: String) = "#EXTM3U\n#EXT-X-PROGRAM-DATE-TIME:$stamp\n#EXTINF:6,\na.ts\n#EXTINF:6,\nb.ts\n"
        assertEquals(HlsProbe.Kind.LIVE, HlsProbe.analyze(pl("2026-11-11T07:59:40.000Z"), now))
        assertEquals(HlsProbe.Kind.STALE, HlsProbe.analyze(pl("2026-11-10T08:00:00.000Z"), now)) // yesterday's broadcast
        assertEquals(HlsProbe.Kind.LIVE, HlsProbe.analyze(pl("2026-11-11T14:59:40.000+0700"), now)) // offset without a colon
    }

    @Test fun masterPlaylistAndNonPlaylist() {
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2000000\nhigh/index.m3u8\n"
        assertEquals(HlsProbe.Kind.UNKNOWN, HlsProbe.analyze(master, now))
        assertEquals("https://cdn.example/live/low/index.m3u8", HlsProbe.firstVariant(master, "https://cdn.example/live/master.m3u8"))
        assertNull(HlsProbe.firstVariant("#EXTM3U\n#EXTINF:6,\na.ts\n", "https://x/"))
        assertEquals(HlsProbe.Kind.UNKNOWN, HlsProbe.analyze("<html>403</html>", now))
    }

    /** CNBC/detik send gzip without being asked; the JDK doesn't undo it, so the playlist used to come out binary. */
    @Test fun fetchDecodesGzipThatWasNotAskedFor() {
        val playlist = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\na.ts\n"
        val gz = java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write(playlist.toByteArray()) } }.toByteArray()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/p.m3u8") { ex ->
                ex.responseHeaders.add("Content-Encoding", "gzip"); ex.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
                ex.sendResponseHeaders(200, gz.size.toLong()); ex.responseBody.use { it.write(gz) }
            }
            start()
        }
        try {
            val (text, _) = HlsProbe.fetch("http://127.0.0.1:${server.address.port}/p.m3u8", emptyMap(), 5000)
            assertEquals(playlist, text)
        } finally { server.stop(0) }
    }

    /** A local server: path → (status, body). */
    private fun serve(routes: Map<String, Pair<Int, String>>, block: (String) -> Unit) {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val (code, body) = routes[ex.requestURI.path] ?: (404 to "nope")
                val b = body.toByteArray(); ex.sendResponseHeaders(code, b.size.toLong()); ex.responseBody.use { it.write(b) }
            }
            start()
        }
        try { block("http://127.0.0.1:${server.address.port}") } finally { server.stop(0) }
    }

    private val media = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:10\n#EXTINF:6,\na.ts\n#EXTINF:6,\nb.ts\n"
    private val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080\nhi.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720\nmid.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlo.m3u8\n"

    /** The master playlist answered while its first variant gave 403: the channel looked LIVE but never played on desktop. */
    @Test fun deadFirstVariantFallsBackToALiveOne() = serve(mapOf("/m.m3u8" to (200 to master), "/hi.m3u8" to (403 to "denied"), "/mid.m3u8" to (200 to media))) { base ->
        val live = HlsProbe.liveVariant(master, "$base/m.m3u8", emptyMap(), 3000)
        assertEquals("$base/mid.m3u8", live?.first?.url)
        assertEquals(HlsProbe.Kind.LIVE, HlsProbe.probe("$base/m.m3u8", emptyMap(), 3000))
    }

    @Test fun allVariantsDeadOrHtmlIsDead() {
        serve(mapOf("/m.m3u8" to (200 to master))) { base -> assertEquals(HlsProbe.Kind.DEAD, HlsProbe.probe("$base/m.m3u8", emptyMap(), 3000)) }
        serve(mapOf("/w.m3u8" to (200 to "<html><title>Wowza Streaming Engine</title></html>"))) { base ->
            assertEquals(HlsProbe.Kind.DEAD, HlsProbe.probe("$base/w.m3u8", emptyMap(), 3000))
        }
    }

    @Test fun preferredOrderFollowsTheQualityCap() {
        val v = HlsProbe.variants(master, "https://x/m.m3u8")
        assertEquals(listOf("hi", "mid", "lo"), HlsProbe.preferred(v, 0).map { it.url.substringAfterLast('/').substringBefore('.') })
        assertEquals(listOf("mid", "lo", "hi"), HlsProbe.preferred(v, 720).map { it.url.substringAfterLast('/').substringBefore('.') })
    }

    @Test fun deadVariantsAreDroppedFromTheMaster() = serve(mapOf("/hi.m3u8" to (403 to "denied"), "/mid.m3u8" to (200 to media), "/lo.m3u8" to (200 to media))) { base ->
        val out = HlsProbe.dropDeadVariants(master, "$base/m.m3u8", emptyMap(), 3000)!!
        assertEquals(listOf("mid.m3u8", "lo.m3u8"), out.lines().filter { it.endsWith(".m3u8") })
        assertEquals(null, HlsProbe.dropDeadVariants(master, "$base/none/m.m3u8", emptyMap(), 3000))
        assertEquals(media, HlsProbe.dropDeadVariants(media, base, emptyMap(), 3000))
    }
}
