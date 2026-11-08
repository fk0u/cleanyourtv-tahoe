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
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.kousozo.tahoetv.core.Epg
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
    var group by remember { mutableStateOf<String?>(null) }
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
    val groups = remember(channels) { channels.orEmpty().mapNotNull { it.group }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(10) }
    val shown = channels.orEmpty()
        .filter { query.isBlank() || it.name.contains(query, true) }
        .filter { group == null || (group == "★" && it.url in favs) || it.group == group }
        .sortedByDescending { it.url in favs }

    Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 28.dp, top = 24.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.width(340.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Title("TV Live", channels?.let { "${it.size} saluran" } ?: error ?: "Memuat playlist…")
            Field(query, { query = it }, "Cari saluran", trailing = { Icon(Icons.Rounded.Search, null, tint = Tahoe.Faint) })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf(null, "★") + groups).forEach { g ->
                    val on = g == group
                    Text(g ?: "Semua", Modifier.clip(RoundedCornerShape(50)).background(if (on) Tahoe.Text else Tahoe.Glass).clickable { group = g }
                        .padding(horizontal = 12.dp, vertical = 6.dp), color = if (on) Color(0xFF111114) else Tahoe.Text, fontSize = 13.sp)
                }
            }
            if (channels == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Tahoe.Accent, trackColor = Tahoe.Glass)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(shown, key = { it.url }) { ch ->
                    val on = ch == playing
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                            .clickable { playing = ch }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(44.dp, 32.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
                            rememberImage(ch.logo)?.let { Image(it, null, Modifier.padding(3.dp).fillMaxSize(), contentScale = ContentScale.Fit) }
                                ?: Text(ch.name.take(2), color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ch.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                            val nowOn = epg?.schedule(ch.name, tick)?.firstOrNull()?.takeIf { it.start <= tick }
                            Text(nowOn?.title ?: ch.group ?: "", fontSize = 12.sp, color = if (nowOn != null) Tahoe.Accent else Tahoe.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(24.dp)).background(Color.Black)) {
                val ch = playing
                if (ch == null) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.LiveTv, null, tint = Tahoe.Faint, modifier = Modifier.size(56.dp))
                    Text("Pilih saluran di kiri", color = Tahoe.Dim)
                } else FxPlayer(ch.url, Modifier.fillMaxSize()) { status = it }
            }
            playing?.let { ch ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(ch.name, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text(status ?: "Memutar langsung", color = if (status != null) Tahoe.Bad else Tahoe.Good, fontSize = 14.sp)
                    }
                    Session.tv?.let { tv ->
                        GhostButton("Putar di TV", Icons.Rounded.Cast) {
                            Thread { runCatching { tv.sh("am start -a android.intent.action.VIEW -t video/* -d " + tech.kousozo.tahoetv.core.TvAdb.quote(ch.url)) } }.start()
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    GhostButton("Buka di pemutar lain", Icons.Rounded.OpenInNew) { browse(ch.url) }
                }
            }
            playing?.let { ch -> Guide(epg?.schedule(ch.name, tick).orEmpty(), epg != null, tick) }
            Text("Saluran dari playlist publik iptv-org, jadwal dari epg.pw (keduanya bisa diganti di Setelan). Sebagian saluran bisa offline atau dibatasi wilayah.",
                fontSize = 12.sp, color = Tahoe.Faint)
        }
    }
}

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

/** JavaFX MediaView inside Compose. One player at a time; disposed when the channel changes or the screen closes. */
@Composable
fun FxPlayer(url: String, modifier: Modifier, onStatus: (String?) -> Unit) {
    val panel = remember { JFXPanel().also { Platform.setImplicitExit(false) } }
    SwingPanel(background = Color.Black, factory = { panel }, modifier = modifier)
    DisposableEffect(url) {
        var mp: MediaPlayer? = null
        onStatus(null)
        Platform.runLater {
            runCatching {
                val player = MediaPlayer(Media(url)).also { mp = it }
                val view = MediaView(player).apply { isPreserveRatio = true }
                val root = StackPane(view).apply { style = "-fx-background-color: black;" }
                view.fitWidthProperty().bind(root.widthProperty()); view.fitHeightProperty().bind(root.heightProperty())
                panel.scene = Scene(root, javafx.scene.paint.Color.BLACK)
                player.setOnError { SwingUtilities.invokeLater { onStatus("Saluran tidak bisa diputar (${player.error?.type ?: "offline"})") } }
                player.setOnStalled { SwingUtilities.invokeLater { onStatus("Menunggu siaran…") } }
                player.setOnPlaying { SwingUtilities.invokeLater { onStatus(null) } }
                player.play()
            }.onFailure { e -> SwingUtilities.invokeLater { onStatus("Saluran tidak bisa diputar: ${e.message}") } }
        }
        onDispose { Platform.runLater { mp?.dispose() } }
    }
}
