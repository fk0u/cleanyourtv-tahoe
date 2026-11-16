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

    @Test fun rtlLanguagesAreDetected() {
        listOf("ar", "ar-EG", "he_IL", "fa", "ur").forEach { assertTrue(Region.isRtl(it), it) }
        listOf("en", "id", "zh", null, "").forEach { assertTrue(!Region.isRtl(it), "$it") }
        assertEquals("ar", Region.language("ar_SA"))
        assertTrue(I18n.isRtl("ar"))
    }

    @Test fun languagePlaylistsMapIso6391To6393() {
        assertEquals("https://iptv-org.github.io/iptv/languages/ind.m3u", Region.languagePlaylist("id"))
        assertEquals("https://iptv-org.github.io/iptv/languages/ara.m3u", Region.languagePlaylist("ar"))
        assertEquals("https://iptv-org.github.io/iptv/languages/zho.m3u", Region.languagePlaylist("zh"))
        assertEquals("https://iptv-org.github.io/iptv/languages/jpn.m3u", Region.languagePlaylist("jpn"))
        assertEquals(null, Region.languagePlaylist("xx"))
        assertEquals(2, Region.languageSources(listOf("en", "eng", "ko", "zz")).size)
    }

    @Test fun viewerKeepsChannelLanguages() {
        val v = Viewer(language = "ar", country = "SA", channelLanguages = listOf("ar", "en", "zz"))
        val back = Viewer.fromJson(v.toJson())
        assertEquals(listOf("ar", "en"), back.channelLanguages)
        assertTrue(back.rtl)
        assertEquals(emptyList(), Viewer.fromJson("{\"country\":\"ID\"}").channelLanguages)
    }
}
