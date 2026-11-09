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

        Section("Server IPTV (LAN)")
        Text("Relay streaming IPTV lokal dengan injeksi header HTTP (Referer/User-Agent). Berbagi siaran dari koutv.m3u dan anotheriptv.m3u ke Android TV, HP, atau VLC di jaringan Wi-Fi rumah.", color = Tahoe.Dim, fontSize = 13.sp)

        val isRunning by remember { IptvServer.isRunning }
        val chCount by remember { IptvServer.channelCount }
        val relCount by remember { IptvServer.relayCount }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!isRunning) {
                GhostButton("Nyalakan Server IPTV", Icons.Rounded.PlayArrow, color = Tahoe.Good) {
                    IptvServer.start(Settings.iptvPort)
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
    }
}

/** Copies the chosen .m3u/.txt into ~/.tahoetv/playlists (so cleaning Downloads doesn't break it) and returns that path. */
private fun pickPlaylist(): String? {
    val d = java.awt.FileDialog(null as java.awt.Frame?, "Pilih file playlist (.m3u / .m3u8 / .txt)", java.awt.FileDialog.LOAD)
    d.isVisible = true
    val f = d.files.firstOrNull() ?: return null
    val dir = java.io.File(System.getProperty("user.home"), ".tahoetv/playlists").apply { mkdirs() }
    return java.io.File(dir, f.name).also { f.copyTo(it, overwrite = true) }.path
}
