package tech.kousozo.tahoetv.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HlsRecorderTest {
    private fun tmp() = File.createTempFile("rec", ".ts").also { it.delete(); it.deleteOnExit() }

    /** A live window of 3 segments that moves by one on every playlist request, then ends. */
    private fun live(total: Int, map: Boolean = false): (String) -> Pair<String, String> {
        var first = 0
        return { u ->
            when {
                u.endsWith("master.m3u8") -> "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2400000\nhigh.m3u8\n" to u
                else -> {
                    val segs = (first until minOf(first + 3, total)).joinToString("") { "#EXTINF:2,\nseg$it.ts\n" }
                    val end = if (first + 3 >= total) "#EXT-X-ENDLIST\n" else ""
                    first++
                    ("#EXTM3U\n#EXT-X-TARGETDURATION:2\n" + (if (map) "#EXT-X-MAP:URI=\"init.mp4\"\n" else "") + segs + end) to u
                }
            }
        }
    }

    @Test fun followsTheBestVariantAndWritesEachSegmentOnce() {
        val f = tmp(); val fetched = mutableListOf<String>()
        val r = HlsRecorder("https://cdn.example/live/master.m3u8", emptyMap(), f, Long.MAX_VALUE, sleep = {}, get = live(6),
            download = { u, o -> fetched += u.substringAfterLast('/'); val b = u.substringAfterLast('/').toByteArray(); o.write(b); b.size.toLong() })
        assertTrue(r.run())
        assertEquals((0 until 6).map { "seg$it.ts" }, fetched)            // every piece once, in order
        assertEquals((0 until 6).joinToString("") { "seg$it.ts" }, f.readText())
        assertEquals(6, r.segments)
    }

    @Test fun fmp4InitSectionWrittenOnceFirst() {
        val f = tmp(); val fetched = mutableListOf<String>()
        HlsRecorder("https://cdn.example/live/high.m3u8", emptyMap(), f, Long.MAX_VALUE, sleep = {}, get = live(4, map = true),
            download = { u, _ -> fetched += u.substringAfterLast('/'); 1 }).run()
        assertEquals(listOf("init.mp4", "seg0.ts", "seg1.ts", "seg2.ts", "seg3.ts"), fetched)
    }

    @Test fun stopsAtTheEndTime() {
        var now = 0L
        val r = HlsRecorder("https://cdn.example/live/high.m3u8", emptyMap(), tmp(), stopAt = 3, clock = { now }, sleep = { now++ },
            get = live(100), download = { _, _ -> 1 })
        assertTrue(r.run())
        assertTrue(r.segments in 3..6, "recorded ${r.segments}")
    }

    @Test fun startsAtTheLiveEdgeNotTheWholeWindow() {
        val fetched = mutableListOf<String>()
        var call = 0
        HlsRecorder("https://x/live.m3u8", emptyMap(), tmp(), Long.MAX_VALUE, sleep = {}, get = { u ->
            call++
            val first = if (call == 1) 0 else 1   // 10-segment window, then it moves by one and ends
            ("#EXTM3U\n" + (first until first + 10).joinToString("") { "#EXTINF:2,\nseg$it.ts\n" } + (if (call > 1) "#EXT-X-ENDLIST\n" else "")) to u
        }, download = { u, _ -> fetched += u.substringAfterLast('/'); 1 }).run()
        assertEquals(listOf("seg7.ts", "seg8.ts", "seg9.ts", "seg10.ts"), fetched)
    }

    @Test fun refusesEncryptedStreams() {
        val r = HlsRecorder("https://x/a.m3u8", emptyMap(), tmp(), Long.MAX_VALUE, sleep = {},
            get = { u -> "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:2,\na.ts\n" to u }, download = { _, _ -> 1 })
        assertFalse(r.run())
        assertEquals("Siaran terenkripsi, tidak bisa direkam.", r.error)
    }
}
