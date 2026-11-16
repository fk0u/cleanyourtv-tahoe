package tech.kousozo.tahoetv.core

import java.io.File
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Privacy-first local monthly viewing recap ("Rekap Tahoe ala Wrapped", #62).
 * Stored strictly on the device, never transmitted to any server, with per-profile isolation and instant wipe.
 */
object WatchRecap {
    private const val FILE_NAME = "recap.tsv"
    private const val DISABLED_MARKER = ".recap_disabled"
    private const val MIN_SESSION_SEC = 15
    private const val MAX_SESSION_SEC = 12 * 3600
    private const val MAX_ROWS = 4000

    data class RankedItem(
        val name: String,
        val minutes: Int,
        val sessions: Int,
    )

    data class Summary(
        val month: String,
        val totalMinutes: Int,
        val sessionCount: Int,
        val topChannels: List<RankedItem>,
        val topShows: List<RankedItem>,
        val peakHour: Int, // -1 if empty
        val tagline: String,
    ) {
        val totalHoursDecimal: String
            get() = if (totalMinutes < 60) "$totalMinutes min"
            else String.format(Locale.US, "%.1f h", totalMinutes / 60.0)
    }

    private fun recapFile(dir: File) = File(dir, FILE_NAME)
    private fun disabledFile(dir: File) = File(dir, DISABLED_MARKER)

    @JvmStatic
    fun isEnabled(dir: File): Boolean = !disabledFile(dir).exists()

    @JvmStatic
    @Synchronized
    fun setEnabled(dir: File, enabled: Boolean) {
        dir.mkdirs()
        val f = disabledFile(dir)
        if (enabled) f.delete() else runCatching { f.writeText("off", Charsets.UTF_8) }
    }

    @JvmStatic
    @Synchronized
    fun clear(dir: File) {
        runCatching { recapFile(dir).delete() }
    }

    @JvmStatic
    @JvmOverloads
    fun currentMonth(zone: ZoneId = ZoneId.systemDefault()): String =
        YearMonth.now(zone).toString()

    @JvmStatic
    @Synchronized
    @JvmOverloads
    fun record(
        dir: File,
        channelId: String,
        channelName: String,
        showTitle: String?,
        durationSec: Int,
        profile: String = "main",
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        if (durationSec < MIN_SESSION_SEC || channelName.isBlank()) return
        if (profile.equals("guest", ignoreCase = true)) return
        if (!isEnabled(dir)) return
        dir.mkdirs()

        val sec = durationSec.coerceAtMost(MAX_SESSION_SEC)
        val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        val month = String.format(Locale.US, "%04d-%02d", zdt.year, zdt.monthValue)
        val safeProf = profile.trim().lowercase(Locale.US).ifBlank { "main" }.replace(Regex("\\s+"), "_")
        val safeId = channelId.trim().replace("\t", " ").replace("\n", " ")
        val safeCh = channelName.trim().replace("\t", " ").replace("\n", " ")
        val safeShow = showTitle.orEmpty().trim().replace("\t", " ").replace("\n", " ")
        val line = "$month\t$nowMs\t$sec\t${zdt.hour}\t$safeProf\t$safeId\t$safeCh\t$safeShow\n"

        val f = recapFile(dir)
        runCatching {
            val lines = if (f.isFile) f.readLines(Charsets.UTF_8).toMutableList() else ArrayList()
            lines += line.trimEnd('\n')
            if (lines.size > MAX_ROWS) {
                val trimmed = lines.subList(lines.size - MAX_ROWS, lines.size)
                val tmp = File(dir, "$FILE_NAME.tmp")
                tmp.writeText(trimmed.joinToString("\n") + "\n", Charsets.UTF_8)
                tmp.renameTo(f)
            } else {
                f.appendText(line, Charsets.UTF_8)
            }
        }
    }

    @JvmStatic
    @Synchronized
    @JvmOverloads
    fun summarize(
        dir: File,
        profile: String = "main",
        month: String = currentMonth(),
        lang: String = "id",
    ): Summary {
        val f = recapFile(dir)
        val targetProfile = profile.trim().lowercase(Locale.US).ifBlank { "main" }
        var totalSec = 0L
        var sessions = 0
        val chSec = HashMap<String, Long>()
        val chCnt = HashMap<String, Int>()
        val showSec = HashMap<String, Long>()
        val showCnt = HashMap<String, Int>()
        val hours = LongArray(24)

        if (f.isFile) {
            runCatching {
                f.forEachLine(Charsets.UTF_8) { line ->
                    val p = line.split('\t')
                    if (p.size < 7) return@forEachLine
                    val rowMonth = p[0]
                    val sec = p[2].toIntOrNull() ?: return@forEachLine
                    val hr = p[3].toIntOrNull()?.coerceIn(0, 23) ?: 0
                    val prof = p[4].ifBlank { "main" }
                    val ch = p[6]
                    val show = p.getOrNull(7).orEmpty()
                    if (rowMonth != month || prof != targetProfile || sec <= 0) return@forEachLine

                    totalSec += sec
                    sessions++
                    hours[hr] += sec.toLong()
                    chSec[ch] = (chSec[ch] ?: 0L) + sec
                    chCnt[ch] = (chCnt[ch] ?: 0) + 1
                    if (show.isNotBlank() && !show.equals(ch, ignoreCase = true)) {
                        showSec[show] = (showSec[show] ?: 0L) + sec
                        showCnt[show] = (showCnt[show] ?: 0) + 1
                    }
                }
            }
        }

        val topChannels = chSec.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { RankedItem(it.key, maxOf(1, (it.value / 60L).toInt()), chCnt[it.key] ?: 1) }
        val topShows = showSec.entries
            .sortedByDescending { it.value }
            .take(3)
            .map { RankedItem(it.key, maxOf(1, (it.value / 60L).toInt()), showCnt[it.key] ?: 1) }

        var peakHour = -1
        var peakVal = 0L
        for (h in 0 until 24) {
            if (hours[h] > peakVal) {
                peakVal = hours[h]
                peakHour = h
            }
        }

        val topCh = topChannels.firstOrNull()
        val topShow = topShows.firstOrNull()
        val tagline = when {
            topShow != null && topShow.sessions >= 2 ->
                I18n.get(lang, "recap.tag_show", topShow.name, topShow.sessions)
            topCh != null ->
                I18n.get(lang, "recap.tag_channel", topCh.name)
            else ->
                I18n.get(lang, "recap.empty")
        }

        return Summary(
            month = month,
            totalMinutes = (totalSec / 60L).toInt(),
            sessionCount = sessions,
            topChannels = topChannels,
            topShows = topShows,
            peakHour = peakHour,
            tagline = tagline,
        )
    }
}
