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

    @Test fun headlineIsInTheViewersLanguageAndVaries() {
        for (l in listOf("en", "id", "zh", "ar")) {
            val all = (0 until 4).map { Slate.headline(l, it) }
            assertEquals(4, all.toSet().size, l)
            assertEquals(all[0], Slate.headline(l, 4)) // wraps around
        }
        assertTrue(Slate.headline("id", 0).contains("Siaran"))
        assertEquals(Slate.headline("en", 1), Slate.headline("fr", 1)) // unknown language: English
        assertTrue(Slate.follow("id").contains("TahoeTV OFFICIAL"))
        assertEquals("حاول مجددًا", Slate.label("ar", "retry"))
        assertTrue(Slate.reason("ar", Slate.Reason.Offline).isNotBlank())
    }

    @Test fun toneIsQuietShortAndAValidWav() {
        val p = SlateTone.pcm()
        assertTrue(p.size in SlateTone.RATE / 2..SlateTone.RATE * 2)
        val peak = p.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue(peak in 8_000..18_000, "peak $peak")            // audible but never loud
        assertTrue(p.last().toInt() == 0 || kotlin.math.abs(p.last().toInt()) < 200) // ends silent: no click
        assertTrue(SlateTone.pcm().contentEquals(p))
        val w = SlateTone.wav()
        assertEquals("RIFF", String(w, 0, 4)); assertEquals("WAVE", String(w, 8, 4)); assertEquals(44 + p.size * 2, w.size)
    }

    @Test fun whatsappChannelLink() {
        assertTrue(Brand.WHATSAPP_CHANNEL.startsWith("https://whatsapp.com/channel/"))
    }
}
