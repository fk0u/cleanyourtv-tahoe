package tech.kousozo.tahoetv

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tech.kousozo.tahoetv.core.Epg
import tech.kousozo.tahoetv.core.LocalTv
import tech.kousozo.tahoetv.core.M3u
import tech.kousozo.tahoetv.core.Sources
import tech.kousozo.tahoetv.core.Web

/** TV Live on the phone: same free channels and guide as the TV and desktop (national priority first, then near you).
 *  Plays here with ExoPlayer; each channel falls back through its backup streams. "Di TV" opens Live TV on the paired TV. */
@Composable
fun LiveScreen() {
    val c = LocalContext.current
    var entries by remember { mutableStateOf(liveCache) }
    var epg by remember { mutableStateOf(epgCache) }
    var failed by remember { mutableStateOf(false) }
    var cat by remember { mutableStateOf<LocalTv.Category?>(null) }
    var playing by remember { mutableStateOf<LocalTv.Entry?>(null) }
    var full by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (entries == null) withContext(Dispatchers.IO) {
            val setup = c.getSharedPreferences("setup", 0)
            val province = setup.getString("region", null) ?: setup.getString("city", "").orEmpty().takeIf { it.isNotBlank() }
                ?.let { Web.geocode(it)?.region }?.also { setup.edit().putString("region", it).apply() }
            runCatching { LocalTv.arrange(Sources.load(), province) }.fold({ entries = it; liveCache = it }, { failed = true })
        }
        if (epg == null) epg = withContext(Dispatchers.IO) { runCatching { Epg.load(Sources.EPG) }.getOrNull() }.also { epgCache = it }
    }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = Instant.now() } }

    // full screen = landscape, player only; Back returns
    val activity = c as? Activity
    DisposableEffect(full) {
        activity?.requestedOrientation = if (full) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    BackHandler(full) { full = false }

    val list = entries
    Column(Modifier.fillMaxSize().then(if (full) Modifier.background(Color.Black) else Modifier.systemBarsPadding())) {
        playing?.let { e ->
            Box(Modifier.fillMaxWidth().then(if (full) Modifier.fillMaxHeight() else Modifier.aspectRatio(16f / 9f)).background(Color.Black)) {
                LivePlayer(e) { list?.let { l -> playing = l[(l.indexOf(e) + it + l.size) % l.size] } }
                IconButton({ full = !full }, Modifier.align(Alignment.TopEnd)) {
                    Icon(if (full) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, "Layar penuh", tint = Color.White)
                }
            }
        }
        if (full) return@Column
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("TV Live", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            playing?.let { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        nowOn(epg, e, now)?.let { Text("Sekarang: ${it.title}", color = Tahoe.Accent, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    val p = remember { Sync.prefs(c) }
                    if (p.getString("token", "").orEmpty().isNotEmpty())
                        TextButton({ tech.kousozo.tahoetv.core.TvLink(p.getString("host", "")!!, p.getString("token", "")!!).apply { open(); Thread { Thread.sleep(1500); launch(TV_LIVE); close() }.start() } }) {
                            Icon(Icons.Rounded.Cast, null, tint = Tahoe.Accent); Spacer(Modifier.width(6.dp)); Text("Di TV", color = Tahoe.Accent)
                        }
                }
            }
            if (list == null) {
                if (failed) Note("Daftar saluran tidak bisa dimuat. Cek internet, lalu buka lagi tab ini.", Icons.Rounded.ErrorOutline)
                else LinearProgressIndicator(Modifier.fillMaxWidth(), color = Tahoe.Accent, trackColor = Tahoe.Glass)
                return@Column
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf<LocalTv.Category?>(null) + LocalTv.Category.entries.filter { k -> list.any { LocalTv.inCategory(it, k) } }).forEach { k ->
                    val on = k == cat
                    Text(k?.label ?: "Semua", Modifier.clip(RoundedCornerShape(50)).background(if (on) Tahoe.Text else Tahoe.Glass).clickable { cat = k }
                        .padding(horizontal = 12.dp, vertical = 7.dp), color = if (on) Color(0xFF111114) else Tahoe.Text, fontSize = 13.sp)
                }
            }
        }
        list?.let { l ->
            LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), contentPadding = PaddingValues(bottom = TabInset)) {
                items(l.filter { cat == null || LocalTv.inCategory(it, cat!!) }, key = { it.channel.url }) { e ->
                    val on = e == playing
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable { playing = e }.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.name, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium)
                            Text(nowOn(epg, e, now)?.let { "${it.title} · ${HM.format(it.start)}–${HM.format(it.stop)}" } ?: e.category.label,
                                color = if (nowOn(epg, e, now) != null) Tahoe.Accent else Tahoe.Faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(if (on) Icons.Rounded.GraphicEq else Icons.Rounded.PlayCircle, null, tint = if (on) Tahoe.Accent else Tahoe.Dim)
                    }
                }
            }
        }
    }
}

/** ExoPlayer with the stream's headers; on error tries the channel's next backup. `zap(±1)` = previous/next channel. */
@Composable
private fun LivePlayer(e: LocalTv.Entry, zap: (Int) -> Unit) {
    val c = LocalContext.current
    val player = remember { ExoPlayer.Builder(c).build() }
    var source by remember(e) { mutableIntStateOf(0) }
    var message by remember(e) { mutableStateOf<String?>("Memuat siaran…") }
    val streams = remember(e) { listOf(e.channel) + e.alternates }
    DisposableEffect(player) { onDispose { player.release() } }
    DisposableEffect(player, e) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { source++ }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) message = null }
        }
        player.addListener(l); onDispose { player.removeListener(l) }
    }
    LaunchedEffect(e, source) {
        val s = streams.getOrNull(source) ?: run { player.stop(); message = "Saluran ini sedang tidak bisa diputar. Coba saluran lain."; return@LaunchedEffect }
        message = if (source == 0) "Memuat siaran…" else "Mencoba sumber cadangan ${source + 1}/${streams.size}…"
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
            .setUserAgent(s.headers["User-Agent"] ?: "TahoeTV").setDefaultRequestProperties(s.headers - "User-Agent")
        player.setMediaSource(DefaultMediaSourceFactory(http).createMediaSource(MediaItem.fromUri(Uri.parse(s.url))))
        player.prepare(); player.play()
        delay(20_000) // never started → next source
        if (player.playbackState != Player.STATE_READY) source++
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView({ PlayerView(it).apply { this.player = player; useController = false; layoutParams = ViewGroup.LayoutParams(-1, -1) } }, Modifier.fillMaxSize())
        message?.let {
            Text(it, Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(12.dp),
                color = Color.White, fontSize = 14.sp)
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalIconButton({ zap(-1) }) { Icon(Icons.Rounded.SkipPrevious, "Saluran sebelumnya") }
            FilledTonalIconButton({ if (player.isPlaying) player.pause() else player.play() }) { Icon(Icons.Rounded.PlayArrow, "Putar/jeda") }
            FilledTonalIconButton({ zap(1) }) { Icon(Icons.Rounded.SkipNext, "Saluran berikutnya") }
        }
    }
}

private fun nowOn(epg: Epg?, e: LocalTv.Entry, now: Instant) = epg?.schedule(e.name, now)?.firstOrNull()?.takeIf { it.start <= now }

private val HM = DateTimeFormatter.ofPattern("HH.mm").withZone(ZoneId.systemDefault())

/** Survives tab switches; refreshed when the app restarts. */
private var liveCache: List<LocalTv.Entry>? = null
private var epgCache: Epg? = null

/** The TV launcher's built-in Live TV (its pseudo package in the app list). */
const val TV_LIVE = "tv.clean.home.livetv"
