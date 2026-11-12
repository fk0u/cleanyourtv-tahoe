package tech.kousozo.tahoetv.core

import kotlin.test.Test
import kotlin.test.assertEquals

class M3uTest {
    @Test fun parsesExtinfAttributesAndSkipsComments() {
        val list = M3u.parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="TVRI.id" tvg-logo="https://x/tvri.png" group-title="General;News",TVRI (720p)
            #EXTVLCOPT:http-user-agent=Mozilla
            https://example.com/tvri.m3u8
            #EXTINF:-1,Kompas TV
            https://example.com/kompas.m3u8
            https://example.com/kompas.m3u8
            """.trimIndent()
        )
        assertEquals(2, list.size)
        assertEquals(M3u.Channel("TVRI", "https://example.com/tvri.m3u8", "https://x/tvri.png", "General", mapOf("User-Agent" to "Mozilla"), tvgId = "TVRI.id"), list[0])
        assertEquals(M3u.Channel("Kompas TV", "https://example.com/kompas.m3u8", null, null), list[1])
    }

    @Test fun compactNumbers() {
        assertEquals("6.4 jt", Web.compact(6_400_000))
        assertEquals("12 rb", Web.compact(12_000))
    }

    @Test fun quotedHeaderValuesLoseTheirQuotes() {
        val c = M3u.parse("#EXTM3U\n#EXTINF:-1,Trans TV\n#EXTVLCOPT:http-referrer=\"https://20.detik.com/\"\n#EXTVLCOPT:http-origin='https://20.detik.com'\nhttps://x/a.m3u8\n").single()
        assertEquals("https://20.detik.com/", c.headers["Referer"])
        assertEquals("https://20.detik.com", c.headers["Origin"])
    }
}
