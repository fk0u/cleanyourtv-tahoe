package tech.kousozo.tahoetv.core

import org.junit.Assert.*
import org.junit.Test

class MediaScraperTest {
    @Test fun parseBingFirstImageExtractsMurl() {
        val html = """<a class="iusc" m="{&quot;murl&quot;:&quot;https://example.com/show-thumb.jpg&quot;,&quot;t&quot;:&quot;Show&quot;}"></a>"""
        assertEquals("https://example.com/show-thumb.jpg", MediaScraper.parseBingFirstImage(html))
    }

    @Test fun parseWikiPageImageExtractsThumbnail() {
        val json = """{"query":{"pages":{"123":{"pageid":123,"title":"Upin & Ipin","thumbnail":{"source":"https://upload.wikimedia.org/upin.jpg"}}}}}"""
        assertEquals("https://upload.wikimedia.org/upin.jpg", MediaScraper.parseWikiPageImage(json))
    }

    @Test fun channelLogoPreservesExistingHttpLogo() {
        assertEquals("https://cdn.example.com/rcti.png", MediaScraper.channelLogo("RCTI", "https://cdn.example.com/rcti.png"))
    }

    @Test fun wikiQueryTargetsTheStationArticleForLogosOnly() {
        assertEquals("TVRI stasiun televisi", MediaScraper.wikiQuery("id", "TVRI", tvChannel = true))
        assertEquals("Trans TV television channel", MediaScraper.wikiQuery("en", "Trans TV", tvChannel = true))
        assertEquals("Dunia Dalam Berita", MediaScraper.wikiQuery("id", "Dunia Dalam Berita", tvChannel = false)) // programmes: plain title
    }

    @Test fun aWikipediaPageCountsOnlyWhenItsTitleMatches() {
        assertTrue(MediaScraper.titleMatches("Trans TV", "Trans TV"))
        assertTrue(MediaScraper.titleMatches("TVRI", "TVRI (stasiun televisi)"))
        assertTrue(MediaScraper.titleMatches("Kompas TV", "Kompas TV"))
        assertTrue(!MediaScraper.titleMatches("Makan Receh", "Perusahaan Umum Daerah Air Minum Kota Surakarta"))
        assertTrue(!MediaScraper.titleMatches("Liga 1", "Liga 1 Indonesia 2019 musim"))
        val json = """{"query":{"pages":{"1":{"title":"Air minum","thumbnail":{"source":"https://x/pipe.jpg"}},"2":{"title":"Makan Receh","thumbnail":{"source":"https://x/show.jpg"}}}}}"""
        assertEquals("https://x/show.jpg", MediaScraper.parseWikiPageImage(json, "Makan Receh"))
        assertEquals(null, MediaScraper.parseWikiPageImage(json.replace("Makan Receh", "Lain"), "Makan Receh"))
    }
}
