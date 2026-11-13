package tech.kousozo.tahoetv.core

import java.io.StringReader
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EpgTest {
    private val xml = """
        <tv>
          <channel id="1"><display-name lang="ID">Metro TV HD</display-name></channel>
          <channel id="2"><display-name lang="ID">Trans 7</display-name></channel>
          <programme start="20261108120000 +0700" stop="20261108130000 +0700" channel="1"><title lang="ID">Metro Siang &amp; Berita</title><desc>Berita</desc></programme>
          <programme channel="1" start="20261108100000 +0700" stop="20261108120000 +0700">
            <title>Pagi</title>
          </programme>
          <programme start="20261108120000 +0700" stop="20261108130000 +0700" channel="9"><title>Orphan</title></programme>
          <programme start="20261108120000 +0700" stop="20261108130000 +0700" channel="1"><title>Metro Siang &amp; Berita</title></programme>
          <programme start="20261108120000 +0700" stop="20261108130000 +0700" channel="2"><title>Jadwal belum tersedia</title></programme>
        </tv>
    """.trimIndent()

    @Test fun matchesByNormalisedNameAndSorts() {
        val epg = Epg.parse(xml)
        val all = epg.schedule("METRO TV", Instant.parse("2026-11-08T00:00:00Z"))
        assertEquals(listOf("Pagi", "Metro Siang & Berita"), all.map { it.title })
        assertEquals("Berita", all[1].desc)
        assertTrue(epg.schedule("MetroTV (1080p)", Instant.parse("2026-11-08T05:30:00Z")).first().title == "Metro Siang & Berita") // 12:30 WIB
        assertTrue(epg.schedule("Unknown").isEmpty())
        assertTrue(epg.schedule("Trans 7", Instant.parse("2026-11-08T00:00:00Z")).isEmpty()) // placeholder only → unknown, so other guides can fill in
    }

    @Test fun filtersByPlaylistChannels() {
        val filtered = Epg.parse(xml, channelFilter = setOf("Trans 7"))
        assertTrue(filtered.schedule("METRO TV", Instant.parse("2026-11-08T00:00:00Z")).isEmpty())
    }

    @Test fun filtersByTimeWindow() {
        // window: 2026-11-08T04:30:00Z (11:30 WIB) to 2026-11-08T06:30:00Z (13:30 WIB)
        // "Pagi" (10:00-12:00 WIB, stops 05:00Z) should be kept because stop > windowStart
        // But if windowStart is 05:30Z (12:30 WIB), "Pagi" ends at 05:00Z and is dropped
        val windowStart = Instant.parse("2026-11-08T05:00:00Z")
        val windowEnd = Instant.parse("2026-11-08T07:00:00Z")
        val epg = Epg.parse(xml, windowStart = windowStart, windowEnd = windowEnd)
        val metro = epg.schedule("METRO TV", Instant.parse("2026-11-08T00:00:00Z"))
        assertEquals(listOf("Metro Siang & Berita"), metro.map { it.title })
    }

    @Test fun truncatesLongDescriptions() {
        val longDesc = "A".repeat(300)
        val xmlWithLongDesc = """
            <tv>
              <channel id="1"><display-name>Metro TV</display-name></channel>
              <programme start="20261108100000 +0700" stop="20261108120000 +0700" channel="1">
                <title>Show</title>
                <desc>$longDesc</desc>
              </programme>
            </tv>
        """.trimIndent()
        val epg = Epg.parse(xmlWithLongDesc)
        val p = epg.schedule("Metro TV", Instant.parse("2026-11-08T00:00:00Z")).first()
        assertEquals(160, p.desc?.length)
    }

    @Test fun handlesLargeGuideStreamWithChannelFilter() {
        val sb = StringBuilder()
        sb.append("<tv>\n")
        for (i in 1..200) {
            sb.append("""  <channel id="ch$i"><display-name>Channel $i</display-name></channel>""").append("\n")
        }
        for (i in 1..200) {
            for (j in 1..10) {
                sb.append("""  <programme start="20261108100000 +0700" stop="20261108120000 +0700" channel="ch$i"><title>Show $i-$j</title></programme>""").append("\n")
            }
        }
        sb.append("</tv>")

        // Filter to only Channel 5
        val epg = Epg.parse(StringReader(sb.toString()), channelFilter = setOf("Channel 5"))
        assertEquals(1, epg.size)
        val shows = epg.schedule("Channel 5", Instant.parse("2026-11-08T00:00:00Z"))
        assertEquals(1, shows.size) // duplicate time deduplicated to 1
        assertEquals("Show 5-1", shows.first().title)
        assertTrue(epg.schedule("Channel 1", Instant.parse("2026-11-08T00:00:00Z")).isEmpty())
    }
}
