package tech.kousozo.tahoetv

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import tech.kousozo.tahoetv.core.DiagLog
import tech.kousozo.tahoetv.core.TvLink

/** "Kirim log": this phone's diagnostic log plus the paired TV's (fetched from the TV), handed to Android's share
 *  sheet (WhatsApp, email, Drive…). Nothing is uploaded anywhere by the app itself. */
object Report {
    fun install(c: Context) = DiagLog.install(File(c.filesDir, "diag.log"))

    private fun version(c: Context) = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "?"

    fun phone(c: Context): String = DiagLog.report("Tahoe TV (HP)", version(c), linkedMapOf(
        "Perangkat" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "FFmpeg" to ffmpeg(),
        "TV dipasangkan" to if (Sync.prefs(c).getString("token", "").isNullOrEmpty()) "tidak" else "ya",
    ))

    fun ffmpeg(): String = runCatching {
        if (androidx.media3.decoder.ffmpeg.FfmpegLibrary.isAvailable()) "tersedia (${androidx.media3.decoder.ffmpeg.FfmpegLibrary.getVersion()})" else "tidak tersedia"
    }.getOrDefault("tidak tersedia")

    /** Builds both reports off the main thread, then opens the share sheet. [done] gets a note for the user
     *  (e.g. the TV couldn't be reached, so only the phone's log went), or null. */
    fun send(c: Context, done: (String?) -> Unit = {}) {
        val app = c.applicationContext
        val p = Sync.prefs(app)
        val host = p.getString("host", "").orEmpty(); val token = p.getString("token", "").orEmpty()
        Thread {
            DiagLog.add("user", "--- Kirim log ---")
            var note: String? = null
            val tv = if (host.isEmpty() || token.isEmpty()) { note = "HP belum dipasangkan dengan TV: hanya log HP yang dikirim."; "" }
                else runCatching { TvLink.fetchLog(host, token) }.getOrElse { note = "TV tidak bisa dihubungi: hanya log HP yang dikirim."; "" }
            // the share sheet carries text; keep it well under Android's ~1 MB limit for an intent
            val text = (phone(app) + (if (tv.isNotEmpty()) "\n\n$tv" else "")).takeLast(200_000)
            Handler(Looper.getMainLooper()).post {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Log Tahoe TV")
                    .putExtra(Intent.EXTRA_TEXT, text)
                runCatching { c.startActivity(Intent.createChooser(send, "Kirim log Tahoe TV").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { note = "Tidak ada aplikasi untuk mengirim log." }
                done(note)
            }
        }.start()
    }
}
