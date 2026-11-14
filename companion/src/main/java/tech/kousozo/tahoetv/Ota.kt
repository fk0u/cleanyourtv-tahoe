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
private const val APK = "tahoe-tv.apk"

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
            .onSuccess { latest = it; state = if (Updates.newer(it.version, current) && APK in it.assets) "Versi ${it.version} tersedia" else "Sudah versi terbaru ($current)" }
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
            if (r != null && Updates.newer(r.version, current) && APK in r.assets) Button({
                scope.launch {
                    val f = File(c.cacheDir, "update.apk")
                    runCatching {
                        val hash = withContext(Dispatchers.IO) { r.checksumFor(APK) }
                        withContext(Dispatchers.IO) { Updates.download(r.assets.getValue(APK), f, hash) { p -> state = "Mengunduh… $p%" } }
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
        s.commit(PendingIntent.getBroadcast(c, id, Intent(c, OtaDone::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE).intentSender)
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
