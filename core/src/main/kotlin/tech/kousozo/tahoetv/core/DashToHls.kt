package tech.kousozo.tahoetv.core

import java.io.StringReader
import java.io.StringWriter
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import kotlin.math.ceil
import kotlin.math.max

/**
 * Converts MPEG-DASH MPD manifests to HLS v7 (fMP4) master and media playlists for players
 * without native DASH/ClearKey support (e.g. JavaFX desktop), and rewrites MPD URLs for the relay.
 */
object DashToHls {

    internal data class TimelineEntry(val t: Long?, val d: Long, val r: Long)

    internal data class SegmentTemplateInfo(
        val initialization: String? = null,
        val media: String? = null,
        val timescale: Long? = null,
        val presentationTimeOffset: Long? = null,
        val startNumber: Long? = null,
        val duration: Long? = null,
        val timeline: List<TimelineEntry> = emptyList()
    ) {
        fun merge(child: SegmentTemplateInfo?): SegmentTemplateInfo {
            if (child == null) return this
            return SegmentTemplateInfo(
                initialization = child.initialization ?: initialization,
                media = child.media ?: media,
                timescale = child.timescale ?: timescale,
                presentationTimeOffset = child.presentationTimeOffset ?: presentationTimeOffset,
                startNumber = child.startNumber ?: startNumber,
                duration = child.duration ?: duration,
                timeline = child.timeline.ifEmpty { timeline }
            )
        }
    }

    internal data class RepresentationInfo(
        val id: String,
        val baseUrl: String,
        val isVideo: Boolean,
        val isAudio: Boolean,
        val bandwidth: Long,
        val width: Int,
        val height: Int,
        val codecs: String?,
        val lang: String?,
        val template: SegmentTemplateInfo?,
        val isDynamic: Boolean,
        val availabilityStartMs: Long?,
        val publishTimeMs: Long?,
        val timeShiftDepthMs: Long?,
        val mediaPresentationDurMs: Long?
    )

    internal data class SegmentSpec(val url: String, val durationSec: Double, val number: Long, val time: Long)

    private val PLACEHOLDER = Regex("""\$\$|\$(RepresentationID|Number|Time|Bandwidth)(?:%0?(\d+)d)?\$""")
    private val VIDEO_CODEC = Regex("""^(avc|hev|hvc|vp0?[89]|av01|mp4v|dvh)""", RegexOption.IGNORE_CASE)
    private const val MAX_LIVE_SEGMENTS = 24
    private const val MAX_STATIC_SEGMENTS = 5000

    /**
     * Generates an HLS v7 master playlist (`#EXT-X-VERSION:7`) pointing each variant to [wrapVariant].
     * Applies [maxHeight] variant preference so non-adaptive players (JavaFX) select a suitable quality first.
     */
    fun toMasterM3u8(
        mpdXml: String,
        baseUrl: String,
        maxHeight: Int = 0,
        wrapVariant: (repId: String) -> String
    ): String? = runCatching {
        val reps = parseRepresentations(parseDoc(mpdXml), baseUrl)
        if (reps.isEmpty()) return null
        val videos = reps.filter { it.isVideo && !it.isAudio }
        val audios = reps.filter { it.isAudio && !it.isVideo }
        val muxed = reps.filter { it.isVideo && it.isAudio }
        val videoCandidates = (videos + muxed).ifEmpty { reps }

        val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n")
        val hasAudioGroup = videos.isNotEmpty() && audios.isNotEmpty()
        if (hasAudioGroup) {
            val a = audios.sortedByDescending { it.bandwidth }.first()
            val langAttr = a.lang?.takeIf { it.isNotBlank() && it != "und" }?.let { ",LANGUAGE=\"$it\"" }.orEmpty()
            sb.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"Audio\",DEFAULT=YES,AUTOSELECT=YES")
            sb.append(langAttr)
            sb.append(",URI=\"").append(wrapVariant(a.id)).append("\"\n")
        }

        val orderedVideos = orderVideoReps(videoCandidates, maxHeight)
        for (v in orderedVideos) {
            sb.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(max(128_000L, v.bandwidth))
            if (v.width > 0 && v.height > 0) sb.append(",RESOLUTION=").append(v.width).append('x').append(v.height)
            v.codecs?.takeIf { it.isNotBlank() }?.let { vc ->
                val codecs = if (hasAudioGroup) {
                    val ac = audios.firstOrNull()?.codecs?.takeIf { it.isNotBlank() }
                    if (ac != null && ac !in vc) "$vc,$ac" else vc
                } else vc
                sb.append(",CODECS=\"").append(codecs).append('"')
            }
            if (hasAudioGroup && v.isVideo && !v.isAudio) sb.append(",AUDIO=\"audio\"")
            sb.append('\n').append(wrapVariant(v.id)).append('\n')
        }
        sb.toString()
    }.getOrNull()

    /**
     * Generates an HLS v7 fMP4 media playlist (`#EXT-X-MAP` + `#EXTINF`) for representation [repId].
     */
    fun toMediaM3u8(
        mpdXml: String,
        baseUrl: String,
        repId: String,
        wrapSegment: (url: String, isInit: Boolean) -> String
    ): String? = runCatching {
        val reps = parseRepresentations(parseDoc(mpdXml), baseUrl)
        val rep = reps.firstOrNull { it.id == repId }
            ?: reps.firstOrNull { it.isVideo }
            ?: reps.firstOrNull()
            ?: return null
        val tmpl = rep.template ?: return null
        val segments = buildSegments(rep, tmpl)
        if (segments.isEmpty()) return null

        val kept = if (rep.isDynamic && segments.size > MAX_LIVE_SEGMENTS) {
            segments.takeLast(MAX_LIVE_SEGMENTS)
        } else segments

        val firstSeg = kept.first()
        val effectiveTs = max(1L, tmpl.timescale ?: 1L)
        val effectiveStartNum = tmpl.startNumber ?: 1L
        val avgDurUnits = max(1L, ((kept.sumOf { it.durationSec } / kept.size) * effectiveTs).toLong())
        val seq = if (rep.isDynamic && tmpl.timeline.isNotEmpty() && firstSeg.time > 0L) {
            max(firstSeg.number, (firstSeg.time + avgDurUnits / 2L) / avgDurUnits)
        } else {
            firstSeg.number
        }
        val targetDur = max(1, ceil(kept.maxOf { it.durationSec }).toInt())

        val sb = StringBuilder()
        sb.append("#EXTM3U\n")
        sb.append("#EXT-X-VERSION:7\n")
        sb.append("#EXT-X-TARGETDURATION:").append(targetDur).append('\n')
        sb.append("#EXT-X-MEDIA-SEQUENCE:").append(seq).append('\n')
        if (!rep.isDynamic) sb.append("#EXT-X-PLAYLIST-TYPE:VOD\n")

        tmpl.initialization?.takeIf { it.isNotBlank() }?.let { initTmpl ->
            val initRel = expandTemplate(initTmpl, rep.id, firstSeg.time, effectiveStartNum, rep.bandwidth)
            val initUrl = resolveUrl(rep.baseUrl, initRel)
            sb.append("#EXT-X-MAP:URI=\"").append(wrapSegment(initUrl, true)).append("\"\n")
        }

        for (seg in kept) {
            sb.append("#EXTINF:").append(String.format(Locale.US, "%.3f", seg.durationSec)).append(",\n")
            sb.append(wrapSegment(seg.url, false)).append('\n')
        }
        if (!rep.isDynamic) sb.append("#EXT-X-ENDLIST\n")
        sb.toString()
    }.getOrNull()

    /**
     * Rewrites `<MPD>` URLs so Shaka Player fetches init and media segments through [wrapSegment]
     * (preserving `UrlSigner` signatures and custom HTTP headers).
     */
    fun rewriteMpd(
        mpdXml: String,
        baseUrl: String,
        wrapSegment: (url: String) -> String
    ): String = runCatching {
        val doc = parseDoc(mpdXml)
        val root = doc.documentElement
        val mpdBase = firstChildBaseUrl(root, baseUrl)
        val mpdMeta = parseMpdMeta(root)
        val mpdTmpl = directSegmentTemplate(root)

        val periods = childElements(root, "Period")
        for (period in periods) {
            val pBase = firstChildBaseUrl(period, mpdBase)
            val pTmpl = mergeNullable(mpdTmpl, directSegmentTemplate(period))
            removeDirectChildren(period, "BaseURL", "SegmentTemplate")

            for (asEl in childElements(period, "AdaptationSet")) {
                val asBase = firstChildBaseUrl(asEl, pBase)
                val asTmpl = mergeNullable(pTmpl, directSegmentTemplate(asEl))
                removeDirectChildren(asEl, "BaseURL", "SegmentTemplate")

                val asContentType = asEl.getAttribute("contentType").trim()
                val asMime = asEl.getAttribute("mimeType").trim()

                for (repEl in childElements(asEl, "Representation")) {
                    val repId = repEl.getAttribute("id").trim().ifEmpty { "0" }
                    val repBase = firstChildBaseUrl(repEl, asBase)
                    val repTmpl = mergeNullable(asTmpl, directSegmentTemplate(repEl))
                    val bw = repEl.getAttribute("bandwidth").toLongOrNull() ?: 0L
                    val w = repEl.getAttribute("width").toIntOrNull() ?: 0
                    val h = repEl.getAttribute("height").toIntOrNull() ?: 0
                    val mime = repEl.getAttribute("mimeType").ifBlank { asMime }
                    val codecs = repEl.getAttribute("codecs").ifBlank { asEl.getAttribute("codecs") }.ifBlank { null }

                    if (repTmpl != null && !repTmpl.media.isNullOrBlank()) {
                        val repInfo = RepresentationInfo(
                            id = repId,
                            baseUrl = repBase,
                            isVideo = "video" in mime.lowercase() || "video" == asContentType.lowercase() || w > 0 || h > 0,
                            isAudio = "audio" in mime.lowercase() || "audio" == asContentType.lowercase(),
                            bandwidth = bw,
                            width = w,
                            height = h,
                            codecs = codecs,
                            lang = null,
                            template = repTmpl,
                            isDynamic = mpdMeta.isDynamic,
                            availabilityStartMs = mpdMeta.availMs,
                            publishTimeMs = mpdMeta.publishMs,
                            timeShiftDepthMs = mpdMeta.shiftMs,
                            mediaPresentationDurMs = mpdMeta.presentationMs
                        )
                        val segments = buildSegments(repInfo, repTmpl)
                        if (segments.isNotEmpty()) {
                            removeDirectChildren(repEl, "BaseURL", "SegmentTemplate", "SegmentList")
                            val firstSeg = segments.first()
                            val effectiveTs = max(1L, repTmpl.timescale ?: 1L)
                            val effectivePto = repTmpl.presentationTimeOffset ?: 0L
                            val effectiveStartNum = repTmpl.startNumber ?: 1L
                            val avgDur = max(1L, ((segments.sumOf { it.durationSec } / segments.size) * effectiveTs).toLong())
                            val segListEl = doc.createElement("SegmentList").apply {
                                setAttribute("timescale", effectiveTs.toString())
                                setAttribute("duration", avgDur.toString())
                                if (effectivePto > 0L) {
                                    setAttribute("presentationTimeOffset", effectivePto.toString())
                                }
                                setAttribute("startNumber", firstSeg.number.toString())
                            }
                            repTmpl.initialization?.takeIf { it.isNotBlank() }?.let { initT ->
                                val initUrl = resolveUrl(repBase, expandTemplate(initT, repId, firstSeg.time, effectiveStartNum, bw))
                                doc.createElement("Initialization").apply {
                                    setAttribute("sourceURL", wrapSegment(initUrl))
                                }.also { segListEl.appendChild(it) }
                            }
                            for (seg in segments) {
                                doc.createElement("SegmentURL").apply {
                                    setAttribute("media", wrapSegment(seg.url))
                                }.also { segListEl.appendChild(it) }
                            }
                            repEl.appendChild(segListEl)
                            continue
                        }
                    }

                    // Wrap static BaseURL / SegmentList / SegmentBase URLs if no SegmentTemplate expanded
                    removeDirectChildren(repEl, "BaseURL")
                    if (childElements(repEl, "SegmentList").isEmpty()) {
                        childElements(repEl, "SegmentBase").forEach { sbEl ->
                            childElements(sbEl, "Initialization").forEach { initEl ->
                                val src = initEl.getAttribute("sourceURL").trim()
                                if (src.isNotEmpty()) initEl.setAttribute("sourceURL", wrapSegment(resolveUrl(repBase, src)))
                            }
                        }
                        if (repBase != baseUrl) {
                            doc.createElement("BaseURL").apply { textContent = wrapSegment(repBase) }.also {
                                repEl.insertBefore(it, repEl.firstChild)
                            }
                        }
                    } else {
                        for (sl in childElements(repEl, "SegmentList")) {
                            childElements(sl, "Initialization").forEach { initEl ->
                                val src = initEl.getAttribute("sourceURL").trim()
                                if (src.isNotEmpty()) initEl.setAttribute("sourceURL", wrapSegment(resolveUrl(repBase, src)))
                            }
                            childElements(sl, "SegmentURL").forEach { su ->
                                val media = su.getAttribute("media").trim()
                                if (media.isNotEmpty()) su.setAttribute("media", wrapSegment(resolveUrl(repBase, media)))
                            }
                        }
                    }
                }
            }
        }
        serializeDoc(doc)
    }.getOrDefault(mpdXml)

    internal fun expandTemplate(
        template: String,
        repId: String,
        time: Long,
        number: Long,
        bandwidth: Long
    ): String = PLACEHOLDER.replace(template) { m ->
        val raw = m.value
        if (raw == "$$") return@replace "$"
        val name = m.groupValues[1]
        val width = m.groupValues[2].toIntOrNull()
        when (name) {
            "RepresentationID" -> repId
            "Number" -> formatNum(number, width)
            "Time" -> formatNum(time, width)
            "Bandwidth" -> formatNum(bandwidth, width)
            else -> raw
        }
    }

    private fun formatNum(value: Long, width: Int?): String =
        if (width != null && width > 0) String.format(Locale.US, "%0${width}d", value) else value.toString()

    private fun resolveUrl(base: String, rel: String): String =
        if (rel.startsWith("http://", true) || rel.startsWith("https://", true)) rel
        else runCatching { URI(base).resolve(rel.replace(" ", "%20")).toString() }.getOrDefault(rel)

    private fun orderVideoReps(reps: List<RepresentationInfo>, maxHeight: Int): List<RepresentationInfo> {
        val ordered = if (maxHeight <= 0) {
            val capped1080 = reps.filter { it.height in 1..1080 }.sortedByDescending { it.height * 1_000_000L + it.bandwidth }
            val over = reps.filter { it.height > 1080 || it.height == 0 }.sortedByDescending { it.bandwidth }
            capped1080 + over
        } else {
            val fit = reps.filter { it.height in 1..maxHeight }.sortedByDescending { it.height * 1_000_000L + it.bandwidth }
            val over = reps.filter { it.height == 0 || it.height > maxHeight }.sortedBy { if (it.height == 0) Long.MAX_VALUE else it.height * 1_000_000L + it.bandwidth }
            fit + over
        }
        return ordered
    }

    private data class MpdMeta(
        val isDynamic: Boolean,
        val availMs: Long?,
        val publishMs: Long?,
        val shiftMs: Long?,
        val presentationMs: Long?
    )

    private fun parseMpdMeta(root: Element): MpdMeta {
        val isDynamic = root.getAttribute("type").trim().equals("dynamic", ignoreCase = true)
        val avail = root.getAttribute("availabilityStartTime").trim().takeIf { it.isNotEmpty() }
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        val publish = root.getAttribute("publishTime").trim().takeIf { it.isNotEmpty() }
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        val shift = root.getAttribute("timeShiftBufferDepth").trim().takeIf { it.isNotEmpty() }
            ?.let { runCatching { Duration.parse(it).toMillis() }.getOrNull() }
        val pres = root.getAttribute("mediaPresentationDuration").trim().takeIf { it.isNotEmpty() }
            ?.let { runCatching { Duration.parse(it).toMillis() }.getOrNull() }
        return MpdMeta(isDynamic, avail, publish, shift, pres)
    }

    private fun parseRepresentations(doc: Document, baseUrl: String): List<RepresentationInfo> {
        val root = doc.documentElement ?: return emptyList()
        val meta = parseMpdMeta(root)
        val mpdBase = firstChildBaseUrl(root, baseUrl)
        val mpdTmpl = directSegmentTemplate(root)

        val period = childElements(root, "Period").firstOrNull() ?: return emptyList()
        val pBase = firstChildBaseUrl(period, mpdBase)
        val pTmpl = mergeNullable(mpdTmpl, directSegmentTemplate(period))

        val out = ArrayList<RepresentationInfo>()
        for (asEl in childElements(period, "AdaptationSet")) {
            val asBase = firstChildBaseUrl(asEl, pBase)
            val asTmpl = mergeNullable(pTmpl, directSegmentTemplate(asEl))
            val contentType = asEl.getAttribute("contentType").trim().lowercase()
            val asMime = asEl.getAttribute("mimeType").trim().lowercase()
            val asCodecs = asEl.getAttribute("codecs").trim().ifEmpty { null }
            val lang = asEl.getAttribute("lang").trim().ifEmpty { null }
            val asW = asEl.getAttribute("width").toIntOrNull() ?: 0
            val asH = asEl.getAttribute("height").toIntOrNull() ?: 0

            for (repEl in childElements(asEl, "Representation")) {
                val id = repEl.getAttribute("id").trim().ifEmpty { continue }
                val repBase = firstChildBaseUrl(repEl, asBase)
                val tmpl = mergeNullable(asTmpl, directSegmentTemplate(repEl))
                val mime = repEl.getAttribute("mimeType").trim().lowercase().ifEmpty { asMime }
                val codecs = repEl.getAttribute("codecs").trim().ifEmpty { null } ?: asCodecs
                val w = repEl.getAttribute("width").toIntOrNull() ?: asW
                val h = repEl.getAttribute("height").toIntOrNull() ?: asH
                val bw = repEl.getAttribute("bandwidth").toLongOrNull() ?: 0L

                val hasVideoCodec = codecs?.split(',')?.any { VIDEO_CODEC.containsMatchIn(it.trim()) } == true
                val isVideo = "video" in mime || contentType == "video" || w > 0 || h > 0 || hasVideoCodec
                val isAudio = "audio" in mime || contentType == "audio" ||
                    (!isVideo && codecs?.contains("mp4a", true) == true)

                out += RepresentationInfo(
                    id = id,
                    baseUrl = repBase,
                    isVideo = isVideo,
                    isAudio = isAudio,
                    bandwidth = bw,
                    width = w,
                    height = h,
                    codecs = codecs,
                    lang = lang,
                    template = tmpl,
                    isDynamic = meta.isDynamic,
                    availabilityStartMs = meta.availMs,
                    publishTimeMs = meta.publishMs,
                    timeShiftDepthMs = meta.shiftMs,
                    mediaPresentationDurMs = meta.presentationMs
                )
            }
        }
        return out
    }

    private fun buildSegments(rep: RepresentationInfo, tmpl: SegmentTemplateInfo): List<SegmentSpec> {
        val mediaTmpl = tmpl.media?.takeIf { it.isNotBlank() } ?: return emptyList()
        val ts = max(1L, tmpl.timescale ?: 1L).toDouble()
        val startNum = tmpl.startNumber ?: 1L
        val out = ArrayList<SegmentSpec>()

        if (tmpl.timeline.isNotEmpty()) {
            var curTime = 0L
            var curNum = startNum
            for (entry in tmpl.timeline) {
                if (entry.t != null) curTime = entry.t
                val repeat = when {
                    entry.r >= 0 -> entry.r
                    else -> 0L
                }.coerceAtMost(MAX_STATIC_SEGMENTS.toLong())
                val durSec = max(0.1, entry.d / ts)
                var k = 0L
                while (k <= repeat && out.size < MAX_STATIC_SEGMENTS) {
                    val rel = expandTemplate(mediaTmpl, rep.id, curTime, curNum, rep.bandwidth)
                    out += SegmentSpec(resolveUrl(rep.baseUrl, rel), durSec, curNum, curTime)
                    curTime += entry.d
                    curNum++
                    k++
                }
            }
            return out
        }

        val dur = tmpl.duration ?: return emptyList()
        if (dur <= 0L) return emptyList()
        val durSec = dur / ts

        if (!rep.isDynamic) {
            val totalDurMs = rep.mediaPresentationDurMs ?: return emptyList()
            val count = ceil((totalDurMs / 1000.0) / durSec).toInt().coerceIn(1, MAX_STATIC_SEGMENTS)
            for (i in 0 until count) {
                val num = startNum + i
                val t = i.toLong() * dur
                val rel = expandTemplate(mediaTmpl, rep.id, t, num, rep.bandwidth)
                out += SegmentSpec(resolveUrl(rep.baseUrl, rel), durSec, num, t)
            }
            return out
        }

        val nowMs = rep.publishTimeMs ?: System.currentTimeMillis()
        val availMs = rep.availabilityStartMs ?: 0L
        val elapsedSec = max(durSec, (nowMs - availMs) / 1000.0 - durSec)
        val latestOffset = (elapsedSec / durSec).toLong().coerceAtLeast(0L)
        val latestNum = startNum + latestOffset
        val windowCount = rep.timeShiftDepthMs?.let { (it / 1000.0 / durSec).toInt().coerceIn(3, 12) } ?: 8
        val firstNum = max(startNum, latestNum - windowCount + 1)
        for (num in firstNum..latestNum) {
            val idx = num - startNum
            val t = idx * dur
            val rel = expandTemplate(mediaTmpl, rep.id, t, num, rep.bandwidth)
            out += SegmentSpec(resolveUrl(rep.baseUrl, rel), durSec, num, t)
        }
        return out
    }

    private fun parseDoc(xml: String): Document {
        val dbf = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        return dbf.newDocumentBuilder().parse(InputSource(StringReader(xml.trimStart('\uFEFF', ' ', '\t', '\n', '\r'))))
    }

    private fun serializeDoc(doc: Document): String {
        val tf = TransformerFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        }
        val t = tf.newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        }
        val sw = StringWriter()
        t.transform(DOMSource(doc), StreamResult(sw))
        return sw.toString()
    }

    private fun childElements(parent: Element, tag: String): List<Element> {
        val out = ArrayList<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val n = children.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && (n as Element).tagName.substringAfter(':') == tag) {
                out += n
            }
        }
        return out
    }

    private fun removeDirectChildren(parent: Element, vararg tags: String) {
        val set = tags.toSet()
        val toRemove = ArrayList<Node>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val n = children.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && (n as Element).tagName.substringAfter(':') in set) {
                toRemove += n
            }
        }
        toRemove.forEach { parent.removeChild(it) }
    }

    private fun firstChildBaseUrl(el: Element, fallback: String): String {
        val raw = childElements(el, "BaseURL").firstOrNull()?.textContent?.trim().orEmpty()
        return if (raw.isEmpty()) fallback else resolveUrl(fallback, raw)
    }

    private fun directSegmentTemplate(parent: Element): SegmentTemplateInfo? {
        val el = childElements(parent, "SegmentTemplate").firstOrNull() ?: return null
        val timelineEl = childElements(el, "SegmentTimeline").firstOrNull()
        val timeline = if (timelineEl != null) {
            childElements(timelineEl, "S").mapNotNull { s ->
                val d = s.getAttribute("d").toLongOrNull() ?: return@mapNotNull null
                TimelineEntry(
                    t = s.getAttribute("t").toLongOrNull(),
                    d = d,
                    r = s.getAttribute("r").toLongOrNull() ?: 0L
                )
            }
        } else emptyList()

        return SegmentTemplateInfo(
            initialization = el.getAttribute("initialization").trim().ifEmpty { null },
            media = el.getAttribute("media").trim().ifEmpty { null },
            timescale = el.getAttribute("timescale").trim().takeIf { it.isNotEmpty() }?.toLongOrNull(),
            presentationTimeOffset = el.getAttribute("presentationTimeOffset").trim().takeIf { it.isNotEmpty() }?.toLongOrNull(),
            startNumber = el.getAttribute("startNumber").trim().takeIf { it.isNotEmpty() }?.toLongOrNull(),
            duration = el.getAttribute("duration").trim().takeIf { it.isNotEmpty() }?.toLongOrNull(),
            timeline = timeline
        )
    }

    private fun mergeNullable(outer: SegmentTemplateInfo?, inner: SegmentTemplateInfo?): SegmentTemplateInfo? =
        outer?.merge(inner) ?: inner
}
