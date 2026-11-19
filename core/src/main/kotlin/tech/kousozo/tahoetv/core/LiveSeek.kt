package tech.kousozo.tahoetv.core

import java.util.Locale

/** Pure helper functions for live stream DVR/rewind seeking and live-edge detection across platforms. */
object LiveSeek {
    /** Distance from live edge (ms) within which playback is treated as live. */
    const val LIVE_EDGE_THRESHOLD_MS = 28_000L

    /** True when playback is currently at (or within tolerance of) the live edge, or when not a live stream. */
    @JvmStatic
    fun isAtLiveEdge(isLive: Boolean, liveOffsetMs: Long): Boolean =
        !isLive || liveOffsetMs < 0L || liveOffsetMs <= LIVE_EDGE_THRESHOLD_MS

    /** How far [positionMs] lags behind [durationMs] inside the seekable DVR window (ms, >= 0). */
    @JvmStatic
    fun behindLiveMs(durationMs: Long, positionMs: Long): Long =
        if (durationMs > 0L && positionMs >= 0L) maxOf(0L, durationMs - positionMs) else 0L

    /** Formats a live-lag offset as `"LIVE"` (when < 1s) or `"-mm:ss"` / `"-h:mm:ss"`. */
    @JvmStatic
    fun formatBehind(behindMs: Long): String {
        if (behindMs < 1_000L) return "LIVE"
        val totalSec = behindMs / 1_000L
        val h = totalSec / 3_600L
        val m = (totalSec % 3_600L) / 60L
        val s = totalSec % 60L
        return if (h > 0L) String.format(Locale.US, "-%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "-%02d:%02d", m, s)
    }

    /** Normalized [0..1] thumb fraction for [positionMs] inside [durationMs] (`1f` when duration is unknown). */
    @JvmStatic
    fun fractionFor(positionMs: Long, durationMs: Long): Float =
        if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 1f

    /** Target position (ms) for normalized [fraction] within [durationMs]. */
    @JvmStatic
    fun positionFor(fraction: Float, durationMs: Long): Long =
        if (durationMs > 0L) (fraction.coerceIn(0f, 1f) * durationMs).toLong().coerceIn(0L, durationMs) else 0L
}
