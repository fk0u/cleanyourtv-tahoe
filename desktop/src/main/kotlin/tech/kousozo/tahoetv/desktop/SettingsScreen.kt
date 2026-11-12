package tech.kousozo.tahoetv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.kousozo.tahoetv.core.M3u
import tech.kousozo.tahoetv.core.Web

@Composable
fun SettingsScreen() {
    val scope = rememberCoroutineScope()
    var city by remember { mutableStateOf(Settings.city) }
    var place by remember { mutableStateOf("${Settings.city} (${"%.3f".format(Settings.lat)}, ${"%.3f".format(Settings.lon)})") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 28.dp, top = 24.dp, bottom = 28.dp).widthIn(max = 720.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Title("Setelan", "Disimpan di komputer ini saja. Dipakai juga saat memasang Tahoe ke TV.")
        Section("Profil")
        Field(Settings.name, { Settings.name = it }, "Nama (untuk sapaan)")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(city, { city = it }, "Kota (cuaca & jadwal shalat)", Modifier.weight(1f))
            GhostButton("Cari", Icons.Rounded.Search) {
                scope.launch {
                    place = "Mencari…"
                    val r = withContext(Dispatchers.IO) { Web.geocode(city) }
                    if (r == null) place = "Kota tidak ditemukan"
                    else { Settings.city = r.name; Settings.province = r.region; Settings.lat = r.lat; Settings.lon = r.lon; city = r.name; place = r.label }
                }
            }
        }
        Text(place, color = Tahoe.Dim, fontSize = 13.sp)

        Section("API key (opsional)")
        Field(Settings.gemini, { Settings.gemini = it.trim() }, "Gemini (asisten)", secret = true)
        Field(Settings.geminiModel, { Settings.geminiModel = it.trim() }, "Model Gemini")
        Field(Settings.youtube, { Settings.youtube = it.trim() }, "YouTube Data API (trending)", secret = true)
        Field(Settings.tmdb, { Settings.tmdb = it.trim() }, "TMDB (Netflix populer)", secret = true)

        Section("TV Live")
        OutlinedTextField(Settings.playlist, { Settings.playlist = it }, label = { Text("Playlist tambahan: URL atau file, satu per baris") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Tahoe.Accent, unfocusedBorderColor = Tahoe.Stroke,
                focusedLabelColor = Tahoe.Accent, unfocusedLabelColor = Tahoe.Faint, cursorColor = Tahoe.Accent))
        Field(Settings.epg, { Settings.epg = it.trim() }, "URL EPG XMLTV (kosong = bawaan)")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Tambah file playlist…", Icons.Rounded.FolderOpen) { pickPlaylist()?.let { Settings.playlist = (Settings.playlist.trim() + "\n" + it).trim() } }
            GhostButton("Hanya sumber bawaan", Icons.Rounded.Restore) { Settings.playlist = ""; Settings.epg = "" }
        }
        Text("Selalu dipakai: TV gratis Indonesia dari iptv-org dan dhanytv; jadwal dari dhanytv + epg.pw. Playlist tambahan digabung ke situ. " +
            "Semua sumber disaring: saluran ber-DRM, restream layanan berbayar, dan merek pay-TV tidak ditampilkan.", color = Tahoe.Faint, fontSize = 12.sp)

        Text("Kualitas video", color = Tahoe.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tech.kousozo.tahoetv.core.PlayPrefs.Quality.entries.forEach { q -> Choice(q.label, Settings.quality == q) { Settings.quality = q } }
        }
        Text("Latensi siaran", color = Tahoe.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tech.kousozo.tahoetv.core.PlayPrefs.Latency.entries.forEach { l -> Choice(l.label, Settings.latency == l) { Settings.latency = l } }
        }
        Text((if (Settings.quality.maxHeight > 0) "Kualitas paling tinggi ${Settings.quality.label} (atau yang terdekat di bawahnya). " else "Kualitas menyesuaikan internet. ") +
            "Latensi ${Settings.latency.label}: ${Settings.latency.note}." + if (WebPlayer.preferred) "" else " Di macOS, latensi diatur otomatis oleh pemutar bawaan.",
            color = Tahoe.Faint, fontSize = 12.sp)

        Section("Wilayah & bahasa")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${tech.kousozo.tahoetv.core.Region.flag(Settings.countryCode)}  ${tech.kousozo.tahoetv.core.Region.name(Settings.countryCode, Settings.lang)} · " +
                (if (Settings.lang == "id") "Bahasa Indonesia" else "English"), color = Tahoe.Text, fontSize = 14.sp)
            GhostButton("Ubah", Icons.Rounded.Public) { Settings.country = "" } // reopens the first-run setup
        }
        Text("Menentukan daftar saluran dan jadwal acara. Teks aplikasi lainnya masih berbahasa Indonesia; terjemahan sedang dikerjakan (#56).",
            color = Tahoe.Faint, fontSize = 12.sp)

        Section("Server IPTV (LAN)")
        Text("Relay streaming IPTV lokal dengan injeksi header HTTP (Referer/User-Agent). Berbagi siaran dari koutv.m3u dan anotheriptv.m3u ke Android TV, HP, atau VLC di jaringan Wi-Fi rumah.", color = Tahoe.Dim, fontSize = 13.sp)

        val isRunning by remember { IptvServer.isRunning }
        val chCount by remember { IptvServer.channelCount }
        val relCount by remember { IptvServer.relayCount }
        var serverStarting by remember { mutableStateOf(false) }
        var serverError by remember { mutableStateOf<String?>(null) }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!isRunning) {
                GhostButton(if (serverStarting) "Menyalakan…" else "Nyalakan Server IPTV", Icons.Rounded.PlayArrow, color = Tahoe.Good) {
                    if (serverStarting) return@GhostButton // one start at a time
                    serverStarting = true; serverError = null
                    Thread { // network load: off the UI thread; daemon so it never keeps a closing app alive
                        val err = runCatching { IptvServer.start(Settings.iptvPort) }.exceptionOrNull()
                        javax.swing.SwingUtilities.invokeLater {
                            serverStarting = false
                            serverError = err?.let { "Server tidak bisa dinyalakan: ${it.message ?: it.javaClass.simpleName} (port ${Settings.iptvPort} dipakai app lain?)" }
                        }
                    }.apply { isDaemon = true }.start()
                }
            } else {
                GhostButton("Matikan Server", Icons.Rounded.Stop, color = Tahoe.Bad) {
                    IptvServer.stop()
                }
                GhostButton("Buka Web Player", Icons.Rounded.OpenInBrowser) {
                    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(IptvServer.webUrl())) }
                }
            }
        }
        serverError?.let { Text(it, color = Tahoe.Bad, fontSize = 12.sp) }

        if (isRunning) {
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(Tahoe.Glass)
                    .border(1.dp, Tahoe.Stroke, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("ONLINE", Icons.Rounded.Wifi)
                    Text("$chCount saluran dimuat ($relCount relay header)", color = Tahoe.Text, fontSize = 14.sp)
                }
                Text("Playlist Master: ${IptvServer.masterPlaylistUrl()}", color = Tahoe.Accent, fontSize = 13.sp)
                Text("EPG Guide: ${IptvServer.epgUrl()}", color = Tahoe.Dim, fontSize = 12.sp)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton("Salin URL Playlist", Icons.Rounded.ContentCopy) {
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(IptvServer.masterPlaylistUrl()), null)
                    }
                    GhostButton("Salin URL EPG", Icons.Rounded.ContentCopy) {
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(IptvServer.epgUrl()), null)
                    }
                }
            }
        }
        Section("Tentang & pembaruan")
        AboutUpdates()
        Section("Bantuan")
        SendLog()
    }
}

/** "Kirim log": saves the diagnostic report (player events and errors, URLs without their tokens) to Downloads,
 *  copies it to the clipboard and opens GitHub Issues, so a problem can be reported with one paste. */
@Composable
private fun SendLog() {
    var saved by remember { mutableStateOf<String?>(null) }
    Text("Ada saluran yang tidak bisa diputar atau aplikasi tertutup sendiri? Kirim log ke pengembang. Isinya catatan pemutar dan error saja; alamat siaran dikirim tanpa token atau kunci.",
        color = Tahoe.Dim, fontSize = 13.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GhostButton("Simpan & salin log", Icons.Rounded.BugReport) {
            saved = runCatching {
                val text = desktopReport()
                val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
                val out = java.io.File(System.getProperty("user.home"), "Downloads/TahoeTV-log-$stamp.txt").apply { parentFile.mkdirs(); writeText(text) }
                java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
                runCatching { java.awt.Desktop.getDesktop().browseFileDirectory(out) }.recoverCatching { java.awt.Desktop.getDesktop().open(out.parentFile) }
                "Disimpan di ${out.path} dan disalin. Tempel di laporan masalah, atau kirim filenya."
            }.getOrElse { "Gagal menyimpan log: ${it.message}" }
        }
        GhostButton("Laporkan di GitHub", Icons.Rounded.OpenInBrowser) {
            val url = "https://github.com/${tech.kousozo.tahoetv.core.Updates.REPO}/issues/new"
            runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
                .onFailure { saved = "Browser tidak bisa dibuka. Buka sendiri: $url" }
        }
    }
    saved?.let { Text(it, color = Tahoe.Dim, fontSize = 13.sp) }
}

private fun desktopReport(): String = tech.kousozo.tahoetv.core.DiagLog.report(
    "Tahoe TV (desktop)", System.getProperty("jpackage.app-version") ?: "dev", linkedMapOf(
        "OS" to "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})",
        "Java" to System.getProperty("java.version"),
        "Server IPTV" to if (IptvServer.isRunning.value) "aktif, ${IptvServer.channelCount.value} saluran" else "mati",
        "Rekaman" to "${Recordings.jobs.size} (${Recordings.jobs.count { it.state == "recording" }} sedang merekam)",
    ))

/** Version, latest release notes (changelog) and OTA: downloads the installer for this Mac/PC and opens it. */
@Composable
private fun AboutUpdates() {
    val scope = rememberCoroutineScope()
    val current = System.getProperty("jpackage.app-version") ?: "dev"
    val os = System.getProperty("os.name").lowercase(); val arm = System.getProperty("os.arch").contains("aarch64")
    val asset = when { "win" in os -> "TahoeTV-windows.msi"; arm -> "TahoeTV-macos-arm64.dmg"; else -> "TahoeTV-macos-intel.dmg" }
    var latest by remember { mutableStateOf<tech.kousozo.tahoetv.core.Updates.Release?>(null) }
    var state by remember { mutableStateOf("Mengecek pembaruan…") }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { tech.kousozo.tahoetv.core.Updates.latest() } }
            .onSuccess { latest = it; state = if (tech.kousozo.tahoetv.core.Updates.newer(it.version, current)) "Versi ${it.version} tersedia" else "Sudah versi terbaru" }
            .onFailure { state = "Tidak bisa mengecek pembaruan" }
    }
    Text("Tahoe TV $current  ·  $state", color = Tahoe.Dim, fontSize = 14.sp)
    val r = latest
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (r != null && tech.kousozo.tahoetv.core.Updates.newer(r.version, current) && asset in r.assets)
            GhostButton("Unduh & pasang ${r.version}", Icons.Rounded.SystemUpdate, color = Tahoe.Accent) {
                scope.launch {
                    val out = java.io.File(System.getProperty("user.home"), "Downloads/$asset")
                    runCatching {
                        withContext(Dispatchers.IO) { tech.kousozo.tahoetv.core.Updates.download(r.assets.getValue(asset), out) { p -> state = "Mengunduh… $p%" } }
                        state = "Selesai. Buka penginstal, lalu tutup aplikasi ini."
                        java.awt.Desktop.getDesktop().open(out)
                    }.onFailure { state = "Gagal mengunduh: ${it.message}" }
                }
            }
        GhostButton("Halaman rilis", Icons.Rounded.OpenInBrowser) { java.awt.Desktop.getDesktop().browse(java.net.URI(r?.page ?: tech.kousozo.tahoetv.core.Updates.PAGE)) }
    }
    r?.let { Text("Catatan rilis ${it.version} (${it.date})\n\n" + it.notes.replace(Regex("(?m)^#+\\s*"), "").replace("**", ""), color = Tahoe.Dim, fontSize = 13.sp) }
}

/** Copies the chosen .m3u/.txt into ~/.tahoetv/playlists (so cleaning Downloads doesn't break it) and returns that path. */
private fun pickPlaylist(): String? {
    val d = java.awt.FileDialog(null as java.awt.Frame?, "Pilih file playlist (.m3u / .m3u8 / .txt)", java.awt.FileDialog.LOAD)
    d.isVisible = true
    val f = d.files.firstOrNull() ?: return null
    val dir = java.io.File(System.getProperty("user.home"), ".tahoetv/playlists").apply { mkdirs() }
    return java.io.File(dir, f.name).also { f.copyTo(it, overwrite = true) }.path
}


/** A selectable pill for a short list of options. */
@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) = Text(text,
    Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
        .background(if (selected) Tahoe.Accent else Tahoe.Glass)
        .selectable(selected = selected, onClick = onClick, role = androidx.compose.ui.semantics.Role.RadioButton)
        .padding(horizontal = 14.dp, vertical = 7.dp),
    color = if (selected) androidx.compose.ui.graphics.Color(0xFF0B0B0F) else Tahoe.Text, fontSize = 13.sp)
