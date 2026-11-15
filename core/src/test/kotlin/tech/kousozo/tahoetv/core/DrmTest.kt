package tech.kousozo.tahoetv.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DrmTest {
    @Test fun detectsDrmSchemesAndDash() {
        assertEquals(Drm.Scheme.CLEARKEY, Drm.scheme("org.w3.clearkey", "0123456789abcdef0123456789abcdef:fedcba9876543210fedcba9876543210"))
        assertEquals(Drm.Scheme.CLEARKEY, Drm.scheme(null, "0123456789abcdef0123456789abcdef:fedcba9876543210fedcba9876543210"))
        assertEquals(Drm.Scheme.WIDEVINE, Drm.scheme("com.widevine.alpha", "https://lic.example/wv"))
        assertEquals(Drm.Scheme.PLAYREADY, Drm.scheme("com.microsoft.playready", "https://lic.example/pr"))
        assertEquals(Drm.Scheme.NONE, Drm.scheme(null, null))

        assertTrue(Drm.isDash("https://cdn.example/stream/master.mpd"))
        assertTrue(Drm.isDash("https://cdn.example/manifest.mpd?token=123"))
        assertTrue(Drm.isDash("https://cdn.example/manifest", manifestType = "mpd"))
        assertFalse(Drm.isDash("https://cdn.example/live/master.m3u8"))
    }

    @Test fun parsesHexAndJwkClearKeyAndBuildsW3cJwk() {
        val kid = "764e726a234a435c87a82e4a1da6a69b"
        val key = "0de18199ebb3316e3aed8529e39542b7"
        val map = Drm.clearKeyMap("$kid:$key&foo=bar")
        assertEquals(mapOf(kid to key), map)

        val jwk = Drm.toClearKeyJwk("$kid:$key")!!
        val parsed = JSONObject(jwk)
        assertEquals("temporary", parsed.getString("type"))
        assertEquals(1, parsed.getJSONArray("keys").length())
        assertEquals(mapOf(kid to key), Drm.clearKeyMap(jwk))

        val multi = Drm.clearKeyMap("01010101-0101-0101-0101-010101010101:22222222222222222222222222222222, aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        assertEquals(2, multi.size)
        assertNull(Drm.toClearKeyJwk("https://lic.example/clearkey"))
        assertEquals("https://lic.example/wv", Drm.licenseUrl("https://lic.example/wv|Referer=https://a.example"))
    }

    @Test fun parsesPreExtinfKodipropExthttpAndPipeDashUrl() {
        val m3u = """
            #EXTM3U
            #EXTVLCOPT:http-user-agent=AgentOne
            #EXTHTTP:{"Referer":"https://ref.one/","Origin":"https://orig.one"}
            #KODIPROP:inputstream.adaptive.manifest_type=mpd
            #KODIPROP:inputstream.adaptive.license_type=org.w3.clearkey
            #KODIPROP:inputstream.adaptive.license_key=764e726a234a435c87a82e4a1da6a69b:0de18199ebb3316e3aed8529e39542b7
            #EXTINF:-1 tvg-id="One.id" http-origin="https://fallback.example",Channel One
            https://cdn.example/live/one.mpd

            #EXTINF:-1,Dangling No Url

            #EXTINF:-1 group-title="Sports" http-origin="https://sp.example",SpoTV
            https://cdn.example/sport/index.mpd|license_type=clearkey&license_key=ec7ee27d83764e4b845c48cca31c8eef:9c0e4191203fccb0fde34ee29999129e&Referer=https://vision.example/&User-Agent=Mozilla/5.0

            #EXTINF:-1,Free FTA Dash
            https://cdn.example/fta/master.mpd
        """.trimIndent()

        val channels = M3u.parse(m3u)
        assertEquals(3, channels.size)

        val one = channels[0]
        assertTrue(one.isDash)
        assertTrue(one.drm)
        assertEquals("org.w3.clearkey", one.drmType)
        assertEquals("https://ref.one/", one.headers["Referer"])
        assertEquals("https://orig.one", one.headers["Origin"])
        assertEquals("AgentOne", one.headers["User-Agent"])

        val two = channels[1]
        assertEquals("https://cdn.example/sport/index.mpd", two.url)
        assertTrue(two.isDash)
        assertTrue(two.drm)
        assertEquals("clearkey", two.drmType)
        assertEquals("ec7ee27d83764e4b845c48cca31c8eef:9c0e4191203fccb0fde34ee29999129e", two.drmKey)
        assertEquals("https://vision.example/", two.headers["Referer"])
        assertEquals("Mozilla/5.0", two.headers["User-Agent"])
        assertEquals("https://sp.example", two.headers["Origin"])

        val three = channels[2]
        assertTrue(three.isDash)
        assertFalse(three.drm)
        assertTrue(Sources.free(three))
    }

    @Test fun sourcesCheckDistinguishesValidMpdFromHtmlError() {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/ok.mpd") { ex ->
                val b = """<?xml version="1.0"?><MPD type="dynamic"></MPD>""".toByteArray()
                ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
            }
            createContext("/bad.mpd") { ex ->
                val b = "<!DOCTYPE html><html><body>Error</body></html>".toByteArray()
                ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
            }
            start()
        }
        try {
            val base = "http://127.0.0.1:${srv.address.port}"
            assertTrue(Sources.check(M3u.Channel("Ok", "$base/ok.mpd", null, null), 2000))
            assertFalse(Sources.check(M3u.Channel("Bad", "$base/bad.mpd", null, null), 2000))
        } finally {
            srv.stop(0)
        }
    }
}
