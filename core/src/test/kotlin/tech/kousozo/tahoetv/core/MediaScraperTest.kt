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
}
