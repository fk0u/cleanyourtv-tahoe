package tech.kousozo.tahoetv.core

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.Reader
import java.io.StringReader
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** XMLTV programme guide, matched to playlist channels by `tvg-id` first, then by normalized channel name. */
class Epg private constructor(
    private val byName: Map<String, List<Programme>>,
    private val byId: Map<String, List<Programme>> = emptyMap(),
) {
    data class Programme(val start: Instant, val stop: Instant, val title: String, val desc: String?)

    /** Now + upcoming programmes for a channel (`tvg-id` first when provided, else normalized channel name). */
    @JvmOverloads
    fun schedule(channelName: String, now: Instant = Instant.now(), tvgId: String? = null): List<Programme> {
        val fromId = tvgId?.trim()?.takeIf { it.isNotEmpty() }?.let { id ->
            byId[id.lowercase()] ?: byId[norm(id)]
        }
        return (fromId ?: byName[norm(channelName)]).orEmpty().filter { it.stop > now }
    }

    /** Now + upcoming programmes for a parsed [M3u.Channel]. */
    fun schedule(channel: M3u.Channel, now: Instant = Instant.now()): List<Programme> =
        schedule(channel.name, now, channel.tvgId)

    val size get() = byName.size

    companion object {
        val EMPTY = Epg(emptyMap(), emptyMap())

        /** Free daily XMLTV for Indonesian channels (epg.pw). */
        const val DEFAULT = "https://epg.pw/xmltv/epg_ID.xml"

        private val TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z")
        /** One pass over the tags we need; works line by line, so no backtracking over the whole document. */
        private val TAG = Regex("""<channel id="([^"]+)"|<display-name[^>]*>([^<]*)</display-name>|<programme\s([^>]*)>|<title[^>]*>([^<]*)</title>|<desc[^>]*>([^<]*)</desc>|(</programme>)""")
        /** Filler some guides use for "no data" (dhanytv: thousands of them); dropped so another guide can fill in. */
        private val PLACEHOLDER = Regex("""(?i)\s*(jadwal belum tersedia|no (programme|program|information)( available)?|tidak ada (jadwal|informasi))\s*""")
        private val ATTR = Regex("""(start|stop|channel)="([^"]+)"""")   // XMLTV doesn't fix attribute order (epg.pw: channel first)

        private val NORM_QUALITY = Regex("\\b(hd|sd|fhd|uhd|4k)\\b")
        private val NORM_BRACKETS = Regex("\\(.*?\\)|\\[.*?]")
        private val NORM_NONALNUM = Regex("[^a-z0-9]")

        /** "Metro TV HD", "METRO TV" and "MetroTV" all become "metrotv". */
        fun norm(s: String) = s.lowercase().replace(NORM_QUALITY, "")
            .replace(NORM_BRACKETS, "").replace(NORM_NONALNUM, "")

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
            val rawFilter = channelFilter?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet()
            val normFilter = channelFilter?.map { norm(it) }?.filter { it.isNotEmpty() }?.toSet()
            val names = HashMap<String, String>()
            val out = HashMap<String, MutableList<Programme>>()
            val outById = HashMap<String, MutableList<Programme>>()
            var channel: String? = null
            var startInst: Instant? = null
            var stopInst: Instant? = null
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
                            val normCid = norm(cid)
                            if (cid !in names && (normFilter == null || rawFilter!!.contains(cid.lowercase()) || normFilter.contains(normCid))) {
                                names[cid] = normCid
                            }
                        }
                        m.groups[2] != null -> channel?.let { cid ->
                            val n = norm(unescape(g[2]))
                            val normCid = norm(cid)
                            if (normFilter == null || rawFilter!!.contains(cid.lowercase()) || normFilter.contains(n) || normFilter.contains(normCid)) {
                                if (cid !in names || names[cid] == normCid) {
                                    names[cid] = n
                                }
                            }
                        }
                        m.groups[3] != null -> {
                            var attrStart: String? = null
                            var attrStop: String? = null
                            var attrChan: String? = null
                            for (am in ATTR.findAll(g[3])) {
                                when (am.groupValues[1]) {
                                    "start" -> attrStart = am.groupValues[2]
                                    "stop" -> attrStop = am.groupValues[2]
                                    "channel" -> attrChan = am.groupValues[2]
                                }
                            }
                            progChannel = attrChan
                            val name = names[progChannel]
                            if (name == null) {
                                skipProg = true
                            } else {
                                title = null
                                desc = null
                                skipProg = false
                                startInst = attrStart?.let { runCatching { OffsetDateTime.parse(it, TIME).toInstant() }.getOrNull() }
                                stopInst = attrStop?.let { runCatching { OffsetDateTime.parse(it, TIME).toInstant() }.getOrNull() }
                                val sInst = startInst
                                val eInst = stopInst
                                if (sInst != null && eInst != null) {
                                    if ((windowStart != null && eInst <= windowStart) ||
                                        (windowEnd != null && sInst >= windowEnd)) {
                                        skipProg = true
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
                                val cid = progChannel
                                val name = names[cid]
                                val t = title
                                val s = startInst
                                val e = stopInst
                                if (cid != null && name != null && t != null && !PLACEHOLDER.matches(t) && s != null && e != null) {
                                    if ((windowStart == null || e > windowStart) && (windowEnd == null || s < windowEnd)) {
                                        val prog = Programme(s, e, t, desc)
                                        val list = out.getOrPut(name) { mutableListOf() }
                                        if (list.size < maxPerChannel) {
                                            list += prog
                                            val cidLower = cid.lowercase()
                                            outById.getOrPut(cidLower) { mutableListOf() } += prog
                                            val normId = norm(cid)
                                            if (normId.isNotEmpty() && normId != cidLower) {
                                                outById.getOrPut(normId) { mutableListOf() } += prog
                                            }
                                            totalCount++
                                        }
                                    }
                                }
                            }
                            progChannel = null
                            startInst = null
                            stopInst = null
                            skipProg = false
                        }
                    }
                }
            }
            fun dedup(map: Map<String, MutableList<Programme>>): Map<String, List<Programme>> =
                map.mapValues { (_, l) -> l.sortedBy { it.start }.fold(mutableListOf<Programme>()) { acc, p -> if (acc.isEmpty() || p.start >= acc.last().stop) acc += p; acc } }
            Epg(dedup(out), dedup(outById))
        }.getOrDefault(EMPTY)

        @JvmOverloads
        fun parse(
            xml: String,
            channelFilter: Set<String>? = null,
            windowStart: Instant? = null,
            windowEnd: Instant? = null
        ): Epg = parse(StringReader(xml), channelFilter, windowStart, windowEnd)

        private const val EPG_CACHE_TTL_MS = 6L * 3600 * 1000
        @Volatile private var cacheDir: File? = null
        private val refreshInFlight = ConcurrentHashMap.newKeySet<String>()
        private val bgPool = Executors.newFixedThreadPool(2) { r -> Thread(r, "tahoe-epg-cache").apply { isDaemon = true } }

        @JvmStatic
        fun initCache(dir: File) {
            cacheDir = File(dir, "epg-cache").also { runCatching { it.mkdirs() } }
        }

        private fun resolvedCacheDir(): File? = cacheDir ?: runCatching {
            File(System.getProperty("user.home"), ".tahoetv/epg-cache").apply { mkdirs() }
        }.getOrNull()?.takeIf { it.isDirectory && it.canWrite() }

        private fun cacheFile(url: String): File? = resolvedCacheDir()?.let { dir ->
            val key = url.hashCode().toUInt().toString(16) + "-" +
                url.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "_").take(32)
            File(dir, "$key.xml")
        }

        private fun fetchToFileAtomic(url: String, target: File) {
            Web.stream(url, 60_000).use { input ->
                val tmp = File(target.parentFile, "${target.name}.tmp")
                tmp.outputStream().use { out -> input.copyTo(out) }
                if (tmp.length() > 64) {
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                } else {
                    tmp.delete()
                }
            }
        }

        @JvmOverloads
        fun load(
            url: String = DEFAULT,
            channelFilter: Set<String>? = null,
            now: Instant = Instant.now()
        ): Epg = runCatching {
            val winStart = now.minusSeconds(6 * 3600)
            val winEnd = now.plusSeconds(24 * 3600)
            val cached = if (url.startsWith("http://") || url.startsWith("https://")) cacheFile(url) else null
            if (cached != null && cached.isFile && cached.length() > 64) {
                val age = System.currentTimeMillis() - cached.lastModified()
                if (age >= EPG_CACHE_TTL_MS && refreshInFlight.add(url)) {
                    bgPool.execute {
                        try {
                            runCatching { fetchToFileAtomic(url, cached) }
                        } finally {
                            refreshInFlight.remove(url)
                        }
                    }
                }
                return@runCatching cached.bufferedReader(Charsets.UTF_8).use { reader ->
                    parse(reader, channelFilter, winStart, winEnd)
                }
            }
            if (cached != null) {
                runCatching { fetchToFileAtomic(url, cached) }
                if (cached.isFile && cached.length() > 64) {
                    return@runCatching cached.bufferedReader(Charsets.UTF_8).use { reader ->
                        parse(reader, channelFilter, winStart, winEnd)
                    }
                }
            }
            Web.stream(url, 60_000).use { input ->
                InputStreamReader(input, Charsets.UTF_8).use { reader ->
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
        ): Epg = loadPriority(urls, channelFilter, now, null)

        /** Loads the first (priority) guide first and notifies [onPartial], then merges secondary guides. */
        @JvmStatic
        @JvmOverloads
        fun loadPriority(
            urls: List<String>,
            channelFilter: Set<String>? = null,
            now: Instant = Instant.now(),
            onPartial: ((Epg) -> Unit)? = null,
        ): Epg {
            if (urls.isEmpty()) return EMPTY
            val merged = HashMap<String, List<Programme>>()
            val mergedById = HashMap<String, List<Programme>>()
            urls.forEachIndexed { index, u ->
                runCatching { load(u, channelFilter, now) }.getOrNull()?.let { g ->
                    g.byName.forEach { (k, v) -> merged.putIfAbsent(k, v) }
                    g.byId.forEach { (k, v) -> mergedById.putIfAbsent(k, v) }
                    if (index == 0 && urls.size > 1 && onPartial != null && (merged.isNotEmpty() || mergedById.isNotEmpty())) {
                        onPartial(Epg(HashMap(merged), HashMap(mergedById)))
                    }
                }
            }
            return Epg(merged, mergedById)
        }
    }
}
