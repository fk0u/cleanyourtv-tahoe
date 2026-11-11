package tech.kousozo.tahoetv.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import tech.kousozo.tahoetv.core.HlsRecorder
import tech.kousozo.tahoetv.core.M3u
import tech.kousozo.tahoetv.core.SourceHealth

/** Recordings (DVR): a show from the guide, or "now for an hour", saved to ~/Movies/Tahoe TV (Videos on Windows/Linux).
 *  Jobs are kept in ~/.tahoetv/recordings.json, so scheduled ones survive a restart; a checker starts them on time and
 *  tries the channel's backup streams when one gives nothing. Recording needs the app (or its IPTV server) to be running. */
object Recordings {
    class Job(
        val id: String, val channel: String, val title: String, val streams: List<M3u.Channel>,
        val start: Long, val stop: Long, file: String, state: String, error: String? = null, bytes: Long = 0,
    ) {
        /** scheduled · recording · done · partial (cut off) · failed · missed · cancelled */
        var state by mutableStateOf(state)
        var error by mutableStateOf(error)
        var bytes by mutableStateOf(bytes)
        var file by mutableStateOf(file)
        @Volatile var recorder: HlsRecorder? = null
        /** Set by [cancel] while recording: checked before each source, so a stop at start-up isn't lost. */
        @Volatile var stopRequested = false
        @Volatile var thread: Thread? = null
        val active get() = state == "scheduled" || state == "recording"
    }

    val jobs = mutableStateListOf<Job>()
    private val store = File(System.getProperty("user.home"), ".tahoetv/recordings.json")
    private val lock = Any()

    /** Where recordings go: ~/Movies/Tahoe TV on macOS, ~/Videos/Tahoe TV on Windows/Linux. */
    val dir: File by lazy {
        val mac = System.getProperty("os.name").orEmpty().startsWith("Mac", true)
        File(File(System.getProperty("user.home"), if (mac) "Movies" else "Videos"), "Tahoe TV").also { it.mkdirs() }
    }

    /** What [HlsRecorder] can record: not DRM and not DASH. ([M3u] sets `drm` for real licences; a `drmType` copied onto a
     *  plain HLS entry doesn't make it encrypted, and the recorder refuses encrypted segments anyway.) */
    fun recordable(c: M3u.Channel) = !c.drm && !c.url.substringBefore('?').endsWith(".mpd", true)

    private var started = false

    /** Loads saved jobs and starts the once-every-10-s checker. Safe to call more than once. */
    fun boot() {
        synchronized(lock) { if (started) return; started = true; load() }
        Thread({ while (true) { runCatching { tick() }; Thread.sleep(10_000) } }, "tahoe-dvr").apply { isDaemon = true }.start()
    }

    /** Record [streams] (main stream then backups) from [start] to [stop] (epoch ms). Starts right away if [start] has passed.
     *  Null when none of the streams can be recorded (DRM / DASH only). */
    fun schedule(channel: String, title: String, streams: List<M3u.Channel>, start: Long, stop: Long): Job? {
        boot()
        val usable = streams.filter(::recordable).ifEmpty { return null }
        val job = synchronized(lock) {
            // already planned, or already recording this channel at that time
            jobs.firstOrNull { it.active && it.channel == channel && (it.start == start || start in it.start until it.stop) }?.let { return it }
            val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(start))
            val name = "$channel - $title - $stamp".replace(Regex("""[\\/:*?"<>|\n\r]"""), " ").replace(Regex("\\s+"), " ").trim().take(150)
            val id = UUID.randomUUID().toString().take(8)
            // a path no other job uses and no file holds (a deleted recording may still be finishing on its old path)
            val file = File(dir, "$name.ts").takeIf { f -> !f.exists() && jobs.none { it.file == f.path } } ?: File(dir, "$name ($id).ts")
            Job(id, channel, title, usable, start, stop, file.path, "scheduled").also { jobs += it; save() }
        }
        Thread { runCatching { tick() } }.start() // start now if it's due
        return job
    }

    /** Stops a recording (keeps what was recorded) or drops a scheduled one. */
    fun cancel(id: String) = synchronized(lock) {
        val j = jobs.firstOrNull { it.id == id } ?: return
        when (j.state) {
            "recording" -> { j.stopRequested = true; j.recorder?.cancel() }
            "scheduled" -> { j.state = "cancelled"; save() }
        }
    }

    /** Removes the job, then deletes its file once a running recording has really stopped writing to it. */
    fun delete(id: String) {
        val j = synchronized(lock) { jobs.firstOrNull { it.id == id }?.also { it.stopRequested = true; it.recorder?.cancel(); jobs.remove(it); save() } } ?: return
        Thread {
            runCatching { j.thread?.join(60_000) } // the segment being downloaded finishes first (≤ its timeout)
            // a job scheduled meanwhile can have the same file name (same channel, title and minute): leave its file alone
            synchronized(lock) { if (jobs.none { it.file == j.file }) runCatching { File(j.file).delete() } }
        }.apply { isDaemon = true }.start()
    }

    private fun tick() = synchronized(lock) {
        val now = System.currentTimeMillis()
        jobs.filter { it.state == "scheduled" }.forEach { j ->
            when {
                now >= j.stop -> { j.state = "missed"; j.error = "Aplikasi tidak berjalan saat acara tayang."; save() }
                now >= j.start -> {
                    j.state = "recording"; save()
                    j.thread = Thread({ record(j) }, "tahoe-rec-${j.id}").apply { isDaemon = true; start() }
                }
            }
        }
    }

    private fun record(j: Job) {
        val out = File(j.file)
        var lastError: String? = null
        var partial = false
        // Known-live first; sources marked "not live" (recordings) are never used, even when that leaves none.
        val sources = SourceHealth.order(j.streams)
        if (sources.isEmpty()) lastError = "Semua sumber saluran ini ditandai bukan siaran langsung."
        for (s in sources) {
            if (j.stopRequested) break
            val r = HlsRecorder(s.url, s.headers, out, j.stop) // starts the file empty: nothing of a failed source stays in it
            j.recorder = r
            if (j.stopRequested) r.cancel() // a stop that came while the recorder was being made
            val watcher = Thread {
                try { while (j.recorder === r) { j.bytes = out.length(); Thread.sleep(2000) } } catch (_: InterruptedException) {}
            }.apply { isDaemon = true; start() }
            val ok = r.run()
            j.recorder = null; watcher.interrupt()
            j.bytes = out.length()
            if (r.segments == 0 && !j.stopRequested) { lastError = r.error ?: "Sumber tidak memberi siaran."; continue } // nothing yet: try the next source
            // Got something: this file is the recording. A source that dies halfway is not mixed with another stream
            // (an fMP4 file would break), the job is marked cut off instead.
            lastError = when {
                !ok -> { partial = true; "Terputus di tengah: ${r.error}" }
                r.skipped > 0 -> "${r.skipped} potongan siaran hilang."
                else -> null
            }
            break
        }
        // fMP4 streams make an MP4 file, not MPEG-TS: give it the extension players expect.
        if (out.exists() && isMp4(out)) File(out.path.removeSuffix(".ts") + ".mp4").let { if (out.renameTo(it)) j.file = it.path }
        synchronized(lock) {
            j.state = when {
                j.bytes == 0L -> if (j.stopRequested) "cancelled" else "failed"
                partial -> "partial"
                else -> "done"
            }
            j.error = if (j.bytes == 0L && !j.stopRequested) lastError ?: "Tidak ada sumber yang bisa direkam." else lastError
            if (j in jobs) save()
        }
    }

    private fun isMp4(f: File) = runCatching { f.inputStream().use { s -> ByteArray(8).also { s.read(it) } }.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp" }.getOrDefault(false)

    // ------------------------------------------------------------------ persistence

    /** Written to a temp file and moved over the store, so a crash mid-write never leaves half a JSON (and lost jobs). */
    private fun save() = synchronized(lock) {
        runCatching {
            store.parentFile.mkdirs()
            val tmp = File(store.parentFile, store.name + ".tmp")
            tmp.writeText(JSONArray(jobs.map { j ->
                JSONObject().put("id", j.id).put("channel", j.channel).put("title", j.title).put("start", j.start).put("stop", j.stop)
                    .put("file", j.file).put("state", j.state).put("error", j.error).put("bytes", j.bytes)
                    .put("streams", JSONArray(j.streams.map { s -> JSONObject().put("url", s.url).put("name", s.name).put("headers", JSONObject(s.headers)) }))
            }).toString(1))
            try { Files.move(tmp.toPath(), store.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(tmp.toPath(), store.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        }
    }

    private fun load() = runCatching {
        if (!store.exists()) return@runCatching
        val a = JSONArray(store.readText())
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val streams = o.optJSONArray("streams")?.let { sa -> (0 until sa.length()).map { k ->
                val so = sa.getJSONObject(k)
                M3u.Channel(so.optString("name"), so.getString("url"), null, null, so.optJSONObject("headers")?.toMap()?.mapValues { "${it.value}" }.orEmpty())
            } }.orEmpty()
            // a recording that was running when the app closed is cut off there
            val cut = o.optString("state") == "recording"
            val state = if (cut) (if (File(o.optString("file")).length() > 0) "partial" else "failed") else o.optString("state")
            val error = if (cut) "Aplikasi ditutup saat merekam: rekaman terpotong di situ." else o.optString("error").takeIf { it.isNotEmpty() && it != "null" }
            jobs += Job(o.getString("id"), o.optString("channel"), o.optString("title"), streams, o.getLong("start"), o.getLong("stop"),
                o.optString("file"), state, error, File(o.optString("file")).length())
        }
    }

    fun json(j: Job, base: String? = null): JSONObject = JSONObject().put("id", j.id).put("channel", j.channel).put("title", j.title)
        .put("start", j.start).put("stop", j.stop).put("state", j.state).put("error", j.error).put("bytes", j.bytes)
        .put("file_name", File(j.file).name).apply { if (base != null && j.bytes > 0) put("url", "$base/recordings/${j.id}") }
}
