package tech.kousozo.tahoetv.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WhatsNewTest {
    @Test fun freshInstallRecordsSilently() {
        assertEquals(WhatsNew.Decision.RECORD_ONLY, WhatsNew.decide(null, "3.6.0"))
        assertEquals(WhatsNew.Decision.RECORD_ONLY, WhatsNew.decide("", "3.6.0"))
    }

    @Test fun showsOnceAfterAnUpdate() {
        assertEquals(WhatsNew.Decision.SHOW, WhatsNew.decide("3.5.0", "3.6.0"))
        assertEquals(WhatsNew.Decision.SHOW, WhatsNew.decide("3.6.0-beta.1", "3.6.0")) // beta to stable counts
        assertEquals(WhatsNew.Decision.NOTHING, WhatsNew.decide("3.6.0", "3.6.0"))     // already shown
    }

    @Test fun downgradeShowsNothing() {
        assertEquals(WhatsNew.Decision.NOTHING, WhatsNew.decide("3.6.0", "3.5.0"))
    }

    @Test fun notesOnlyForTheInstalledVersion() {
        assertTrue(WhatsNew.notesApply("v3.6.0".removePrefix("v"), "3.6.0"))
        assertFalse(WhatsNew.notesApply("3.7.0", "3.6.0")) // a newer release isn't what was just installed
    }

    @Test fun markdownBecomesPlainText() {
        assertEquals("Tahoe TV v3.7.0\n•  Fast\n•  Safe", WhatsNew.plainNotes("## Tahoe TV **v3.7.0**\n- Fast\n* Safe\n"))
    }
}
