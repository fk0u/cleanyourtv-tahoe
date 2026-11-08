package tech.kousozo.tahoetv.core

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
}
