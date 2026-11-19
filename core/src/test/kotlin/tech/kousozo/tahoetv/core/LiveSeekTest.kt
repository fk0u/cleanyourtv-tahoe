package tech.kousozo.tahoetv.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveSeekTest {
    @Test fun liveEdgeRespectsThresholdAndNonLive() {
        assertTrue(LiveSeek.isAtLiveEdge(isLive = false, liveOffsetMs = 120_000L))
        assertTrue(LiveSeek.isAtLiveEdge(isLive = true, liveOffsetMs = -1L))
        assertTrue(LiveSeek.isAtLiveEdge(isLive = true, liveOffsetMs = 0L))
        assertTrue(LiveSeek.isAtLiveEdge(isLive = true, liveOffsetMs = LiveSeek.LIVE_EDGE_THRESHOLD_MS))
        assertFalse(LiveSeek.isAtLiveEdge(isLive = true, liveOffsetMs = LiveSeek.LIVE_EDGE_THRESHOLD_MS + 1L))
    }

    @Test fun behindLiveCalculatesAndClamps() {
        assertEquals(0L, LiveSeek.behindLiveMs(0L, 1_000L))
        assertEquals(0L, LiveSeek.behindLiveMs(-5L, 1_000L))
        assertEquals(0L, LiveSeek.behindLiveMs(60_000L, -100L))
        assertEquals(0L, LiveSeek.behindLiveMs(60_000L, 90_000L))
        assertEquals(15_000L, LiveSeek.behindLiveMs(60_000L, 45_000L))
    }

    @Test fun formatBehindFormatsLiveMinutesAndHours() {
        assertEquals("LIVE", LiveSeek.formatBehind(-500L))
        assertEquals("LIVE", LiveSeek.formatBehind(0L))
        assertEquals("LIVE", LiveSeek.formatBehind(999L))
        assertEquals("-00:01", LiveSeek.formatBehind(1_000L))
        assertEquals("-02:05", LiveSeek.formatBehind(125_000L))
        assertEquals("-1:01:05", LiveSeek.formatBehind(3_665_000L))
    }

    @Test fun fractionAndPositionRoundTripAndClamp() {
        assertEquals(1f, LiveSeek.fractionFor(5_000L, 0L))
        assertEquals(0f, LiveSeek.fractionFor(-500L, 10_000L))
        assertEquals(0.5f, LiveSeek.fractionFor(5_000L, 10_000L))
        assertEquals(1f, LiveSeek.fractionFor(20_000L, 10_000L))

        assertEquals(0L, LiveSeek.positionFor(0.5f, 0L))
        assertEquals(0L, LiveSeek.positionFor(-0.3f, 10_000L))
        assertEquals(5_000L, LiveSeek.positionFor(0.5f, 10_000L))
        assertEquals(10_000L, LiveSeek.positionFor(1.4f, 10_000L))
    }
}
