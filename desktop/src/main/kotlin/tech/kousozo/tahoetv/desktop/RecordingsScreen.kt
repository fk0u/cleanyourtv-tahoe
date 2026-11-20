package tech.kousozo.tahoetv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Recordings (DVR): scheduled, running and finished, newest first. Play opens the file in the system's player;
 *  the same files are reachable from the TV/phone at http://<this computer>:<port>/recordings/<id>. */
@Composable
fun RecordingsScreen() {
    LaunchedEffect(Unit) { Recordings.boot() }
    var filter by remember { mutableStateOf(0) } // 0 = all, 1 = scheduled / running, 2 = finished
    val jobs = Recordings.jobs.sortedByDescending { it.start }.filter { j ->
        when (filter) { 1 -> j.state == "scheduled" || j.state == "recording"; 2 -> j.state == "done" || j.state == "partial"; else -> true }
    }
    var problem by remember { mutableStateOf<String?>(null) }
    val lang = Settings.lang
    /** Opens a file or folder in the system's app; says so when that isn't possible instead of doing nothing. */
    val open: (File) -> Unit = { f ->
        problem = runCatching { java.awt.Desktop.getDesktop().open(f); null }.getOrElse { "${tech.kousozo.tahoetv.core.I18n.get(lang, "pair.failed")}: ${f.name} (${it.message ?: ""})" }
    }
    Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 28.dp, top = 24.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tech.kousozo.tahoetv.core.I18n.get(lang, "rec.title"), fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp)
            Text(tech.kousozo.tahoetv.core.I18n.get(lang, "rec.subtitle", Recordings.dir.path), fontSize = 14.sp, color = Tahoe.Dim)
        }
        // Figma chips: Semua / Terjadwal / Selesai
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(0 to tech.kousozo.tahoetv.core.I18n.get(lang, "live.all"), 1 to tech.kousozo.tahoetv.core.I18n.get(lang, "rec.state_sched"), 2 to tech.kousozo.tahoetv.core.I18n.get(lang, "rec.state_done")).forEach { (k, l) ->
                val on = filter == k
                Text(l.lowercase().replaceFirstChar { it.uppercase() },
                    Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).background(if (on) Tahoe.Text else Color.White.copy(alpha = 0.09f))
                        .border(1.dp, if (on) Color.Transparent else Tahoe.Stroke, androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .clickable { filter = k }.padding(horizontal = 16.dp, vertical = 8.dp),
                    fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (on) Color(0xFF0D0F14) else Tahoe.Dim)
            }
        }
        var bgInstalled by remember { mutableStateOf(BackgroundRecorder.isInstalled()) }
        var bgMessage by remember { mutableStateOf<String?>(null) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.Rounded.FolderOpen, tech.kousozo.tahoetv.core.I18n.get(lang, "rec.open_folder")) { open(Recordings.dir) }
            IconAction(Icons.Rounded.LiveTv, tech.kousozo.tahoetv.core.I18n.get(lang, "rec.from_live")) { DeskNav.screen = Screen.Live }
            if (BackgroundRecorder.isSupported()) {
                IconAction(
                    if (bgInstalled) Icons.Rounded.NotificationsActive else Icons.Rounded.Schedule,
                    tech.kousozo.tahoetv.core.I18n.get(lang, if (bgInstalled) "rec.bg_remove" else "rec.bg_install"),
                    color = if (bgInstalled) Tahoe.Good else Tahoe.Accent,
                ) {
                    val res = if (bgInstalled) BackgroundRecorder.remove() else BackgroundRecorder.install()
                    res.fold(
                        { msg -> bgInstalled = BackgroundRecorder.isInstalled(); bgMessage = msg },
                        { err -> bgMessage = err.message ?: "Error" },
                    )
                }
            }
        }
        if (BackgroundRecorder.isSupported()) {
            Text(
                tech.kousozo.tahoetv.core.I18n.get(lang, if (bgInstalled) "rec.bg_status_on" else "rec.bg_status_off") +
                    (bgMessage?.let { " · $it" } ?: ""),
                color = if (bgInstalled) Tahoe.Good else Tahoe.Dim,
                fontSize = 12.sp,
            )
        }
        problem?.let { Text(it, color = Tahoe.Bad, fontSize = 13.sp) }
        if (jobs.isEmpty()) {
            Glass { Text(tech.kousozo.tahoetv.core.I18n.get(lang, "rec.empty"), color = Tahoe.Dim, fontSize = 14.sp) }
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(jobs, key = { it.id }) { j -> RecordingRow(j, open) }
        }
    }
}

@Composable
private fun RecordingRow(j: Recordings.Job, open: (File) -> Unit) {
    val lang = Settings.lang
    val zone = Settings.viewer.zone
    val dayFmt = remember(lang, zone) { DateTimeFormatter.ofPattern("EEE d MMM, HH.mm", java.util.Locale.forLanguageTag(lang)).withZone(zone) }
    val hmFmt = remember(zone) { DateTimeFormatter.ofPattern("HH.mm").withZone(zone) }
    val (label, color) = when (j.state) {
        "recording" -> "● " + tech.kousozo.tahoetv.core.I18n.get(lang, "rec.state_rec") to Color(0xFFFF453B)
        "scheduled" -> tech.kousozo.tahoetv.core.I18n.get(lang, "rec.state_sched") to Color(0xFF5CD1FF)
        "done" -> tech.kousozo.tahoetv.core.I18n.get(lang, "rec.state_done") to Color(0xFF30D159)
        "partial" -> tech.kousozo.tahoetv.core.I18n.get(lang, "rec.state_partial") to Color(0xFFFF8A5C)
        "cancelled" -> tech.kousozo.tahoetv.core.I18n.get(lang, "rec.state_cancelled") to Tahoe.Faint
        "missed" -> tech.kousozo.tahoetv.core.I18n.get(lang, "rec.state_missed") to Tahoe.Faint
        else -> tech.kousozo.tahoetv.core.I18n.get(lang, "rec.state_failed") to Color(0xFFFF453B)
    }
    Row(
        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).background(Tahoe.Card).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // thumbnail: tinted by the channel (the file's own frame is not extracted yet)
        val h = j.channel.hashCode()
        val c1 = Color(red = ((h shr 16) and 255) / 255f * 0.5f + 0.1f, green = ((h shr 8) and 255) / 255f * 0.5f + 0.1f, blue = (h and 255) / 255f * 0.5f + 0.2f)
        Box(Modifier.size(140.dp, 78.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(c1, c1.copy(alpha = 0.35f), Color(0xFF10131A)))), contentAlignment = Alignment.Center) {
            Text(j.channel.take(4).uppercase(), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.85f))
        }
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(j.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${j.channel} · ${dayFmt.format(Instant.ofEpochMilli(j.start))}–${hmFmt.format(Instant.ofEpochMilli(j.stop))}" +
                (if (j.bytes > 0) " · ${size(j.bytes)}" else ""), fontSize = 14.sp, color = Tahoe.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            j.error?.let { Text(it, fontSize = 12.sp, color = Tahoe.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        val f = File(j.file)
        if (j.bytes > 0 && f.isFile) IconAction(Icons.Rounded.PlayArrow, tech.kousozo.tahoetv.core.I18n.get(lang, "rec.play"), filled = true) { open(f) }
        when {
            j.state == "recording" -> IconAction(Icons.Rounded.Stop, tech.kousozo.tahoetv.core.I18n.get(lang, "rec.stop"), color = Tahoe.Bad) { Recordings.cancel(j.id) }
            j.state == "scheduled" -> IconAction(Icons.Rounded.Close, tech.kousozo.tahoetv.core.I18n.get(lang, "rec.cancel")) { Recordings.cancel(j.id) }
            else -> IconButton({ Recordings.delete(j.id) }) { Icon(Icons.Rounded.DeleteOutline, tech.kousozo.tahoetv.core.I18n.get(lang, "rec.delete_aria"), tint = Tahoe.Faint) }
        }
        Text(label.uppercase(), Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).background(color.copy(alpha = 0.18f)).padding(horizontal = 12.dp, vertical = 6.dp),
            fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color, letterSpacing = 1.3.sp, maxLines = 1)
    }
}

private fun size(b: Long) = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "${b / 1024} KB"
}
