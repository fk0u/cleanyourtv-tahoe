package tech.kousozo.tahoetv.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThemeTest {
    @Test fun modesResolve() {
        assertEquals(Theme.Mode.DARK, Theme.Mode.of(null))
        assertEquals(Theme.Mode.LIGHT, Theme.Mode.of("light"))
        assertTrue(Theme.isDark(Theme.Mode.SYSTEM, true))
        assertTrue(!Theme.isDark(Theme.Mode.LIGHT, true))
    }

    @Test fun textIsReadableInEveryPalette() {
        for (dark in listOf(true, false)) for (hc in listOf(true, false)) {
            val p = Theme.palette(dark, hc)
            val min = if (hc) 7.0 else 4.5
            assertTrue(Theme.contrast(p.text, p.bg) >= min, "text dark=$dark hc=$hc")
            assertTrue(Theme.contrast(p.accent, p.bg) >= 3.0, "accent dark=$dark hc=$hc")
        }
    }
}
