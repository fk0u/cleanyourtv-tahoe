package tech.kousozo.tahoetv.core

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RegionTest {
    @Test fun indonesiaKeepsItsCuratedSources() {
        assertEquals(Sources.DEFAULT, Region.sources("ID"))
        assertEquals(Sources.EPG, Region.guides("id"))
    }

    @Test fun otherCountriesUseIptvOrgAndEpgPw() {
        assertEquals(listOf("https://iptv-org.github.io/iptv/countries/us.m3u"), Region.sources("US").map { it.url })
        assertEquals(listOf("https://epg.pw/xmltv/epg_GB.xml"), Region.guides("gb"))
    }

    @Test fun languagesNormalizeAndFallBackToEnglish() {
        assertEquals("id", Region.language("in"))        // Android's old code for Indonesian
        assertEquals("id", Region.language("id_ID"))
        assertEquals("en", Region.language("en-GB"))
        assertEquals("en", Region.language("fr"))        // no French yet
        assertEquals("en", Region.language(null))
    }

    @Test fun countriesAreValidatedAndNamed() {
        assertEquals("US", Region.country(" us "))
        assertEquals(null, Region.country("XX1"))
        assertEquals("Indonesia", Region.name("ID", "en"))
        assertEquals("🇮🇩", Region.flag("id"))
        val list = Region.countries("en")
        assertTrue(list.size > 200 && list.any { it.code == "JP" && it.name == "Japan" })
    }

    @Test fun suggestionComesFromTheDevice() {
        assertEquals("JP" to "en", Region.suggest(Locale("ja", "JP")))
        assertEquals("ID" to "id", Region.suggest(Locale("in", "ID")))
        assertEquals(Region.HOME to "en", Region.suggest(Locale("en")))
    }

    @Test fun prayerTimesDefaultByCountry() {
        assertTrue(Region.prayerByDefault("ID") && Region.prayerByDefault("my"))
        assertTrue(!Region.prayerByDefault("US"))
    }
}
