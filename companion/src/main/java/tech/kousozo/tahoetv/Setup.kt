package tech.kousozo.tahoetv

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import tech.kousozo.tahoetv.core.TvAdb

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
        SetupModel.Step.Scan -> Page("Langkah 1 dari 5", "Cari TV", "Pastikan HP dan TV terhubung ke Wi-Fi yang sama.") {
            Note("Aktifkan izin sambungan TV (cukup sekali saja):\n1. Buka Setelan TV → Tentang → tekan \"Build\" 7×\n2. Kembali → Opsi developer → aktifkan USB debugging", Icons.Rounded.Info)
            Section(when {
                m.noWifi -> "HP belum tersambung ke Wi-Fi."
                m.scanning && m.found.isEmpty() -> "Mencari TV di jaringan…"
                m.found.isEmpty() -> "Tidak ada TV ditemukan."
                else -> "Pilih TV kamu"
            })
            if (m.scanning) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Tahoe.Accent, trackColor = Tahoe.Glass)
            m.found.forEach { f ->
                GlassCard(onClick = {
                    if (f.adb) m.connect(f.ip) else Toast.makeText(c, "Aktifkan izin sambungan di Setelan TV dulu, lalu pindai ulang", Toast.LENGTH_SHORT).show()
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
            SecondaryButton("Kembali", Icons.AutoMirrored.Rounded.ArrowBack, onClick = exit)
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
                val items = TvAdb.CATALOG.filter { it.pkg in m.debloat }
                if (items.isEmpty()) Note("Tidak ada lagi aplikasi bawaan dari daftar kami yang aktif di TV ini.")
                items.forEach { b -> CheckRow(b.what, b.pkg, m.debloat[b.pkg] == true) { m.debloat[b.pkg] = m.debloat[b.pkg] != true } }
                val off = TvAdb.CATALOG.count { it.pkg in m.alreadyOff }
                if (off > 0) Text("$off aplikasi lain sudah mati.", color = Tahoe.Faint, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                PrimaryButton("Lanjut", icon = Icons.AutoMirrored.Rounded.ArrowForward) { m.next() }
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
                if (!m.busy && m.step == SetupModel.Step.Apply) SecondaryButton("Ubah setelan", Icons.AutoMirrored.Rounded.ArrowBack) { m.next() }
                if (!m.busy) SecondaryButton("Ke halaman utama", Icons.Rounded.Home, onClick = exit)
            }
        }
    }
}
