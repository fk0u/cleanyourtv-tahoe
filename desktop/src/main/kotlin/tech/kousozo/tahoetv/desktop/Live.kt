package tech.kousozo.tahoetv.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.kousozo.tahoetv.core.Epg
import tech.kousozo.tahoetv.core.LocalTv
import tech.kousozo.tahoetv.core.M3u
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/** Live TV from an M3U playlist. Playback via JavaFX media (HLS + H.264/AAC, what most free IPTV streams use). */
@Composable
fun LiveScreen() {
    var channels by remember { mutableStateOf<List<M3u.Channel>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf<String?>(null) } // null = Semua, "★" = favorites, else a LocalTv.Category name
    var playing by remember { mutableStateOf<M3u.Channel?>(null) }
    var favs by remember { mutableStateOf(Settings.favorites) }
    var epg by remember { mutableStateOf<Epg?>(null) }
    var tick by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick = Instant.now() } }
    val epgUrl = Settings.epg
    LaunchedEffect(epgUrl) { epg = null; epg = withContext(Dispatchers.IO) { runCatching { Epg.load(epgUrl) }.getOrNull() } }
    val playlist = Settings.playlist
    LaunchedEffect(playlist) {
        channels = null; error = null
        withContext(Dispatchers.IO) { runCatching { M3u.load(playlist) } }.fold({ channels = it }, { error = "Playlist tidak bisa dimuat: ${it.message}" })
    }
    val province = Settings.province
    val entries = remember(channels, province) { LocalTv.arrange(channels.orEmpty(), province) }
    val names = remember(entries) { entries.associate { it.channel.url to it.name } }
    val cats = remember(entries) { LocalTv.Category.entries.filter { c -> entries.any { LocalTv.inCategory(it, c) } } }
    val shown = entries
        .filter { query.isBlank() || it.name.contains(query, true) }
        .filter { e -> when (group) { null -> true; "★" -> e.channel.url in favs; else -> LocalTv.inCategory(e, LocalTv.Category.valueOf(group!!)) } }
        .sortedByDescending { it.channel.url in favs }

    Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 28.dp, top = 24.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.width(340.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Title("TV Live", channels?.let { "${entries.size} saluran Indonesia" + (province.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") } ?: error ?: "Memuat playlist…")
            Field(query, { query = it }, "Cari saluran", trailing = { Icon(Icons.Rounded.Search, null, tint = Tahoe.Faint) })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf(null) + cats.map { it.name } + "★").forEach { g ->
                    val on = g == group
                    Text(g?.let { if (it == "★") "★ Favorit" else LocalTv.Category.valueOf(it).label } ?: "Semua", Modifier.clip(RoundedCornerShape(50)).background(if (on) Tahoe.Text else Tahoe.Glass).clickable { group = g }
                        .padding(horizontal = 12.dp, vertical = 6.dp), color = if (on) Color(0xFF111114) else Tahoe.Text, fontSize = 13.sp)
                }
            }
            if (channels == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Tahoe.Accent, trackColor = Tahoe.Glass)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(shown, key = { it.channel.url }) { e ->
                    val ch = e.channel
                    val on = ch == playing
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                            .clickable { playing = ch }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(44.dp, 32.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
                            rememberImage(ch.logo)?.let { Image(it, null, Modifier.padding(3.dp).fillMaxSize(), contentScale = ContentScale.Fit) }
                                ?: Text(e.name.take(2), color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(e.name, Modifier.weight(1f, fill = false), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                                if (e.near) Badge("Dekat", Tahoe.Accent)
                                if (e.partTime) Badge("Tidak 24 jam", Tahoe.Faint)
                            }
                            val nowOn = epg?.schedule(e.name, tick)?.firstOrNull()?.takeIf { it.start <= tick }
                            Text(nowOn?.title ?: e.category.label, fontSize = 12.sp, color = if (nowOn != null) Tahoe.Accent else Tahoe.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        val fav = ch.url in favs
                        IconButton({ favs = if (fav) favs - ch.url else favs + ch.url; Settings.favorites = favs }, Modifier.size(32.dp)) {
                            Icon(if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favorit", tint = if (fav) Color(0xFFFFD60A) else Tahoe.Faint, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            var status by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(playing) { playing?.let { ch -> PlayerWindow.play(ch.url, names[ch.url] ?: ch.name) { status = it } } }
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(24.dp)).background(Color.Black)) {
                val ch = playing
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (ch == null) {
                        Icon(Icons.Rounded.LiveTv, null, tint = Tahoe.Faint, modifier = Modifier.size(56.dp))
                        Text("Pilih saluran di kiri", color = Tahoe.Dim)
                    } else {
                        Box(Modifier.size(120.dp, 80.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
                            rememberImage(ch.logo)?.let { Image(it, null, Modifier.padding(10.dp).fillMaxSize(), contentScale = ContentScale.Fit) }
                                ?: Icon(Icons.Rounded.LiveTv, null, tint = Color.Black, modifier = Modifier.size(40.dp))
                        }
                        Text("Diputar di jendela pemutar", color = Tahoe.Dim)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimaryButton("Tampilkan pemutar", Icons.Rounded.PictureInPicture) { PlayerWindow.show() }
                            GhostButton("Hentikan", Icons.Rounded.Stop) { PlayerWindow.stop(); playing = null }
                        }
                        Text("F = layar penuh · Spasi = jeda · Esc = tutup", color = Tahoe.Faint, fontSize = 12.sp)
                    }
                }
            }
            playing?.let { ch ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(names[ch.url] ?: ch.name, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text(status ?: "Memutar langsung", color = if (status != null) Tahoe.Bad else Tahoe.Good, fontSize = 14.sp)
                    }
                    Session.tv?.let { tv ->
                        GhostButton("Tonton di BitTV (TV)", Icons.Rounded.Cast) { // BitTV is the TV's watching app; Tahoe brings the guide
                            Thread { runCatching { tv.sh("monkey -p com.live_streaming_tv.online_tv -c android.intent.category.LEANBACK_LAUNCHER 1") } }.start()
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    GhostButton("Buka di pemutar lain", Icons.Rounded.OpenInNew) { browse(ch.url) }
                }
            }
            playing?.let { ch -> Guide(epg?.schedule(names[ch.url] ?: ch.name, tick).orEmpty(), epg != null, tick) }
            Text("Saluran TV Indonesia dari playlist publik iptv-org, jadwal dari epg.pw (keduanya bisa diganti di Setelan). Sebagian saluran bisa offline atau dibatasi wilayah.",
                fontSize = 12.sp, color = Tahoe.Faint)
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) = Text(text, Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.18f))
    .padding(horizontal = 7.dp, vertical = 1.dp), color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)

private val HM = DateTimeFormatter.ofPattern("HH.mm").withZone(ZoneId.systemDefault())

/** Now playing with progress + what's next, from the XMLTV guide. */
@Composable
private fun ColumnScope.Guide(schedule: List<Epg.Programme>, loaded: Boolean, now: Instant) {
    if (schedule.isEmpty()) { Text(if (loaded) "Jadwal acara tidak tersedia untuk saluran ini." else "Memuat jadwal acara…", color = Tahoe.Faint, fontSize = 13.sp); return }
    Glass(Modifier.fillMaxWidth().weight(1f, fill = false)) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(schedule.take(12)) { p ->
                val live = p.start <= now
                Row(verticalAlignment = Alignment.Top) {
                    Text(HM.format(p.start), Modifier.width(56.dp), color = if (live) Tahoe.Accent else Tahoe.Dim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Column(Modifier.weight(1f)) {
                        Text(p.title, fontSize = 15.sp, fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal)
                        if (live) {
                            val f = ((now.epochSecond - p.start.epochSecond).toFloat() / (p.stop.epochSecond - p.start.epochSecond)).coerceIn(0f, 1f)
                            LinearProgressIndicator({ f }, Modifier.fillMaxWidth().padding(vertical = 4.dp).height(3.dp), color = Tahoe.Accent, trackColor = Tahoe.Stroke)
                            p.desc?.let { Text(it, fontSize = 13.sp, color = Tahoe.Dim, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                    if (live) Text("SEDANG TAYANG", fontSize = 10.sp, color = Tahoe.Bad, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
