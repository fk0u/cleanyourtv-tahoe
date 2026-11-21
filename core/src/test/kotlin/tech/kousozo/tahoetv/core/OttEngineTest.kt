package tech.kousozo.tahoetv.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OttEngineTest {

    @Before
    @After
    fun resetEngine() {
        OttEngine.setPrimaryServer("")
        OttEngine.setFallbackEnabled(true)
    }

    @Test
    fun parseM3uMetadataExtractsEpgAndDirectives() {
        val playlist = """
            #EXTM3U url-tvg="https://epg.example/all.xml" catchup="shift" refresh="30"
            #EXTINF:-1 tvg-id="trans7.id" tvg-name="Trans7" tvg-logo="https://x/t7.png" group-title="Nasional" catchup="append" catchup-source="https://x/?play={utc}",Trans 7
            https://stream.example/trans7.m3u8
        """.trimIndent()

        val meta = M3u.parseMetadata(playlist)
        assertEquals("https://epg.example/all.xml", meta.epgUrl)
        assertEquals("shift", meta.catchup)
        assertEquals(30, meta.refreshMinutes)

        val channels = M3u.parse(playlist)
        assertEquals(1, channels.size)
        val ch = channels[0]
        assertEquals("Trans 7", ch.name)
        assertEquals("trans7.id", ch.tvgId)
        assertEquals("append", ch.catchup)
        assertEquals("https://x/?play={utc}", ch.catchupSource)
    }

    @Test
    fun primaryServerConfigurationAndFailover() {
        assertEquals(false, OttEngine.isPrimaryConfigured())
        assertEquals(OttEngine.ServerStatus.STANDBY, OttEngine.status)

        // Set primary server
        OttEngine.setPrimaryServer("https://plsemar.pages.dev/playlist.m3u")
        assertEquals(true, OttEngine.isPrimaryConfigured())
        assertEquals(OttEngine.DEFAULT_OTT_UA, OttEngine.config.userAgent)

        // Test failover fallback when server is unreachable or offline
        // Giving an unreachable localhost URL will cause fetchFromPrimary to fail and trigger graceful fallback
        OttEngine.setPrimaryServer("http://127.0.0.1:54321/nonexistent.m3u")
        val channels = OttEngine.loadChannels(countries = listOf(Region.HOME))
        // Should have triggered fallback
        assertEquals(OttEngine.ServerStatus.FALLBACK, OttEngine.status)
        assertTrue("Fallback channels should be loaded from built-in sources", channels.isNotEmpty())
    }

    @Test
    fun streamLevelFailoverLookup() {
        val primaryChannel = M3u.Channel(
            name = "Trans TV",
            url = "http://primary-ott.example/transtv.m3u8",
            logo = null,
            group = "Nasional",
            tvgId = "transtv.id",
        )

        val fallbackChannel = M3u.Channel(
            name = "Trans TV",
            url = "https://cdn.example/fallback/transtv/live.m3u8",
            logo = null,
            group = "Nasional",
            tvgId = "transtv.id",
        )

        OttEngine.registerFallbackChannels(listOf(fallbackChannel))

        val candidate = OttEngine.findFallbackStream(primaryChannel)
        assertNotNull("Should find fallback stream by tvgId / name", candidate)
        assertEquals(fallbackChannel.url, candidate?.url)

        // If the channel has the same URL, it shouldn't return itself
        val selfLookup = OttEngine.findFallbackStream(fallbackChannel)
        assertNull("Should not return identical stream as fallback", selfLookup)
    }

    @Test
    fun tvSettingsSnapshotSerializesOttFields() {
        val snap = TvSettings.Snapshot(
            ottServerUrl = "https://plsemar.pages.dev",
            ottUserAgent = "OTTNavigator/Custom",
        )
        val json = snap.toJson()
        assertEquals("https://plsemar.pages.dev", json.getString("ottServerUrl"))
        assertEquals("OTTNavigator/Custom", json.getString("ottUserAgent"))

        val restored = TvSettings.Snapshot.fromJson(json)
        assertEquals("https://plsemar.pages.dev", restored.ottServerUrl)
        assertEquals("OTTNavigator/Custom", restored.ottUserAgent)
    }
}
