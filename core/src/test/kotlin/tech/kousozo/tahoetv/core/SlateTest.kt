package tech.kousozo.tahoetv.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlateTest {
    @Test fun ordersLanguagesWithViewerFirst() {
        assertEquals(listOf("id", "en", "zh"), Slate.order("id"))
        assertEquals(listOf("en", "id", "zh"), Slate.order("en"))
        assertEquals(listOf("zh", "id", "en"), Slate.order("zh"))
        assertEquals(listOf("en", "id", "zh"), Slate.order("other")) // unknown language falls back to English first
    }

    @Test fun localisesReportTitleAndUrl() {
        assertEquals("[Saluran] RCTI", Slate.reportTitle("id", "RCTI"))
        assertEquals("[Channel] BBC One", Slate.reportTitle("en", "BBC One"))
        assertEquals("[频道] CCTV-1", Slate.reportTitle("zh", "CCTV-1"))
        assertTrue(Slate.reportUrl("RCTI", "id").contains("%5BSaluran%5D+RCTI"))
    }

    @Test fun retryStateMachineCountsDownAndGivesUp() {
        val state = Slate.RetryState(maxRetries = 2, retrySeconds = 2)
        assertEquals(0, state.retries)
        assertEquals(2, state.secondsLeft)
        assertFalse(state.isWaiting)

        // tick 1s
        assertFalse(state.tick())
        assertEquals(1, state.secondsLeft)

        // tick 2s -> retry 1 triggers
        assertTrue(state.tick())
        assertEquals(1, state.retries)
        assertEquals(2, state.secondsLeft)
        assertFalse(state.isWaiting)

        // tick 3s
        assertFalse(state.tick())
        assertEquals(1, state.secondsLeft)

        // tick 4s -> max retries reached -> WAITING
        assertFalse(state.tick())
        assertEquals(2, state.retries)
        assertTrue(state.isWaiting)
        assertEquals(0, state.secondsLeft)

        // Further ticks do nothing
        assertFalse(state.tick())

        // User triggers retryNow
        state.retryNow()
        assertEquals(0, state.retries)
        assertEquals(2, state.secondsLeft)
        assertFalse(state.isWaiting)
    }
}
