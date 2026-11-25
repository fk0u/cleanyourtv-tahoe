package tech.kousozo.tahoetv.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MonogramTest {
    @Test fun channelsOfOneBroadcasterDiffer() {
        val set = listOf("TVRI", "TVRI Sport", "TVRI World", "TVRI Aceh").map { Monogram.of(it) }
        assertEquals(set.size, set.toSet().size, "$set")
        assertEquals("TVR", Monogram.of("TVRI"))
        assertEquals("TS", Monogram.of("TVRI Sport"))
    }
    @Test fun otherShapes() {
        assertEquals("IC", Monogram.of("The Indonesia Channel"))
        assertEquals("CN", Monogram.of("Cartoon Network"))
        assertEquals("MN+", Monogram.of("MN+"))
        assertEquals("GAR", Monogram.of("Garuda"))
        assertEquals("", Monogram.of(null)); assertEquals("", Monogram.of("  "))
    }
}
