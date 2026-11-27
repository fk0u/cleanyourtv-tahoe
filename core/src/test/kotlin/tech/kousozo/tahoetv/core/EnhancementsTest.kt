package tech.kousozo.tahoetv.core

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancementsTest {
    @Test
    fun updatesPickAssetPrefersPlainLanguageNameWithHistoricalFallback() {
        val modern = Updates.Release(
            version = "3.4.0",
            notes = "Upgrade",
            page = Updates.PAGE,
            date = "2026-11-15",
            assets = mapOf(
                "TahoeTV-App-for-TV-and-Phone.apk" to "https://example.com/app.apk",
                "TahoeTV-Phone-Remote-and-Setup.apk" to "https://example.com/remote.apk",
                "SHA256SUMS" to "https://example.com/SHA256SUMS",
            ),
            sha256Map = mapOf(
                "TahoeTV-App-for-TV-and-Phone.apk" to "a".repeat(64),
            ),
        )
        assertEquals("TahoeTV-App-for-TV-and-Phone.apk", modern.pickAsset(Updates.LAUNCHER_ASSETS))
        assertEquals("TahoeTV-Phone-Remote-and-Setup.apk", modern.pickAsset(Updates.REMOTE_ASSETS))

        val legacy = Updates.Release(
            version = "3.3.0",
            notes = "Old",
            page = Updates.PAGE,
            date = "2026-11-01",
            assets = mapOf(
                "tahoe-launcher.apk" to "https://example.com/old-launcher.apk",
                "tahoe-tv.apk" to "https://example.com/old-tv.apk",
            ),
        )
        assertEquals("tahoe-launcher.apk", legacy.pickAsset(Updates.LAUNCHER_ASSETS))
        assertEquals("tahoe-tv.apk", legacy.pickAsset(Updates.REMOTE_ASSETS))
    }

    @Test(expected = IOException::class)
    fun updatesDownloadRejectsNonHttpScheme() {
        val tmp = Files.createTempFile("tahoe-dl", ".apk").toFile()
        tmp.deleteOnExit()
        Updates.download("file:///etc/passwd", tmp, null as String?, null)
    }

    @Test
    fun sourceHealthEwmaTracksReliabilityAndRapidZapCancelsSupersededGeneration() {
        SourceHealth.resetForTest()
        val u = "https://cdn.example.com/rcti/index.m3u8"
        SourceHealth.reportMetrics(u, StreamSelector.Metrics(live = true, latencyMs = 90, height = 1080, bandwidthBps = 5_000_000, hasAbr = true, score = 88.0))
        val afterOneOk = SourceHealth.stability(u)
        SourceHealth.report(u, false)
        val afterFail = SourceHealth.stability(u)
        assertTrue("EWMA stability should decrease after failure ($afterFail < $afterOneOk)", afterFail < afterOneOk)

        val g1 = StreamSelector.newZapGeneration()
        val g2 = StreamSelector.newZapGeneration()
        val c1 = M3u.Channel("A", "u1", null, null)
        val c2 = M3u.Channel("A", "u2", null, null)
        // Old generation returns promptly without waiting for probes
        val ranked = StreamSelector.rankOnOpen(listOf(c1, c2), 2000, generation = g1)
        assertEquals(2, ranked.size)
        assertTrue(g2 > g1)
    }

    @Test
    fun localTvArrangeFuzzyMergesTypoOrSpacedChannelVariantsOnly() {
        val c1 = M3u.Channel("Kompas TV", "https://a.example/kompas.m3u8", null, "News")
        val c2 = M3u.Channel("KompasTV FHD", "https://b.example/kompas2.m3u8", null, "News")
        val c3 = M3u.Channel("TVRI Aceh", "https://c.example/aceh.m3u8", null, "Regional")
        val c4 = M3u.Channel("TVRI Bali", "https://d.example/bali.m3u8", null, "Regional")
        val out = LocalTv.arrange(listOf(c1, c2, c3, c4), null, listOf("ID"))
        val kompas = out.filter { it.name.contains("Kompas", ignoreCase = true) }
        assertEquals(1, kompas.size)
        assertEquals(1, kompas.first().alternates.size)
        assertEquals(2, out.count { it.name.startsWith("TVRI", ignoreCase = true) })
    }

    @Test
    fun searchRunSupportsTypoTolerantFuzzyFallbackBelowExactHits() {
        val e1 = LocalTv.classify(M3u.Channel("Kompas TV", "https://a.example/kompas.m3u8", null, "News"), emptyList(), true)
        val e2 = LocalTv.classify(M3u.Channel("SCTV", "https://b.example/sctv.m3u8", null, "Nasional"), emptyList(), true)
        val hits = Search.run(listOf(e1, e2), null, "kmpas", Instant.now())
        assertEquals(listOf("Kompas TV"), hits.map { it.entry.name })
    }

    @Test
    fun mediaScraperUnescapesBingMurlWithAmpersand() {
        val html = """<a m="murl&quot;:&quot;https://cdn.example.com/img.png?w=400&amp;h=300&quot;"></a>"""
        assertEquals("https://cdn.example.com/img.png?w=400&h=300", MediaScraper.parseBingFirstImage(html))
    }

    @Test
    fun controlPlaneHeartbeatUpdateModalPolicyAndOverridesRoundTrip() {
        val dir = Files.createTempDirectory("tahoe-cp").toFile()
        dir.deleteOnExit()
        val id1 = ControlPlane.deviceId(dir)
        val id2 = ControlPlane.deviceId(dir)
        assertEquals(id1, id2)
        assertTrue(id1.length >= 16)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/devices/heartbeat") { ex ->
            val resp = """
                {
                  "ok": true,
                  "server_time": 123456,
                  "update_policy": {
                    "force_modal": true,
                    "min_version_code": 30,
                    "min_version": "3.5.0",
                    "recommended_version": "3.5.0",
                    "modal_title": "Update Tersedia",
                    "modal_message": "Harap perbarui aplikasi."
                  },
                  "channel_overrides": {
                    "blocked_urls": ["https://bad.example.com/stream.m3u8"],
                    "blocked_hosts": ["blocked.org"],
                    "announcement": "Maintenance",
                    "featured_notice": "Live"
                  },
                  "notifications": [
                    {"id": 7, "title": "Halo TV", "body": "Saluran baru tersedia", "kind": "info", "force_modal": false}
                  ],
                  "latest_release": {
                    "version": "3.5.0",
                    "notes": "New UI",
                    "date": "2026-11-15",
                    "page": "https://tahoe.kousozo.tech",
                    "assets": [{"name": "TahoeTV-App-for-TV-and-Phone.apk", "url": "https://example.com/a.apk", "sha256": "${"b".repeat(64)}"}]
                  }
                }
            """.trimIndent().toByteArray()
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.start()
        val prev = ControlPlane.baseUrl
        try {
            ControlPlane.baseUrl = "http://127.0.0.1:${server.address.port}"
            val hb = ControlPlane.heartbeat(
                dir,
                ControlPlane.DeviceProfile(deviceId = id1, appRole = "desktop", platform = "macos_intel"),
            )
            assertNotNull(hb)
            assertEquals(1, hb!!.notifications.size)
            assertEquals("Halo TV", hb.notifications.first().title)
            assertTrue(ControlPlane.isUrlBlocked("https://bad.example.com/stream.m3u8"))
            assertTrue(ControlPlane.isUrlBlocked("https://sub.blocked.org/live.m3u8"))
            assertFalse(ControlPlane.isUrlBlocked("https://good.example.com/live.m3u8"))
            assertTrue(ControlPlane.shouldShowUpdateModal(dir, "3.4.0", 28, hb.release))
            // "Later" closes even a required update; it is asked for again a day later
            ControlPlane.markUpdateModalDismissed(dir, "3.5.0")
            assertFalse(ControlPlane.shouldShowUpdateModal(dir, "3.4.0", 28, hb.release))
            assertTrue(ControlPlane.shouldShowUpdateModal(dir, "3.4.0", 28, hb.release,
                System.currentTimeMillis() + ControlPlane.REMIND_AFTER_MS + 1000))
        } finally {
            ControlPlane.baseUrl = prev
            server.stop(0)
        }
    }

    @Test
    fun localTvArrangeScalesLinearlyAndPreservesFuzzyMerges() {
        val channels = ArrayList<M3u.Channel>(8005)
        for (i in 0 until 8000) {
            channels += M3u.Channel(
                name = "Channel Number $i Global",
                group = "Entertainment",
                logo = null,
                url = "https://cdn.example.com/$i/live.m3u8",
            )
        }
        channels += M3u.Channel(
            name = "Nusantara Premiere Channel HD",
            group = "Entertainment",
            logo = null,
            url = "https://cdn.example.com/nusa/1.m3u8",
        )
        channels += M3u.Channel(
            name = "Nusantara Premiere Channe",
            group = "Entertainment",
            logo = null,
            url = "https://cdn.example.com/nusa/2.m3u8",
        )
        LocalTv.arrange(channels.take(50), "DKI Jakarta", listOf(Region.HOME)) // warm up JIT before timing
        val t0 = System.nanoTime()
        val arranged = LocalTv.arrange(channels, "DKI Jakarta", listOf(Region.HOME))
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("Expected O(N) arrange on 8k channels < 5000ms, got ${elapsedMs}ms", elapsedMs < 5000)
        val mergedEntry = arranged.firstOrNull { it.name.contains("Nusantara Premiere") }
        assertNotNull(mergedEntry)
        assertEquals(1, mergedEntry!!.alternates.size)
    }
}
