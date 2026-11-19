package tech.kousozo.tahoetv.desktop

import androidx.compose.foundation.background
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
    val jobs = Recordings.jobs.sortedByDescending { it.start }
    var problem by remember { mutableStateOf<String?>(null) }
    val lang = Settings.lang
    /** Opens a file or folder in the system's app; says so when that isn't possible instead of doing nothing. */
    val open: (File) -> Unit = { f ->
        problem = runCatching { java.awt.Desktop.getDesktop().open(f); null }.getOrElse { "${tech.kousozo.tahoetv.core.I18n.get(lang, "pair.failed")}: ${f.name} (${it.message ?: ""})" }
    }
    Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 28.dp, top = 24.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Title(tech.kousozo.tahoetv.core.I18n.get(lang, "rec.title"), tech.kousozo.tahoetv.core.I18n.get(lang, "rec.subtitle", Recordings.dir.path))
        var bgInstalled by remember { mutableStateOf(BackgroundRecorder.isInstalled()) }
        var bgMessage by remember { mutableStateOf<String?>(null) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            GhostButton(tech.kousozo.tahoetv.core.I18n.get(lang, "rec.open_folder"), Icons.Rounded.FolderOpen) { open(Recordings.dir) }
            GhostButton(tech.kousozo.tahoetv.core.I18n.get(lang, "rec.from_live"), Icons.Rounded.LiveTv) { DeskNav.screen = Screen.Live }
            if (BackgroundRecorder.isSupported()) {
                GhostButton(
                    tech.kousozo.tahoetv.core.I18n.get(lang, if (bgInstalled) "rec.bg_remove" else "rec.bg_install"),
                    if (bgInstalled) Icons.Rounded.NotificationsActive else Icons.Rounded.Schedule,
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
private fun RecordingRow(j: Recordings.Job, open: (File) -> Unit) = Glass(padding = 14) {
    val lang = Settings.lang
    val zone = Settings.viewer.zone
    val dayFmt = remember(lang, zone) { DateTimeFormatter.ofPattern("EEE d MMM, HH.mm", java.util.Locale.forLanguageTag(lang)).withZone(zone) }
    val hmFmt = remember(zone) { DateTimeFormatter.ofPattern("HH.mm").withZone(zone) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val (label, color) = when (j.state) {
            "recording" -> tech.kousozo.tahoetv.core.I18n.get(lang, "rec.state_rec") to Tahoe.Bad
            "scheduled" -> tech.kousozo.tahoetv.core.I18n.get(lang, "rec.state_sched") to Tahoe.Accent
            "done" -> tech.kousozo.tahoetv.core.I18n.get(lang, "rec.state_done") to Tahoe.Good
            "partial" -> tech.kousozo.tahoetv.core.I18n.get(lang, "rec.state_partial") to Color(0xFFFF9F0A)
            "cancelled" -> tech.kousozo.tahoetv.core.I18n.get(lang, "rec.state_cancelled") to Tahoe.Faint
            "missed" -> tech.kousozo.tahoetv.core.I18n.get(lang, "rec.state_missed") to Tahoe.Faint
            else -> tech.kousozo.tahoetv.core.I18n.get(lang, "rec.state_failed") to Tahoe.Bad
        }
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(j.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${j.channel} · ${dayFmt.format(Instant.ofEpochMilli(j.start))}–${hmFmt.format(Instant.ofEpochMilli(j.stop))}" +
                (if (j.bytes > 0) " · ${size(j.bytes)}" else ""), fontSize = 13.sp, color = Tahoe.Dim)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(5.dp)).background(color.copy(alpha = 0.16f)).padding(horizontal = 6.dp, vertical = 2.dp),
                    color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                j.error?.let { Text(it, fontSize = 12.sp, color = Tahoe.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        val f = File(j.file)
        if (j.bytes > 0 && f.isFile) GhostButton(tech.kousozo.tahoetv.core.I18n.get(lang, "rec.play"), Icons.Rounded.PlayArrow) { open(f) }
        when {
            j.state == "recording" -> GhostButton(tech.kousozo.tahoetv.core.I18n.get(lang, "rec.stop"), Icons.Rounded.Stop, color = Tahoe.Bad) { Recordings.cancel(j.id) }
            j.state == "scheduled" -> GhostButton(tech.kousozo.tahoetv.core.I18n.get(lang, "rec.cancel"), Icons.Rounded.Close) { Recordings.cancel(j.id) }
            else -> IconButton({ Recordings.delete(j.id) }) { Icon(Icons.Rounded.DeleteOutline, tech.kousozo.tahoetv.core.I18n.get(lang, "rec.delete_aria"), tint = Tahoe.Faint) }
        }
    }
}

private fun size(b: Long) = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "${b / 1024} KB"
}
