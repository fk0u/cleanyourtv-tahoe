package tech.kousozo.tahoetv.core

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.exp

/** Which stream URLs answer and how fast. Every stream is probed in the background when the app starts (cached on disk
 *  for 15 min), and players report what actually played or failed, so tuning a channel starts a source known to work
 *  instead of waiting for dead ones to time out first. Shared by the TV launcher, the phone app and the desktop app. */
object SourceHealth {
    class Status(
        val live: Boolean,
        val ms: Long,
        val at: Long,
        val height: Int = 0,
        val bandwidth: Long = 0L,
        val score: Double = 0.0,
        val okCount: Int = if (live) 1 else 0,
        val failCount: Int = if (live) 0 else 1,
        val ewma: Double = if (live) 0.85 else 0.35,
        val tightWindow: Boolean = false,
    )

    private const val TTL = 15 * 60_000L
    private val map = ConcurrentHashMap<String, Status>()
    private val pool = java.util.concurrent.ThreadPoolExecutor(
        4, 6, 30L, TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue(),
    ) { r -> Thread(r, "tahoe-probe").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }.apply { allowCoreThreadTimeOut(true) }

    /** True while a stream is playing or previewing: queued probes wait, so they never compete with the picture for bandwidth
     *  and CPU (the cause of sudden buffering while the health check swept hundreds of channels). */
    @Volatile @JvmField var yieldToPlayback: Boolean = false

    /** Blocks a probe thread until playback is done (or [maxMs] passed, so a probe never waits forever). Returns the ms waited. */
    @JvmStatic @JvmOverloads internal fun waitForPlayback(maxMs: Long = 120_000L, stepMs: Long = 300L): Long {
        var waited = 0L
        while (yieldToPlayback && waited < maxMs) { Thread.sleep(stepMs); waited += stepMs }
        return waited
    }

    /** Adapts background stream-probe concurrency to TV hardware capabilities (#38). */
    @JvmStatic fun setProbeConcurrency(threads: Int) {
        val n = threads.coerceIn(1, 8)
        pool.maximumPoolSize = maxOf(n, pool.maximumPoolSize)
        pool.corePoolSize = n
        pool.maximumPoolSize = n
    }
    private val queued = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var file: File? = null
    /** Streams a person marked "this is a recording, not live". Kept across sessions; never played first again. */
    private val banned = ConcurrentHashMap.newKeySet<String>()
    private var banFile: File? = null

    internal fun resetForTest() { file = null; banFile = null; map.clear(); banned.clear(); queued.clear() }

    /** Restores the last results (if still fresh) and saves new ones to [f]. Call once at start-up.
     *  [bans]: where sources marked "not live" are kept. Must be durable storage (Android: filesDir, not cacheDir, which
     *  the system may clear); defaults to a file next to [f]. */
    @JvmStatic @JvmOverloads fun init(f: File, bans: File? = null) {
        if (file != null) return
        file = f
        banFile = (bans ?: File(f.parentFile, "source-banned.txt")).also { b -> runCatching { b.readLines().filter { it.isNotBlank() }.forEach { banned += it } } }
        val now = System.currentTimeMillis()
        runCatching {
            f.forEachLine { l ->
                runCatching {
                    val p = l.split('\t')
                    if (p.size == 4 || p.size >= 7) {
                        val ms = p[2].toLong()
                        val at = p[3].toLong()
                        val h = if (p.size >= 7) p[4].toIntOrNull() ?: 0 else 0
                        val bw = if (p.size >= 7) p[5].toLongOrNull() ?: 0L else 0L
                        val sc = if (p.size >= 7) p[6].toDoubleOrNull() ?: 0.0 else 0.0
                        val ok = if (p.size >= 10) p[7].toIntOrNull() ?: (if (p[1] == "1") 1 else 0) else (if (p[1] == "1") 1 else 0)
                        val fail = if (p.size >= 10) p[8].toIntOrNull() ?: (if (p[1] == "1") 0 else 1) else (if (p[1] == "1") 0 else 1)
                        val ewma = if (p.size >= 10) p[9].toDoubleOrNull() ?: 0.75 else if (p[1] == "1") 0.85 else 0.35
                        val tight = p.size >= 11 && p[10] == "1"
                        if (now - at < TTL) map[p[0]] = Status(p[1] == "1", ms, at, h, bw, sc, ok, fail, ewma, tight)
                    }
                }
            }
        }
    }

    private fun atomicWrite(target: File, content: String) {
        runCatching {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(content)
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    @Synchronized internal fun save() = file?.let { f ->
        atomicWrite(f, map.entries.joinToString("\n") { (u, s) ->
            "$u\t${if (s.live) 1 else 0}\t${s.ms}\t${s.at}\t${s.height}\t${s.bandwidth}\t${s.score}\t${s.okCount}\t${s.failCount}\t${s.ewma}\t${if (s.tightWindow) 1 else 0}"
        })
    }

    @JvmStatic fun isTightWindow(url: String): Boolean = map[url]?.tightWindow == true

    @JvmStatic fun status(url: String): Status? =
        if (url in banned) Status(false, 0, System.currentTimeMillis()) else map[url]?.takeIf { System.currentTimeMillis() - it.at < TTL }

    /** "This source plays a recording (yesterday's show, a loop)": skip it from now on, in every app session. */
    @JvmStatic fun ban(url: String) { banned += url; saveBans() }
    @JvmStatic fun unban(url: String) { banned -= url; saveBans() }
    @JvmStatic fun isBanned(url: String) = url in banned
    /** Undo for "not live": every marked source of this channel (main + backups) is played again. Returns how many. */
    @JvmStatic fun unbanAll(streams: List<M3u.Channel>): Int {
        val n = streams.count { banned.remove(it.url) }
        if (n > 0) saveBans()
        return n
    }
    @Synchronized private fun saveBans() { banFile?.let { f -> atomicWrite(f, banned.joinToString("\n")) } }

    /** Current EWMA reliability in 0.0..1.0 (time-decayed toward neutral prior 0.75). */
    @JvmStatic fun stability(url: String): Double {
        val raw = map[url] ?: return 0.75
        return decayedEwma(raw, System.currentTimeMillis())
    }

    private fun decayedEwma(prev: Status, now: Long): Double {
        val hours = ((now - prev.at).coerceAtLeast(0L)) / 3_600_000.0
        val prior = 0.75
        return (prior + (prev.ewma - prior) * exp(-hours / 48.0)).coerceIn(0.05, 0.99)
    }

    private fun updateEwma(prev: Status?, live: Boolean, now: Long): Triple<Int, Int, Double> {
        val alpha = 0.35
        val base = if (prev != null) decayedEwma(prev, now) else if (live) 0.80 else 0.45
        val obs = if (live) 1.0 else 0.0
        val next = (alpha * obs + (1.0 - alpha) * base).coerceIn(0.05, 0.99)
        val ok = (prev?.okCount ?: 0) + if (live) 1 else 0
        val fail = (prev?.failCount ?: 0) + if (!live) 1 else 0
        return Triple(ok, fail, next)
    }

    /** A player's verdict beats a probe: it really played (or really failed). */
    @JvmStatic fun report(url: String, live: Boolean) {
        if (url in banned) return
        val now = System.currentTimeMillis()
        val prev = map[url]
        val (ok, fail, ewma) = updateEwma(prev, live, now)
        val newScore = if (prev != null && prev.score > 0.0) {
            StreamSelector.fuzzyScore(prev.ms, prev.height, prev.bandwidth, false, ewma)
        } else prev?.score ?: 0.0
        map[url] = Status(live, prev?.ms ?: 0, now, prev?.height ?: 0, prev?.bandwidth ?: 0L, newScore, ok, fail, ewma, prev?.tightWindow ?: false)
    }

    @JvmStatic fun reportMetrics(url: String, m: StreamSelector.Metrics) {
        if (url in banned) return
        val now = System.currentTimeMillis()
        val prev = map[url]
        val (ok, fail, ewma) = updateEwma(prev, m.live, now)
        val adjustedScore = if (m.live) {
            StreamSelector.fuzzyScore(m.latencyMs, m.height, m.bandwidthBps, m.hasAbr, ewma) - if (m.tightWindow) 4.0 else 0.0
        } else 0.0
        map[url] = Status(m.live, m.latencyMs, now, m.height, m.bandwidthBps, adjustedScore.coerceAtLeast(0.0), ok, fail, ewma, m.tightWindow)
    }

    /** Probes every stream not checked in the last 15 min, in the given order (put the likely next channels first). */
    @JvmStatic @JvmOverloads fun probeAll(streams: List<M3u.Channel>, done: Runnable? = null) {
        val todo = streams.filter { status(it.url) == null && queued.add(it.url) }
        if (todo.isEmpty()) { done?.run(); return }
        val left = AtomicInteger(todo.size)
        todo.forEach { c ->
            pool.execute {
                waitForPlayback()
                reportMetrics(c.url, StreamSelector.inspect(c, 3500))
                queued.remove(c.url)
                val rem = left.decrementAndGet()
                if (rem == 0) { save(); done?.run() } else if (rem % 50 == 0) save()
            }
        }
    }

    /** Main stream + backups, best first: live by fuzzy score desc (or latency asc), then unknown, then known dead. Sources marked as recordings are left out. */
    @JvmStatic fun order(streams: List<M3u.Channel>): List<M3u.Channel> = streams.filterNot { it.url in banned }.sortedBy { s ->
        val st = status(s.url)
        when {
            st == null -> 1_000_000.0
            st.live -> if (st.score > 0.0) (1000.0 - st.score) + (st.ms.coerceAtMost(9999L) / 10000.0) else st.ms.toDouble()
            else -> 10_000_000.0
        }
    }

    /** true = some source live, false = every source checked and dead, null = not known yet. */
    @JvmStatic fun live(streams: List<M3u.Channel>): Boolean? {
        val st = streams.map { status(it.url) }
        return when { st.any { it?.live == true } -> true; st.all { it != null } -> false; else -> null }
    }

    /** Every stream of [entries] (main + backups), for [probeAll]. */
    @JvmStatic fun streamsOf(entries: List<LocalTv.Entry>): List<M3u.Channel> = entries.flatMap { listOf(it.channel) + it.alternates }
}
