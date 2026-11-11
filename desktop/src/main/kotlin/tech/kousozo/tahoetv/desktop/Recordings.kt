package tech.kousozo.tahoetv.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import tech.kousozo.tahoetv.core.HlsRecorder
import tech.kousozo.tahoetv.core.M3u
import tech.kousozo.tahoetv.core.SourceHealth

/** Recordings (DVR): a show from the guide, or "now for an hour", saved to ~/Movies/Tahoe TV (Videos on Windows).
 *  Jobs are kept in ~/.tahoetv/recordings.json, so scheduled ones survive a restart; a checker starts them on time and
 *  tries the channel's backup streams when one fails. Recording needs the app (or its IPTV server) to be running. */
object Recordings {
    class Job(
        val id: String, val channel: String, val title: String, val streams: List<M3u.Channel>,
        val start: Long, val stop: Long, file: String, state: String, error: String? = null, bytes: Long = 0,
    ) {
        /** scheduled · recording · done · failed · missed · cancelled */
        var state by mutableStateOf(state)
        var error by mutableStateOf(error)
        var bytes by mutableStateOf(bytes)
        var file by mutableStateOf(file)
        @Volatile var recorder: HlsRecorder? = null
        val active get() = state == "scheduled" || state == "recording"
    }

    val jobs = mutableStateListOf<Job>()
    private val store = File(System.getProperty("user.home"), ".tahoetv/recordings.json")

    /** Where recordings go: ~/Movies/Tahoe TV on macOS, ~/Videos/Tahoe TV on Windows/Linux. */
    val dir: File by lazy {
        val home = File(System.getProperty("user.home"))
        val base = listOf("Movies", "Videos").map { File(home, it) }.firstOrNull { it.isDirectory } ?: File(home, ".tahoetv")
        File(base, "Tahoe TV").also { it.mkdirs() }
    }

    private var started = false

    /** Loads saved jobs and starts the once-every-10-s checker. Safe to call more than once. */
    @Synchronized fun boot() {
        if (started) return
        started = true
        load()
        Thread({ while (true) { runCatching { tick() }; Thread.sleep(10_000) } }, "tahoe-dvr").apply { isDaemon = true }.start()
    }

    /** Record [streams] (main stream then backups) from [start] to [stop] (epoch ms). Starts right away if [start] has passed. */
    fun schedule(channel: String, title: String, streams: List<M3u.Channel>, start: Long, stop: Long): Job {
        boot()
        jobs.firstOrNull { it.active && it.channel == channel && it.start == start }?.let { return it } // already planned
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(start))
        val name = "$channel - $title - $stamp".replace(Regex("""[\\/:*?"<>|\n\r]"""), " ").replace(Regex("\\s+"), " ").trim().take(150)
        val job = Job(UUID.randomUUID().toString().take(8), channel, title, streams, start, stop, File(dir, "$name.ts").path, "scheduled")
        jobs += job; save()
        Thread { runCatching { tick() } }.start() // start now if it's due
        return job
    }

    /** Stops a recording (keeps what was recorded) or drops a scheduled one. */
    fun cancel(id: String) {
        val j = jobs.firstOrNull { it.id == id } ?: return
        if (j.state == "recording") j.recorder?.cancel() else if (j.state == "scheduled") j.state = "cancelled"
        save()
    }

    /** Removes the job and deletes its file. */
    fun delete(id: String) {
        val j = jobs.firstOrNull { it.id == id } ?: return
        j.recorder?.cancel()
        runCatching { File(j.file).delete() }
        jobs.remove(j); save()
    }

    @Synchronized private fun tick() {
        val now = System.currentTimeMillis()
        jobs.filter { it.state == "scheduled" }.forEach { j ->
            when {
                now >= j.stop -> { j.state = "missed"; j.error = "Aplikasi tidak berjalan saat acara tayang."; save() }
                now >= j.start -> { j.state = "recording"; save(); Thread({ record(j) }, "tahoe-rec-${j.id}").apply { isDaemon = true }.start() }
            }
        }
    }

    private fun record(j: Job) {
        val out = File(j.file)
        var lastError: String? = null
        // Known-live first, recordings ("not live") left out; a source that fails before giving anything → the next one.
        for (s in SourceHealth.order(j.streams).ifEmpty { j.streams }) {
            if (j.state != "recording") break
            val r = HlsRecorder(s.url, s.headers, out, j.stop)
            j.recorder = r
            val watcher = Thread {
                try { while (j.recorder === r && j.state == "recording") { j.bytes = out.length(); Thread.sleep(2000) } } catch (_: InterruptedException) {}
            }.apply { isDaemon = true; start() }
            val ok = r.run()
            j.recorder = null; watcher.interrupt()
            j.bytes = out.length()
            if (ok || r.segments > 0) { lastError = if (ok) null else r.error; break }
            lastError = r.error
        }
        // fMP4 streams make an MP4 file, not MPEG-TS: give it the extension players expect.
        if (out.exists() && isMp4(out)) File(out.path.removeSuffix(".ts") + ".mp4").let { if (out.renameTo(it)) j.file = it.path }
        j.state = if (j.bytes > 0) "done" else "failed"
        j.error = lastError ?: if (j.bytes == 0L) "Tidak ada sumber yang bisa direkam." else null
        save()
    }

    private fun isMp4(f: File) = runCatching { f.inputStream().use { s -> ByteArray(8).also { s.read(it) } }.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp" }.getOrDefault(false)

    // ------------------------------------------------------------------ persistence

    @Synchronized private fun save() = runCatching {
        store.parentFile.mkdirs()
        store.writeText(JSONArray(jobs.map { j ->
            JSONObject().put("id", j.id).put("channel", j.channel).put("title", j.title).put("start", j.start).put("stop", j.stop)
                .put("file", j.file).put("state", j.state).put("error", j.error).put("bytes", j.bytes)
                .put("streams", JSONArray(j.streams.map { s -> JSONObject().put("url", s.url).put("name", s.name).put("headers", JSONObject(s.headers)) }))
        }).toString(1))
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
            val state = if (cut) (if (File(o.optString("file")).length() > 0) "done" else "failed") else o.optString("state")
            val error = if (cut) "Aplikasi ditutup saat merekam: rekaman terpotong di situ." else o.optString("error").takeIf { it.isNotEmpty() && it != "null" }
            jobs += Job(o.getString("id"), o.optString("channel"), o.optString("title"), streams, o.getLong("start"), o.getLong("stop"),
                o.optString("file"), state, error, File(o.optString("file")).length())
        }
    }

    fun json(j: Job, base: String? = null): JSONObject = JSONObject().put("id", j.id).put("channel", j.channel).put("title", j.title)
        .put("start", j.start).put("stop", j.stop).put("state", j.state).put("error", j.error).put("bytes", j.bytes)
        .put("file_name", File(j.file).name).apply { if (base != null && j.bytes > 0) put("url", "$base/recordings/${j.id}") }
}
