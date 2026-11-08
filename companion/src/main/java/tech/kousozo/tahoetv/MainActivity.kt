package tech.kousozo.tahoetv

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Home: pairing status, notification/music sync and the web remote; entry to the phone-only TV setup wizard. */
class MainActivity : ComponentActivity() {
    private val resumed = mutableIntStateOf(0)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        enableEdgeToEdge(androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            TahoeTheme {
                var setup by rememberSaveable { mutableStateOf(false) }
                Surface(color = Tahoe.Bg) {
                    AnimatedContent(setup, label = "screen") { inSetup ->
                        if (inSetup) SetupScreen(onExit = { setup = false }) else HomeScreen(resumed.intValue, onSetup = { setup = true })
                    }
                }
            }
        }
    }

    override fun onResume() { super.onResume(); resumed.intValue++ }
}

// ==================================================================== home

@Composable
fun HomeScreen(resumeTick: Int, onSetup: () -> Unit) {
    val c = LocalContext.current
    val p = remember { Sync.prefs(c) }
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(p.getString("host", "").orEmpty()) }
    var code by remember { mutableStateOf("") }
    var notif by remember { mutableStateOf(p.getBoolean("notif", true)) }
    var media by remember { mutableStateOf(p.getBoolean("media", true)) }
    val granted = remember(resumeTick) {
        Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners").orEmpty()
            .contains(ComponentName(c, Sync::class.java).flattenToString())
    }
    val paired = p.getString("token", "").orEmpty().isNotEmpty()

    LaunchedEffect(Unit) { // auto-detect a TV already running Tahoe
        if (host.isEmpty()) Scan.run(c) { f -> if (f.tahoe && host.isEmpty()) scope.launch { host = f.ip } }
    }

    Page(null, "Tahoe TV", "Pasang launcher ke TV langsung dari HP, lalu notifikasi & musik dari HP muncul di TV.") {
        GlassCard(onClick = onSetup) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Rounded.Tv, null, tint = Tahoe.Accent, modifier = Modifier.size(34.dp))
                Column(Modifier.weight(1f)) {
                    Text("Pasang / kelola Tahoe di TV", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("Cari TV di Wi-Fi, debloat, pasang launcher, dan atur semuanya. Tanpa laptop.", color = Tahoe.Dim, fontSize = 13.sp)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = Tahoe.Faint)
            }
        }

        Section("Status")
        GlassCard {
            StatusLine(granted, if (granted) "Akses notifikasi diizinkan" else "Akses notifikasi belum diizinkan")
            StatusLine(paired, if (!paired) "Belum dipasangkan dengan TV" else Sync.status.value)
        }
        if (!granted) SecondaryButton("Izinkan akses notifikasi", Icons.Rounded.Notifications) {
            c.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        SwitchRow("Teruskan notifikasi", notif) { notif = it; p.edit().putBoolean("notif", it).apply() }
        SwitchRow("Tampilkan musik yang diputar", media) { media = it; p.edit().putBoolean("media", it).apply(); Sync.inst?.sendMedia() }

        Section("Sambungkan manual")
        Text("Untuk TV yang sudah memakai Tahoe: buka Pusat Kontrol → HP di TV untuk melihat kodenya.", color = Tahoe.Dim, fontSize = 13.sp)
        Field(host, { host = it }, "Alamat TV (terdeteksi otomatis)", KeyboardType.Uri)
        Field(code, { if (it.length <= 6) code = it.filter(Char::isDigit) }, "Kode 6 digit", KeyboardType.NumberPassword)
        PrimaryButton("Sambungkan", enabled = host.isNotBlank() && code.length == 6, icon = Icons.Rounded.Link) {
            scope.launch {
                val msg = pair(c, host.trim(), code)
                if (msg == null) { p.edit().putString("host", host.trim()).apply(); code = ""; Sync.inst?.connect() }
                Toast.makeText(c, msg ?: "Tersambung", Toast.LENGTH_SHORT).show()
            }
        }
        SecondaryButton("Buka remote & gamepad (web)", Icons.Rounded.SportsEsports) {
            if (host.isNotBlank()) c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://${host.trim()}:8765/")))
        }
    }
}

@Composable
private fun StatusLine(ok: Boolean, text: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null, tint = if (ok) Tahoe.Good else Tahoe.Faint, modifier = Modifier.size(18.dp))
    Text(text, fontSize = 15.sp)
}

/** POST /pair with the TV's 6-digit code. Returns null on success, else an error message. */
private suspend fun pair(c: android.content.Context, host: String, code: String): String? = withContext(Dispatchers.IO) {
    try {
        val con = URL("http://$host:8765/pair").openConnection() as HttpURLConnection
        con.connectTimeout = 5000; con.requestMethod = "POST"; con.doOutput = true
        con.setRequestProperty("Content-Type", "application/json")
        con.outputStream.use { it.write(JSONObject().put("code", code).toString().toByteArray()) }
        val body = (if (con.responseCode >= 400) con.errorStream else con.inputStream).bufferedReader().readText()
        val res = JSONObject(body)
        if (!res.has("token")) return@withContext res.optString("error", "Gagal")
        Sync.prefs(c).edit().putString("host", host).putString("token", res.getString("token")).apply()
        null
    } catch (e: Exception) { "Tidak bisa menghubungi TV: ${e.message}" }
}

// ==================================================================== setup wizard

@Composable
fun SetupScreen(onExit: () -> Unit, m: SetupModel = viewModel()) {
    val c = LocalContext.current
    val exit = { m.scan(); onExit() } // next visit starts fresh
    BackHandler {
        when (m.step) {
            SetupModel.Step.Scan, SetupModel.Step.Done -> exit()
            SetupModel.Step.Apply, SetupModel.Step.Restore -> if (!m.busy) exit()
            else -> m.back()
        }
    }
    when (m.step) {
        SetupModel.Step.Scan -> Page("Langkah 1 dari 5", "Cari TV", "HP dan TV harus di Wi-Fi yang sama.") {
            Note("Di TV, nyalakan debugging sekali saja:\n1. Setelan → Preferensi Perangkat → Tentang → tekan \"Build\" 7×\n2. Kembali → Opsi developer → USB debugging: AKTIF", Icons.Rounded.Info)
            Section(when {
                m.noWifi -> "HP belum tersambung ke Wi-Fi."
                m.scanning && m.found.isEmpty() -> "Mencari TV di jaringan…"
                m.found.isEmpty() -> "Tidak ada TV ditemukan."
                else -> "Pilih TV kamu"
            })
            if (m.scanning) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Tahoe.Accent, trackColor = Tahoe.Glass)
            m.found.forEach { f ->
                GlassCard(onClick = {
                    if (f.adb) m.connect(f.ip) else Toast.makeText(c, "Nyalakan USB debugging di TV dulu, lalu pindai ulang", Toast.LENGTH_SHORT).show()
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(Icons.Rounded.Tv, null, tint = if (f.adb) Tahoe.Accent else Tahoe.Faint)
                        Column(Modifier.weight(1f)) {
                            Text(f.label, fontWeight = FontWeight.SemiBold)
                            Text(f.status, color = Tahoe.Dim, fontSize = 13.sp)
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = Tahoe.Faint)
                    }
                }
            }
            if (!m.scanning && m.found.isEmpty() && !m.noWifi) Text("Pastikan TV menyala dan debugging aktif, atau ketik IP-nya di bawah.", color = Tahoe.Dim, fontSize = 14.sp)
            Section("Atau ketik IP")
            Field(m.host, { m.host = it }, "IP TV, mis. 192.168.1.50", KeyboardType.Uri)
            PrimaryButton("Sambungkan", enabled = m.host.isNotBlank()) { m.connect() }
            SecondaryButton("Pindai ulang", Icons.Rounded.Refresh) { m.scan() }
            SecondaryButton("Kembali", Icons.Rounded.ArrowBack, onClick = exit)
        }

        SetupModel.Step.Connect -> Page("Langkah 2 dari 5", "Izinkan di TV",
            "Di layar TV muncul \"Izinkan debugging USB?\". Centang \"Selalu izinkan dari komputer ini\", lalu pilih OKE.") {
            val err = m.error
            if (err == null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(vertical = 12.dp)) {
                    CircularProgressIndicator(color = Tahoe.Accent, modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    Text("Menghubungkan ke ${m.host}…", color = Tahoe.Dim)
                }
                Note("Prompt tidak muncul? Tekan tombol apa saja di remote untuk membangunkan TV (screensaver bisa menutupinya).", Icons.Rounded.Info)
            } else {
                Note("Gagal: $err\n\nCek: debugging aktif, TV menyala, dan prompt di TV sudah diizinkan.", Icons.Rounded.ErrorOutline)
                PrimaryButton("Coba lagi", icon = Icons.Rounded.Refresh) { m.connect() }
                SecondaryButton("Kembali") { m.back() }
            }
        }

        SetupModel.Step.Info -> {
            val tv = m.tv!!
            var confirm by remember { mutableStateOf(false) }
            Page("Langkah 3 dari 5", tv.model,
                "Android ${tv.release} (SDK ${tv.sdk})" + (if (tv.lowRam) " · RAM rendah" else "") + (m.version?.let { "\nTahoe $it sudah terpasang" } ?: "")) {
                if (tv.sdk < 26) Note("Launcher Tahoe butuh Android 8.0 ke atas. TV ini hanya bisa di-debloat.", Icons.Rounded.Info)
                if (m.version != null) SecondaryButton("Pulihkan TV ke bawaan…", Icons.Rounded.Restore, Tahoe.Bad) { confirm = true }
                Section("Matikan aplikasi yang tidak perlu")
                Text("Bisa dikembalikan kapan saja lewat \"Pulihkan TV\".", color = Tahoe.Dim, fontSize = 13.sp)
                val items = Tv.CATALOG.filter { it.pkg in m.debloat }
                if (items.isEmpty()) Note("Tidak ada lagi aplikasi bawaan dari daftar kami yang aktif di TV ini.")
                items.forEach { b -> CheckRow(b.what, b.pkg, m.debloat[b.pkg] == true) { m.debloat[b.pkg] = m.debloat[b.pkg] != true } }
                val off = Tv.CATALOG.count { it.pkg in m.alreadyOff }
                if (off > 0) Text("$off aplikasi lain sudah mati.", color = Tahoe.Faint, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                PrimaryButton("Lanjut", icon = Icons.Rounded.ArrowForward) { m.next() }
            }
            if (confirm) AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text("Pulihkan TV?") },
                text = { Text("Aplikasi yang dimatikan lewat HP ini dinyalakan lagi, launcher bawaan diaktifkan, dan Tahoe TV di-uninstall.") },
                confirmButton = { TextButton({ confirm = false; m.restore() }) { Text("Pulihkan", color = Tahoe.Bad) } },
                dismissButton = { TextButton({ confirm = false }) { Text("Batal") } },
                containerColor = androidx.compose.ui.graphics.Color(0xFF1C1C1E),
            )
        }

        SetupModel.Step.Personal -> Page("Langkah 4 dari 5", "Personalisasi", "Semua bisa diubah lagi nanti dari app ini.") {
            Field(m.name, { m.name = it }, "Nama (untuk sapaan)")
            Field(m.city, { m.city = it }, "Kota (cuaca & jadwal shalat)", trailing = {
                IconButton({ m.geocode() }) { Icon(Icons.Rounded.Search, "Cari kota", tint = Tahoe.Accent) }
            })
            if (m.place.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.Place, null, tint = Tahoe.Accent, modifier = Modifier.size(16.dp))
                Text(m.place, color = Tahoe.Dim, fontSize = 13.sp)
            }
            Field(m.avatar, { m.avatar = it }, "URL foto profil (opsional)", KeyboardType.Uri)

            Section("Dock")
            Text("Urutan mengikuti urutan dicentang, maksimal 7.", color = Tahoe.Dim, fontSize = 13.sp)
            m.dockChoices.forEach { pkg ->
                val i = m.dock.indexOf(pkg)
                CheckRow(m.tv!!.label(pkg), pkg, i >= 0, badge = if (i >= 0) "${i + 1}" else null) {
                    if (!m.toggleDock(pkg)) Toast.makeText(c, "Maksimal 7", Toast.LENGTH_SHORT).show()
                }
            }

            Section("API key (opsional)")
            Text("Disimpan di HP ini dan di TV saja.", color = Tahoe.Dim, fontSize = 13.sp)
            Field(m.gemini, { m.gemini = it.trim() }, "Gemini (asisten)", secret = true)
            Field(m.youtube, { m.youtube = it.trim() }, "YouTube Data API (hero trending)", secret = true)
            Field(m.tmdb, { m.tmdb = it.trim() }, "TMDB (hero Netflix)", secret = true)

            Section("Lainnya")
            SwitchRow("DNS pemblokir iklan (AdGuard)", m.adguard) { m.adguard = it }
            SwitchRow("Matikan launcher bawaan bila perlu", m.disableStock) { m.disableStock = it }
            Spacer(Modifier.height(8.dp))
            PrimaryButton("Pasang ke TV", icon = Icons.Rounded.Download) { m.apply() }
        }

        SetupModel.Step.Apply, SetupModel.Step.Restore, SetupModel.Step.Done -> {
            val done = m.step == SetupModel.Step.Done
            val restoring = m.step == SetupModel.Step.Restore
            Page(
                if (done) "Selesai" else if (restoring) "Pemulihan" else "Langkah 5 dari 5",
                if (done) "Tahoe TV siap" else if (restoring) (if (m.busy) "Memulihkan TV…" else "TV dipulihkan") else if (m.error != null) "Terhenti" else "Memasang…",
                if (done) "Lihat TV-mu, lalu tekan OK di layar \"Siapa yang menonton?\"." else if (m.busy) "Biarkan app ini terbuka dan HP tetap di Wi-Fi yang sama." else null,
            ) {
                if (m.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Tahoe.Accent, trackColor = Tahoe.Glass)
                GlassCard {
                    m.log.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = if (it.startsWith("✗")) Tahoe.Bad else Tahoe.Text) }
                }
                if (done) {
                    Note("Demi keamanan, matikan debugging setelah selesai. Untuk mengubah setelan lagi, nyalakan USB debugging lalu buka app ini.", Icons.Rounded.Shield)
                    PrimaryButton("Matikan debugging di TV", icon = Icons.Rounded.LockOpen) {
                        m.disableDebugging { Toast.makeText(c, "Debugging dimatikan", Toast.LENGTH_SHORT).show() }
                    }
                }
                if (!m.busy && m.step == SetupModel.Step.Apply) SecondaryButton("Ubah setelan", Icons.Rounded.ArrowBack) { m.next() }
                if (!m.busy) SecondaryButton("Ke halaman utama", Icons.Rounded.Home, onClick = exit)
            }
        }
    }
}
