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

    @Test
    fun parseOttJsonBase64DecodesCorrectly() {
        val json = """
        {
          "categories": {
            "cat1": { "name": "U2FsdXJhbiBOYXNpb25hbA==" }
          },
          "provider": {
            "epg": "https://raw.githubusercontent.com/AqFad2811/epg/refs/heads/main/indonesia.xml"
          },
          "channels": [
            {
              "category": "Y2F0MQ==",
              "name": "QU5UVg==",
              "icon": "aHR0cHM6Ly9pLmltZ3VyLmNvbS9XajZxdTZNLnBuZw==",
              "url": "aHR0cHM6Ly92aWRzZW1hci5wYWdlcy5kZXYvdGVycmFsdW5hMy83ODIubXBk",
              "source_format": "ZGFzaA==",
              "user_agent": "TW96aWxsYS81LjA="
            },
            {
              "category": "Y2F0MQ==",
              "name": "R1RW",
              "url": "aHR0cHM6Ly9ycGx1cy5pbmlzZW1hci5teS5pZC9ndHYubTN1OA==",
              "user_agent": "YW5kcm9pZC1SKw==",
              "referrer": "aHR0cHM6Ly93d3cucmN0aXBsdXMuY29tLw=="
            }
          ]
        }
        """.trimIndent()

        assertTrue(M3u.isOttJson(json))
        val (meta, channels) = M3u.parseOttJson(json)
        assertEquals("https://raw.githubusercontent.com/AqFad2811/epg/refs/heads/main/indonesia.xml", meta.epgUrl)
        assertEquals(2, channels.size)

        val antv = channels[0]
        assertEquals("ANTV", antv.name)
        assertEquals("https://vidsemar.pages.dev/terraluna3/782.mpd", antv.url)
        assertEquals("Saluran Nasional", antv.group)
        assertEquals("https://i.imgur.com/Wj6qu6M.png", antv.logo)
        assertEquals("dash", antv.manifestType)
        assertEquals("Mozilla/5.0", antv.headers["User-Agent"])

        val gtv = channels[1]
        assertEquals("GTV", gtv.name)
        assertEquals("https://rplus.inisemar.my.id/gtv.m3u8", gtv.url)
        assertEquals("android-R+", gtv.headers["User-Agent"])
        assertEquals("https://www.rctiplus.com/", gtv.headers["Referer"])

        // Also ensure M3u.parse routes it directly
        val parsedViaGeneral = M3u.parse(json)
        assertEquals(2, parsedViaGeneral.size)
        assertEquals("ANTV", parsedViaGeneral[0].name)
    }

    @Test
    fun livePlsemarFetchesSuccessfullyWithOttEngine() {
        OttEngine.setPrimaryServer("https://plsemar.pages.dev")
        val channels = OttEngine.loadChannels()
        assertTrue("Live fetch from https://plsemar.pages.dev should produce channels", channels.isNotEmpty())
        assertEquals(OttEngine.ServerStatus.ONLINE, OttEngine.status)
        assertNotNull("EPG URL should be extracted", OttEngine.lastEpgUrl)

        val antv = channels.firstOrNull { it.name.contains("ANTV", ignoreCase = true) }
        assertNotNull("Should find ANTV in plsemar channels", antv)
        assertTrue("ANTV should have DASH or custom headers", antv?.isDash == true || antv?.headers?.isNotEmpty() == true)
    }
}
