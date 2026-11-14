package tech.kousozo.tahoetv.core

import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/** Everything the first-run onboarding asks (#77), shared by the phone and desktop (and later the TV). Stored by each
 *  app as one JSON string ([toJson] / [fromJson]); a missing field keeps its default, so older apps' data still loads. */
data class Viewer @JvmOverloads constructor(
    /** App language: one of [Region.LANGUAGES]. */
    val language: String = "en",
    /** Where the viewer lives (ISO 3166 alpha-2): suggestions, prayer method, "near you". Blank = onboarding not done. */
    val country: String = "",
    /** City for weather and prayer times; blank = not set (those features stay hidden). */
    val city: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
    /** Province/state, for "near you" (Indonesia's channel lists are tagged by province). */
    val region: String = "",
    /** IANA time zone for clocks, guide, prayer times; blank = the device's. */
    val timeZone: String = "",
    val prayer: Boolean = false,
    /** Aladhan calculation method id (see [Prayer.METHODS]). */
    val prayerMethod: Int = 3,
    /** Countries whose channels are loaded; empty = just [country]. */
    val channelCountries: List<String> = emptyList(),
    /** Categories to show (by [LocalTv.Category] name); empty = all. Hidden ones can still be searched. */
    val categories: Set<String> = emptySet(),
    /** Favourite channels by stable [M3u.Channel.channelId] (or legacy stream URL), in the viewer's order: they come first in the channel list. */
    val favorites: List<String> = emptyList(),
    /** Schema version for preference storage migration. */
    val schema: Int = 2,
    /** Monotonic revision for phone↔TV merge sync (#111). */
    val revision: Long = 0L,
    /** Large text & simplified layout for elderly / low-vision viewers (#90, #64). */
    val simpleMode: Boolean = false,
) {
    val done get() = country.isNotBlank()

    /** [categories] that apply to the chosen channel countries: "near you" and TVRI only exist with Indonesia, so a
     *  leftover selection of them can't hide every channel after Indonesia is removed. */
    val activeCategories: Set<String> get() =
        if (Region.HOME in countries) categories else categories - setOf(LocalTv.Category.Near.name, LocalTv.Category.Tvri.name)

    /** Province for "near you": only when the viewer lives in Indonesia and watches its channels. */
    val nearRegion: String? get() = region.takeIf { it.isNotBlank() && country == Region.HOME && Region.HOME in countries }
    val countries get() = channelCountries.ifEmpty { listOfNotNull(Region.country(country) ?: Region.HOME) }
    val zone: ZoneId get() = runCatching { ZoneId.of(timeZone) }.getOrDefault(ZoneId.systemDefault())

    private fun favRank(e: LocalTv.Entry, rank: Map<String, Int>): Int? =
        rank[e.channel.channelId] ?: rank[e.channel.url]
            ?: e.alternates.firstNotNullOfOrNull { rank[it.channelId] ?: rank[it.url] }

    /** The channel list as the viewer set it up: chosen categories only (favourites always stay), favourites first in
     *  their order, then the usual order. */
    fun arrange(entries: List<LocalTv.Entry>): List<LocalTv.Entry> {
        val rank = favorites.withIndex().associate { (i, u) -> u to i }
        val cats = activeCategories
        val near = LocalTv.Category.Near.name in cats
        val shown = if (cats.isEmpty()) entries else entries.filter { favRank(it, rank) != null || it.category.name in cats || (near && it.near) }
        return shown.sortedBy { favRank(it, rank) ?: Int.MAX_VALUE } // stable: the rest keep their order
    }

    fun isFavorite(ch: M3u.Channel): Boolean = ch.channelId in favorites || ch.url in favorites

    fun toggleFavorite(ch: M3u.Channel): Viewer =
        if (isFavorite(ch)) copy(favorites = favorites.filterNot { it == ch.channelId || it == ch.url })
        else copy(favorites = (favorites + ch.channelId).distinct())

    /** Rewrites any legacy URL-keyed favourites to stable [M3u.Channel.channelId]s when matching channels exist. */
    fun migrateFavorites(channels: List<M3u.Channel>): Viewer {
        if (favorites.isEmpty()) return this
        val byUrl = channels.associate { it.url to it.channelId }
        val mapped = favorites.map { byUrl[it] ?: it }.distinct()
        return if (mapped == favorites && schema >= 2) this else copy(favorites = mapped, schema = 2)
    }

    fun toJson(): String = JSONObject()
        .put("schema", schema)
        .put("revision", revision)
        .put("simpleMode", simpleMode)
        .put("language", language).put("country", country).put("city", city).put("lat", lat ?: JSONObject.NULL).put("lon", lon ?: JSONObject.NULL)
        .put("region", region).put("timeZone", timeZone).put("prayer", prayer).put("prayerMethod", prayerMethod)
        .put("channelCountries", JSONArray(channelCountries)).put("categories", JSONArray(categories.toList())).put("favorites", JSONArray(favorites))
        .toString()

    companion object {
        @JvmStatic fun fromJson(s: String?): Viewer {
            val j = runCatching { JSONObject(s ?: return Viewer()) }.getOrNull() ?: return Viewer()
            fun list(k: String) = j.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } }.orEmpty()
            return Viewer(
                language = Region.language(j.optString("language", "en")),
                country = Region.country(j.optString("country")).orEmpty(),
                city = j.optString("city"), lat = j.optDouble("lat").takeUnless { it.isNaN() }, lon = j.optDouble("lon").takeUnless { it.isNaN() },
                region = j.optString("region"), timeZone = j.optString("timeZone"),
                prayer = j.optBoolean("prayer", false), prayerMethod = j.optInt("prayerMethod", 3),
                channelCountries = list("channelCountries").mapNotNull { Region.country(it) },
                categories = list("categories").toSet(), favorites = list("favorites"),
                schema = j.optInt("schema", 2),
                revision = j.optLong("revision", 0L),
                simpleMode = j.optBoolean("simpleMode", false),
            )
        }

        /** Produces a minimal phone↔TV patch envelope (`favorites.set` when only favourites changed, otherwise `region.set`). */
        @JvmStatic fun diff(before: Viewer, after: Viewer): JSONObject {
            val rev = maxOf(before.revision, after.revision) + 1L
            val root = JSONObject().put("t", "viewer_patch").put("revision", rev)
            if (before.copy(favorites = after.favorites, revision = after.revision) == after) {
                return root.put("kind", "favorites.set").put("favorites", JSONArray(after.favorites))
            }
            return root.put("kind", "region.set").put("viewer", JSONObject(after.copy(revision = rev).toJson()))
        }

        /** Merges an incoming phone patch envelope or legacy full Viewer JSON into [current], discarding stale revisions
         *  and preserving TV-added favourites when applying region changes (#111). */
        @JvmStatic fun applyPatch(current: Viewer, rawJson: String?): Viewer {
            val j = runCatching { JSONObject(rawJson ?: return current) }.getOrNull() ?: return current
            val type = j.optString("t")
            val incRev = j.optLong("revision", 0L)
            if (incRev > 0L && current.revision > incRev) return current
            if (type == "viewer_patch") {
                return when (j.optString("kind")) {
                    "favorites.set" -> {
                        val arr = j.optJSONArray("favorites") ?: return current
                        val favs = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
                        current.copy(favorites = favs, revision = maxOf(current.revision, incRev))
                    }
                    else -> {
                        val vObj = j.optJSONObject("viewer") ?: return current
                        val incoming = fromJson(vObj.toString())
                        if (!incoming.done) return current
                        val mergedFavs = (incoming.favorites + current.favorites).distinct()
                        incoming.copy(favorites = mergedFavs, revision = maxOf(current.revision, maxOf(incRev, incoming.revision)))
                    }
                }
            }
            val incoming = fromJson(rawJson)
            if (!incoming.done) return current
            if (incoming.revision > 0L && current.revision > incoming.revision) return current
            val mergedFavs = (incoming.favorites + current.favorites).distinct()
            return incoming.copy(favorites = mergedFavs, revision = maxOf(current.revision, incoming.revision))
        }

        /** Defaults for a viewer in [country]: prayer on where most people pray, that country's method, its channels. */
        @JvmStatic fun suggested(country: String, language: String): Viewer = Viewer(
            language = language, country = country, prayer = Region.prayerByDefault(country), prayerMethod = Prayer.methodFor(country),
            channelCountries = listOf(country),
        )
    }
}

/** Prayer-time calculation methods (Aladhan ids) and the usual one per country. */
object Prayer {
    data class Method(val id: Int, val name: String)

    val METHODS = listOf(
        Method(20, "Kemenag RI (Indonesia)"), Method(17, "JAKIM (Malaysia)"), Method(11, "MUIS (Singapore)"),
        Method(3, "Muslim World League"), Method(2, "ISNA (North America)"), Method(4, "Umm al-Qura (Makkah)"),
        Method(5, "Egyptian General Authority"), Method(1, "University of Islamic Sciences, Karachi"), Method(13, "Diyanet (Türkiye)"),
        Method(7, "University of Tehran"), Method(8, "Gulf Region"), Method(9, "Kuwait"), Method(10, "Qatar"),
        Method(12, "UOIF (France)"), Method(14, "Spiritual Administration of Muslims of Russia"), Method(15, "Moonsighting Committee"),
        Method(16, "Dubai"), Method(18, "Tunisia"), Method(19, "Algeria"), Method(21, "Morocco"), Method(23, "Jordan"),
    )

    private val BY_COUNTRY = mapOf(
        "ID" to 20, "MY" to 17, "BN" to 17, "SG" to 11, "TR" to 13, "SA" to 4, "YE" to 4, "EG" to 5, "SD" to 5, "LY" to 5,
        "PK" to 1, "BD" to 1, "AF" to 1, "IN" to 1, "IR" to 7, "AE" to 16, "BH" to 8, "OM" to 8, "KW" to 9, "QA" to 10,
        "FR" to 12, "RU" to 14, "TN" to 18, "DZ" to 19, "MA" to 21, "JO" to 23, "US" to 2, "CA" to 2, "GB" to 15,
    )

    /** The method usually used in [country]; Muslim World League where there's no national one. */
    @JvmStatic fun methodFor(country: String) = BY_COUNTRY[country.uppercase()] ?: 3
    @JvmStatic fun name(id: Int) = METHODS.firstOrNull { it.id == id }?.name ?: "Method $id"
}
