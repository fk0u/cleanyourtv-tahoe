package tech.kousozo.tahoetv.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.kousozo.tahoetv.core.Web

fun browse(url: String) = runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }

/** Streaming services opened in the default browser (where DRM playback like Netflix actually works). */
private val SERVICES = listOf(
    Triple("YouTube", "https://www.youtube.com", Color(0xFFFF0033)),
    Triple("Netflix", "https://www.netflix.com", Color(0xFFE50914)),
    Triple("Vidio", "https://www.vidio.com", Color(0xFFEE2B5B)),
    Triple("Spotify", "https://open.spotify.com", Color(0xFF1DB954)),
    Triple("Prime Video", "https://www.primevideo.com", Color(0xFF00A8E1)),
    Triple("Disney+", "https://www.hotstar.com/id", Color(0xFF113CCF)),
    Triple("TikTok", "https://www.tiktok.com", Color(0xFF25F4EE)),
    Triple("Viu", "https://www.viu.com", Color(0xFFF5B400)),
)

@Composable
fun HomeScreen(onLive: () -> Unit) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = LocalTime.now(); delay(15_000) } }
    var weather by remember { mutableStateOf<Web.Weather?>(null) }
    var prayer by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var heroes by remember { mutableStateOf<List<Web.Hero>>(emptyList()) }
    val lat = Settings.lat; val lon = Settings.lon
    LaunchedEffect(lat, lon, Settings.youtube, Settings.tmdb) {
        withContext(Dispatchers.IO) {
            weather = Web.weather(lat, lon)
            prayer = Web.prayer(lat, lon)
            val yt = Settings.youtube.takeIf { it.isNotBlank() }?.let { Web.youtubeTrending(it) }.orEmpty()
            val nf = Settings.tmdb.takeIf { it.isNotBlank() }?.let { Web.netflixPopular(it) }.orEmpty()
            heroes = (0 until maxOf(yt.size, nf.size)).flatMap { listOfNotNull(yt.getOrNull(it), nf.getOrNull(it)) }
        }
    }
    val hello = when (now.hour) { in 4..10 -> "Selamat pagi"; in 11..14 -> "Selamat siang"; in 15..17 -> "Selamat sore"; else -> "Selamat malam" }
    val next = prayer.firstOrNull { LocalTime.parse(it.second) > now } ?: prayer.firstOrNull()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 28.dp, top = 24.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text((hello + if (Settings.name.isNotBlank()) ", ${Settings.name}" else "").uppercase(), fontSize = 13.sp, color = Tahoe.Faint, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
                Text(now.format(DateTimeFormatter.ofPattern("HH.mm")), fontSize = 56.sp, fontWeight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                weather?.let { Chip("${it.tempC}° ${it.label} · ${Settings.city}", Icons.Rounded.Cloud) }
                next?.let { Chip("${it.first} ${it.second}", Icons.Rounded.NightsStay) }
            }
        }

        HeroCarousel(heroes)

        Section("Tonton")
        BoxWithConstraints {
            val cols = if (maxWidth > 900.dp) 8 else 4
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SERVICES.chunked(cols).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { (name, url, color) ->
                            Box(
                                Modifier.weight(1f).aspectRatio(1.6f).clip(RoundedCornerShape(18.dp))
                                    .background(Brush.linearGradient(listOf(color.copy(alpha = 0.85f), color.copy(alpha = 0.35f)))).clickable { browse(url) },
                                contentAlignment = Alignment.Center,
                            ) { Text(name, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                        }
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        Glass(Modifier.fillMaxWidth(), onClick = onLive) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.LiveTv, null, tint = Tahoe.Accent, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("TV Live", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("Saluran TV gratis dari playlist IPTV, langsung di app ini.", color = Tahoe.Dim, fontSize = 14.sp)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = Tahoe.Faint)
            }
        }

        Section("Tanya Tahoe")
        AssistantPanel()
    }
}

@Composable
private fun HeroCarousel(heroes: List<Web.Hero>) {
    var i by remember(heroes) { mutableStateOf(0) }
    LaunchedEffect(heroes) { while (heroes.isNotEmpty()) { delay(8000); i = (i + 1) % heroes.size } }
    Box(Modifier.fillMaxWidth().height(380.dp).clip(RoundedCornerShape(28.dp)).background(Tahoe.Glass)) {
        if (heroes.isEmpty()) {
            Column(Modifier.align(Alignment.CenterStart).padding(36.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Selamat datang di Tahoe", fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Text("Isi API key YouTube atau TMDB di Setelan untuk rekomendasi trending di sini.", color = Tahoe.Dim, fontSize = 16.sp)
            }
            return@Box
        }
        Crossfade(heroes[i % heroes.size], label = "hero") { h ->
            Box(Modifier.fillMaxSize()) {
                rememberImage(h.image)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Black.copy(alpha = 0.1f)))))
                Column(Modifier.align(Alignment.BottomStart).padding(36.dp).widthIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("TRENDING DI ${h.source.uppercase()}", fontSize = 13.sp, color = Tahoe.Dim, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
                    Text(h.title, fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(h.sub, fontSize = 15.sp, color = Tahoe.Dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("Putar", Icons.Rounded.PlayArrow) { browse(h.url) }
                        Session.tv?.let { tv -> GhostButton("Putar di TV", Icons.Rounded.Cast) { Thread { runCatching { tv.sh("am start -a android.intent.action.VIEW -d " + tech.kousozo.tahoetv.core.TvAdb.quote(h.url)) } }.start() } }
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(28.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            heroes.indices.forEach { k ->
                Box(Modifier.size(if (k == i) 22.dp else 7.dp, 7.dp).clip(CircleShape).background(if (k == i) Tahoe.Text else Tahoe.Faint).clickable { i = k })
            }
        }
    }
}

@Composable
private fun AssistantPanel() {
    var q by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val history = remember { mutableStateListOf<Pair<String, String>>() }
    val scope = rememberCoroutineScope()
    fun send() {
        val question = q.trim().ifEmpty { return }
        if (Settings.gemini.isBlank()) { answer = "Isi API key Gemini di Setelan dulu."; return }
        busy = true; q = ""
        scope.launch {
            val a = withContext(Dispatchers.IO) {
                runCatching { Web.ask(Settings.gemini, Settings.geminiModel, question, Settings.city, history.takeLast(6)) }.getOrElse { "Gagal: ${it.message}" }
            }
            history += "user" to question; history += "model" to a
            answer = a; busy = false
        }
    }
    Glass(Modifier.fillMaxWidth()) {
        Field(q, { q = it }, "Tanya apa saja: berita terbaru, rekomendasi film, jadwal bola…",
            Modifier.fillMaxWidth().onEnter { send() },
            trailing = { IconButton(::send, enabled = !busy) { Icon(Icons.AutoMirrored.Rounded.Send, "Kirim", tint = Tahoe.Accent) } })
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Tahoe.Accent, trackColor = Tahoe.Glass)
        answer?.let { Text(it, fontSize = 15.sp, lineHeight = 22.sp, color = Tahoe.Text) }
    }
}

fun Modifier.onEnter(action: () -> Unit) = onPreviewKeyEvent {
    if (it.key == Key.Enter && it.type == KeyEventType.KeyDown) { action(); true } else false
}
