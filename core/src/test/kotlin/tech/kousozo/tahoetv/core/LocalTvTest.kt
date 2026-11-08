package tech.kousozo.tahoetv.core

import kotlin.test.Test
import kotlin.test.assertEquals
import tech.kousozo.tahoetv.core.LocalTv.Category

class LocalTvTest {
    private fun ch(name: String, group: String? = "General") = M3u.Channel(name, "https://x/${name.hashCode()}.m3u8", null, group)

    @Test fun classifiesIndonesianChannels() {
        val near = LocalTv.nearKeywords("Kalimantan Timur")
        fun cat(n: String, g: String? = "General") = LocalTv.classify(ch(n, g), near).category
        assertEquals(Category.National, cat("RCTI HD (1080p)"))
        assertEquals(Category.National, cat("Metro TV (Indonesia)", "News"))
        assertEquals(Category.National, cat("tvOne [Geo-blocked]", "Undefined"))
        assertEquals(Category.Tvri, cat("TVRI East Java (720p)"))
        assertEquals(Category.Local, cat("JTV Malang (480p)"))
        assertEquals(Category.Religious, cat("Rodja TV (720p)", "Religious"))
        assertEquals(Category.Other, cat("FTV (Taiwan) (720p) [Not 24/7]"))
        assertEquals(Category.Other, cat("Biznet Kids (1080p)", "Kids"))
        assertEquals("TVRI East Kalimantan", LocalTv.cleanName("TVRI East Kalimantan (720p) [Not 24/7]"))
    }

    @Test fun nearYouAndOrdering() {
        assertEquals(listOf("west papua"), LocalTv.nearKeywords("Papua Barat"))      // not plain "Papua"
        assertEquals(listOf("batam tv"), LocalTv.nearKeywords("Kepulauan Riau"))   // not plain "Riau"
        assertEquals(emptyList(), LocalTv.nearKeywords(null))
        val list = LocalTv.arrange(listOf(ch("Jogja TV"), ch("SCTV"), ch("TVRI East Kalimantan (720p)"), ch("RCTI HD (1080p)"),
            ch("Balikpapan TV (720p)"), ch("RCTI [Not 24/7]")), "Kalimantan Timur")
        assertEquals(listOf("TVRI East Kalimantan", "Balikpapan TV", "RCTI", "SCTV", "Jogja TV"), list.map { it.name })
        assertEquals(false, list.first { it.name == "RCTI" }.partTime) // duplicate kept the 24/7 stream
    }
}
