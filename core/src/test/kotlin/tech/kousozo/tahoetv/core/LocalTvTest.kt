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
        assertEquals(Category.Sports, cat("beIN Sports 1", "Sports"))
        assertEquals(Category.Sports, cat("SPOTV 2 HD", "Sports Channels"))
        assertEquals(Category.Movies, cat("HBO HD", "HBO Group"))
        assertEquals(Category.Movies, cat("Cinemax", "Premium Movies"))
        assertEquals(Category.Vod, cat("Film Action Sub Indo", "VOD INDO"))
        assertEquals(Category.Entertainment, cat("tvN HD", "Entertainment"))
        assertEquals("TVRI Kalimantan Timur", LocalTv.cleanName("TVRI East Kalimantan (720p) [Not 24/7]"))
        assertEquals("RCTI", LocalTv.cleanName("RCTI HD (R+)"))
        assertEquals("Trans7", LocalTv.cleanName("TRANS 7 TV"))
        assertEquals("MNCTV", LocalTv.cleanName("MNC TV HD"))
        assertEquals("RTV", LocalTv.cleanName("RTV (Channel Feed))"))
        assertEquals("TVRI Nusa Tenggara Barat", LocalTv.cleanName("TVRI NTB"))
        assertEquals("TVRI Maluku", LocalTv.cleanName("TVRI Ambon (480p)"))
    }

    @Test fun nearYouAndOrdering() {
        assertEquals(listOf("west papua", "tvri papua barat"), LocalTv.nearKeywords("Papua Barat"))      // not plain "Papua"
        assertEquals(listOf("batam tv", "tvri kepulauan riau"), LocalTv.nearKeywords("Kepulauan Riau"))   // not plain "Riau"
        assertEquals(emptyList(), LocalTv.nearKeywords(null))
        val list = LocalTv.arrange(listOf(ch("Jogja TV"), ch("SCTV"), ch("TVRI East Kalimantan (720p)"), ch("RCTI HD (1080p)"),
            ch("Balikpapan TV (720p)"), ch("RCTI [Not 24/7]")), "Kalimantan Timur")
        assertEquals(listOf("RCTI", "SCTV", "TVRI Kalimantan Timur", "Balikpapan TV", "Jogja TV"), list.map { it.name }) // national priority first, then near you
        val rcti = list.first { it.name == "RCTI" }
        assertEquals(false, rcti.partTime)          // the 24/7 stream leads
        assertEquals(1, rcti.alternates.size)       // the other one is kept as a backup
    }
}
