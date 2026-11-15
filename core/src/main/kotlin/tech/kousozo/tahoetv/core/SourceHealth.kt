package tech.kousozo.tahoetv.core

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

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
    )

    private const val TTL = 15 * 60_000L
    private val map = ConcurrentHashMap<String, Status>()
    // Few threads on purpose: probing runs while people watch, and must not starve the stream on a weak TV.
    private val pool = Executors.newFixedThreadPool(6) { r -> Thread(r, "tahoe-probe").apply { isDaemon = true } }
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
                        if (now - at < TTL) map[p[0]] = Status(p[1] == "1", ms, at, h, bw, sc)
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
            "$u\t${if (s.live) 1 else 0}\t${s.ms}\t${s.at}\t${s.height}\t${s.bandwidth}\t${s.score}"
        })
    }

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

    /** A player's verdict beats a probe: it really played (or really failed). */
    @JvmStatic fun report(url: String, live: Boolean) {
        if (url in banned) return
        val prev = status(url)
        map[url] = Status(live, prev?.ms ?: 0, System.currentTimeMillis(), prev?.height ?: 0, prev?.bandwidth ?: 0L, prev?.score ?: 0.0)
    }

    @JvmStatic fun reportMetrics(url: String, m: StreamSelector.Metrics) {
        if (url in banned) return
        map[url] = Status(m.live, m.latencyMs, System.currentTimeMillis(), m.height, m.bandwidthBps, m.score)
    }

    /** Probes every stream not checked in the last 15 min, in the given order (put the likely next channels first). */
    @JvmStatic @JvmOverloads fun probeAll(streams: List<M3u.Channel>, done: Runnable? = null) {
        val todo = streams.filter { status(it.url) == null && queued.add(it.url) }
        if (todo.isEmpty()) { done?.run(); return }
        val left = AtomicInteger(todo.size)
        todo.forEach { c ->
            pool.execute {
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
