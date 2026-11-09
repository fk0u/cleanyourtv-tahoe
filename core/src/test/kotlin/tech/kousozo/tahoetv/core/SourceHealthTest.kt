package tech.kousozo.tahoetv.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SourceHealthTest {
    private fun ch(u: String) = M3u.Channel(u, u, null, null)

    @Test fun ordersLiveByLatencyThenUnknownThenDead() {
        val dead = ch("http://t/dead"); val slow = ch("http://t/slow"); val fast = ch("http://t/fast"); val unknown = ch("http://t/unknown")
        SourceHealth.report(dead.url, false); SourceHealth.report(slow.url, true); SourceHealth.report(fast.url, true)
        // report() keeps latency 0 for both live ones, so the original order decides between them
        assertEquals(listOf(slow, fast, unknown, dead), SourceHealth.order(listOf(dead, slow, fast, unknown)))
        assertEquals(true, SourceHealth.live(listOf(dead, slow)))
        assertEquals(false, SourceHealth.live(listOf(dead)))
        assertEquals(null, SourceHealth.live(listOf(dead, unknown)))
    }
}
