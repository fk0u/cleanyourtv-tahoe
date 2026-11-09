package tech.kousozo.tahoetv

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.drawscope.rotate
import tech.kousozo.tahoetv.core.SourceHealth
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
import androidx.compose.ui.graphics.Brush
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
    val entries = Channels.entries
    val epg = Channels.epg
    val failed = Channels.failed
    var cat by remember { mutableStateOf(Nav.liveCategory) }
    var playing by remember { mutableStateOf<LocalTv.Entry?>(null) }
    var full by Nav::full
    LaunchedEffect(Unit) { Channels.load(c) }
    // requests from Beranda / Kategori: open a category, or start a channel
    LaunchedEffect(Nav.liveCategory) { cat = Nav.liveCategory }
    LaunchedEffect(Nav.playUrl, entries) {
        val u = Nav.playUrl ?: return@LaunchedEffect
        entries?.firstOrNull { it.channel.url == u }?.let { playing = it; Nav.playUrl = null }
    }
    LaunchedEffect(playing) { playing?.let { Channels.remember(c, it) } }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = Instant.now() } }

    // full screen = landscape, player only, system bars and tab bar hidden; Back returns.
    // Turning the phone to landscape while a channel plays also goes full screen (rotation no longer recreates the screen).
    val activity = c as? Activity
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    LaunchedEffect(landscape) { if (playing != null) full = landscape }
    DisposableEffect(full) {
        activity?.requestedOrientation = if (full) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.window?.let { w ->
            val ic = androidx.core.view.WindowCompat.getInsetsController(w, w.decorView)
            ic.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (full) ic.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars()) else ic.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { w -> androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
        }
    }
    DisposableEffect(Unit) { onDispose { Nav.full = false } } // leaving the tab never leaves the tab bar hidden
    BackHandler(full) { full = false }

    val list = entries
    Column(Modifier.fillMaxSize().then(if (full) Modifier.background(Color.Black) else Modifier.systemBarsPadding())) {
        playing?.let { e ->
            Box(Modifier.fillMaxWidth().then(if (full) Modifier.fillMaxHeight() else Modifier.aspectRatio(16f / 9f)).background(Color.Black)
                .semantics { contentDescription = "Siaran ${e.name}" }) {
                LivePlayer(e) { list?.let { l -> playing = l[(l.indexOf(e) + it + l.size) % l.size] } }
                IconButton({ full = !full }, Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                    Icon(if (full) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, if (full) "Keluar dari layar penuh" else "Layar penuh", tint = Color.White)
                }
            }
        }
        if (full) return@Column
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (playing == null) Text("TV Live", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            playing?.let { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChannelLogo(e, Modifier.size(56.dp, 38.dp)); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Pill("LIVE", Tahoe.Bad, filled = true); Spacer(Modifier.width(8.dp))
                            Text(e.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                        nowOn(epg, e, now)?.let { Text("${it.title} · ${HM.format(it.start)}–${HM.format(it.stop)}", color = Tahoe.Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
                    Text(k?.label ?: "Semua", Modifier.clip(RoundedCornerShape(50)).background(if (on) Tahoe.Accent else Tahoe.Glass).clickable { cat = k; Nav.liveCategory = k }
                        .padding(horizontal = 12.dp, vertical = 7.dp), color = if (on) Color(0xFF111114) else Tahoe.Text, fontSize = 13.sp)
                }
            }
        }
        list?.let { l ->
            LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), contentPadding = PaddingValues(bottom = TabInset)) {
                items(l.filter { cat == null || LocalTv.inCategory(it, cat!!) }, key = { it.channel.url }) { e ->
                    val on = e == playing
                    val prog = nowOn(epg, e, now)
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp))
                        .background(if (on) Color.White.copy(alpha = 0.14f) else Tahoe.Glass).clickable { playing = e }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        ChannelLogo(e, Modifier.size(72.dp, 48.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(e.name, Modifier.weight(1f, fill = false), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (prog != null) { Spacer(Modifier.width(6.dp)); Pill("EPG", Tahoe.Faint) }
                            }
                            Text(prog?.title ?: e.category.label, color = if (prog != null) Tahoe.Dim else Tahoe.Faint, fontSize = 12.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            prog?.let { pr ->
                                LinearProgressIndicator({ progress(pr, now) }, Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                                    color = if (on) Tahoe.Accent else Tahoe.Dim, trackColor = Tahoe.Stroke)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
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
    var everReady by remember(e) { mutableStateOf(false) }
    var retries by remember(e) { mutableIntStateOf(0) }
    val streams = remember(e) { SourceHealth.order(listOf(e.channel) + e.alternates) } // known-live sources first
    DisposableEffect(player) { onDispose { player.release() } }
    DisposableEffect(player, e) {
        val l = object : Player.Listener {
            // A source that already played reconnects at the live edge (blip, rebuffer, fell behind live);
            // only one that never played, or keeps failing, moves to the backup.
            override fun onPlayerError(error: PlaybackException) {
                // Fell out of the live window: rejoin at the live point. Anything else: carry on from the same spot.
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) { player.seekToDefaultPosition(); player.prepare(); return }
                if (!everReady || retries >= 3) { streams.getOrNull(source)?.let { SourceHealth.report(it.url, false) }; source++; return }
                retries++; message = "Menyambung ulang…"
                player.prepare(); player.play()
            }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) { streams.getOrNull(source)?.let { SourceHealth.report(it.url, true) }; message = null; everReady = true; retries = 0 } }
        }
        player.addListener(l); onDispose { player.removeListener(l) }
    }
    LaunchedEffect(e, source) {
        val s = streams.getOrNull(source) ?: run { player.stop(); message = "Saluran ini sedang tidak bisa diputar. Coba saluran lain."; return@LaunchedEffect }
        everReady = false; retries = 0
        message = if (source == 0) "Menyambung ke siaran…" else "Mencoba sumber cadangan ${source + 1}/${streams.size}…"
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
            .setUserAgent(s.headers["User-Agent"] ?: "TahoeTV/2.9.4").setDefaultRequestProperties(s.headers - "User-Agent")
        player.setMediaSource(DefaultMediaSourceFactory(http).createMediaSource(MediaItem.fromUri(Uri.parse(s.url))))
        player.prepare(); player.play()
        delay(20_000) // never started → next source
        if (!everReady) { s.let { SourceHealth.report(it.url, false) }; source++ }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView({ PlayerView(it).apply { this.player = player; useController = false; layoutParams = ViewGroup.LayoutParams(-1, -1) } }, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.55f)))))
        Row(Modifier.align(Alignment.TopStart).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("LIVE", Tahoe.Bad, filled = true); Spacer(Modifier.width(8.dp))
            Text(e.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        message?.let {
            if (it.startsWith("Saluran ini")) Text(it, Modifier.align(Alignment.Center).semantics { liveRegion = LiveRegionMode.Polite }.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(12.dp),
                color = Color.White, fontSize = 14.sp)
            else ChannelLoader(e.name, it, Modifier.align(Alignment.Center).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = "${e.name}: $it" })
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalIconButton({ zap(-1) }) { Icon(Icons.Rounded.SkipPrevious, "Saluran sebelumnya") }
            var paused by remember { mutableStateOf(false) }
            FilledTonalIconButton({ if (player.isPlaying) player.pause() else player.play(); paused = !player.playWhenReady }) {
                Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (paused) "Putar" else "Jeda")
            }
            FilledTonalIconButton({ zap(1) }) { Icon(Icons.Rounded.SkipNext, "Saluran berikutnya") }
        }
    }
}

internal fun nowOn(epg: Epg?, e: LocalTv.Entry, now: Instant) = epg?.schedule(e.name, now)?.firstOrNull()?.takeIf { it.start <= now }

private val HM = DateTimeFormatter.ofPattern("HH.mm").withZone(ZoneId.systemDefault())

/** Channel list + guide shared by Beranda, TV Live and Kategori; loaded once per app start. */
object Channels {
    var entries by mutableStateOf<List<LocalTv.Entry>?>(null); private set
    var epg by mutableStateOf<Epg?>(null); private set
    var failed by mutableStateOf(false); private set
    private var loading = false

    suspend fun load(c: android.content.Context) {
        if (entries != null || loading) return
        loading = true
        withContext(Dispatchers.IO) {
            val setup = c.getSharedPreferences("setup", 0)
            val province = setup.getString("region", null) ?: setup.getString("city", "").orEmpty().takeIf { it.isNotBlank() }
                ?.let { Web.geocode(it)?.region }?.also { setup.edit().putString("region", it).apply() }
            SourceHealth.init(java.io.File(c.cacheDir, "source-health.tsv"))
            runCatching { LocalTv.arrange(Sources.loadAll(), province) }.fold({ entries = it; SourceHealth.probeAll(SourceHealth.streamsOf(it)) }, { failed = true })
            epg = runCatching { Epg.load(Sources.EPG) }.getOrNull()
        }
        loading = false
    }

    /** Recently watched (newest first, max 10), by stream URL. */
    fun recent(c: android.content.Context): List<LocalTv.Entry> {
        val urls = c.getSharedPreferences("live", 0).getString("recent", "").orEmpty().split('\n').filter { it.isNotBlank() }
        val all = entries ?: return emptyList()
        return urls.mapNotNull { u -> all.firstOrNull { it.channel.url == u } }
    }

    fun remember(c: android.content.Context, e: LocalTv.Entry) {
        val p = c.getSharedPreferences("live", 0)
        val urls = listOf(e.channel.url) + p.getString("recent", "").orEmpty().split('\n').filter { it.isNotBlank() && it != e.channel.url }
        p.edit().putString("recent", urls.take(10).joinToString("\n")).apply()
    }
}

/** Cross-tab requests (Beranda / Kategori → TV Live). */
object Nav {
    var tab by mutableIntStateOf(0)
    var liveCategory by mutableStateOf<LocalTv.Category?>(null)
    var playUrl by mutableStateOf<String?>(null)
    /** Live TV player is full screen: the tab bar hides. */
    var full by mutableStateOf(false)
    fun play(e: LocalTv.Entry) { playUrl = e.channel.url; tab = 1 }
    fun category(k: LocalTv.Category?) { liveCategory = k; tab = 1 }
}

/** The TV launcher's built-in Live TV (its pseudo package in the app list). */
const val TV_LIVE = "tv.clean.home.livetv"

@Composable
private fun ChannelLogo(e: LocalTv.Entry, modifier: Modifier) = Box(
    modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.92f)), contentAlignment = Alignment.Center,
) {
    val img = rememberNetImage(e.channel.logo)
    if (img != null) androidx.compose.foundation.Image(img, e.name, Modifier.padding(6.dp).fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
    else Text(e.name.take(3).uppercase(), color = Color(0xFF111114), fontSize = 13.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun Pill(text: String, color: Color, filled: Boolean = false) = Text(text,
    Modifier.clip(RoundedCornerShape(6.dp)).background(if (filled) color else color.copy(alpha = 0.18f)).padding(horizontal = 6.dp, vertical = 1.dp),
    color = if (filled) Color.White else color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)

private fun progress(p: Epg.Programme, now: Instant) =
    ((now.epochSecond - p.start.epochSecond).toFloat() / (p.stop.epochSecond - p.start.epochSecond).coerceAtLeast(1)).coerceIn(0f, 1f)


/** While a channel loads: its initials in a white tile inside a rotating cyan ring with a soft pulse, name and status. */
@Composable
private fun ChannelLoader(name: String, status: String, modifier: Modifier = Modifier) {
    val t by androidx.compose.animation.core.rememberInfiniteTransition(label = "load").animateFloat(0f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1200, easing = androidx.compose.animation.core.LinearEasing)), label = "spin")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val pulse = (0.5 + 0.5 * kotlin.math.sin(t * 2 * Math.PI)).toFloat()
                val inset = 8.dp.toPx(); val grow = 4.dp.toPx() * pulse
                drawCircle(Tahoe.Accent.copy(alpha = 0.15f + 0.2f * pulse), radius = size.minDimension / 2 - inset + grow, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                rotate(t * 360f) {
                    drawArc(Brush.sweepGradient(listOf(Color.Transparent, Tahoe.Accent, Color(tech.kousozo.tahoetv.core.Brand.CYAN_LIGHT))),
                        0f, 300f, false, topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                        size = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
            }
            Box(Modifier.size(64.dp, 44.dp).background(Color.White, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Text(name.take(3).uppercase(), color = Color(0xFF111114), fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
            }
        }
        Text(name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(status, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}
