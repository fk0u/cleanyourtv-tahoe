package tech.kousozo.tahoetv.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourcesTest {
    private val playlist = """
        #EXTM3U
        #EXTINF:-1 group-title="TVRI",TVRI Bali (480p)
        https://ott-balancer.tvri.go.id/live/eds/Bali/hls/Bali.m3u8
        #EXTINF:-1 tvg-logo="x" http-referrer="https://a.example/" group-title="Indonesia Channels",Stara TV
        #EXTVLCOPT:http-user-agent=Mozilla/5.0
        https://jakarta.staratv.id/live.m3u8
        #EXTINF:-1 group-title="Premium Movies",LIFETIME
        #KODIPROP:inputstream.adaptive.license_type=clearkey
        #KODIPROP:inputstream.adaptive.license_key=abc:def
        https://cdn.example/lifetime/manifest.mpd
        #EXTINF:-1 group-title="Indonesia Channels",RCTI HD
        https://mncmedia.malingtv.workers.dev/rcti.m3u8
        #EXTINF:-1 group-title="Indonesia Channels",HBO
        http://spmid.example/hbo.m3u8
    """.trimIndent()

    @Test fun parsesHeadersAndDrm() {
        val l = M3u.parse(playlist)
        assertEquals(5, l.size)
        assertEquals(mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "https://a.example/"), l[1].headers)
        assertTrue(l[2].drm)
        assertFalse(l[0].drm)
    }

    @Test fun keepsFreeTvOnly() {
        assertEquals(listOf("TVRI Bali", "Stara TV"), M3u.parse(playlist).filter(Sources::free).map { it.name })
    }
}
