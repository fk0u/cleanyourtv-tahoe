package tech.kousozo.tahoetv.core

import java.net.URI

/** Movies and series are a library of their own, never live channels: anything hosted on pixeldrain, or filed under a film /
 *  series category, goes here and is kept out of the Live TV list. Release-style file names ("Nama.Film.2021.1080p.mkv",
 *  "Serial S02E05") become a clean title, a year and, for series, one entry per show with its episodes. */
object FilmLibrary {
    enum class Kind { Movie, Series }

    /** One movie, or one series with its episodes in order. */
    data class Title(val key: String, val title: String, val year: Int?, val kind: Kind, val items: List<Item>) {
        val first: M3u.Channel get() = items.first().channel
    }
    data class Item(val channel: M3u.Channel, val season: Int?, val episode: Int?)

    private val HOSTS = listOf("pixeldrain.com", "pixeldrain.net", "pixeldra.in")
    private val GROUP = Regex("""(?<![a-z])(film|films|movie|movies|sinema|cinema|bioskop|series|serial|drama|vod|tv\s*show|anime)(?![a-z])""")
    private val SERIES_GROUP = Regex("""(?<![a-z])(series|serial|drama|tv\s*show|anime)(?![a-z])""")
    private val EPISODE = Regex("""(?i)\bS(\d{1,2})\s*[ ._-]?\s*E(\d{1,3})\b|\b(\d{1,2})x(\d{2,3})\b|\b(?:eps?|episode|ep)\s*\.?\s*(\d{1,3})\b""")
    private val YEAR = Regex("""[\s.(\[_-]((?:19|20)\d{2})(?=[\s.)\]_-]|$)""")
    private val NOISE = Regex("""(?i)\b(2160p|1080p|720p|480p|360p|4k|uhd|hdr|web-?dl|web-?rip|blu-?ray|bdrip|brrip|dvdrip|hdrip|hdtv|x264|x265|h\.?264|h\.?265|hevc|aac|ac3|dts|10bit|sub(?:s|title)?\s*(?:indo|id|eng)?|indo|dubbed|dub|nf|amzn|extended|remastered|proper|repack)\b""")
    private val EXT = Regex("""(?i)\.(mp4|mkv|avi|mov|webm|m4v|ts)$""")
    private val TAGS = Regex("""\[[^\]]*\]|\{[^}]*\}""")

    fun host(url: String): String = runCatching { URI(url.trim()).host.orEmpty().lowercase().removePrefix("www.") }.getOrDefault("")

    fun isPixeldrain(url: String): Boolean = host(url).let { h -> HOSTS.any { h == it || h.endsWith(".$it") } }

    /** True for what belongs in Film & Series and must not appear in Live TV: pixeldrain, or a film/series group holding a file or release-named title. */
    fun isLibrary(c: M3u.Channel): Boolean {
        if (isPixeldrain(c.url)) return true
        val g = c.group?.lowercase().orEmpty()
        if (g.isEmpty() || !GROUP.containsMatchIn(g)) return false
        // a "Movies" group on a playlist is often a 24/7 channel (AMC, BBC First): only a file or a release-style title is a film
        val p = parse(c.name)
        return LocalTv.isVod(c) || p.year != null || p.episode != null
    }

    fun kindOf(c: M3u.Channel): Kind {
        val g = c.group?.lowercase().orEmpty()
        return if (SERIES_GROUP.containsMatchIn(g) || EPISODE.containsMatchIn(c.name)) Kind.Series else Kind.Movie
    }

    data class Parsed(val title: String, val year: Int?, val season: Int?, val episode: Int?)

    /** "The.Raid.2.2014.1080p.BluRay.x264.mkv" → The Raid 2 / 2014; "Serial Ku S02E05 720p" → Serial Ku, season 2, episode 5. */
    fun parse(raw: String): Parsed {
        var s = EXT.replace(raw.trim(), "")
        s = TAGS.replace(s, " ").replace('_', ' ')
        var season: Int? = null; var ep: Int? = null
        EPISODE.find(s)?.let { m ->
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> { season = g[1].toInt(); ep = g[2].toInt() }
                g[3].isNotEmpty() -> { season = g[3].toInt(); ep = g[4].toInt() }
                else -> ep = g[5].toIntOrNull()
            }
            s = s.substring(0, m.range.first)
        }
        val year = YEAR.findAll(" $s").lastOrNull()?.let { it.groupValues[1].toInt() }
        if (year != null) s = YEAR.find(" $s")?.let { m -> (" $s").substring(0, m.range.first) } ?: s
        s = NOISE.replace(s, " ")
        s = s.replace('.', ' ').replace(Regex("[\\s\\-:]+$"), "").replace(Regex("\\s+"), " ").trim()
        if (s.isBlank()) s = EXT.replace(raw.trim(), "").trim()
        return Parsed(s, year, season, ep)
    }

    /** Library entries of [channels], one row per movie or series (episodes folded in), titles A→Z. */
    fun group(channels: List<M3u.Channel>): List<Title> {
        val map = LinkedHashMap<String, MutableList<Pair<M3u.Channel, Parsed>>>()
        val kinds = HashMap<String, Kind>()
        for (c in channels) {
            if (!isLibrary(c)) continue
            val p = parse(c.name)
            val kind = kindOf(c)
            val key = (if (kind == Kind.Series) "s:" else "m:") + p.title.lowercase() + (if (kind == Kind.Movie) "|" + (p.year ?: "") else "")
            map.getOrPut(key) { ArrayList() }.add(c to p); kinds[key] = kind
        }
        return map.map { (key, l) ->
            val sorted = l.sortedWith(compareBy({ it.second.season ?: 0 }, { it.second.episode ?: 0 }, { it.first.name }))
            Title(key, sorted.first().second.title, sorted.firstNotNullOfOrNull { it.second.year }, kinds.getValue(key),
                sorted.map { Item(it.first, it.second.season, it.second.episode) })
        }.sortedBy { it.title.lowercase() }
    }

    /** Episode label: "S2 E5", "E5" or "". */
    fun episodeLabel(i: Item): String = when {
        i.season != null && i.episode != null -> "S${i.season} E${i.episode}"
        i.episode != null -> "E${i.episode}"
        else -> ""
    }
}
