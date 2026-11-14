package tech.kousozo.tahoetv.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewerTest {
    private fun e(name: String, cat: LocalTv.Category) =
        LocalTv.Entry(M3u.Channel(name, "https://x/$name.m3u8", null, null), name, cat, false, false, false, 99)

    @Test fun roundTripsThroughJson() {
        val v = Viewer("zh", "JP", "Tokyo", 35.68, 139.69, "Tokyo", "Asia/Tokyo", true, 3, listOf("JP", "ID"), setOf("News"), listOf("u1", "u2"))
        assertEquals(v, Viewer.fromJson(v.toJson()))
        assertEquals(Viewer(), Viewer.fromJson(null))
        assertEquals(Viewer(), Viewer.fromJson("not json"))
    }

    @Test fun favouritesFirstAndCategoriesFilter() {
        val news = e("news", LocalTv.Category.News); val sport = e("sport", LocalTv.Category.Sports); val kids = e("kids", LocalTv.Category.Kids)
        val v = Viewer(country = "ID", categories = setOf("News"), favorites = listOf(kids.channel.url))
        assertEquals(listOf("kids", "news"), v.arrange(listOf(news, sport, kids)).map { it.name }) // kids kept: it's a favourite
        assertEquals(listOf("kids", "news", "sport"), v.copy(categories = emptySet()).arrange(listOf(news, sport, kids)).map { it.name })
    }

    @Test fun suggestionsFollowTheCountry() {
        val id = Viewer.suggested("ID", "id")
        assertTrue(id.prayer); assertEquals(20, id.prayerMethod); assertEquals(listOf("ID"), id.countries)
        val us = Viewer.suggested("US", "en")
        assertTrue(!us.prayer); assertEquals(2, us.prayerMethod)
        assertEquals(3, Prayer.methodFor("NL"))
    }

    @Test fun everyLanguageHasEveryKey() {
        val keys = listOf("lang.title", "city.search", "prayer.method", "order.sub", "sum.favs", "cat.Kids")
        for (l in Region.LANGUAGES) for (k in keys) assertTrue(OnboardingText.get(l, k) != k, "$l/$k")
        assertEquals("zh", Region.language("zh-CN"))
    }

    @Test fun nearMeansNearbyAndIndonesiaOnlyCategoriesDropWithoutIndonesia() {
        val local = LocalTv.Entry(M3u.Channel("tvlokal", "https://x/l.m3u8", null, null), "tvlokal", LocalTv.Category.Local, true, false, false, 99)
        val news = e("news", LocalTv.Category.News)
        val v = Viewer(country = "ID", channelCountries = listOf("ID"), categories = setOf("Near", "Tvri"))
        assertEquals(listOf("tvlokal"), v.arrange(listOf(news, local)).map { it.name })   // near by proximity, not by category
        val abroad = v.copy(channelCountries = listOf("JP"))
        assertEquals(emptySet(), abroad.activeCategories)                                    // Near can't hide everything
        assertEquals(2, abroad.arrange(listOf(news, local)).size)
        assertEquals(null, Viewer(country = "JP", region = "Tokyo", channelCountries = listOf("ID")).nearRegion)
    }

    @Test fun stableChannelIdsMatchAcrossUrlRotationsAndMigrateLegacyUrls() {
        val withTvgId = M3u.Channel("Metro TV HD", "https://old.example/metro.m3u8", null, "News", tvgId = "MetroTV.id")
        val withoutTvgId = M3u.Channel("Trans 7 (720p)", "https://old.example/t7.m3u8", null, "Entertainment")
        assertEquals("metrotv.id", withTvgId.channelId)
        assertEquals("trans7", withoutTvgId.channelId)

        var v = Viewer(country = "ID", categories = setOf("Entertainment"))
            .toggleFavorite(withTvgId)
        assertTrue(v.isFavorite(withTvgId))
        assertEquals(listOf("metrotv.id"), v.favorites)

        // Stream URL rotates in a refreshed playlist, but tvg-id stays the same
        val rotatedMetro = withTvgId.copy(url = "https://new-cdn.example/metro-v2.m3u8")
        val eMetro = LocalTv.Entry(rotatedMetro, "Metro TV", LocalTv.Category.News, false, false, false, 10)
        val eT7 = LocalTv.Entry(withoutTvgId, "Trans7", LocalTv.Category.Entertainment, false, false, false, 1)
        assertEquals(listOf("Metro TV", "Trans7"), v.arrange(listOf(eT7, eMetro)).map { it.name })

        // Legacy URL-keyed favourites still arrange properly and migrate cleanly to channelId
        val legacy = Viewer(country = "ID", favorites = listOf(withoutTvgId.url, "metrotv.id"), schema = 1)
        assertEquals(listOf("Trans7", "Metro TV"), legacy.arrange(listOf(eMetro, eT7)).map { it.name })
        val migrated = legacy.migrateFavorites(listOf(rotatedMetro, withoutTvgId))
        assertEquals(listOf("trans7", "metrotv.id"), migrated.favorites)
        assertEquals(2, migrated.schema)
        assertEquals(2, Viewer.fromJson(migrated.toJson()).schema)
    }
}
