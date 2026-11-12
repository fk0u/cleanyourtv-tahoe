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

    @Test fun optionsBeforeExtinfBelongToTheNextStream() { // dhanytv layout, "http-referer" spelling
        val l = M3u.parse("""
            #EXTINF:-1,TransTV (Detik)
            https://live.example/transtv.m3u8

            #EXTVLCOPT:http-referer=https://www.cnbcindonesia.com/
            #EXTVLCOPT:http-origin=https://20.detik.com
            #EXTINF:-1,Trans7 HD (Detik)
            https://live.example/trans7.m3u8
        """.trimIndent())
        assertEquals(emptyMap(), l[0].headers)
        assertEquals(mapOf("Referer" to "https://www.cnbcindonesia.com/", "Origin" to "https://20.detik.com"), l[1].headers)
    }

    @Test fun windoStylePlaylist() { // #EXTGRP groups, stream_headers, license_type copied onto plain HLS
        val l = M3u.parse("""
            #EXTINF:-1,TransTV (detik)
            #EXTGRP:Indonesia Channels
            #KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha
            https://live.cnnindonesia.com/transtv/chunklist.m3u8
            #EXTINF:-1,Padang TV
            #KODIPROP:inputstream.adaptive.stream_headers=User-Agent=Mozilla/5.0&Referer=https://player.example/
            http://example.org/padang/playlist.m3u8
            #EXTINF:-1,SCTV (V+)
            #KODIPROP:inputstream.adaptive.license_key=https://lic.example/license?id=8
            https://cdn.example/sctv/index.mpd
            #EXTINF:-1,FOX WEATHER
            https://247wlive.foxweather.com/stream/index.m3u8
            #EXTINF:-1,RCTI (R+)
            https://rplus.inisemar.my.id/rcti.m3u8
            #EXTINF:-1,TRAKTIR AKU DONG
            https://github.com/someone/raw/donate.mp4
        """.trimIndent())
        assertEquals("Indonesia Channels", l[0].group)
        assertFalse(l[0].drm)                                        // HLS without a key plays fine
        assertEquals(mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "https://player.example/"), l[1].headers)
        assertTrue(l[2].drm)
        assertEquals(listOf("TransTV (detik)", "Padang TV", "FOX WEATHER"), l.filter(Sources::free).map { it.name })
    }

    @Test fun keepsFreeTvOnly() {
        assertEquals(listOf("TVRI Bali", "Stara TV"), M3u.parse(playlist).filter(Sources::free).map { it.name })
    }

    /** Every channel gets checked, however long the list: the old fixed 3 × timeout cut-off left ~3 in 4 unchecked. */
    @Test fun statusCheckReachesEveryChannel() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = java.util.concurrent.Executors.newFixedThreadPool(64)
            createContext("/") { ex ->
                Thread.sleep(150) // a slow-ish server: 200 channels ÷ 32 threads × 150 ms ≈ 1 s > 3 × the 300 ms timeout
                val b = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\na.ts\n".toByteArray()
                ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
            }
            start()
        }
        try {
            val chans = (0 until 200).map { M3u.Channel("C$it", "http://127.0.0.1:${server.address.port}/c$it/index.m3u8", null, null) }
            val seen = java.util.concurrent.atomic.AtomicInteger()
            val res = Sources.checkStatus(chans, timeoutMs = 300) { _, _ -> seen.incrementAndGet() }
            assertEquals(200, res.size)
            assertEquals(200, seen.get())
        } finally { server.stop(0) }
    }

    @Test fun budgetGrowsWithTheList() {
        assertEquals(1 * 3500 * 3L + 10_000, Sources.budget(10, 3500))
        assertEquals(33 * 3500 * 3L + 10_000, Sources.budget(1028, 3500))
    }
}
