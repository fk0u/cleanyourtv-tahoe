package tech.kousozo.tahoetv

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.drawscope.rotate
import tech.kousozo.tahoetv.core.DiagLog
import tech.kousozo.tahoetv.core.SourceHealth
import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.common.C
import androidx.media3.common.Timeline
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
import tech.kousozo.tahoetv.core.Search
import tech.kousozo.tahoetv.core.Web

/** TV Live on the phone: same free channels and guide as the TV and desktop. The player is the app-wide [Playback]
 *  (it keeps playing as a mini-player on other tabs). Redesign (#60): big title, glass search, chips, channel rows with
 *  the show on now; the player's controls hide after 3 s and come back with a tap. */
@Composable
fun LiveScreen() {
    val c = LocalContext.current
    val entries = Channels.entries
    val epg = Channels.epg
    val failed = Channels.failed
    var cat by remember { mutableStateOf(Nav.liveCategory) }
    var query by remember { mutableStateOf("") }
    val playing = Playback.entry
    var full by Nav::full
    LaunchedEffect(Unit) { Channels.load(c) }
    // requests from Beranda / Kategori / a channel page: open a category, or start a channel
    LaunchedEffect(Nav.liveCategory) { cat = Nav.liveCategory }
    LaunchedEffect(Nav.playUrl, entries) {
        val u = Nav.playUrl ?: return@LaunchedEffect
        entries?.firstOrNull { it.channel.url == u }?.let { Playback.play(c, it); Nav.playUrl = null }
    }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = Instant.now() } }

    // full screen = landscape, player only, system bars and tab bar hidden; Back returns.
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
    val searchMinute = if (query.isBlank()) 0L else now.epochSecond / 60
    val hits = remember(list, epg, query, searchMinute) { list?.let { Search.run(it, epg, query, now) } }
    val favSet = Channels.favs.toSet()
    val shown = remember(hits, cat, Nav.favOnly, favSet) {
        hits?.filter { (cat == null || LocalTv.inCategory(it.entry, cat!!)) && (!Nav.favOnly || it.entry.channel.url in favSet || it.entry.channel.channelId in favSet) }
    }
    val fullOrPip = full || Nav.inPip
    Column(Modifier.fillMaxSize().then(if (fullOrPip) Modifier.background(Color.Black) else Modifier.systemBarsPadding())) {
        playing?.let { e ->
            Box(Modifier.fillMaxWidth().then(if (fullOrPip) Modifier.fillMaxHeight() else Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(22.dp)).aspectRatio(16f / 9f)).background(Color.Black)
                .semantics { contentDescription = "Siaran ${e.name}" }) {
                PlayerStage(e, fullOrPip, onFull = { full = !full }) { dir -> Playback.zap(c, shown?.map { it.entry } ?: list, dir) }
            }
        }
        if (fullOrPip) return@Column
        val lang = PhoneRegion.language
        val activeDev = remember(Pairing.tick) { Sync.devices(c).active() }
        val tvHost = activeDev?.host.orEmpty()
        val tvToken = activeDev?.token.orEmpty()
        val tvLink = remember(tvHost, tvToken) {
            if (tvHost.isNotEmpty() && tvToken.isNotEmpty()) tech.kousozo.tahoetv.core.TvLink.shared(tvHost, tvToken) else null
        }
        var tvState by remember(tvLink) { mutableStateOf(tvLink?.lastTvState) }
        DisposableEffect(tvLink) {
            if (tvLink == null) return@DisposableEffect onDispose {}
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            tvState = tvLink.lastTvState
            tvLink.onTvState = { s -> main.post { tvState = s } }
            onDispose { tvLink.onTvState = {} }
        }
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (playing == null) {
                Text(tech.kousozo.tahoetv.core.I18n.get(lang, "live.kicker"), color = Tahoe.Faint, fontSize = 12.sp, fontFamily = Mono, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 12.dp))
                Text(tech.kousozo.tahoetv.core.I18n.get(lang, "nav.live"), fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
            }
            playing?.let { e -> NowPlayingInfo(e, epg, now) }
            if (tvLink != null && tvState != null) {
                TvConnectBar(tvState!!, tvLink, lang)
            }
            if (list == null) {
                if (failed) Note(tech.kousozo.tahoetv.core.I18n.get(lang, "live.load_failed_retry"), Icons.Rounded.ErrorOutline)
                else LinearProgressIndicator(Modifier.fillMaxWidth(), color = Tahoe.Text, trackColor = Tahoe.Glass)
                return@Column
            }
            // glass search pill
            Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(Tahoe.Glass).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = Tahoe.Dim, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(tech.kousozo.tahoetv.core.I18n.get(lang, "home.search_hint"), color = Tahoe.Faint, fontSize = 15.sp)
                    androidx.compose.foundation.text.BasicTextField(query, { query = it }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = tech.kousozo.tahoetv.core.I18n.get(lang, "home.search_hint") },
                        textStyle = androidx.compose.ui.text.TextStyle(color = Tahoe.Text, fontSize = 15.sp, fontFamily = Outfit),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Tahoe.Text))
                }
                if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, tech.kousozo.tahoetv.core.I18n.get(lang, "live.clear_search"), tint = Tahoe.Dim) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(tech.kousozo.tahoetv.core.I18n.get(lang, "live.all"), cat == null && !Nav.favOnly) { cat = null; Nav.liveCategory = null; Nav.favOnly = false }
                if (Channels.favs.isNotEmpty()) Chip(tech.kousozo.tahoetv.core.I18n.get(lang, "live.favorites_heart"), Nav.favOnly) { Nav.favOnly = !Nav.favOnly }
                LocalTv.Category.entries.filter { k -> list.any { LocalTv.inCategory(it, k) } }.forEach { k ->
                    Chip(k.label(lang), k == cat) { cat = k; Nav.liveCategory = k }
                }
            }
        }
        shown?.let { hits ->
            val rows = hits
            LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), contentPadding = PaddingValues(top = 4.dp, bottom = TabInset)) {
                items(rows, key = { it.entry.channel.url }) { h -> ChannelRow(h.entry, h.programme, epg, now, on = h.entry == playing) }
            }
        }
    }
}

/** Launches system share sheet with a Tahoe TV channel link (#63). */
fun shareChannel(c: android.content.Context, e: LocalTv.Entry) {
    val text = "${e.name} — Tahoe TV\nhttps://tahoe.kousozo.tech/?channel=${Uri.encode(e.channel.channelId)}"
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    runCatching {
        c.startActivity(android.content.Intent.createChooser(send, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "player.share")))
    }
}

/** Under the player: name, show on now, format badge, ♥ and the channel page. */
@Composable
private fun NowPlayingInfo(e: LocalTv.Entry, epg: Epg?, now: Instant) {
    val c = LocalContext.current
    val show = nowOn(epg, e, now)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(e.name, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(show?.let { "${it.title} · ${HM.format(it.start)}–${HM.format(it.stop)}" } ?: e.category.label,
                    Modifier.weight(1f, fill = false), color = Tahoe.Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Playback.format?.let { Spacer(Modifier.width(8.dp)); FormatBadge(it) }
            }
        }
        Spacer(Modifier.width(10.dp))
        val fav = e.channel.url in Channels.favs
        RoundGlass(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (fav) "Hapus dari favorit" else "Tambah ke favorit",
            tint = if (fav) Tahoe.Bad else Tahoe.Text) { Channels.toggleFav(c, e) }
        Spacer(Modifier.width(8.dp))
        RoundGlass(Icons.Rounded.Share, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "player.share")) { shareChannel(c, e) }
        Spacer(Modifier.width(8.dp))
        RoundGlass(Icons.Rounded.Info, "Halaman saluran") { Nav.detail = e }
        val activeDev = remember(Pairing.tick) { Sync.devices(c).active() }
        if (activeDev != null) {
            Spacer(Modifier.width(8.dp))
            RoundGlass(Icons.Rounded.Cast, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "connect.play_on_tv")) {
                tech.kousozo.tahoetv.core.TvLink.shared(activeDev.host, activeDev.token)
                    .sessionLoad(e.channel.channelId, e.channel.url, e.name)
            }
        }
    }
}

@Composable
private fun TvConnectBar(state: org.json.JSONObject, link: tech.kousozo.tahoetv.core.TvLink, lang: String) {
    val c = LocalContext.current
    val channel = state.optString("channel", "")
    if (channel.isEmpty()) return
    val channelId = state.optString("channelId", "")
    val paused = state.optBoolean("paused", !state.optBoolean("playing", true))
    val number = state.optInt("number", 0)
    val label = tech.kousozo.tahoetv.core.I18n.get(lang, if (paused) "connect.paused_on_tv" else "connect.playing_on_tv", channel)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Tahoe.Glass)
            .border(1.dp, Tahoe.Stroke, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Tv, null, tint = Tahoe.Text, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = Tahoe.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (number > 0) Text("#$number", color = Tahoe.Dim, fontSize = 12.sp, fontFamily = Mono)
        }
        IconButton({
            val match = Channels.entries?.firstOrNull {
                (channelId.isNotEmpty() && it.channel.channelId == channelId) ||
                    it.name.equals(channel, ignoreCase = true)
            }
            if (match != null) {
                Playback.play(c, match)
                link.sessionStop()
            }
        }, Modifier.size(34.dp)) {
            Icon(Icons.Rounded.Smartphone, tech.kousozo.tahoetv.core.I18n.get(lang, "connect.continue_on_phone"), tint = Tahoe.Accent, modifier = Modifier.size(18.dp))
        }
        IconButton({ if (paused) link.sessionPlay() else link.sessionPause() }, Modifier.size(34.dp)) {
            Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (paused) "Putar" else "Jeda", tint = Tahoe.Text, modifier = Modifier.size(20.dp))
        }
        IconButton({ link.key("ch_down") }, Modifier.size(34.dp)) {
            Icon(Icons.Rounded.SkipPrevious, "Saluran sebelumnya", tint = Tahoe.Dim, modifier = Modifier.size(18.dp))
        }
        IconButton({ link.key("ch_up") }, Modifier.size(34.dp)) {
            Icon(Icons.Rounded.SkipNext, "Saluran berikutnya", tint = Tahoe.Dim, modifier = Modifier.size(18.dp))
        }
        IconButton({ link.sessionStop() }, Modifier.size(34.dp)) {
            Icon(Icons.Rounded.Stop, tech.kousozo.tahoetv.core.I18n.get(lang, "connect.stop_tv"), tint = Tahoe.Bad, modifier = Modifier.size(18.dp))
        }
    }
}

/** "1080p · H.264 · AAC" in a hairline box, like a music app's "AAC · 44,1 kHz". */
@Composable
fun FormatBadge(text: String) = Text(text,
    Modifier.border(1.dp, Tahoe.Faint, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
    color = Tahoe.Dim, fontSize = 10.sp, fontFamily = Mono)

@Composable
private fun ChannelRow(e: LocalTv.Entry, match: Epg.Programme?, epg: Epg?, now: Instant, on: Boolean) {
    val c = LocalContext.current
    val prog = match ?: nowOn(epg, e, now)
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(20.dp))
        .background(if (on) Color.White.copy(alpha = 0.12f) else Color.Transparent).clickable { Playback.play(c, e) }.padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ChannelLogo(e, Modifier.size(64.dp, 44.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(prog?.let { pr -> if (pr.start > now) "Pukul ${HM.format(pr.start)} · ${pr.title}" else pr.title } ?: e.category.label,
                color = if (match != null) Tahoe.Text else Tahoe.Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            prog?.takeIf { it.start <= now }?.let { pr ->
                LinearProgressIndicator({ progress(pr, now) }, Modifier.padding(top = 6.dp).fillMaxWidth(0.6f).height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = if (on) Tahoe.Text else Tahoe.Dim, trackColor = Tahoe.Stroke)
            }
        }
        if (e.channel.url in Channels.favs) Icon(Icons.Rounded.Favorite, "Favorit", tint = Tahoe.Bad, modifier = Modifier.size(16.dp))
        IconButton({ Nav.detail = e }) { Icon(Icons.Rounded.MoreHoriz, "Halaman ${e.name}", tint = Tahoe.Dim) }
    }
}

/** The video with its overlay: LIVE pill and name on top, big transport in the middle (⏪30 ⏯ ⏩30), channel up/down and
 *  full screen at the bottom. Controls fade out after 3 s of no touch and come back on tap; they stay while loading or
 *  on an error. */
@Composable
private fun PlayerStage(e: LocalTv.Entry, full: Boolean, onFull: () -> Unit, zap: (Int) -> Unit) {
    val c = LocalContext.current
    val player = remember { Playback.player(c) }
    val am = remember { c.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager }
    var touchExploration by remember { mutableStateOf(am?.isTouchExplorationEnabled == true) }
    DisposableEffect(am) {
        if (am == null) return@DisposableEffect onDispose {}
        val listener = android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener { enabled ->
            touchExploration = enabled
        }
        am.addTouchExplorationStateChangeListener(listener)
        onDispose { am.removeTouchExplorationStateChangeListener(listener) }
    }
    var shownAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var settings by remember { mutableStateOf(false) }
    var zoomFill by remember { mutableStateOf(false) }
    if (settings) PlayerSettingsSheet { settings = false }
    var visible by remember { mutableStateOf(true) }
    val busy = Playback.message != null
    LaunchedEffect(shownAt, busy, touchExploration) {
        visible = true
        if (!busy && !touchExploration) {
            delay(3000)
            visible = false
        }
    }
    LaunchedEffect(full, visible) {
        val w = (c as? Activity)?.window ?: return@LaunchedEffect
        val ic = androidx.core.view.WindowCompat.getInsetsController(w, w.decorView)
        if (full) {
            if (visible) ic.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            else ic.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }
    fun poke() { shownAt = System.currentTimeMillis() }
    val tapMod = if (touchExploration) {
        Modifier.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { poke() }
    } else {
        Modifier.pointerInput(busy, zoomFill) {
            detectTapGestures(
                onTap = { if (visible && !busy) visible = false else poke() },
                onDoubleTap = { offset ->
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    when {
                        offset.x < w / 3f -> {
                            player.seekTo(maxOf(0L, player.currentPosition - 10_000L))
                            player.play()
                            poke()
                        }
                        offset.x > w * 2f / 3f -> {
                            seekForward(player, 10_000L)
                            poke()
                        }
                        else -> {
                            zoomFill = !zoomFill
                            poke()
                        }
                    }
                },
            )
        }
    }
    Box(Modifier.fillMaxSize().then(tapMod)) {
        AndroidView({
            PlayerView(it).apply {
                this.player = player
                useController = false
                resizeMode = if (zoomFill) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                layoutParams = ViewGroup.LayoutParams(-1, -1)
            }
        }, Modifier.fillMaxSize(), update = {
            it.player = player
            it.resizeMode = if (zoomFill) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        })
        val slate = Playback.slate
        if (slate != null) ChannelSlate(e, slate, full, zap)
        else Playback.message?.let {
            if (it.isNotEmpty()) Text(it, Modifier.align(Alignment.Center).semantics { liveRegion = LiveRegionMode.Polite }
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(12.dp), color = Color.White, fontSize = 14.sp)
            else ChannelLoader(e.name, Modifier.fillMaxSize().semantics { liveRegion = LiveRegionMode.Polite; contentDescription = "Memuat ${e.name}" })
        }
        // the slate brings its own actions: the player controls would sit on top of it
        androidx.compose.animation.AnimatedVisibility(visible && slate == null && !Nav.inPip, Modifier.fillMaxSize(),
            enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
            Box(Modifier.fillMaxSize().background(if (busy) Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent, Color.Black.copy(alpha = 0.45f)))
                else Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.15f), Color.Black.copy(alpha = 0.6f))))) {
                Row(Modifier.align(Alignment.TopStart).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill("LIVE", Tahoe.Bad, filled = true); Spacer(Modifier.width(8.dp))
                    Text(e.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                val lang = PhoneRegion.language
                Row(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                    IconButton({ poke(); zoomFill = !zoomFill }) {
                        Icon(Icons.Rounded.AspectRatio, tech.kousozo.tahoetv.core.I18n.get(lang, if (zoomFill) "player.zoom_fit" else "player.zoom_fill"), tint = Color.White)
                    }
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        IconButton({
                            poke()
                            (c as? Activity)?.let { act ->
                                runCatching {
                                    act.enterPictureInPictureMode(
                                        android.app.PictureInPictureParams.Builder()
                                            .setAspectRatio(android.util.Rational(16, 9))
                                            .build()
                                    )
                                }
                            }
                        }) {
                            Icon(Icons.Rounded.PictureInPictureAlt, tech.kousozo.tahoetv.core.I18n.get(lang, "player.pip"), tint = Color.White)
                        }
                    }
                    IconButton({ poke(); settings = true }) { Icon(Icons.Rounded.Settings, "Setelan pemutar: kualitas dan latensi", tint = Color.White) }
                    IconButton({ poke(); onFull() }) {
                        Icon(if (full) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, if (full) "Keluar dari layar penuh" else "Layar penuh", tint = Color.White)
                    }
                }
                // big transport only once it plays: while loading or on an error the middle belongs to the loader / message
                if (!busy) Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (full) 48.dp else 28.dp)) {
                    IconButton({ poke(); player.seekTo(maxOf(0L, player.currentPosition - 30_000)); player.play() }, Modifier.size(52.dp)) {
                        Icon(Icons.Rounded.Replay30, "Mundur 30 detik", tint = Color.White, modifier = Modifier.size(34.dp)) }
                    IconButton({ poke(); Playback.toggle() }, Modifier.size(68.dp)) {
                        Icon(if (Playback.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (Playback.paused) "Putar" else "Jeda", tint = Color.White, modifier = Modifier.size(52.dp)) }
                    IconButton({ poke(); seekForward(player, 30_000) }, Modifier.size(52.dp)) {
                        Icon(Icons.Rounded.Forward30, "Maju 30 detik, atau kembali ke siaran langsung", tint = Color.White, modifier = Modifier.size(34.dp)) }
                }
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ poke(); zap(-1) }) { Icon(Icons.Rounded.SkipPrevious, "Saluran sebelumnya", tint = Color.White) }
                    Spacer(Modifier.weight(1f))
                    Playback.format?.let { Text(it, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, fontFamily = Mono) }
                    Spacer(Modifier.weight(1f))
                    IconButton({ poke(); zap(1) }) { Icon(Icons.Rounded.SkipNext, "Saluran berikutnya", tint = Color.White) }
                }
            }
        }
    }
}

/** True when the source that just became ready is a recording, not live: a finished HLS playlist (ENDLIST / VOD) or a live
 *  window whose position is more than 15 min in the past. Movies/VOD and non-HLS streams are never judged this way. */
internal fun isRecording(player: ExoPlayer, s: M3u.Channel?): Boolean {
    if (s == null || LocalTv.isVod(s) || player.currentManifest !is HlsManifest) return false
    val t = player.currentTimeline
    if (t.isEmpty) return false
    val w = t.getWindow(player.currentMediaItemIndex, Timeline.Window())
    if (!w.isLive) return true
    val off = player.currentLiveOffset
    return off != C.TIME_UNSET && off > 15 * 60_000L
}

/** ⏩ within the live window: forward by [ms], or back to live when that would pass the usual ~20 s cushion.
 *  Decided from the live offset (live streams often report no duration). Shared rule with the TV's seekRel. */
internal fun seekForward(player: ExoPlayer, ms: Long) {
    val off = player.currentLiveOffset
    when {
        player.isCurrentMediaItemLive && off != C.TIME_UNSET -> if (off - ms <= 28_000) player.seekToDefaultPosition() else player.seekTo(player.currentPosition + ms)
        player.isCurrentMediaItemLive -> player.seekToDefaultPosition()
        else -> player.seekTo(player.currentPosition + ms)
    }
    player.play()
}

internal fun nowOn(epg: Epg?, e: LocalTv.Entry, now: Instant) = epg?.schedule(e.name, now, e.channel.tvgId)?.firstOrNull()?.takeIf { it.start <= now }

val HM = DateTimeFormatter.ofPattern("HH.mm").withZone(ZoneId.systemDefault())

/** Channel list + guide shared by Beranda, TV Live and Kategori; loaded once per app start. */
object Channels {
    var entries by mutableStateOf<List<LocalTv.Entry>?>(null); private set
    var epg by mutableStateOf<Epg?>(null); private set
    var failed by mutableStateOf(false); private set
    private var loading = false
    /** Bumped on a country change: a load still running for the old country throws its result away. */
    private var generation = 0

    /** Country changed: forget the list; the next screen that shows loads the new one. */
    fun reload(@Suppress("UNUSED_PARAMETER") c: android.content.Context) { generation++; loading = false; entries = null; all = null; epg = null; failed = false }

    suspend fun load(c: android.content.Context) {
        if (entries != null || loading) return
        loading = true
        val mine = generation
        try { withContext(Dispatchers.IO) {
            val setup = c.getSharedPreferences("setup", 0)
            val viewer = PhoneRegion.viewer
            val country = PhoneRegion.code
            // the onboarding's region; the older setup's only before onboarding (Indonesia, no city chosen yet)
            val province = viewer.nearRegion ?: setup.getString("region", null).takeIf { !viewer.done } ?: setup.getString("city", "").orEmpty().takeIf { it.isNotBlank() }
                ?.let { Web.geocode(it)?.region }?.also { setup.edit().putString("region", it).apply() }
            SourceHealth.init(java.io.File(c.cacheDir, "source-health.tsv"), java.io.File(c.filesDir, "source-banned.txt")) // bans: durable
            val list = runCatching { LocalTv.arrange(Sources.loadCountries(null, viewer.countries), province, viewer.countries) }.getOrNull()
            val channelFilter = list?.flatMap { listOfNotNull(it.name, it.channel.tvgId) }?.toSet()
            val guide = runCatching { Epg.load(viewer.countries.flatMap { tech.kousozo.tahoetv.core.Region.guides(it) }.distinct(), channelFilter) }.getOrNull()
            if (mine != generation) return@withContext // the country changed meanwhile
            if (list.isNullOrEmpty()) failed = true // no playlist for this country, or no internet: show the note, not a blank list
            else { all = list; entries = PhoneRegion.viewer.arrange(list); SourceHealth.probeAll(SourceHealth.streamsOf(list)) }
            epg = guide
        } } finally { if (mine == generation) loading = false } // also when the screen leaves mid-load: the next one retries
    }

    /** Recently watched (newest first, max 10), by stream URL. */
    fun recent(c: android.content.Context): List<LocalTv.Entry> {
        val urls = c.getSharedPreferences("live", 0).getString("recent", "").orEmpty().split('\n').filter { it.isNotBlank() }
        val all = entries ?: return emptyList()
        return urls.mapNotNull { u -> all.firstOrNull { it.channel.url == u } }
    }

    /** Every loaded channel, before the viewer's categories and order ([entries] is what the screens show). */
    private var all: List<LocalTv.Entry>? = null
    /** Categories or favourite order changed: re-apply them without reloading. */
    fun rearrange() { if (entries != null) all?.let { entries = PhoneRegion.viewer.arrange(it) } }

    /** Favourite channels (stream URLs), kept on this phone. */
    var favs by mutableStateOf<Set<String>>(emptySet()); private set
    fun setFavs(c: android.content.Context, list: List<String>) {
        favs = list.toSet(); c.getSharedPreferences("live", 0).edit().putStringSet("favs", favs).apply()
    }
    fun loadFavs(c: android.content.Context) { favs = c.getSharedPreferences("live", 0).getStringSet("favs", emptySet()).orEmpty().toSet() }
    fun toggleFav(c: android.content.Context, e: LocalTv.Entry) {
        favs = if (e.channel.url in favs) favs - e.channel.url else favs + e.channel.url
        c.getSharedPreferences("live", 0).edit().putStringSet("favs", favs).apply()
        PhoneRegion.favoritesChanged(c, favs)
        rearrange() // the new favourite moves to the front now
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
    /** Android Picture-in-Picture window active (#63). */
    var inPip by mutableStateOf(false)
    /** Channel page open over the tabs, or null. */
    var detail by mutableStateOf<LocalTv.Entry?>(null)
    /** The channel in Beranda's hero (tints the app when nothing plays). */
    var hero by mutableStateOf<LocalTv.Entry?>(null)
    /** TV Live list shows favourites only. */
    var favOnly by mutableStateOf(false)
    fun play(e: LocalTv.Entry) { playUrl = e.channel.url; tab = 1; detail = null }
    fun category(k: LocalTv.Category?, favourites: Boolean = false) { liveCategory = k; favOnly = favourites; tab = 1; detail = null }
}

/** The TV launcher's built-in Live TV (its pseudo package in the app list). */
const val TV_LIVE = "tv.clean.home.livetv"

@Composable
fun ChannelLogo(e: LocalTv.Entry, modifier: Modifier) = Box(
    modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.92f)), contentAlignment = Alignment.Center,
) {
    val img = rememberNetImage(e.channel.logo)
    if (img != null) androidx.compose.foundation.Image(img, e.name, Modifier.padding(6.dp).fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
    else Text(e.name.take(3).uppercase(), color = Color(0xFF111114), fontSize = 13.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun Pill(text: String, color: Color, filled: Boolean = false) = Text(text,
    Modifier.clip(RoundedCornerShape(6.dp)).background(if (filled) color else color.copy(alpha = 0.18f)).padding(horizontal = 6.dp, vertical = 1.dp),
    color = if (filled) Color.White else color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)

fun progress(p: Epg.Programme, now: Instant) =
    ((now.epochSecond - p.start.epochSecond).toFloat() / (p.stop.epochSecond - p.start.epochSecond).coerceAtLeast(1)).coerceIn(0f, 1f)


/** While a channel loads, Apple TV / Netflix style: a dim scrim over the picture, the channel's initials in a white tile, its
 *  name, and a tvOS spinner (12 ticks fading around a circle). No text about sources or retries. */
@Composable
private fun ChannelLoader(name: String, modifier: Modifier = Modifier) {
    val t by androidx.compose.animation.core.rememberInfiniteTransition(label = "load").animateFloat(0f, 12f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1000, easing = androidx.compose.animation.core.LinearEasing)), label = "spin")
    Column(modifier.background(Color(0xCC07080D)), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(80.dp, 54.dp).background(Color.White, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Text(name.take(3).uppercase(), color = Color(0xFF111114), fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
        }
        Spacer(Modifier.height(10.dp))
        Text(name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Spacer(Modifier.height(14.dp))
        androidx.compose.foundation.Canvas(Modifier.size(34.dp)) {
            val phase = t.toInt() % 12
            val r = size.minDimension / 2
            for (i in 0 until 12) {
                val behind = ((phase - i) % 12 + 12) % 12
                rotate(i * 30f) {
                    drawLine(Color.White.copy(alpha = 0.2f + 0.8f * (1f - behind / 11f)),
                        androidx.compose.ui.geometry.Offset(center.x, center.y - r * 0.95f), androidx.compose.ui.geometry.Offset(center.x, center.y - r * 0.6f),
                        strokeWidth = r * 0.14f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
        }
    }
}


/** Setelan pemutar: highest video quality and distance from live, shared with the TV and desktop options. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSettingsSheet(onDismiss: () -> Unit) {
    val c = LocalContext.current
    val lang = PhoneRegion.language
    ModalBottomSheet(onDismiss, containerColor = Tahoe.Surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tech.kousozo.tahoetv.core.I18n.get(lang, "player.settings"), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text(tech.kousozo.tahoetv.core.I18n.get(lang, "player.quality"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tech.kousozo.tahoetv.core.PlayPrefs.Quality.entries.forEach { q -> Chip(q.label(lang), Playback.quality == q) { Playback.setQuality(c, q) } }
            }
            Text(if (Playback.quality.maxHeight > 0) tech.kousozo.tahoetv.core.I18n.get(lang, "player.quality_cap", Playback.quality.label(lang))
                else tech.kousozo.tahoetv.core.I18n.get(lang, "player.quality_auto_note"), fontSize = 13.sp, color = Tahoe.Dim)
            Text(tech.kousozo.tahoetv.core.I18n.get(lang, "player.latency"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tech.kousozo.tahoetv.core.PlayPrefs.Latency.entries.forEach { l -> Chip(l.label(lang), Playback.latency == l) { Playback.setLatency(c, l) } }
            }
            Text(if (Playback.latency == tech.kousozo.tahoetv.core.PlayPrefs.Latency.Low) tech.kousozo.tahoetv.core.I18n.get(lang, "player.latency_low_warn", Playback.latency.note(lang))
                else tech.kousozo.tahoetv.core.I18n.get(lang, "player.latency_note_dot", Playback.latency.note(lang)),
                fontSize = 13.sp, color = Tahoe.Dim)
        }
    }
}

/** In place of a channel that can't play (#76): the Tahoe slate with the apology in Indonesian, English and Chinese
 *  (the viewer's language first), the reason, and Try again / Next channel / Report. The player keeps retrying.
 *  In the small player the other two languages take turns on one line, so everything fits. */
@Composable
private fun ChannelSlate(e: LocalTv.Entry, reason: tech.kousozo.tahoetv.core.Slate.Reason, full: Boolean, zap: (Int) -> Unit) {
    val c = LocalContext.current
    val lang = PhoneRegion.language
    val order = tech.kousozo.tahoetv.core.Slate.order(lang)
    val logo = rememberNetImage(e.channel.logo)
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(tech.kousozo.tahoetv.core.Brand.LAKE_DEEP), Color(tech.kousozo.tahoetv.core.Brand.NIGHT), Color(tech.kousozo.tahoetv.core.Brand.CANVAS))))
        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.align(Alignment.Center).padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (full) 10.dp else 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(if (full) 48.dp else 28.dp).clip(RoundedCornerShape(if (full) 10.dp else 6.dp)).background(Color.White.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                    if (logo != null) androidx.compose.foundation.Image(logo, null, Modifier.fillMaxSize().padding(3.dp), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                    else BrandMark(if (full) 28.dp else 16.dp)
                }
                Text(e.name, color = Color.White, fontSize = if (full) 20.sp else 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(tech.kousozo.tahoetv.core.Slate.message(order[0]), color = Color.White, fontSize = if (full) 16.sp else 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = if (full) 3 else 2, overflow = TextOverflow.Ellipsis)
            val others = order.drop(1).joinToString("  ·  ") { tech.kousozo.tahoetv.core.Slate.message(it) }
            Text(others, color = Color.White.copy(alpha = 0.62f), fontSize = if (full) 13.sp else 10.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = if (full) 2 else 1, overflow = TextOverflow.Ellipsis)
            val whenText = if (Playback.retryIn > 0) tech.kousozo.tahoetv.core.Slate.label(lang, "trying") else tech.kousozo.tahoetv.core.Slate.label(lang, "waiting")
            Text(tech.kousozo.tahoetv.core.Slate.reason(order.first(), reason) + "  ·  " + whenText,
                color = Color.White.copy(alpha = 0.62f), fontSize = if (full) 13.sp else 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = if (full) 6.dp else 2.dp)) {
                SlateButton(tech.kousozo.tahoetv.core.Slate.label(lang, "retry"), Icons.Rounded.Refresh, full, primary = true) { Playback.retry(c) }
                SlateButton(tech.kousozo.tahoetv.core.Slate.label(lang, "next"), Icons.Rounded.SkipNext, full) { zap(1) }
                if (full) SlateButton(tech.kousozo.tahoetv.core.Slate.label(lang, "report"), Icons.Rounded.Flag, full) {
                    runCatching { c.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(tech.kousozo.tahoetv.core.Slate.reportUrl(e.name, lang)))) }
                }
            }
        }
    }
}

@Composable
private fun SlateButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, big: Boolean, primary: Boolean = false, onClick: () -> Unit) = Row(
    Modifier.defaultMinSize(minHeight = 48.dp, minWidth = 48.dp)
        .clip(RoundedCornerShape(50)).background(if (primary) Color.White else Color.White.copy(alpha = 0.16f)).clickable(onClick = onClick)
        .padding(horizontal = if (big) 16.dp else 12.dp, vertical = if (big) 10.dp else 6.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
) {
    val fg = if (primary) Color(0xFF111114) else Color.White
    Icon(icon, null, tint = fg, modifier = Modifier.size(if (big) 18.dp else 14.dp))
    Text(text, color = fg, fontSize = if (big) 14.sp else 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
}
