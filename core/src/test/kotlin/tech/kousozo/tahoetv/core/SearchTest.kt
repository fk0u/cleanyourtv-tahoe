package tech.kousozo.tahoetv.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchTest {
    private val now = Instant.parse("2026-11-11T12:00:00Z")
    private val epg = Epg.parse("""
        <tv>
          <channel id="1"><display-name>Trans 7</display-name></channel>
          <channel id="2"><display-name>Indosiar</display-name></channel>
          <programme start="20261111110000 +0000" stop="20261111130000 +0000" channel="1"><title>Lapor Pak!</title></programme>
          <programme start="20261111180000 +0000" stop="20261111200000 +0000" channel="2"><title>Liga Champions: Persib</title></programme>
        </tv>
    """.trimIndent())
    private fun e(name: String, group: String = "Nasional") =
        LocalTv.classify(M3u.Channel(name, "https://x/$name.m3u8", null, group), emptyList())
    private val all = listOf(e("Trans7"), e("Indosiar"), e("Trans TV"), e("TVRI Sport", "Sports"))

    @Test fun nameFirstThenShows() {
        assertEquals(listOf("Trans7", "Trans TV"), Search.run(all, epg, "trans", now).map { it.entry.name })
        val hits = Search.run(all, epg, "persib", now)
        assertEquals(listOf("Indosiar"), hits.map { it.entry.name })
        assertEquals("Liga Champions: Persib", hits[0].programme?.title)
    }

    @Test fun everyWordMustMatchAndCaseIgnored() {
        assertEquals(listOf("Trans7"), Search.run(all, epg, "LAPOR pak", now).map { it.entry.name })
        assertEquals(emptyList(), Search.run(all, epg, "lapor bola", now).map { it.entry.name })
        assertEquals(all.size, Search.run(all, epg, "  ", now).size)
    }
}
