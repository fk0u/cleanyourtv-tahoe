package tech.kousozo.tahoetv.core

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * Multi-stage server selection & Mamdani-inspired fuzzy scoring for IPTV streams.
 *
 * Stage 1 (Liveness): checks reachability & valid live playlist/MPD across candidate servers.
 * Stage 2 (Inspection): measures response latency and extracts resolution, bandwidth, and ABR support.
 * Stage 3 (Fuzzy Selection): evaluates fuzzy memberships for latency, video quality, stability, and ABR,
 * returning a 0..100 suitability score so the highest-scoring finalist server is used first.
 */
object StreamSelector {
    data class Metrics(
        val live: Boolean,
        val latencyMs: Long,
        val height: Int = 0,
        val bandwidthBps: Long = 0L,
        val hasAbr: Boolean = false,
        val score: Double = 0.0,
        val tightWindow: Boolean = false,
    )

    private val HEIGHT_RE = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
    private val BW_RE = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
    private val MPD_HEIGHT_RE = Regex("""height="(\d+)"""", RegexOption.IGNORE_CASE)
    private val MPD_BW_RE = Regex("""bandwidth="(\d+)"""", RegexOption.IGNORE_CASE)

    private val POOL = Executors.newFixedThreadPool(16) { r ->
        Thread(r, "tahoe-selector").apply { isDaemon = true }
    }

    /** Fuzzy triangular/trapezoidal membership: 1.0 when x <= lo, 0.0 when x >= hi. */
    private fun rampDown(x: Double, lo: Double, hi: Double): Double = when {
        x <= lo -> 1.0
        x >= hi -> 0.0
        else -> (hi - x) / (hi - lo)
    }

    /** Fuzzy triangular/trapezoidal membership: 0.0 when x <= lo, 1.0 when x >= hi. */
    private fun rampUp(x: Double, lo: Double, hi: Double): Double = when {
        x <= lo -> 0.0
        x >= hi -> 1.0
        else -> (x - lo) / (hi - lo)
    }

    /** Computes a 0..100 fuzzy suitability score from latency, resolution, bandwidth, ABR, and reliability. */
    @JvmStatic
    fun fuzzyScore(
        latencyMs: Long,
        height: Int,
        bandwidthBps: Long,
        hasAbr: Boolean,
        stability: Double = 0.75
    ): Double {
        val ms = latencyMs.coerceAtLeast(1L).toDouble()
        // Latency fuzzy sets
        val fast = rampDown(ms, 200.0, 900.0)
        val medium = min(rampUp(ms, 200.0, 700.0), rampDown(ms, 900.0, 2200.0))
        val slow = rampUp(ms, 1500.0, 3500.0)
        val latencyScore = (fast * 1.0 + medium * 0.58 + slow * 0.12) /
            (fast + medium + slow).coerceAtLeast(1e-6)

        // Quality fuzzy sets (unknown resolution defaults to neutral 0.50)
        val qualityScore = when {
            height >= 1080 -> 1.0
            height >= 720 -> 0.82 + 0.18 * rampUp(height.toDouble(), 720.0, 1080.0)
            height >= 480 -> 0.52 + 0.30 * rampUp(height.toDouble(), 480.0, 720.0)
            height > 0 -> 0.25 + 0.27 * rampUp(height.toDouble(), 240.0, 480.0)
            bandwidthBps > 0 -> 0.35 + 0.55 * rampUp(bandwidthBps.toDouble(), 400_000.0, 5_000_000.0)
            else -> 0.50
        }

        // Bandwidth & ABR fuzzy bonus
        val bwNorm = if (bandwidthBps > 0) rampUp(bandwidthBps.toDouble(), 300_000.0, 6_000_000.0) else 0.45
        val abrNorm = if (hasAbr) 1.0 else 0.35
        val formatScore = bwNorm * 0.65 + abrNorm * 0.35

        val combined = qualityScore * 0.40 +
            latencyScore * 0.35 +
            stability.coerceIn(0.0, 1.0) * 0.15 +
            formatScore * 0.10
        return (combined * 100.0).coerceIn(0.0, 100.0)
    }

    /** Stage 1 + 2 + 3 inspection of a single candidate stream. */
    @JvmStatic
    fun inspect(c: M3u.Channel, timeoutMs: Int = 3000): Metrics {
        val t0 = System.nanoTime()
        return runCatching {
            val con = Web.openFollowingRedirects(c.url, timeoutMs, c.headers, connectTimeoutMs = timeoutMs)
            val text: String
            val finalUrl: String
            try {
                if (con.responseCode !in 200..299) return Metrics(false, elapsedMs(t0))
                finalUrl = con.url.toString()
                val out = java.io.ByteArrayOutputStream()
                Web.body(con)?.use { s ->
                    val buf = ByteArray(8192)
                    while (out.size() < 256 * 1024) {
                        val n = s.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
                text = out.toString("UTF-8")
            } finally {
                con.disconnect()
            }

            val stability = SourceHealth.stability(c.url)
            val ms = elapsedMs(t0)
            val trimmed = text.trimStart()
            if (c.isDash || trimmed.contains("<MPD", ignoreCase = true)) {
                val low = trimmed.lowercase()
                if (("<mpd" !in low && "<?xml" !in low) || low.startsWith("<html") || low.startsWith("<!doctype html")) {
                    return Metrics(false, ms)
                }
                val heights = MPD_HEIGHT_RE.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
                val bws = MPD_BW_RE.findAll(text).mapNotNull { it.groupValues[1].toLongOrNull() }.toList()
                val maxH = heights.maxOrNull() ?: 0
                val maxBw = bws.maxOrNull() ?: 0L
                val abr = heights.size > 1 || bws.size > 1
                val score = fuzzyScore(ms, maxH, maxBw, abr, stability)
                return Metrics(true, ms, maxH, maxBw, abr, score)
            }

            if (c.url.contains(".m3u8", true) || trimmed.startsWith("#EXTM3U")) {
                if (!trimmed.startsWith("#EXTM3U")) return Metrics(false, ms)
                if (trimmed.contains("#EXT-X-STREAM-INF")) {
                    val heights = HEIGHT_RE.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
                    val bws = BW_RE.findAll(text).mapNotNull { it.groupValues[1].toLongOrNull() }.toList()
                    val liveVar = if (LocalTv.isVod(c)) null else HlsProbe.liveVariant(text, finalUrl, c.headers, timeoutMs)
                    val pm = when {
                        LocalTv.isVod(c) -> HlsProbe.PlaylistMetrics(HlsProbe.Kind.LIVE)
                        liveVar == null -> HlsProbe.PlaylistMetrics(HlsProbe.Kind.DEAD)
                        else -> HlsProbe.inspectMedia(liveVar.second.first)
                    }
                    if (pm.kind == HlsProbe.Kind.DEAD || pm.kind == HlsProbe.Kind.REPLAY || pm.kind == HlsProbe.Kind.STALE) {
                        return Metrics(false, ms)
                    }
                    val maxH = heights.maxOrNull() ?: 0
                    val maxBw = bws.maxOrNull() ?: 0L
                    val abr = HlsProbe.variants(text, finalUrl).size > 1
                    val score = (fuzzyScore(ms, maxH, maxBw, abr, stability) - if (pm.tightLiveWindow) 4.0 else 0.0).coerceAtLeast(0.0)
                    return Metrics(true, ms, maxH, maxBw, abr, score, pm.tightLiveWindow)
                }
                val pm = if (LocalTv.isVod(c)) HlsProbe.PlaylistMetrics(HlsProbe.Kind.LIVE) else HlsProbe.inspectMedia(text)
                if (pm.kind == HlsProbe.Kind.REPLAY || pm.kind == HlsProbe.Kind.STALE || pm.kind == HlsProbe.Kind.DEAD) {
                    return Metrics(false, ms)
                }
                val score = (fuzzyScore(ms, 0, 0L, false, stability) - if (pm.tightLiveWindow) 4.0 else 0.0).coerceAtLeast(0.0)
                return Metrics(true, ms, 0, 0L, false, score, pm.tightLiveWindow)
            }

            Metrics(true, ms, 0, 0L, false, fuzzyScore(ms, 0, 0L, false, stability))
        }.getOrElse { Metrics(false, elapsedMs(t0)) }
    }

    private val zapGeneration = AtomicLong(0L)

    /** Increments and returns the active zap generation token so superseded channel probes abort cleanly. */
    @JvmStatic fun newZapGeneration(): Long = zapGeneration.incrementAndGet()

    @JvmStatic fun currentZapGeneration(): Long = zapGeneration.get()

    /**
     * Multi-stage parallel tournament across [streams] (called when opening a channel or during deep health check).
     * Stage 1 filters live candidates, Stage 2 inspects quality/latency, Stage 3 ranks via fuzzy score and records
     * results into [SourceHealth] so the winning finalist plays first across all platforms.
     */
    @JvmStatic @JvmOverloads
    fun rankOnOpen(
        streams: List<M3u.Channel>,
        timeoutMs: Int = 2500,
        generation: Long = zapGeneration.get(),
    ): List<M3u.Channel> {
        val active = streams.filterNot { SourceHealth.isBanned(it.url) }
        if (active.size <= 1) return active
        if (zapGeneration.get() != generation) return SourceHealth.order(active)
        // If every candidate already has a fresh quality score in SourceHealth, just order immediately
        if (active.all { SourceHealth.status(it.url)?.score?.let { s -> s > 0.0 } == true || SourceHealth.status(it.url)?.live == false }) {
            return SourceHealth.order(active)
        }
        val jobs = active.map { c ->
            c to POOL.submit<Metrics> {
                if (zapGeneration.get() != generation) Metrics(false, 0) else inspect(c, timeoutMs)
            }
        }
        val deadline = System.currentTimeMillis() + timeoutMs + 700L
        for ((c, future) in jobs) {
            if (zapGeneration.get() != generation || Thread.currentThread().isInterrupted) {
                jobs.forEach { it.second.cancel(true) }
                break
            }
            val rem = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
            val m = runCatching { future.get(rem, TimeUnit.MILLISECONDS) }.getOrElse {
                future.cancel(true)
                null
            }
            if (m != null && zapGeneration.get() == generation) SourceHealth.reportMetrics(c.url, m)
        }
        return SourceHealth.order(active)
    }

    /** Normalized Jaro-Winkler / Bigram fuzzy string similarity in 0.0..1.0 for channel & programme matching. */
    @JvmStatic
    fun fuzzySimilarity(a: String, b: String): Double {
        val s1 = a.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
        val s2 = b.trim().lowercase().replace(Regex("[^a-z0-9]"), "")
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        if (s1.length == 1 || s2.length == 1) return if (s1 == s2) 1.0 else 0.0
        val bigrams1 = (0 until s1.length - 1).map { s1.substring(it, it + 2) }.toMutableList()
        val bigrams2 = (0 until s2.length - 1).map { s2.substring(it, it + 2) }.toMutableList()
        var matches = 0
        for (bg in bigrams1) {
            val idx = bigrams2.indexOf(bg)
            if (idx >= 0) {
                matches++
                bigrams2.removeAt(idx)
            }
        }
        return (2.0 * matches) / (s1.length - 1 + s2.length - 1)
    }

    private fun elapsedMs(t0: Long): Long = max(1L, (System.nanoTime() - t0) / 1_000_000L)
}
