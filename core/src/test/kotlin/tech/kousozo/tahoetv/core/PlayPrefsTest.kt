package tech.kousozo.tahoetv.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayPrefsTest {
    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=6000000,RESOLUTION=1920x1080
        v1080.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=3000000,RESOLUTION=1280x720
        v720.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=1200000,RESOLUTION=854x480
        v480.m3u8
    """.trimIndent()

    @Test fun capKeepsVariantsAtOrUnder() {
        val out = PlayPrefs.capVariants(master, 720)
        assertFalse("v1080" in out)
        assertTrue("v720" in out && "v480" in out)
        assertFalse(out.contains("RESOLUTION=1920x1080"))
    }

    @Test fun capBelowEverythingKeepsTheSmallest() {
        val out = PlayPrefs.capVariants(master.replace("854x480", "960x540").replace("v480", "v540"), 480)
        assertEquals(listOf("v540.m3u8"), out.lines().filter { it.endsWith(".m3u8") })
    }

    @Test fun autoAndMediaPlaylistsUnchanged() {
        assertEquals(master, PlayPrefs.capVariants(master, 0))
        val media = "#EXTM3U\n#EXTINF:6,\na.ts\n"
        assertEquals(media, PlayPrefs.capVariants(media, 720))
    }

    @Test fun optionsRoundTripAndCycle() {
        assertEquals(PlayPrefs.Quality.P720, PlayPrefs.Quality.of("720"))
        assertEquals(PlayPrefs.Quality.Auto, PlayPrefs.Quality.of(null))
        assertEquals(PlayPrefs.Latency.Standard, PlayPrefs.Latency.of("??"))
        assertEquals(PlayPrefs.Quality.P480, PlayPrefs.next(PlayPrefs.Quality.Auto, -1))
        assertEquals(PlayPrefs.Latency.Low, PlayPrefs.next(PlayPrefs.Latency.Stable, 1))
    }
}
