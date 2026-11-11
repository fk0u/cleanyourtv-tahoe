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
    /** Opens a file or folder in the system's app; says so when that isn't possible instead of doing nothing. */
    val open: (File) -> Unit = { f ->
        problem = runCatching { java.awt.Desktop.getDesktop().open(f); null }.getOrElse { "Tidak bisa membuka ${f.name}: ${it.message ?: "tidak ada aplikasi untuk file ini"}" }
    }
    Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 28.dp, top = 24.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Title("Rekaman", "Disimpan di ${Recordings.dir.path}. Rekaman berjalan selama Tahoe TV terbuka di komputer ini.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Buka folder", Icons.Rounded.FolderOpen) { open(Recordings.dir) }
            GhostButton("Rekam dari TV Live", Icons.Rounded.LiveTv) { DeskNav.screen = Screen.Live }
        }
        problem?.let { Text(it, color = Tahoe.Bad, fontSize = 13.sp) }
        if (jobs.isEmpty()) {
            Glass { Text("Belum ada rekaman. Di TV Live, pilih saluran lalu tekan ⏺ Rekam pada acara yang sedang atau akan tayang.", color = Tahoe.Dim, fontSize = 14.sp) }
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(jobs, key = { it.id }) { j -> RecordingRow(j, open) }
        }
    }
}

@Composable
private fun RecordingRow(j: Recordings.Job, open: (File) -> Unit) = Glass(padding = 14) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val (label, color) = when (j.state) {
            "recording" -> "MEREKAM" to Tahoe.Bad
            "scheduled" -> "TERJADWAL" to Tahoe.Accent
            "done" -> "SELESAI" to Tahoe.Good
            "partial" -> "TERPOTONG" to Color(0xFFFF9F0A)
            "cancelled" -> "DIBATALKAN" to Tahoe.Faint
            "missed" -> "TERLEWAT" to Tahoe.Faint
            else -> "GAGAL" to Tahoe.Bad
        }
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(j.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${j.channel} · ${DAY.format(Instant.ofEpochMilli(j.start))}–${HM.format(Instant.ofEpochMilli(j.stop))}" +
                (if (j.bytes > 0) " · ${size(j.bytes)}" else ""), fontSize = 13.sp, color = Tahoe.Dim)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(5.dp)).background(color.copy(alpha = 0.16f)).padding(horizontal = 6.dp, vertical = 2.dp),
                    color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                j.error?.let { Text(it, fontSize = 12.sp, color = Tahoe.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        val f = File(j.file)
        if (j.bytes > 0 && f.isFile) GhostButton("Putar", Icons.Rounded.PlayArrow) { open(f) }
        when {
            j.state == "recording" -> GhostButton("Hentikan", Icons.Rounded.Stop, color = Tahoe.Bad) { Recordings.cancel(j.id) }
            j.state == "scheduled" -> GhostButton("Batalkan", Icons.Rounded.Close) { Recordings.cancel(j.id) }
            else -> IconButton({ Recordings.delete(j.id) }) { Icon(Icons.Rounded.DeleteOutline, "Hapus rekaman dan filenya", tint = Tahoe.Faint) }
        }
    }
}

private fun size(b: Long) = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "${b / 1024} KB"
}

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM, HH.mm", java.util.Locale("id")).withZone(ZoneId.systemDefault())
private val HM = DateTimeFormatter.ofPattern("HH.mm").withZone(ZoneId.systemDefault())
