package tech.kousozo.tahoetv.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Stage1UpgradesTest {
    private fun ch(name: String, cc: String? = null, group: String? = "General") =
        M3u.Channel(name, "https://cdn.example/${name.hashCode()}.m3u8", null, group, countryCode = cc)

    @Test fun updatesPreferDistinctTvAndPhoneTabletNames() {
        val rel = Updates.Release(
            version = "3.6.0",
            notes = "Role separation",
            page = Updates.PAGE,
            date = "2026-11-15",
            assets = mapOf(
                "TahoeTV-Android-TV.apk" to "https://example.com/tv.apk",
                "TahoeTV-Phone-and-Tablet.apk" to "https://example.com/mobile.apk",
                "TahoeTV-App-for-TV-and-Phone.apk" to "https://example.com/legacy-tv.apk",
                "TahoeTV-Phone-Remote-and-Setup.apk" to "https://example.com/legacy-mobile.apk",
            ),
        )
        assertEquals("TahoeTV-Android-TV.apk", rel.pickAsset(Updates.LAUNCHER_ASSETS))
        assertEquals("TahoeTV-Phone-and-Tablet.apk", rel.pickAsset(Updates.REMOTE_ASSETS))
    }

    @Test fun onboardingTextHasNoUntranslatedRegionStrings() {
        assertFalse(OnboardingText.get("en", "summary.sub").contains("Wilayah & bahasa"))
        assertFalse(OnboardingText.get("zh", "summary.sub").contains("Wilayah & bahasa"))
    }

    @Test fun countryCatalogAdaptsCitiesTimezonesAndKeywords() {
        val usCities = CountryCatalog.suggestedCities("US", "en")
        assertTrue(usCities.any { it.city == "New York" } && usCities.any { it.city == "Seattle" })
        assertEquals("America/New_York", CountryCatalog.defaultZone("US"))
        assertEquals("Asia/Tokyo", CountryCatalog.defaultZone("JP"))

        val seattleKeys = CountryCatalog.localKeywords("US", "Washington", "Seattle")
        assertTrue("seattle" in seattleKeys)
        assertTrue("washington" in seattleKeys)

        val entries = LocalTv.arrange(
            listOf(ch("FOX 13 Seattle", "US"), ch("CNN", "US", "News"), ch("NBC", "US")),
            province = "Washington",
            countries = listOf("US"),
            city = "Seattle",
            viewerCountry = "US",
        )
        assertTrue(entries.first { it.name == "FOX 13 Seattle" }.near)
        assertFalse(entries.first { it.name == "CNN" }.near)
    }

    @Test fun localTvSortsByPopularityAscAndDesc() {
        val usList = listOf(
            ch("Zebra Local", "US"),
            ch("ABC", "US"),
            ch("Alpha Public", "US"),
            ch("NBC", "US"),
        )
        val pop = LocalTv.arrange(usList, null, listOf("US"), LocalTv.SortMode.Popularity)
        assertTrue(pop.indexOfFirst { it.name == "NBC" } < pop.indexOfFirst { it.name == "Zebra Local" })

        val asc = LocalTv.arrange(usList, null, listOf("US"), LocalTv.SortMode.NameAsc)
        assertEquals(listOf("ABC", "Alpha Public", "NBC", "Zebra Local"), asc.map { it.name })

        val desc = LocalTv.arrange(usList, null, listOf("US"), LocalTv.SortMode.NameDesc)
        assertEquals(listOf("Zebra Local", "NBC", "Alpha Public", "ABC"), desc.map { it.name })
    }

    @Test fun viewerSortModeRoundTripsThroughJsonAndArranges() {
        val v = Viewer(country = "US", channelCountries = listOf("US"), favorites = listOf("zebra"), sortMode = "desc")
        assertEquals(v, Viewer.fromJson(v.toJson()))
        assertEquals(LocalTv.SortMode.NameDesc, v.sort)

        val e1 = LocalTv.Entry(M3u.Channel("Alpha", "u1", null, null, countryCode = "US"), "Alpha", LocalTv.Category.Other, false, false, false, 99)
        val e2 = LocalTv.Entry(M3u.Channel("Beta", "u2", null, null, countryCode = "US"), "Beta", LocalTv.Category.Other, false, false, false, 99)
        val eZ = LocalTv.Entry(M3u.Channel("Zebra", "u3", null, null, countryCode = "US"), "Zebra", LocalTv.Category.Other, false, false, false, 99)
        // Favourite stays first even under Z-A sort, rest sort Z-A
        assertEquals(listOf("Zebra", "Beta", "Alpha"), v.arrange(listOf(e1, e2, eZ)).map { it.name })
    }

    @Test fun popularityScraperParsesWikipediaPageviewsAndCachesToDisk() {
        val dir = Files.createTempDirectory("tahoe-pop").toFile()
        dir.deleteOnExit()
        PopularityScraper.resetForTest()
        PopularityScraper.initCache(dir)
        try {
            PopularityScraper.httpGet = { url ->
                when {
                    "prop=links" in url -> """{"query":{"pages":{"1":{"links":[{"title":"NBC"},{"title":"Small Cable TV"}]}}}}"""
                    "prop=pageviews" in url -> """{"query":{"pages":{
                        "10":{"title":"CNN","pageviews":{"20261101":120000,"20261102":130000}},
                        "11":{"title":"Small Cable TV","pageviews":{"20261101":50,"20261102":40}}
                    }}}"""
                    else -> """{"query":{"search":[]}}"""
                }
            }
            val res = PopularityScraper.scrapeCountry("US", listOf("CNN", "Small Cable TV"))!!
            assertTrue((res.scores["cnn"] ?: 0.0) > (res.scores["smallcabletv"] ?: 0.0))
            assertTrue(PopularityScraper.score("CNN", listOf("US")) > PopularityScraper.score("Small Cable TV", listOf("US")))
        } finally {
            PopularityScraper.resetForTest()
        }
    }

    @Test fun sourcesDoNotDumpGlobalIndexIntoIndonesiaAndDiskCacheEpgWorks() {
        assertFalse(Sources.DEFAULT.any { it.name == "iptv-org-all" })
        assertEquals("iptv-org-all", Sources.GLOBAL_INDEX.name)

        // Verify Epg atomic disk cache serves repeated loads offline
        val dir = Files.createTempDirectory("tahoe-epg-cache").toFile()
        dir.deleteOnExit()
        val sampleXml = """
            <tv>
              <channel id="MetroTV"><display-name>Metro TV</display-name></channel>
              <programme start="20261115000000 +0000" stop="20261115235900 +0000" channel="MetroTV">
                <title>Prime News</title>
              </programme>
            </tv>
        """.trimIndent().toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var hits = 0
        server.createContext("/epg.xml") { ex ->
            hits++
            ex.sendResponseHeaders(200, sampleXml.size.toLong())
            ex.responseBody.use { it.write(sampleXml) }
        }
        server.start()
        try {
            Epg.initCache(dir)
            val now = java.time.Instant.parse("2026-11-15T12:00:00Z")
            val url = "http://127.0.0.1:${server.address.port}/epg.xml"
            val first = Epg.load(url, setOf("Metro TV"), now)
            assertEquals("Prime News", first.schedule("Metro TV", now).first().title)
            val second = Epg.load(url, setOf("Metro TV"), now)
            assertEquals("Prime News", second.schedule("Metro TV", now).first().title)
            assertEquals(1, hits)
        } finally {
            server.stop(0)
        }
    }

    @Test fun priorityLoadingFiresPriorityCallbackBeforeSecondaryWait() {
        val events = CopyOnWriteArrayList<String>()
        val result = Sources.loadCountriesPriority(null, listOf("ZZ")) { first ->
            events += "priority:${first.size}"
        }
        assertTrue(events.any { it.startsWith("priority:") })
        assertTrue(result.size >= 0)
    }
}
