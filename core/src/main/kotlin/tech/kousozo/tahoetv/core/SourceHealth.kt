package tech.kousozo.tahoetv.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Which stream URLs answer and how fast. Every stream is probed in the background when the app starts (cached on disk
 *  for 15 min), and players report what actually played or failed, so tuning a channel starts a source known to work
 *  instead of waiting for dead ones to time out first. Shared by the TV launcher, the phone app and the desktop app. */
object SourceHealth {
    class Status(val live: Boolean, val ms: Long, val at: Long)

    private const val TTL = 15 * 60_000L
    private val map = ConcurrentHashMap<String, Status>()
    // Few threads on purpose: probing runs while people watch, and must not starve the stream on a weak TV.
    private val pool = Executors.newFixedThreadPool(6) { r -> Thread(r, "tahoe-probe").apply { isDaemon = true } }
    private val queued = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var file: File? = null

    /** Restores the last results (if still fresh) and saves new ones to [f]. Call once at start-up. */
    @JvmStatic fun init(f: File) {
        if (file != null) return
        file = f
        val now = System.currentTimeMillis()
        runCatching {
            f.readLines().forEach { l ->
                val p = l.split('\t')
                if (p.size == 4 && now - p[3].toLong() < TTL) map[p[0]] = Status(p[1] == "1", p[2].toLong(), p[3].toLong())
            }
        }
    }

    private fun save() = file?.let { f ->
        runCatching { f.writeText(map.entries.joinToString("\n") { (u, s) -> "$u\t${if (s.live) 1 else 0}\t${s.ms}\t${s.at}" }) }
    }

    @JvmStatic fun status(url: String): Status? = map[url]?.takeIf { System.currentTimeMillis() - it.at < TTL }

    /** A player's verdict beats a probe: it really played (or really failed). */
    @JvmStatic fun report(url: String, live: Boolean) { map[url] = Status(live, status(url)?.ms ?: 0, System.currentTimeMillis()) }

    /** Probes every stream not checked in the last 15 min, in the given order (put the likely next channels first). */
    @JvmStatic @JvmOverloads fun probeAll(streams: List<M3u.Channel>, done: Runnable? = null) {
        val todo = streams.filter { status(it.url) == null && queued.add(it.url) }
        if (todo.isEmpty()) { done?.run(); return }
        val left = AtomicInteger(todo.size)
        todo.forEach { c ->
            pool.execute {
                val t = System.nanoTime()
                val live = Sources.check(c, 3500)
                map[c.url] = Status(live, (System.nanoTime() - t) / 1_000_000, System.currentTimeMillis())
                queued.remove(c.url)
                if (left.decrementAndGet() == 0) { save(); done?.run() } else if (left.get() % 50 == 0) save()
            }
        }
    }

    /** Main stream + backups, best first: live by latency, then unknown (original order), then known dead. */
    @JvmStatic fun order(streams: List<M3u.Channel>): List<M3u.Channel> = streams.sortedBy { s ->
        val st = status(s.url)
        when { st == null -> 1_000_000L; st.live -> st.ms; else -> 10_000_000L }
    }

    /** true = some source live, false = every source checked and dead, null = not known yet. */
    @JvmStatic fun live(streams: List<M3u.Channel>): Boolean? {
        val st = streams.map { status(it.url) }
        return when { st.any { it?.live == true } -> true; st.all { it != null } -> false; else -> null }
    }

    /** Every stream of [entries] (main + backups), for [probeAll]. */
    @JvmStatic fun streamsOf(entries: List<LocalTv.Entry>): List<M3u.Channel> = entries.flatMap { listOf(it.channel) + it.alternates }
}
