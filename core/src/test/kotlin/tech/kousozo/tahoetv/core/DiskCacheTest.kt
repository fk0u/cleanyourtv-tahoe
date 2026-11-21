package tech.kousozo.tahoetv.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiskCacheTest {
    private fun cache(max: Long) = DiskCache(Files.createTempDirectory("dc").toFile(), max)

    @Test fun roundTrip() {
        val c = cache(1000)
        assertNull(c.get("a"))
        c.put("a", byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), c.get("a"))
    }

    @Test fun evictsLeastRecentlyUsedFirst() {
        val c = cache(250)
        c.put("old", ByteArray(100)); Thread.sleep(20)
        c.put("mid", ByteArray(100)); Thread.sleep(20)
        c.get("old"); Thread.sleep(20) // refreshed: now "mid" is the oldest
        c.put("new", ByteArray(100))
        assertNull(c.get("mid"))
        assertTrue(c.get("old") != null && c.get("new") != null)
        assertTrue(c.sizeBytes() <= 250)
    }

    @Test fun ignoresEmptyAndOversized() {
        val c = cache(10)
        c.put("e", ByteArray(0)); c.put("big", ByteArray(11))
        assertEquals(0, c.sizeBytes())
    }
}
