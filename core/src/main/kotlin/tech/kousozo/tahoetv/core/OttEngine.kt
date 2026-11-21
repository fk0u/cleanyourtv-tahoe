package tech.kousozo.tahoetv.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * OTT IPTV Provider & Failover Engine.
 *
 * Implements primary OTT server routing with automatic failover:
 * 1. Attempts the configured primary OTT server (with OTT client headers such as OTTNavigator User-Agent, Referer, and metadata).
 * 2. If the primary server responds with valid streams, prioritizes its channels and ingests any XMLTV EPG (`url-tvg`) automatically.
 * 3. If the primary server is unreachable, times out, or returns empty channels, seamlessly fails over to the built-in backup servers.
 * 4. Maintains an active fallback index so individual stream failures during playback can immediately switch over to backup streams.
 */
object OttEngine {

    const val DEFAULT_PRIMARY_OTT_URL = "https://plsemar.pages.dev"
    const val DEFAULT_PRIMARY_EPG_URL = "https://raw.githubusercontent.com/AqFad2811/epg/refs/heads/main/indonesia.xml"
    const val DEFAULT_OTT_UA = "TiviMate/4.7.0"

    enum class ServerStatus {
        /** Initial unverified state */
        STANDBY,
        /** Primary OTT server is reachable and active */
        ONLINE,
        /** Primary OTT server unreachable or errored; automatic fallback to built-in servers is active */
        FALLBACK,
        /** Both primary and fallback experienced errors */
        ERROR,
    }

    data class OttConfig(
        val primaryServerUrl: String = DEFAULT_PRIMARY_OTT_URL,
        val userAgent: String = DEFAULT_OTT_UA,
        val fallbackEnabled: Boolean = true,
        val headers: Map<String, String> = emptyMap(),
        val timeoutMs: Int = 10_000,
    )

    @Volatile
    var config: OttConfig = OttConfig()
        private set

    @Volatile
    var status: ServerStatus = ServerStatus.STANDBY
        private set

    @Volatile
    var lastEpgUrl: String? = DEFAULT_PRIMARY_EPG_URL
        private set

    @Volatile
    var lastCheckedAt: Long = 0L
        private set

    private val fallbackChannels = ConcurrentHashMap<String, M3u.Channel>()
    private val bgPool = Executors.newFixedThreadPool(2) { r -> Thread(r, "tahoe-ott-engine").apply { isDaemon = true } }

    /** True when a primary OTT server URL is configured and active. */
    @JvmStatic
    fun isPrimaryConfigured(): Boolean = config.primaryServerUrl.isNotBlank()

    /** Resets configuration to default values (including default primary OTT server). */
    @JvmStatic
    fun resetDefault() {
        config = OttConfig()
        status = ServerStatus.STANDBY
        lastEpgUrl = DEFAULT_PRIMARY_EPG_URL
    }

    /** Configures the primary OTT server URL, User-Agent, and optional request headers. */
    @JvmStatic
    @JvmOverloads
    fun setPrimaryServer(url: String, userAgent: String? = null, headers: Map<String, String>? = null) {
        val cleanUrl = url.trim()
        val ua = userAgent?.trim()?.takeIf { it.isNotEmpty() } ?: config.userAgent
        val h = headers ?: config.headers
        config = config.copy(
            primaryServerUrl = cleanUrl,
            userAgent = ua,
            headers = h,
        )
        status = if (cleanUrl.isBlank()) ServerStatus.STANDBY else ServerStatus.STANDBY
    }

    /** Enables or disables automatic fallback to built-in servers. */
    @JvmStatic
    fun setFallbackEnabled(enabled: Boolean) {
        config = config.copy(fallbackEnabled = enabled)
    }

    /** Indexes fallback channels for stream-level failover. */
    @JvmStatic
    fun registerFallbackChannels(channels: List<M3u.Channel>) {
        channels.forEach { ch ->
            fallbackChannels[ch.channelId] = ch
            fallbackChannels[M3u.slug(ch.name)] = ch
        }
    }

    /**
     * Looks up an alternative playable stream from the fallback channel pool for [failedChannel]
     * when its primary stream experiences an error (403, 404, or disconnect).
     */
    @JvmStatic
    fun findFallbackStream(failedChannel: M3u.Channel): M3u.Channel? {
        val match = fallbackChannels[failedChannel.channelId]
            ?: fallbackChannels[M3u.slug(failedChannel.name)]
        return match?.takeIf { it.url != failedChannel.url }
    }

    /**
     * Loads channels prioritizing the primary OTT server, automatically falling back to built-in
     * sources if the primary server fails or is empty.
     */
    @JvmStatic
    @JvmOverloads
    fun loadChannels(
        extra: String? = null,
        countries: List<String> = listOf(Region.HOME),
        languages: List<String> = emptyList(),
        onPriorityReady: ((List<M3u.Channel>) -> Unit)? = null,
    ): List<M3u.Channel> {
        val primaryUrl = config.primaryServerUrl.trim()
        if (primaryUrl.isNotEmpty()) {
            val primaryChannels = runCatching { fetchFromPrimary(primaryUrl) }.getOrNull()
            if (!primaryChannels.isNullOrEmpty()) {
                status = ServerStatus.ONLINE
                lastCheckedAt = System.currentTimeMillis()
                onPriorityReady?.invoke(primaryChannels)

                // Combine primary OTT channels with built-in sources (DEFAULT) so all channels
                // are available and the most stable, low-buffering streams are selected.
                if (config.fallbackEnabled) {
                    val fallback = Sources.loadBuiltInFallback(countries, languages)
                    registerFallbackChannels(fallback)
                    return (primaryChannels + fallback).distinctBy { it.url }
                }
                return primaryChannels
            } else {
                DiagLog.add(
                    "ott",
                    "Primary OTT server ($primaryUrl) tidak merespons atau daftar saluran kosong. Beralih otomatis ke server fallback built-in."
                )
                status = ServerStatus.FALLBACK
                lastCheckedAt = System.currentTimeMillis()
            }
        } else {
            status = ServerStatus.STANDBY
        }

        // Fallback to existing built-in sources
        val fallback = Sources.loadBuiltInFallback(countries, languages, extra, onPriorityReady)
        registerFallbackChannels(fallback)
        return fallback
    }

    /** Fetches and parses playlist from the primary OTT server. */
    private fun fetchFromPrimary(url: String): List<M3u.Channel> {
        val candidates = listOf(config.userAgent, "TiviMate/4.7.0", "OTT Navigator/1.7.5.1")
            .filter { it.isNotBlank() }
            .distinct()

        for (ua in candidates) {
            val reqHeaders = LinkedHashMap<String, String>().apply {
                put("User-Agent", ua)
                putAll(config.headers)
            }
            val text = runCatching { Web.get(url, timeoutMs = config.timeoutMs, headers = reqHeaders) }.getOrNull()
            if (text.isNullOrBlank()) continue

            val trimmed = text.trim()
            val lower = trimmed.lowercase()
            // Cloudflare worker / server redirect landing page protection ("Akses Channel lewat OTT Navigator yak")
            if (lower.startsWith("<!doctype html") || lower.startsWith("<html")) continue

            if (M3u.isOttJson(trimmed)) {
                val (meta, channels) = M3u.parseOttJson(trimmed)
                if (channels.isNotEmpty()) {
                    if (!meta.epgUrl.isNullOrBlank()) lastEpgUrl = meta.epgUrl
                    return channels.map { ch ->
                        if (!ch.headers.containsKey("User-Agent") && ua.isNotEmpty()) {
                            ch.copy(headers = ch.headers + ("User-Agent" to ua))
                        } else ch
                    }
                }
            }

            val meta = M3u.parseMetadata(trimmed)
            if (!meta.epgUrl.isNullOrBlank()) {
                lastEpgUrl = meta.epgUrl
            }

            val parsed = M3u.parse(trimmed)
            if (parsed.isNotEmpty()) {
                return parsed.map { ch ->
                    if (!ch.headers.containsKey("User-Agent") && ua.isNotEmpty()) {
                        ch.copy(headers = ch.headers + ("User-Agent" to ua))
                    } else ch
                }
            }
        }
        return emptyList()
    }
}
