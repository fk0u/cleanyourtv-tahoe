package tech.kousozo.tahoetv.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
                    else { Settings.city = r.name; Settings.lat = r.lat; Settings.lon = r.lon; city = r.name; place = r.label }
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
        Field(Settings.playlist, { Settings.playlist = it.trim() }, "URL playlist M3U")
        Field(Settings.epg, { Settings.epg = it.trim() }, "URL EPG (XMLTV)")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Pakai sumber bawaan (Indonesia)", Icons.Rounded.Restore) { Settings.playlist = M3u.DEFAULT; Settings.epg = tech.kousozo.tahoetv.core.Epg.DEFAULT }
        }
        Text("Bawaan: saluran gratis Indonesia dari iptv-org dan jadwal acara dari epg.pw. Kamu bisa memakai playlist dan EPG resmi milikmu sendiri.", color = Tahoe.Faint, fontSize = 12.sp)
    }
}
