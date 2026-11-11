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
}
