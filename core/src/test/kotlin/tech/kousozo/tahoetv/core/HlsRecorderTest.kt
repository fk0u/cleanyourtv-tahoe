package tech.kousozo.tahoetv.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HlsRecorderTest {
    private fun tmp() = File.createTempFile("rec", ".ts").also { it.delete(); it.deleteOnExit() }

    /** A live window of 3 segments that moves by one on every playlist request, then ends. Segment names carry the
     *  variant they came from ("high-seg0.ts"), so picking the wrong variant shows. */
    private fun live(total: Int, map: Boolean = false): (String) -> Pair<String, String> {
        var first = 0
        return { u ->
            when {
                u.endsWith("master.m3u8") -> "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2400000\nhigh.m3u8\n" to u
                else -> {
                    val v = u.substringAfterLast('/').removeSuffix(".m3u8")
                    val segs = (first until minOf(first + 3, total)).joinToString("") { "#EXTINF:2,\n$v-seg$it.ts\n" }
                    val end = if (first + 3 >= total) "#EXT-X-ENDLIST\n" else ""
                    first++
                    ("#EXTM3U\n#EXT-X-TARGETDURATION:2\n" + (if (map) "#EXT-X-MAP:URI=\"init.mp4\"\n" else "") + segs + end) to u
                }
            }
        }
    }

    /** Writes the segment's file name as its content. */
    private fun named(log: MutableList<String>) = { u: String, _: String?, o: java.io.OutputStream ->
        val n = u.substringAfterLast('/'); log += n; o.write(n.toByteArray()); n.length.toLong()
    }

    @Test fun followsTheBestVariantAndWritesEachSegmentOnce() {
        val f = tmp(); val fetched = mutableListOf<String>()
        val r = HlsRecorder("https://cdn.example/live/master.m3u8", emptyMap(), f, Long.MAX_VALUE, sleep = {}, get = live(6), download = named(fetched))
        assertTrue(r.run())
        assertEquals((0 until 6).map { "high-seg$it.ts" }, fetched)       // the 2.4 Mb/s variant, every piece once, in order
        assertEquals((0 until 6).joinToString("") { "high-seg$it.ts" }, f.readText())
        assertEquals(6, r.segments)
    }

    @Test fun fmp4InitSectionWrittenFirst() {
        val f = tmp(); val fetched = mutableListOf<String>()
        assertTrue(HlsRecorder("https://cdn.example/live/high.m3u8", emptyMap(), f, Long.MAX_VALUE, sleep = {}, get = live(4, map = true),
            download = named(fetched)).run())
        assertEquals("init.mp4" + (0 until 4).joinToString("") { "high-seg$it.ts" }, f.readText())
    }

    @Test fun changedInitSectionIsWrittenAgain() {
        val f = tmp()
        val pl = "#EXTM3U\n#EXT-X-MAP:URI=\"a.mp4\"\n#EXTINF:2,\n1.m4s\n#EXTINF:2,\n2.m4s\n#EXT-X-MAP:URI=\"b.mp4\"\n#EXTINF:2,\n3.m4s\n#EXT-X-ENDLIST\n"
        assertTrue(HlsRecorder("https://x/p.m3u8", emptyMap(), f, Long.MAX_VALUE, sleep = {}, get = { u -> pl to u }, download = named(mutableListOf())).run())
        assertEquals("a.mp41.m4s2.m4sb.mp43.m4s", f.readText())
    }

    @Test fun stopsAtTheEndTime() {
        var now = 0L
        // 2 s segments → a refresh every second; each refresh brings one new segment
        val r = HlsRecorder("https://cdn.example/live/high.m3u8", emptyMap(), tmp(), stopAt = 3500, clock = { now }, sleep = { now += it },
            get = live(100), download = { _, _, _ -> 1 })
        assertTrue(r.run())
        assertEquals(6, r.segments) // 3 at 0 s, then one each at 1, 2 and 3 s; at 4 s it's over
        assertTrue(now in 3500..4500)
    }

    @Test fun startsAtTheLiveEdgeNotTheWholeWindow() {
        val fetched = mutableListOf<String>()
        var call = 0
        HlsRecorder("https://x/live.m3u8", emptyMap(), tmp(), Long.MAX_VALUE, sleep = {}, get = { u ->
            call++
            val first = if (call == 1) 0 else 1   // 10-segment window, then it moves by one and ends
            ("#EXTM3U\n" + (first until first + 10).joinToString("") { "#EXTINF:2,\nseg$it.ts\n" } + (if (call > 1) "#EXT-X-ENDLIST\n" else "")) to u
        }, download = named(fetched)).run()
        assertEquals(listOf("seg7.ts", "seg8.ts", "seg9.ts", "seg10.ts"), fetched)
    }

    @Test fun failedSegmentLeavesNothingAndIsRetried() {
        val f = tmp(); var fails = 1
        val pl = "#EXTM3U\n#EXTINF:2,\na.ts\n#EXTINF:2,\nb.ts\n"
        var calls = 0
        val r = HlsRecorder("https://x/p.m3u8", emptyMap(), f, Long.MAX_VALUE, sleep = {}, get = { u -> (if (++calls > 1) "$pl#EXT-X-ENDLIST\n" else pl) to u },
            download = { u, _, o ->
                val n = u.substringAfterLast('/')
                o.write("half".toByteArray())
                if (n == "a.ts" && fails-- > 0) error("reset")
                o.write(n.toByteArray()); 4L + n.length
            })
        assertTrue(r.run())
        assertEquals("halfb.tshalfa.ts", f.readText()) // the broken half of a.ts never reached the file; a.ts came on the retry
        assertEquals(2, r.segments)
    }

    @Test fun segmentThatKeepsFailingIsSkipped() {
        val r = HlsRecorder("https://x/p.m3u8", emptyMap(), tmp(), Long.MAX_VALUE, sleep = {},
            get = { u -> "#EXTM3U\n#EXTINF:2,\nbad.ts\n#EXTINF:2,\nok.ts\n#EXT-X-ENDLIST\n" to u },
            download = { u, _, _ -> if (u.endsWith("bad.ts")) error("404") else 1 })
        assertTrue(r.run())
        assertEquals(1, r.segments); assertEquals(1, r.skipped)
    }

    @Test fun byteRangesAreFetchedSeparately() {
        val got = mutableListOf<String?>()
        val pl = "#EXTM3U\n#EXT-X-BYTERANGE:100@0\n#EXTINF:2,\nall.ts\n#EXT-X-BYTERANGE:50\n#EXTINF:2,\nall.ts\n#EXT-X-ENDLIST\n"
        val r = HlsRecorder("https://x/p.m3u8", emptyMap(), tmp(), Long.MAX_VALUE, sleep = {}, get = { u -> pl to u }, download = { _, range, _ -> got += range; 1 })
        assertTrue(r.run())
        assertEquals(listOf<String?>("0-99", "100-149"), got)
    }

    @Test fun malformedLineIsSkipped() {
        val got = mutableListOf<String>()
        HlsRecorder("https://x/p.m3u8", emptyMap(), tmp(), Long.MAX_VALUE, sleep = {},
            get = { u -> "#EXTM3U\n#EXTINF:2,\nbad seg|^.ts\n#EXTINF:2,\nok.ts\n#EXT-X-ENDLIST\n" to u }, download = named(got)).run()
        assertEquals(listOf("ok.ts"), got)
    }

    @Test fun startsTheFileEmpty() {
        val f = tmp().apply { writeText("old partial") }
        HlsRecorder("https://x/p.m3u8", emptyMap(), f, Long.MAX_VALUE, sleep = {},
            get = { u -> "#EXTM3U\n#EXTINF:2,\nnew.ts\n#EXT-X-ENDLIST\n" to u }, download = named(mutableListOf())).run()
        assertEquals("new.ts", f.readText())
    }

    @Test fun refusesEncryptedStreams() {
        val r = HlsRecorder("https://x/a.m3u8", emptyMap(), tmp(), Long.MAX_VALUE, sleep = {},
            get = { u -> "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\n#EXTINF:2,\na.ts\n" to u }, download = { _, _, _ -> 1 })
        assertFalse(r.run())
        assertEquals("Siaran terenkripsi, tidak bisa direkam.", r.error)
    }
}
