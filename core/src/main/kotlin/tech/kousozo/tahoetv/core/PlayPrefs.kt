package tech.kousozo.tahoetv.core

/** Viewer choices shared by every player (TV, phone, desktop): the highest video quality to use, and how far behind the
 *  live point to play. Stored by key, so each app keeps them in its own settings. */
object PlayPrefs {
    /** [maxHeight] 0 = no cap (the player adapts to the connection). A cap picks the best variant at or under it; a stream
     *  that only has bigger variants plays its smallest one. */
    enum class Quality(val key: String, val label: String, val maxHeight: Int) {
        Auto("auto", "Otomatis", 0), P1080("1080", "1080p", 1080), P720("720", "720p", 720), P480("480", "480p", 480);
        fun label(lang: String): String = I18n.get(lang, "quality.$key")
        companion object {
            @JvmStatic fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Auto
        }
    }

    /** How far behind live to play. Closer = more like live TV but more pauses on a slow server; further = steadier. */
    enum class Latency(val key: String, val label: String, val note: String, val offsetMs: Long) {
        Low("low", "Rendah", "±8 detik di belakang siaran", 8_000),
        Standard("standard", "Standar", "±20 detik, seimbang", 20_000),
        Stable("stable", "Stabil", "±35 detik, paling sedikit jeda", 35_000);
        fun label(lang: String): String = I18n.get(lang, "latency.$key")
        fun note(lang: String): String = I18n.get(lang, "latency.$key.note")
        companion object {
            @JvmStatic fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Standard
        }
    }

    /** Next option in the list, wrapping around (◀ ▶ on the TV). */
    @JvmStatic fun next(q: Quality, dir: Int) = Quality.entries[((q.ordinal + dir) % Quality.entries.size + Quality.entries.size) % Quality.entries.size]
    @JvmStatic fun next(l: Latency, dir: Int) = Latency.entries[((l.ordinal + dir) % Latency.entries.size + Latency.entries.size) % Latency.entries.size]

    /** ExoPlayer buffer & live-offset configuration tuned for stream characteristics and TV hardware tier (#32, #38). */
    data class PlaybackTuning(
        val targetOffsetMs: Long,
        val minOffsetMs: Long,
        val maxOffsetMs: Long,
        val minPlaybackSpeed: Float,
        val maxPlaybackSpeed: Float,
        val minBufferMs: Int,
        val maxBufferMs: Int,
        val bufferForPlaybackMs: Int,
        val bufferForPlaybackAfterRebufferMs: Int,
    )

    @JvmStatic
    @JvmOverloads
    fun tuningFor(latency: Latency, tightWindow: Boolean = false, lightDevice: Boolean = false): PlaybackTuning {
        val target = if (tightWindow && latency == Latency.Low) 12_000L else latency.offsetMs
        val minOff = maxOf(4_000L, target / 2L)
        val maxOff = maxOf(target + if (tightWindow) 25_000L else 15_000L, target * 2L)
        val minSpeed = 0.96f
        val maxSpeed = when {
            latency == Latency.Stable || tightWindow -> 1.00f
            latency == Latency.Standard -> 1.01f
            else -> 1.02f
        }
        val baseMinBuf = when (latency) {
            Latency.Low -> if (lightDevice) 10_000 else 14_000
            Latency.Standard -> if (lightDevice) 16_000 else 22_000
            Latency.Stable -> if (lightDevice) 22_000 else 32_000
        }
        val baseMaxBuf = when (latency) {
            Latency.Low -> if (lightDevice) 24_000 else 36_000
            Latency.Standard -> if (lightDevice) 30_000 else 50_000
            Latency.Stable -> if (lightDevice) 38_000 else 65_000
        }
        val startBuf = when (latency) {
            Latency.Low -> if (tightWindow) 3_000 else 2_500
            Latency.Standard -> if (tightWindow) 4_000 else 3_200
            Latency.Stable -> 5_000
        }
        val rebuf = when (latency) {
            Latency.Low -> if (tightWindow) 5_000 else 4_500
            Latency.Standard -> if (tightWindow) 7_000 else 5_500
            Latency.Stable -> 8_500
        }
        return PlaybackTuning(target, minOff, maxOff, minSpeed, maxSpeed, baseMinBuf, baseMaxBuf, startBuf, rebuf)
    }

    /** Subtitle caption font scale & accessibility preference (#51). */
    enum class SubtitleSize(val key: String, val spTv: Float, val spPhone: Float) {
        Normal("normal", 20f, 15f),
        Large("large", 25f, 18f),
        ExtraLarge("xlarge", 30f, 22f);

        fun label(lang: String): String = I18n.get(lang, "sub.size_$key")

        companion object {
            @JvmStatic fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Normal
            @JvmStatic fun next(s: SubtitleSize, dir: Int) = entries[((s.ordinal + dir) % entries.size + entries.size) % entries.size]
        }
    }

    /** Stable key for remembering audio/subtitle language preferences per channel (#51). */
    @JvmStatic
    fun channelTrackKey(channelId: String?, name: String): String =
        channelId?.trim()?.takeIf { it.isNotEmpty() } ?: name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_")

    private val CODECS = Regex("""CODECS="([^"]*)"""")
    private val VIDEO = Regex("""^(avc|hvc|hev|vp0?[89]|av01|mp4v|dvh)""")
    private fun isAudioOnly(inf: String): Boolean {
        val codecs = CODECS.find(inf)?.groupValues?.get(1)?.split(',')?.map { it.trim().lowercase() } ?: return false
        return codecs.isNotEmpty() && codecs.none { VIDEO.containsMatchIn(it) }
    }
    private val RES = Regex("""RESOLUTION=\d+x(\d+)""")

    /** A master playlist limited to [maxHeight]: keeps the variants at or under it (or the smallest one if none fit), so
     *  players that can't choose a quality themselves (JavaFX) play the chosen one. Other playlists come back unchanged. */
    @JvmStatic fun capVariants(text: String, maxHeight: Int): String {
        if (maxHeight <= 0 || "#EXT-X-STREAM-INF" !in text) return text
        val lines = text.lines()
        data class V(val inf: Int, val uri: Int, val h: Int)
        val variants = lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF") }.mapNotNull { i ->
            val u = (i + 1 until lines.size).firstOrNull { lines[it].isNotBlank() && !lines[it].startsWith("#") } ?: return@mapNotNull null
            V(i, u, RES.find(lines[i])?.groupValues?.get(1)?.toIntOrNull() ?: 0)
        }
        if (variants.isEmpty()) return text
        val known = variants.filter { it.h > 0 }
        if (known.isEmpty()) return text // no resolutions given: can't tell, leave it to the player
        // variants without a resolution are dropped too once others have one (they could be any size), except
        // audio-only ones (CODECS without a video codec): those have no size to cap
        val audioOnly = variants.filter { it.h == 0 && isAudioOnly(lines[it.inf]) }
        val keep = (known.filter { it.h <= maxHeight }.ifEmpty { listOf(known.minBy { it.h }) } + audioOnly).toSet()
        val drop = (variants - keep).flatMap { listOf(it.inf, it.uri) }.toSet()
        return lines.filterIndexed { i, _ -> i !in drop }.joinToString("\n")
    }
}
