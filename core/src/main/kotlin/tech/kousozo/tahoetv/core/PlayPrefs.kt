package tech.kousozo.tahoetv.core

/** Viewer choices shared by every player (TV, phone, desktop): the highest video quality to use, and how far behind the
 *  live point to play. Stored by key, so each app keeps them in its own settings. */
object PlayPrefs {
    /** [maxHeight] 0 = no cap (the player adapts to the connection). A cap picks the best variant at or under it; a stream
     *  that only has bigger variants plays its smallest one. */
    enum class Quality(val key: String, val label: String, val maxHeight: Int) {
        Auto("auto", "Otomatis", 0), P1080("1080", "1080p", 1080), P720("720", "720p", 720), P480("480", "480p", 480);
        companion object {
            @JvmStatic fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Auto
        }
    }

    /** How far behind live to play. Closer = more like live TV but more pauses on a slow server; further = steadier. */
    enum class Latency(val key: String, val label: String, val note: String, val offsetMs: Long) {
        Low("low", "Rendah", "±8 detik di belakang siaran", 8_000),
        Standard("standard", "Standar", "±20 detik, seimbang", 20_000),
        Stable("stable", "Stabil", "±35 detik, paling sedikit jeda", 35_000);
        companion object {
            @JvmStatic fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Standard
        }
    }

    /** Next option in the list, wrapping around (◀ ▶ on the TV). */
    @JvmStatic fun next(q: Quality, dir: Int) = Quality.entries[((q.ordinal + dir) % Quality.entries.size + Quality.entries.size) % Quality.entries.size]
    @JvmStatic fun next(l: Latency, dir: Int) = Latency.entries[((l.ordinal + dir) % Latency.entries.size + Latency.entries.size) % Latency.entries.size]

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
        // variants without a resolution are dropped too once others have one: they could be any size
        val keep = known.filter { it.h <= maxHeight }.ifEmpty { listOf(known.minBy { it.h }) }.toSet()
        val drop = (variants - keep).flatMap { listOf(it.inf, it.uri) }.toSet()
        return lines.filterIndexed { i, _ -> i !in drop }.joinToString("\n")
    }
}
