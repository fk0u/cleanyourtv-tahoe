package tech.kousozo.tahoetv.core

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SourceHealthTest {
    private fun ch(u: String) = M3u.Channel(u, u, null, null)

    @AfterTest fun tearDown() {
        SourceHealth.resetForTest()
    }

    @Test fun ordersLiveByLatencyThenUnknownThenDead() {
        SourceHealth.resetForTest()
        val dead = ch("http://t/dead"); val slow = ch("http://t/slow"); val fast = ch("http://t/fast"); val unknown = ch("http://t/unknown")
        SourceHealth.report(dead.url, false); SourceHealth.report(slow.url, true); SourceHealth.report(fast.url, true)
        // report() keeps latency 0 for both live ones, so the original order decides between them
        assertEquals(listOf(slow, fast, unknown, dead), SourceHealth.order(listOf(dead, slow, fast, unknown)))
        assertEquals(true, SourceHealth.live(listOf(dead, slow)))
        assertEquals(false, SourceHealth.live(listOf(dead)))
        assertEquals(null, SourceHealth.live(listOf(dead, unknown)))
    }

    @Test fun skipsMalformedLinesAndRestoresValidEntries() {
        val dir = File(System.getProperty("java.io.tmpdir"), "sh-malformed-${System.nanoTime()}").apply { mkdirs() }
        val cache = File(dir, "health.tsv")
        try {
            val now = System.currentTimeMillis()
            cache.writeText(
                listOf(
                    "corrupt",
                    "http://first/live.m3u8\t1\t42\t$now",
                    "http://bad\t1\tnan\t100",
                    "",
                    "http://second/dead.m3u8\t0\t120\t$now",
                ).joinToString("\n")
            )
            SourceHealth.resetForTest()
            SourceHealth.init(cache)
            val first = SourceHealth.status("http://first/live.m3u8")
            val second = SourceHealth.status("http://second/dead.m3u8")
            assertNotNull(first)
            assertTrue(first.live)
            assertEquals(42L, first.ms)
            assertNotNull(second)
            assertEquals(false, second.live)
            assertEquals(null, SourceHealth.status("http://bad"))
        } finally {
            SourceHealth.resetForTest()
            dir.deleteRecursively()
        }
    }

    @Test fun concurrentSavesProduceValidCacheFile() {
        val dir = File(System.getProperty("java.io.tmpdir"), "sh-conc-${System.nanoTime()}").apply { mkdirs() }
        val cache = File(dir, "health.tsv")
        val pool = Executors.newFixedThreadPool(16)
        try {
            SourceHealth.resetForTest()
            SourceHealth.init(cache)
            val start = CountDownLatch(1)
            val done = CountDownLatch(16)
            for (t in 0 until 16) {
                pool.execute {
                    start.await()
                    try {
                        for (i in 0 until 20) {
                            SourceHealth.report("http://stream/$t/$i.m3u8", i % 2 == 0)
                            if (i % 5 == 0) SourceHealth.save()
                        }
                        SourceHealth.save()
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue(done.await(15, TimeUnit.SECONDS))
            SourceHealth.resetForTest()
            SourceHealth.init(cache)
            for (t in 0 until 16) {
                for (i in 0 until 20) {
                    val st = SourceHealth.status("http://stream/$t/$i.m3u8")
                    assertNotNull(st, "Missing status for $t/$i after concurrent saves")
                    assertEquals(i % 2 == 0, st.live)
                }
            }
        } finally {
            pool.shutdownNow()
            SourceHealth.resetForTest()
            dir.deleteRecursively()
        }
    }
}
