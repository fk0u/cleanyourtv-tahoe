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
            assertTrue(Theme.contrast(p.warm, p.bg) >= 3.0, "warm dark=$dark hc=$hc")
            // secondary text must stay readable for older viewers (WCAG AA body text)
            if (!hc) assertTrue(Theme.contrast(blend(p.dim, p.bg), p.bg) >= 4.5, "dim dark=$dark")
        }
    }

    /** Alpha-composite [fg] over opaque [bg]. */
    private fun blend(fg: Int, bg: Int): Int {
        val a = (fg ushr 24) / 255.0
        fun ch(s: Int) = ((fg shr s and 0xFF) * a + (bg shr s and 0xFF) * (1 - a)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    @Test fun motionCurvesAreEaseOutNotEaseIn() {
        assertTrue(Brand.EASE_OUT[1] >= 0.9f)          // fast start
        assertEquals(4, Brand.EASE_DRAWER.size)
        assertTrue(Brand.MOTION_FAST <= 250 && Brand.MOTION_MED <= 300)
    }
}
