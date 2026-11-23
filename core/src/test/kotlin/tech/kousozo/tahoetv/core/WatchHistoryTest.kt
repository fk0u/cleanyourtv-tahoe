package tech.kousozo.tahoetv.core

import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchHistoryTest {
    private val utc = ZoneOffset.UTC
    private fun at(day: String, hour: Int) = Instant.parse("${day}T${"%02d".format(hour)}:00:00Z").toEpochMilli()

    @Test fun roundTrip() {
        val items = listOf(WatchHistory.Item(at("2026-11-23", 10), WatchHistory.PHONE, "https://a/x.m3u8"), WatchHistory.Item(at("2026-11-22", 9), WatchHistory.TV, "https://b/y.m3u8"))
        assertEquals(items, WatchHistory.parse(WatchHistory.serialize(items)))
    }

    @Test fun badLinesAreSkipped() = assertEquals(emptyList(), WatchHistory.parse("nope\n12\tfridge\turl\n\t\t"))

    @Test fun newestFirstAndSameChannelSameDayOnce() {
        var h = emptyList<WatchHistory.Item>()
        h = WatchHistory.add(h, WatchHistory.Item(at("2026-11-23", 8), WatchHistory.PHONE, "u1"), utc)
        h = WatchHistory.add(h, WatchHistory.Item(at("2026-11-23", 9), WatchHistory.PHONE, "u2"), utc)
        h = WatchHistory.add(h, WatchHistory.Item(at("2026-11-23", 10), WatchHistory.PHONE, "u1"), utc) // u1 again, same day
        assertEquals(listOf("u1", "u2"), h.map { it.url })
        assertEquals(at("2026-11-23", 10), h.first().atMs)
    }

    @Test fun sameChannelOnAnotherDayOrDeviceIsKept() {
        var h = listOf(WatchHistory.Item(at("2026-11-22", 20), WatchHistory.PHONE, "u1"))
        h = WatchHistory.add(h, WatchHistory.Item(at("2026-11-23", 8), WatchHistory.PHONE, "u1"), utc)
        h = WatchHistory.add(h, WatchHistory.Item(at("2026-11-23", 9), WatchHistory.TV, "u1"), utc)
        assertEquals(3, h.size)
    }

    @Test fun cappedAtMax() {
        var h = emptyList<WatchHistory.Item>()
        for (i in 0 until WatchHistory.MAX + 20) h = WatchHistory.add(h, WatchHistory.Item(1_000L + i, WatchHistory.PHONE, "u$i"), utc)
        assertEquals(WatchHistory.MAX, h.size)
    }

    @Test fun groupsByRealDay() {
        val now = Instant.parse("2026-11-23T12:00:00Z")
        assertEquals(WatchHistory.Day.TODAY, WatchHistory.dayOf(WatchHistory.Item(at("2026-11-23", 1), "phone", "u"), now, utc))
        assertEquals(WatchHistory.Day.YESTERDAY, WatchHistory.dayOf(WatchHistory.Item(at("2026-11-22", 23), "phone", "u"), now, utc))
        assertEquals(WatchHistory.Day.EARLIER, WatchHistory.dayOf(WatchHistory.Item(at("2026-11-20", 12), "phone", "u"), now, utc))
    }
}
