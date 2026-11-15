package tech.kousozo.tahoetv

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.kousozo.tahoetv.core.Updates

/** OTA for the phone app: newest GitHub release, its changelog, and install through Android's installer. */
private val APK_CANDIDATES = Updates.REMOTE_ASSETS

/** Pop-up update modal backed by ControlPlane policy + GitHub/Server release metadata. */
@Composable
fun UpdateModalDialog() {
    if (!PushHelper.showUpdateModal) return
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val r = PushHelper.latestRelease
    val pol = PushHelper.policy
    val (current, currentCode) = remember { PushHelper.appVersion(c) }
    val hasRemoteApk = r != null && r.hasAnyAsset(APK_CANDIDATES)
    val newerAvailable = r != null && Updates.newer(r.version, current)
    val mandatory = (pol.minVersionCode > 0 && currentCode < pol.minVersionCode) ||
        (pol.forceModal && newerAvailable) ||
        (r?.forceModal == true && newerAvailable)

    var status by remember(r?.version) { mutableStateOf("") }
    var progress by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!mandatory && !busy) PushHelper.dismissUpdateModal(c) },
        containerColor = Tahoe.Surface,
        titleContentColor = Tahoe.Text,
        textContentColor = Tahoe.Text,
        title = {
            val titleText = pol.modalTitle.ifBlank {
                if (r != null && newerAvailable) "Pembaruan Tahoe TV Remote ${r.version}" else "Info Pembaruan Tahoe TV Remote"
            }
            Text(titleText, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, fontSize = 18.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Terpasang: $current" + if (r != null) "  ·  Terbaru: ${r.version}" else "",
                    fontSize = 12.sp,
                    color = Tahoe.Dim,
                    fontFamily = Mono,
                )
                if (pol.modalMessage.isNotBlank()) {
                    Text(pol.modalMessage, fontSize = 14.sp, color = Tahoe.Text)
                }
                if (r != null && r.notes.isNotBlank()) {
                    Text(r.notes.take(400), fontSize = 13.sp, color = Tahoe.Dim)
                } else if (r == null) {
                    Text("Memeriksa rilis terbaru dari server…", fontSize = 13.sp, color = Tahoe.Dim)
                }
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { ((progress ?: 0).coerceIn(0, 100)) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = Tahoe.Accent,
                        trackColor = Tahoe.Glass,
                    )
                }
                if (status.isNotBlank()) {
                    Text(status, fontSize = 12.sp, color = Tahoe.Accent)
                }
            }
        },
        confirmButton = {
            if (hasRemoteApk && r != null && (newerAvailable || mandatory)) {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val f = File(c.cacheDir, "update-remote.apk")
                            runCatching {
                                status = "Menyiapkan unduhan…"
                                val (_, url, hash) = withContext(Dispatchers.IO) { r.resolveDownload(APK_CANDIDATES) }
                                withContext(Dispatchers.IO) {
                                    Updates.download(url, f, hash) { p ->
                                        progress = p
                                        status = "Mengunduh… $p%"
                                    }
                                }
                                status = "Memasang… konfirmasi di layar berikutnya"
                                withContext(Dispatchers.IO) { install(c, f) }
                            }.onFailure {
                                status = "Gagal: ${it.message}"
                            }
                            busy = false
                        }
                    },
                ) { Text(if (busy) "Mengunduh…" else "Perbarui Sekarang") }
            } else {
                TextButton({
                    if (!mandatory) PushHelper.dismissUpdateModal(c)
                    else if (r != null) PushHelper.openSafeUrl(c, r.page)
                }) { Text(if (mandatory) "Buka Halaman Unduhan" else "Tutup", color = Tahoe.Accent) }
            }
        },
        dismissButton = {
            if (!mandatory && !busy) {
                TextButton({ PushHelper.dismissUpdateModal(c) }) {
                    Text("Nanti", color = Tahoe.Dim)
                }
            }
        },
    )
}

@Composable
fun UpdateCard() {
    val c = LocalContext.current
    val current = remember { runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull().orEmpty() }
    var latest by remember { mutableStateOf<Updates.Release?>(null) }
    var state by remember { mutableStateOf("Mengecek pembaruan…") }
    var showNotes by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { Updates.latest() } }
            .onSuccess { latest = it; state = if (Updates.newer(it.version, current) && it.hasAnyAsset(APK_CANDIDATES)) "Versi ${it.version} tersedia" else "Sudah versi terbaru ($current)" }
            .onFailure { state = "Tidak bisa mengecek pembaruan" }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Icon(Icons.Rounded.SystemUpdate, null, tint = Tahoe.Accent); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Tahoe TV $current", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(state, color = Tahoe.Dim, fontSize = 12.sp)
            }
            val r = latest
            if (r != null && Updates.newer(r.version, current) && r.hasAnyAsset(APK_CANDIDATES)) Button({
                scope.launch {
                    val f = File(c.cacheDir, "update.apk")
                    runCatching {
                        val (_, url, hash) = withContext(Dispatchers.IO) { r.resolveDownload(APK_CANDIDATES) }
                        withContext(Dispatchers.IO) { Updates.download(url, f, hash) { p -> state = "Mengunduh… $p%" } }
                        state = "Memasang… konfirmasi di layar berikutnya"
                        withContext(Dispatchers.IO) { install(c, f) }
                    }.onFailure { state = "Gagal: ${it.message}" }
                }
            }) { Text("Perbarui") }
        }
        latest?.let { r -> TextButton({ showNotes = !showNotes }) { Text(if (showNotes) "Sembunyikan catatan rilis" else "Yang baru di ${r.version}") } }
        if (showNotes) latest?.let { Text(it.notes.replace(Regex("(?m)^#+\\s*"), "").replace("**", ""), fontSize = 13.sp, color = Tahoe.Dim) }
    }
}

fun install(c: Context, apk: File) {
    val pi = c.packageManager.packageInstaller
    val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
    pi.openSession(id).use { s ->
        s.openWrite("tahoe", 0, apk.length()).use { o -> apk.inputStream().use { it.copyTo(o) }; s.fsync(o) }
        val doneIntent = Intent(c, OtaDone::class.java).setPackage(c.packageName)
        s.commit(PendingIntent.getBroadcast(c, id, doneIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE).intentSender)
    }
}

class OtaDone : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                @Suppress("DEPRECATION") (i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> Toast.makeText(c, "Pembaruan gagal: ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}", Toast.LENGTH_LONG).show()
        }
    }
}
