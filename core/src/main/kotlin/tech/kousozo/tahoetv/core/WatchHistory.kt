package tech.kousozo.tahoetv.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What was really watched, when, and on which device — the data behind the phone's "Riwayat tontonan". One line per start:
 *  `epochMillis TAB device TAB url`. Newest first, at most [MAX] lines, the same channel is listed once per day. */
object WatchHistory {
    const val MAX = 200
    const val PHONE = "phone"
    const val TV = "tv"

    data class Item(val atMs: Long, val device: String, val url: String)

    @JvmStatic fun parse(text: String): List<Item> = text.lineSequence().mapNotNull { l ->
        val p = l.split('\t')
        val ts = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        val dev = p.getOrNull(1)?.takeIf { it == PHONE || it == TV } ?: return@mapNotNull null
        val url = p.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        Item(ts, dev, url)
    }.toList()

    @JvmStatic fun serialize(items: List<Item>): String = items.joinToString("\n") { "${it.atMs}\t${it.device}\t${it.url}" }

    /** [item] first; an older line for the same channel on the same device and day is dropped (watching it twice today is one entry). */
    @JvmStatic fun add(existing: List<Item>, item: Item, zone: ZoneId = ZoneId.systemDefault()): List<Item> {
        fun day(i: Item) = Instant.ofEpochMilli(i.atMs).atZone(zone).toLocalDate()
        val d = day(item)
        return (listOf(item) + existing.filterNot { it.url == item.url && it.device == item.device && day(it) == d })
            .sortedByDescending { it.atMs }.take(MAX)
    }

    enum class Day { TODAY, YESTERDAY, EARLIER }

    @JvmStatic fun dayOf(item: Item, now: Instant, zone: ZoneId = ZoneId.systemDefault()): Day {
        val d = Instant.ofEpochMilli(item.atMs).atZone(zone).toLocalDate()
        val today: LocalDate = now.atZone(zone).toLocalDate()
        return when {
            d == today -> Day.TODAY
            d == today.minusDays(1) -> Day.YESTERDAY
            else -> Day.EARLIER
        }
    }
}
