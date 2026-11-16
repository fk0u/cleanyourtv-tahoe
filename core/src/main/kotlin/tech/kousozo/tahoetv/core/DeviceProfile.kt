package tech.kousozo.tahoetv.core

import java.util.concurrent.ConcurrentHashMap

/** Hardware capability classification & adaptive runtime budget for TV devices (#38). */
object DeviceProfile {
    enum class Tier(val key: String) {
        LIGHT("light"),
        STANDARD("standard"),
        FULL("full");

        fun label(lang: String): String = I18n.get(lang, "device.tier_$key")

        companion object {
            @JvmStatic
            fun of(key: String?): Tier? = entries.firstOrNull { it.key == key?.trim()?.lowercase() }
        }
    }

    data class Specs(
        val ramMb: Int,
        val isLowRam: Boolean,
        val cpuCores: Int,
        val apiLevel: Int,
        val abi: String,
        val widthPx: Int,
        val heightPx: Int,
        val refreshHz: Int = 60,
        val hdrSupported: Boolean = false,
        val hwHevc: Boolean = false,
        val hwAv1: Boolean = false,
        val hwAc3: Boolean = false,
    )

    data class Profile(
        val detectedTier: Tier,
        val activeTier: Tier,
        val manualOverride: Tier?,
        val specs: Specs,
        val enableHeroBlur: Boolean,
        val heroAnimMs: Long,
        val imageCacheKb: Int,
        val probeConcurrency: Int,
        val recommendedQuality: PlayPrefs.Quality,
        val minBufferMs: Int,
        val maxBufferMs: Int,
    )

    @Volatile var startupMs: Long = -1L
        private set

    private val firstFrameMsByChannel = ConcurrentHashMap<String, Long>()

    @JvmStatic
    fun recordStartupMs(ms: Long) {
        if (ms < 0L) return
        startupMs = ms
        DiagLog.add("perf", "startup -> home ${ms}ms")
    }

    @JvmStatic
    fun recordFirstFrameMs(channelName: String, ms: Long) {
        if (ms < 0L) return
        firstFrameMsByChannel[channelName] = ms
        DiagLog.add("perf", "tune -> first frame ($channelName) ${ms}ms")
    }

    @JvmStatic
    fun lastFirstFrameMs(channelName: String): Long? = firstFrameMsByChannel[channelName]

    @JvmStatic
    @JvmOverloads
    fun classify(specs: Specs, overrideTier: Tier? = null): Profile {
        val detected = when {
            specs.isLowRam || specs.ramMb in 1..1535 || specs.cpuCores <= 2 || specs.apiLevel in 1..25 -> Tier.LIGHT
            specs.ramMb >= 3072 && specs.cpuCores >= 4 && specs.apiLevel >= 29 -> Tier.FULL
            specs.ramMb >= 2560 && specs.cpuCores >= 4 && specs.hwHevc && specs.heightPx >= 1080 -> Tier.FULL
            else -> Tier.STANDARD
        }
        val active = overrideTier ?: detected
        return when (active) {
            Tier.LIGHT -> Profile(
                detectedTier = detected,
                activeTier = active,
                manualOverride = overrideTier,
                specs = specs,
                enableHeroBlur = false,
                heroAnimMs = 0L,
                imageCacheKb = 16 * 1024,
                probeConcurrency = 2,
                recommendedQuality = PlayPrefs.Quality.P720,
                minBufferMs = 15_000,
                maxBufferMs = 30_000,
            )
            Tier.STANDARD -> Profile(
                detectedTier = detected,
                activeTier = active,
                manualOverride = overrideTier,
                specs = specs,
                enableHeroBlur = false,
                heroAnimMs = 180L,
                imageCacheKb = 32 * 1024,
                probeConcurrency = 4,
                recommendedQuality = PlayPrefs.Quality.Auto,
                minBufferMs = 20_000,
                maxBufferMs = 50_000,
            )
            Tier.FULL -> Profile(
                detectedTier = detected,
                activeTier = active,
                manualOverride = overrideTier,
                specs = specs,
                enableHeroBlur = true,
                heroAnimMs = 280L,
                imageCacheKb = 64 * 1024,
                probeConcurrency = 6,
                recommendedQuality = PlayPrefs.Quality.Auto,
                minBufferMs = 24_000,
                maxBufferMs = 60_000,
            )
        }
    }

    @JvmStatic
    fun cycleOverride(current: Tier?): Tier? = when (current) {
        null -> Tier.LIGHT
        Tier.LIGHT -> Tier.STANDARD
        Tier.STANDARD -> Tier.FULL
        Tier.FULL -> null
    }
}
