package tech.kousozo.tahoetv.core

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A small diagnostic log for "Kirim log": the last [MAX] events of the players (channel tuned, source failed,
 *  decoder used, errors) plus crashes, kept in memory and saved to a file so a crash or a restart doesn't lose it.
 *  Privacy: query strings (tokens, keys) and userinfo are cut out of every URL, and only events the app writes
 *  itself go in — no notifications, no typed text. Plain JVM: TV, phone and desktop share it. */
object DiagLog {
    const val MAX = 400
    private val lines = ArrayDeque<String>()
    private var file: File? = null
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    /** Any URI scheme (RFC 3986: letter, then letters/digits/+/./-), any case. */
    private val URL = Regex("""([A-Za-z][A-Za-z0-9+.\-]*)://([^\s/?#@]*@)?([^\s/?#]+)([^\s?#]*)(\?[^\s#]*)?(#\S*)?""")
    private var savedAt = 0L

    /** Loads what the previous run left (so a crash report survives the restart) and logs uncaught exceptions. */
    @JvmStatic @Synchronized fun install(f: File) {
        if (file != null) return
        file = f
        runCatching { f.readLines().takeLast(MAX).forEach { lines.addLast(it) } }
        add("app", "--- dibuka ---")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { add("crash", "${t.name}: ${stack(e)}"); save() }
            prev?.uncaughtException(t, e)
        }
    }

    /** One event, e.g. add("live", "tune RCTI → cdn.example.com/live/index.m3u8"). URLs lose their query strings. */
    @JvmStatic @Synchronized fun add(tag: String, msg: String) {
        lines.addLast("${time.format(Date())} [$tag] ${redact(msg).replace('\n', ' ').take(600)}")
        while (lines.size > MAX) lines.removeFirst()
        // errors and crashes are written at once; ordinary events at most every 10 s, so a restart keeps them too
        val now = System.currentTimeMillis()
        if (tag == "error" || tag == "crash" || now - savedAt > 10_000) save()
    }

    @JvmStatic fun error(tag: String, msg: String, e: Throwable? = null) =
        add("error", "$tag: $msg" + (e?.let { " — ${it.javaClass.simpleName}: ${it.message}" } ?: ""))

    @JvmStatic @Synchronized fun save() {
        savedAt = System.currentTimeMillis()
        file?.let { f -> runCatching { f.parentFile?.mkdirs(); f.writeText(lines.joinToString("\n")) } }
    }

    @JvmStatic @Synchronized fun text(): String = lines.joinToString("\n")

    @JvmStatic @Synchronized fun clear() { lines.clear(); save() }

    /** The text to send: a header with the app and device, then the log (newest last). */
    @JvmStatic @Synchronized fun report(app: String, version: String, device: Map<String, String> = emptyMap(), extra: String = ""): String = buildString {
        appendLine("== $app $version ==")
        appendLine("Waktu: ${time.format(Date())} (${java.util.TimeZone.getDefault().id})")
        device.forEach { (k, v) -> appendLine("$k: $v") }
        if (extra.isNotBlank()) appendLine(extra.trimEnd())
        appendLine("-- log (${lines.size} baris, terbaru di bawah) --")
        append(text())
    }

    /** Cuts what can identify an account out of a URL: `user:pass@` and the query string/fragment. */
    @JvmStatic fun redact(s: String): String = URL.replace(s) { m ->
        val (scheme, _, host, path, query, frag) = m.destructured
        "$scheme://$host$path" + (if (query.isNotEmpty() || frag.isNotEmpty()) "?…" else "")
    }

    private fun stack(e: Throwable): String = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
        .lineSequence().take(25).joinToString(" | ")
}
