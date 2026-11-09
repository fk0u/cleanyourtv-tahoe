package tech.kousozo.tahoetv.core

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** XMLTV programme guide, matched to playlist channels by name (free guides rarely share ids with free playlists). */
class Epg private constructor(private val byName: Map<String, List<Programme>>) {
    data class Programme(val start: Instant, val stop: Instant, val title: String, val desc: String?)

    /** Now + upcoming programmes for a channel name, empty when the guide doesn't know it. */
    fun schedule(channelName: String, now: Instant = Instant.now()): List<Programme> =
        byName[norm(channelName)].orEmpty().filter { it.stop > now }

    val size get() = byName.size

    companion object {
        /** Free daily XMLTV for Indonesian channels (epg.pw). */
        const val DEFAULT = "https://epg.pw/xmltv/epg_ID.xml"

        private val TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")
        /** One pass over the tags we need; works line by line, so no backtracking over the whole document. */
        private val TAG = Regex("""<channel id="([^"]+)"|<display-name[^>]*>([^<]*)</display-name>|<programme\s([^>]*)>|<title[^>]*>([^<]*)</title>|<desc[^>]*>([^<]*)</desc>|(</programme>)""")
        /** Filler some guides use for "no data" (dhanytv: thousands of them); dropped so another guide can fill in. */
        private val PLACEHOLDER = Regex("""(?i)\s*(jadwal belum tersedia|no (programme|program|information)( available)?|tidak ada (jadwal|informasi))\s*""")
        private val ATTR = Regex("""(start|stop|channel)="([^"]+)"""")   // XMLTV doesn't fix attribute order (epg.pw: channel first)

        /** "Metro TV HD", "METRO TV" and "MetroTV" all become "metrotv". */
        fun norm(s: String) = s.lowercase().replace(Regex("\\b(hd|sd|fhd|uhd|4k|indonesia|tv indonesia)\\b"), "")
            .replace(Regex("\\(.*?\\)|\\[.*?]"), "").replace(Regex("[^a-z0-9]"), "")

        private fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").trim()

        fun parse(xml: String): Epg {
            val names = HashMap<String, String>()
            val out = HashMap<String, MutableList<Programme>>()
            var channel: String? = null
            var start: String? = null; var stop: String? = null; var progChannel: String? = null; var title: String? = null; var desc: String? = null
            for (line in xml.lineSequence()) for (m in TAG.findAll(line)) {
                val g = m.groupValues
                when {
                    g[1].isNotEmpty() -> channel = g[1]
                    m.groups[2] != null -> channel?.let { if (it !in names) names[it] = norm(unescape(g[2])) }
                    m.groups[3] != null -> {
                        val at = ATTR.findAll(g[3]).associate { it.groupValues[1] to it.groupValues[2] }
                        start = at["start"]; stop = at["stop"]; progChannel = at["channel"]; title = null; desc = null
                    }
                    m.groups[4] != null -> if (title == null) title = unescape(g[4])
                    m.groups[5] != null -> if (desc == null) desc = unescape(g[5]).takeIf { it.isNotEmpty() }
                    g[6].isNotEmpty() -> {
                        val name = names[progChannel]; val t = title
                        if (name != null && t != null && !PLACEHOLDER.matches(t) && start != null && stop != null) runCatching {
                            out.getOrPut(name) { mutableListOf() } +=
                                Programme(OffsetDateTime.parse(start, TIME).toInstant(), OffsetDateTime.parse(stop, TIME).toInstant(), t, desc)
                        }
                        progChannel = null
                    }
                }
            }
            // sort, then drop duplicates/overlaps some guides contain (same show listed twice)
            return Epg(out.mapValues { (_, l) -> l.sortedBy { it.start }.fold(mutableListOf<Programme>()) { acc, p -> if (acc.isEmpty() || p.start >= acc.last().stop) acc += p; acc } })
        }

        fun load(url: String = DEFAULT) = parse(Web.get(url, 60_000))

        /** Several guides combined; for a channel both know, the earlier guide wins. Unreachable guides are skipped. */
        fun load(urls: List<String>): Epg {
            val merged = HashMap<String, List<Programme>>()
            urls.forEach { u -> runCatching { load(u) }.getOrNull()?.byName?.forEach { (k, v) -> merged.putIfAbsent(k, v) } }
            return Epg(merged)
        }
    }
}
