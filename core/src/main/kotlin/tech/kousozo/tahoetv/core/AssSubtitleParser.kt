package tech.kousozo.tahoetv.core

import java.util.Locale

/**
 * Pure-JVM ASS/SSA subtitle parser and WebVTT converter (#51):
 * supports `[Script Info]` resolution metadata, `[V4+ Styles]` / `[V4 Styles]` colours/alignment/bold/italic,
 * and `[Events]` `Dialogue:` lines with inline override tags (`\b1`, `\i1`, `\an1..9`, `\c&HBBGGRR&`, `\pos(x,y)`, `\N`).
 */
object AssSubtitleParser {
    data class Style(
        val name: String = "Default",
        val fontSize: Int = 20,
        val primaryColorArgb: Int = 0xFFFFFFFF.toInt(),
        val outlineColorArgb: Int = 0xFF000000.toInt(),
        val bold: Boolean = false,
        val italic: Boolean = false,
        val alignment: Int = 2, // numpad 1..9 (2 = bottom-center)
    )

    data class Cue(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val colorArgb: Int = 0xFFFFFFFF.toInt(),
        val outlineArgb: Int = 0xFF000000.toInt(),
        val bold: Boolean = false,
        val italic: Boolean = false,
        val alignment: Int = 2,
        val posX: Float? = null,
        val posY: Float? = null,
    )

    data class Document(
        val playResX: Int = 1920,
        val playResY: Int = 1080,
        val styles: Map<String, Style> = emptyMap(),
        val cues: List<Cue> = emptyList(),
    ) {
        fun activeAt(positionMs: Long): List<Cue> =
            cues.filter { positionMs in it.startMs until it.endMs }
    }

    private val OVERRIDE_BLOCK = Regex("""\{([^}]*)}""")
    private val AN_TAG = Regex("""\\an([1-9])""")
    private val A_SSA_TAG = Regex("""\\a(\d+)""")
    private val POS_TAG = Regex("""\\pos\(\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*\)""")
    private val COLOR_TAG = Regex("""\\(?:1?c)&?H([0-9A-Fa-f]{1,8})&?""")

    @JvmStatic
    fun looksLikeAss(raw: String): Boolean {
        val head = raw.take(2048)
        return "[Script Info]" in head || "[V4+ Styles]" in head || "[V4 Styles]" in head || "Dialogue:" in head
    }

    @JvmStatic
    fun parseTimeMs(raw: String): Long? {
        val parts = raw.trim().split(':')
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val secParts = parts[2].split('.', ',')
        val s = secParts[0].toLongOrNull() ?: return null
        val fracStr = secParts.getOrNull(1).orEmpty().take(3)
        val ms = when (fracStr.length) {
            0 -> 0L
            1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
            2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
            else -> fracStr.toLongOrNull() ?: 0L
        }
        return ((h * 3600L) + (m * 60L) + s) * 1000L + ms
    }

    /** Parses SSA/ASS `&HAABBGGRR` or `&HBBGGRR` colour strings into Android/JVM `0xAARRGGBB` ints. */
    @JvmStatic
    fun parseAssColor(raw: String?, fallback: Int = 0xFFFFFFFF.toInt()): Int {
        val clean = raw?.trim()?.removePrefix("&H")?.removePrefix("&h")?.removeSuffix("&").orEmpty()
        if (clean.isEmpty()) return fallback
        val v = clean.toLongOrNull(16) ?: return fallback
        val padded = v and 0xFFFFFFFFL
        return if (clean.length <= 6) {
            val b = ((padded shr 16) and 0xFF).toInt()
            val g = ((padded shr 8) and 0xFF).toInt()
            val r = (padded and 0xFF).toInt()
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        } else {
            val assA = ((padded shr 24) and 0xFF).toInt()
            val alpha = 255 - assA // ASS 00 = opaque, FF = transparent
            val b = ((padded shr 16) and 0xFF).toInt()
            val g = ((padded shr 8) and 0xFF).toInt()
            val r = (padded and 0xFF).toInt()
            (alpha shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    @JvmStatic
    fun parseDocument(raw: String): Document {
        var playResX = 1920
        var playResY = 1080
        var section = ""
        var styleCols = listOf("Name", "Fontsize", "PrimaryColour", "OutlineColour", "Bold", "Italic", "Alignment")
        var eventCols = listOf("Layer", "Start", "End", "Style", "Name", "MarginL", "MarginR", "MarginV", "Effect", "Text")
        val styles = LinkedHashMap<String, Style>()
        val cues = ArrayList<Cue>()

        for (rawLine in raw.lineSequence()) {
            val line = rawLine.trimStart('\uFEFF').trim()
            if (line.isEmpty() || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.lowercase(Locale.US)
                continue
            }
            when (section) {
                "[script info]" -> {
                    if (line.startsWith("PlayResX:", ignoreCase = true)) {
                        line.substringAfter(':').trim().toIntOrNull()?.takeIf { it > 0 }?.let { playResX = it }
                    } else if (line.startsWith("PlayResY:", ignoreCase = true)) {
                        line.substringAfter(':').trim().toIntOrNull()?.takeIf { it > 0 }?.let { playResY = it }
                    }
                }
                "[v4+ styles]", "[v4 styles]" -> {
                    if (line.startsWith("Format:", ignoreCase = true)) {
                        styleCols = line.substringAfter(':').split(',').map { it.trim() }
                    } else if (line.startsWith("Style:", ignoreCase = true)) {
                        val vals = line.substringAfter(':').split(',').map { it.trim() }
                        fun col(name: String): String? = styleCols.indexOfFirst { it.equals(name, true) }
                            .takeIf { it >= 0 }?.let { vals.getOrNull(it) }
                        val sName = col("Name")?.takeIf { it.isNotEmpty() } ?: continue
                        val alignRaw = col("Alignment")?.toIntOrNull() ?: 2
                        val alignment = if (section == "[v4 styles]") legacySsaAlignToNumpad(alignRaw) else alignRaw.coerceIn(1, 9)
                        styles[sName] = Style(
                            name = sName,
                            fontSize = col("Fontsize")?.toIntOrNull()?.coerceAtLeast(10) ?: 20,
                            primaryColorArgb = parseAssColor(col("PrimaryColour")),
                            outlineColorArgb = parseAssColor(col("OutlineColour"), 0xFF000000.toInt()),
                            bold = col("Bold") in listOf("-1", "1", "true"),
                            italic = col("Italic") in listOf("-1", "1", "true"),
                            alignment = alignment,
                        )
                    }
                }
                "[events]" -> {
                    if (line.startsWith("Format:", ignoreCase = true)) {
                        eventCols = line.substringAfter(':').split(',').map { it.trim() }
                    } else if (line.startsWith("Dialogue:", ignoreCase = true)) {
                        val body = line.substringAfter(':')
                        val vals = body.split(',', limit = eventCols.size).mapIndexed { i, v ->
                            if (i == eventCols.lastIndex) v else v.trim()
                        }
                        fun col(name: String): String? = eventCols.indexOfFirst { it.equals(name, true) }
                            .takeIf { it >= 0 }?.let { vals.getOrNull(it) }
                        val startMs = col("Start")?.let(::parseTimeMs) ?: continue
                        val endMs = col("End")?.let(::parseTimeMs) ?: continue
                        if (endMs <= startMs) continue
                        val style = styles[col("Style").orEmpty()] ?: Style()
                        val rawText = col("Text").orEmpty()
                        parseDialogueCue(startMs, endMs, rawText, style, playResX, playResY)?.let { cues += it }
                    }
                }
            }
        }
        return Document(playResX, playResY, styles, cues.sortedBy { it.startMs })
    }

    @JvmStatic
    fun parse(raw: String): List<Cue> = parseDocument(raw).cues

    /** Converts an ASS/SSA file to WebVTT with basic positioning (`line`/`align`) and inline styling (`<b>`, `<i>`). */
    @JvmStatic
    fun toWebVtt(raw: String): String {
        val doc = parseDocument(raw)
        val sb = StringBuilder("WEBVTT\n\n")
        for (c in doc.cues) {
            sb.append(formatVttTime(c.startMs)).append(" --> ").append(formatVttTime(c.endMs))
            val align = when (c.alignment % 3) {
                1 -> "start"
                0 -> "end"
                else -> "center"
            }
            val linePct = c.posY?.let { (it * 100f).toInt().coerceIn(5, 92) } ?: when (c.alignment) {
                7, 8, 9 -> 8
                4, 5, 6 -> 50
                else -> 88
            }
            sb.append(" line:$linePct% align:$align\n")
            var txt = c.text
            if (c.italic) txt = "<i>$txt</i>"
            if (c.bold) txt = "<b>$txt</b>"
            sb.append(txt).append("\n\n")
        }
        return sb.toString()
    }

    private fun formatVttTime(ms: Long): String {
        val h = ms / 3_600_000L
        val m = (ms % 3_600_000L) / 60_000L
        val s = (ms % 60_000L) / 1000L
        val rem = ms % 1000L
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", h, m, s, rem)
    }

    private fun legacySsaAlignToNumpad(a: Int): Int = when (a) {
        1, 2, 3 -> a
        5, 6, 7 -> a + 2
        9, 10, 11 -> a - 5
        else -> 2
    }

    private fun parseDialogueCue(
        startMs: Long,
        endMs: Long,
        rawText: String,
        baseStyle: Style,
        playResX: Int,
        playResY: Int,
    ): Cue? {
        var color = baseStyle.primaryColorArgb
        var bold = baseStyle.bold
        var italic = baseStyle.italic
        var alignment = baseStyle.alignment
        var posX: Float? = null
        var posY: Float? = null

        for (m in OVERRIDE_BLOCK.findAll(rawText)) {
            val block = m.groupValues[1]
            if ("\\b1" in block) bold = true
            else if ("\\b0" in block) bold = false
            if ("\\i1" in block) italic = true
            else if ("\\i0" in block) italic = false
            AN_TAG.find(block)?.groupValues?.get(1)?.toIntOrNull()?.let { alignment = it }
            A_SSA_TAG.find(block)?.groupValues?.get(1)?.toIntOrNull()?.let { alignment = legacySsaAlignToNumpad(it) }
            COLOR_TAG.find(block)?.groupValues?.get(1)?.let { hex ->
                color = parseAssColor(hex, color)
            }
            POS_TAG.find(block)?.let { pm ->
                val px = pm.groupValues[1].toFloatOrNull()
                val py = pm.groupValues[2].toFloatOrNull()
                if (px != null && playResX > 0) posX = (px / playResX).coerceIn(0f, 1f)
                if (py != null && playResY > 0) posY = (py / playResY).coerceIn(0f, 1f)
            }
        }

        val cleaned = OVERRIDE_BLOCK.replace(rawText, "")
            .replace("\\N", "\n")
            .replace("\\n", "\n")
            .replace("\\h", " ")
            .trim()
        if (cleaned.isEmpty()) return null
        return Cue(
            startMs = startMs,
            endMs = endMs,
            text = cleaned,
            colorArgb = color,
            outlineArgb = baseStyle.outlineColorArgb,
            bold = bold,
            italic = italic,
            alignment = alignment,
            posX = posX,
            posY = posY,
        )
    }
}
