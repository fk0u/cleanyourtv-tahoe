package tech.kousozo.tahoetv.core

import java.net.URI
import java.util.Locale

/** Cross-platform technical diagnostics snapshot & formatter ("Stats for Nerds", #61). */
object PlayerStats {
    data class Snapshot(
        val channelName: String,
        val sourceIndex: Int = 1,
        val sourceTotal: Int = 1,
        val streamUrl: String = "",
        val status: String = "LIVE",
        val width: Int = 0,
        val height: Int = 0,
        val fps: Float = 0f,
        val videoCodec: String = "",
        val audioCodec: String = "",
        val decoderName: String = "",
        val videoBitrateKbps: Int = 0,
        val bufferSeconds: Double = 0.0,
        val liveOffsetSeconds: Double = Double.NaN,
        val rebufferCount: Int = 0,
        val rebufferMs: Long = 0L,
        val droppedFrames: Long = 0L,
        val bandwidthKbps: Int = 0,
    ) {
        val redactedHost: String get() = redactHost(streamUrl)
        val resolution: String get() = if (width > 0 && height > 0) "${width}×${height}" else if (height > 0) "${height}p" else "—"
    }

    /** Extracts a redacted host+path summary without credentials, tokens, or query strings. */
    @JvmStatic
    fun redactHost(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return "—"
        val parsed = runCatching { URI(trimmed) }.getOrNull()
        val host = parsed?.host?.takeIf { it.isNotBlank() }
        if (host != null) {
            val path = parsed.path.orEmpty().trim('/').substringBefore('/').let { seg -> if (seg.isNotEmpty()) "/$seg" else "" }
            return "$host$path"
        }
        return DiagLog.redact(trimmed).removePrefix("https://").removePrefix("http://").substringBefore('?').take(48)
    }

    /** Key-value rows suitable for TV / phone / desktop overlays. */
    @JvmStatic
    fun rows(snap: Snapshot, lang: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>(12)
        out += I18n.get(lang, "stats.channel") to snap.channelName
        out += I18n.get(lang, "stats.source") to I18n.get(lang, "stats.source_fmt", snap.sourceIndex.coerceAtLeast(1), snap.sourceTotal.coerceAtLeast(1), snap.redactedHost)
        out += I18n.get(lang, "stats.status") to snap.status.ifBlank { "LIVE" }
        val resFps = if (snap.fps > 1f) "${snap.resolution} @ ${String.format(Locale.US, "%.1f", snap.fps)} fps" else snap.resolution
        out += I18n.get(lang, "stats.resolution") to resFps
        val codecs = listOf(snap.videoCodec.ifBlank { "—" }, snap.audioCodec.ifBlank { "—" }).joinToString(" / ")
        out += I18n.get(lang, "stats.codecs") to codecs
        if (snap.decoderName.isNotBlank()) out += I18n.get(lang, "stats.decoder") to snap.decoderName
        if (snap.videoBitrateKbps > 0) out += I18n.get(lang, "stats.bitrate") to formatKbps(snap.videoBitrateKbps)
        out += I18n.get(lang, "stats.buffer") to String.format(Locale.US, "%.1f s", snap.bufferSeconds.coerceAtLeast(0.0))
        if (!snap.liveOffsetSeconds.isNaN()) {
            out += I18n.get(lang, "stats.live_offset") to String.format(Locale.US, "%.1f s", snap.liveOffsetSeconds.coerceAtLeast(0.0))
        }
        out += I18n.get(lang, "stats.rebuffer") to "${snap.rebufferCount}× (${String.format(Locale.US, "%.1f s", snap.rebufferMs / 1000.0)})"
        out += I18n.get(lang, "stats.dropped") to snap.droppedFrames.coerceAtLeast(0L).toString()
        if (snap.bandwidthKbps > 0) out += I18n.get(lang, "stats.bandwidth") to formatKbps(snap.bandwidthKbps)
        return out
    }

    /** Multi-line plain-text summary for copying to clipboard or appending to DiagLog. */
    @JvmStatic
    fun formatText(snap: Snapshot, lang: String): String =
        rows(snap, lang).joinToString("\n") { (k, v) -> "$k: $v" }

    private fun formatKbps(kbps: Int): String =
        if (kbps >= 1000) String.format(Locale.US, "%.2f Mbps", kbps / 1000.0) else "$kbps kbps"
}
