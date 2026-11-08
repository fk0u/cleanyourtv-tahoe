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
        private val CHANNEL = Regex("""<channel id="([^"]+)">.*?<display-name[^>]*>([^<]+)</display-name>""", RegexOption.DOT_MATCHES_ALL)
        private val PROG = Regex("""<programme start="([^"]+)" stop="([^"]+)" channel="([^"]+)"[^>]*>(.*?)</programme>""", RegexOption.DOT_MATCHES_ALL)
        private val TITLE = Regex("""<title[^>]*>([^<]*)</title>""")
        private val DESC = Regex("""<desc[^>]*>([^<]*)</desc>""")

        /** "Metro TV HD", "METRO TV" and "MetroTV" all become "metrotv". */
        fun norm(s: String) = s.lowercase().replace(Regex("\\b(hd|sd|fhd|uhd|4k|indonesia|tv indonesia)\\b"), "")
            .replace(Regex("\\(.*?\\)|\\[.*?]"), "").replace(Regex("[^a-z0-9]"), "")

        private fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").trim()

        // ponytail: regex over the whole document (a few MB); a streaming XML parser if guides grow to tens of MB.
        fun parse(xml: String): Epg {
            val names = CHANNEL.findAll(xml).associate { it.groupValues[1] to norm(unescape(it.groupValues[2])) }
            val out = HashMap<String, MutableList<Programme>>()
            for (m in PROG.findAll(xml)) {
                val name = names[m.groupValues[3]] ?: continue
                val body = m.groupValues[4]
                val title = TITLE.find(body)?.groupValues?.get(1)?.let(::unescape) ?: continue
                out.getOrPut(name) { mutableListOf() } += Programme(
                    OffsetDateTime.parse(m.groupValues[1], TIME).toInstant(), OffsetDateTime.parse(m.groupValues[2], TIME).toInstant(),
                    title, DESC.find(body)?.groupValues?.get(1)?.let(::unescape)?.takeIf { it.isNotEmpty() })
            }
            out.values.forEach { l -> l.sortBy { it.start } }
            return Epg(out)
        }

        fun load(url: String = DEFAULT) = parse(Web.get(url, 60_000))
    }
}
