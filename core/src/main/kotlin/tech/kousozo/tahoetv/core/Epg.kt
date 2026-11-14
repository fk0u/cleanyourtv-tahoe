package tech.kousozo.tahoetv.core

import java.io.BufferedReader
import java.io.Reader
import java.io.StringReader
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
        val EMPTY = Epg(emptyMap())

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

        @JvmOverloads
        fun parse(
            reader: Reader,
            channelFilter: Set<String>? = null,
            windowStart: Instant? = null,
            windowEnd: Instant? = null,
            maxPerChannel: Int = 100,
            maxTotal: Int = 10_000
        ): Epg = runCatching {
            val normFilter = channelFilter?.map { norm(it) }?.filter { it.isNotEmpty() }?.toSet()
            val names = HashMap<String, String>()
            val out = HashMap<String, MutableList<Programme>>()
            var channel: String? = null
            var start: String? = null
            var stop: String? = null
            var progChannel: String? = null
            var title: String? = null
            var desc: String? = null
            var skipProg = false
            var totalCount = 0

            val bufferedReader = if (reader is BufferedReader) reader else BufferedReader(reader, 16384)
            for (line in bufferedReader.lineSequence()) {
                if (totalCount >= maxTotal) break
                for (m in TAG.findAll(line)) {
                    val g = m.groupValues
                    when {
                        g[1].isNotEmpty() -> {
                            channel = g[1]
                            val cid = channel
                            if (cid !in names && (normFilter == null || normFilter.contains(norm(cid)))) {
                                names[cid] = norm(cid)
                            }
                        }
                        m.groups[2] != null -> channel?.let { cid ->
                            val n = norm(unescape(g[2]))
                            if (normFilter == null || normFilter.contains(n) || normFilter.contains(norm(cid))) {
                                if (cid !in names || names[cid] == norm(cid)) {
                                    names[cid] = n
                                }
                            }
                        }
                        m.groups[3] != null -> {
                            val at = ATTR.findAll(g[3]).associate { it.groupValues[1] to it.groupValues[2] }
                            progChannel = at["channel"]
                            val name = names[progChannel]
                            if (name == null) {
                                skipProg = true
                            } else {
                                start = at["start"]
                                stop = at["stop"]
                                title = null
                                desc = null
                                skipProg = false
                                if (start != null && stop != null && (windowStart != null || windowEnd != null)) {
                                    val sInst = runCatching { OffsetDateTime.parse(start, TIME).toInstant() }.getOrNull()
                                    val eInst = runCatching { OffsetDateTime.parse(stop, TIME).toInstant() }.getOrNull()
                                    if (sInst != null && eInst != null) {
                                        if ((windowStart != null && eInst <= windowStart) ||
                                            (windowEnd != null && sInst >= windowEnd)) {
                                            skipProg = true
                                        }
                                    }
                                }
                                if (!skipProg && (out[name]?.size ?: 0) >= maxPerChannel) {
                                    skipProg = true
                                }
                            }
                        }
                        m.groups[4] != null -> if (!skipProg && title == null) title = unescape(g[4])
                        m.groups[5] != null -> if (!skipProg && desc == null) desc = unescape(g[5]).takeIf { it.isNotEmpty() }?.take(160)
                        g[6].isNotEmpty() -> {
                            if (!skipProg) {
                                val name = names[progChannel]
                                val t = title
                                if (name != null && t != null && !PLACEHOLDER.matches(t) && start != null && stop != null) {
                                    runCatching {
                                        val s = OffsetDateTime.parse(start, TIME).toInstant()
                                        val e = OffsetDateTime.parse(stop, TIME).toInstant()
                                        if ((windowStart == null || e > windowStart) && (windowEnd == null || s < windowEnd)) {
                                            val list = out.getOrPut(name) { mutableListOf() }
                                            if (list.size < maxPerChannel) {
                                                list += Programme(s, e, t, desc)
                                                totalCount++
                                            }
                                        }
                                    }
                                }
                            }
                            progChannel = null
                            skipProg = false
                        }
                    }
                }
            }
            // sort, then drop duplicates/overlaps some guides contain (same show listed twice)
            Epg(out.mapValues { (_, l) -> l.sortedBy { it.start }.fold(mutableListOf<Programme>()) { acc, p -> if (acc.isEmpty() || p.start >= acc.last().stop) acc += p; acc } })
        }.getOrDefault(EMPTY)

        @JvmOverloads
        fun parse(
            xml: String,
            channelFilter: Set<String>? = null,
            windowStart: Instant? = null,
            windowEnd: Instant? = null
        ): Epg = parse(StringReader(xml), channelFilter, windowStart, windowEnd)

        @JvmOverloads
        fun load(
            url: String = DEFAULT,
            channelFilter: Set<String>? = null,
            now: Instant = Instant.now()
        ): Epg = runCatching {
            val winStart = now.minusSeconds(6 * 3600)
            val winEnd = now.plusSeconds(24 * 3600)
            Web.stream(url, 60_000).use { input ->
                java.io.InputStreamReader(input, Charsets.UTF_8).use { reader ->
                    parse(reader, channelFilter, winStart, winEnd)
                }
            }
        }.getOrDefault(EMPTY)

        /** Several guides combined; for a channel both know, the earlier guide wins. Unreachable guides are skipped. */
        @JvmOverloads
        fun load(
            urls: List<String>,
            channelFilter: Set<String>? = null,
            now: Instant = Instant.now()
        ): Epg {
            val merged = HashMap<String, List<Programme>>()
            urls.forEach { u ->
                runCatching { load(u, channelFilter, now) }.getOrNull()?.byName?.forEach { (k, v) ->
                    merged.putIfAbsent(k, v)
                }
            }
            return Epg(merged)
        }
    }
}
