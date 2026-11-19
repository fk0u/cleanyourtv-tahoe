package tech.kousozo.tahoetv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.awaitFirstDown
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
    var orientationReady by remember { mutableStateOf(false) }
    LaunchedEffect(landscape) { if (orientationReady) { if (playing != null) full = landscape } else orientationReady = true }
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
        if (!Channels.debugOffline) playing?.let { e ->
            Box(
                Modifier.fillMaxWidth().then(
                    if (fullOrPip) Modifier.fillMaxHeight()
                    else Modifier.height(244.dp),
                ).background(Color.Black)
                    .semantics { contentDescription = tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "live.stream_aria", e.name) },
            ) {
                KeepLtr { PlayerStage(e, fullOrPip, onFull = { full = !full }) { dir -> Playback.zap(c, shown?.map { it.entry } ?: list, dir) } }
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
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(if (playing == null || Channels.debugOffline) 20.dp else 12.dp)) {
            if (playing == null || Channels.debugOffline) {
                // Figma "Mobile / Live TV": Title 32 SemiBold
                Text(tech.kousozo.tahoetv.core.I18n.get(lang, "nav.live_short"), fontSize = 32.sp, lineHeight = 37.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.64).sp, modifier = Modifier.padding(top = 16.dp).a11yHeading())
            } else {
                NowPlayingInfo(playing, epg, now)
            }
            if (tvLink != null && tvState != null && !Channels.debugOffline) {
                TvConnectBar(tvState!!, tvLink, lang)
            }
            if (list == null || Channels.debugOffline) {
                if (failed || Channels.debugOffline) OfflineState()
                else LinearProgressIndicator(Modifier.fillMaxWidth(), color = Tahoe.Text, trackColor = Tahoe.Glass)
                return@Column
            }
            // Figma "Search": surface field with a hairline, radius 16, 20 dp icon, Body 16 placeholder
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(16.dp)).background(Tahoe.Surface)
                .border(if (ThemePrefs.highContrast) 2.dp else 1.dp, Tahoe.Stroke, RoundedCornerShape(16.dp)).padding(start = 16.dp, end = if (query.isNotEmpty()) 4.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = Tahoe.Dim, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(tech.kousozo.tahoetv.core.I18n.get(lang, "home.search_hint"), color = Tahoe.Faint, fontSize = 16.sp, lineHeight = 23.sp)
                    androidx.compose.foundation.text.BasicTextField(query, { query = it }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = tech.kousozo.tahoetv.core.I18n.get(lang, "home.search_hint") },
                        textStyle = androidx.compose.ui.text.TextStyle(color = Tahoe.Text, fontSize = 16.sp, fontFamily = Outfit),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Tahoe.Text))
                }
                if (query.isNotEmpty()) IconButton({ query = "" }, Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, tech.kousozo.tahoetv.core.I18n.get(lang, "live.clear_search"), tint = Tahoe.Dim) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(tech.kousozo.tahoetv.core.I18n.get(lang, "live.all"), cat == null && !Nav.favOnly) { cat = null; Nav.liveCategory = null; Nav.favOnly = false }
                if (Channels.favs.isNotEmpty()) Chip(tech.kousozo.tahoetv.core.I18n.get(lang, "live.favorites_heart"), Nav.favOnly) { Nav.favOnly = !Nav.favOnly }
                LocalTv.Category.entries.filter { k -> list.any { LocalTv.inCategory(it, k) } }.forEach { k ->
                    Chip(k.label(lang), k == cat) { cat = k; Nav.liveCategory = k }
                }
            }
        }
        val sortRow: @Composable () -> Unit = {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tech.kousozo.tahoetv.core.I18n.get(lang, "sort.label"), color = Tahoe.Dim, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                val activeSort = PhoneRegion.viewer.sort
                LocalTv.SortMode.entries.forEach { m -> Chip(m.label(lang), m == activeSort) { PhoneRegion.setSortMode(c, m) } }
            }
        }
        // browsing (no search, no filter, nothing playing): Figma "Pilihan untukmu" posters and the "Kategori" grid first
        val browsing = query.isBlank() && cat == null && !Nav.favOnly && playing == null && list != null
        if (!Channels.debugOffline && list != null) shown?.let { hits ->
            val rows = hits
            val wide = LocalConfiguration.current.screenWidthDp >= 600
            if (wide) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(320.dp),
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = TabInset),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    gridItems(rows, key = { it.entry.channel.url }) { h -> ChannelRow(h.entry, h.programme, epg, now, on = h.entry == playing) }
                }
            } else {
                LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), contentPadding = PaddingValues(top = 12.dp, bottom = TabInset)) {
                    if (playing != null && query.isBlank() && cat == null && !Nav.favOnly) {
                        val others = list.orEmpty().filter { it.channel.url != playing.channel.url }.take(4)
                        if (others.isNotEmpty()) {
                            item(key = "other-hdr") {
                                Text(
                                    tech.kousozo.tahoetv.core.I18n.get(lang, "player.other_channels_swipe").uppercase(),
                                    fontSize = 11.sp, color = Tahoe.Faint, fontWeight = FontWeight.SemiBold, letterSpacing = 1.32.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp).a11yHeading(),
                                )
                            }
                            items(others, key = { "other:${it.channel.url}" }) { ch ->
                                val chShow = nowOn(epg, ch, now)
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp))
                                        .clickable(role = Role.Button) { Playback.play(c, ch) }
                                        .semantics(mergeDescendants = true) {}
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    ChannelArt(ch, Modifier.size(110.dp, 62.dp).clip(RoundedCornerShape(14.dp)), logoScale = 0.8f)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(ch.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(chShow?.title ?: ch.category.label(lang), fontSize = 13.sp, color = Tahoe.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                    if (browsing) item(key = "browse") { BrowseSections(list.orEmpty(), now) { k -> cat = k; Nav.liveCategory = k } }
                    item(key = "sort") { sortRow() }
                    items(rows, key = { it.entry.channel.url }) { h -> ChannelRow(h.entry, h.programme, epg, now, on = h.entry == playing) }
                }
            }
        }
    }
}

/** Figma Live TV browse: "Pilihan untukmu" (150 × 220 posters) and "Kategori" (two-column 96 dp tiles). */
@Composable
private fun BrowseSections(list: List<LocalTv.Entry>, now: Instant, onCategory: (LocalTv.Category) -> Unit) {
    val lang = PhoneRegion.language
    val L = { k: String -> tech.kousozo.tahoetv.core.I18n.get(lang, k) }
    Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(L("live.for_you"), Modifier.weight(1f).a11yHeading(), fontSize = 20.sp, lineHeight = 25.sp, letterSpacing = (-0.2).sp, fontWeight = FontWeight.SemiBold)
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(list.take(10), key = { "p" + it.channel.url }) { e ->
                    Column(
                        Modifier.width(150.dp).clip(RoundedCornerShape(16.dp))
                            .clickable(role = Role.Button) { Nav.play(e) }
                            .semantics(mergeDescendants = true) {},
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(150.dp, 220.dp).clip(RoundedCornerShape(16.dp))) {
                            ChannelArt(e, Modifier.fillMaxSize(), logoScale = 0.7f)
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))))
                            Text(e.category.label(lang).uppercase(), Modifier.align(Alignment.BottomStart).padding(12.dp), color = Color.White, fontSize = 11.sp,
                                lineHeight = 13.sp, letterSpacing = 1.32.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                        Text(nowOn(Channels.epg, e, now)?.title ?: e.name, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        val cats = LocalTv.Category.entries.filter { k -> list.any { LocalTv.inCategory(it, k) } }
        if (cats.isNotEmpty()) Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(L("home.categories"), fontSize = 20.sp, lineHeight = 25.sp, letterSpacing = (-0.2).sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.a11yHeading())
            cats.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { k ->
                        val tint = categoryTint(k)
                        Box(Modifier.weight(1f).height(96.dp).clip(RoundedCornerShape(18.dp))
                            .background(Brush.linearGradient(listOf(tint, Color(tech.kousozo.tahoetv.core.Brand.LAKE_DEEP), Tahoe.Stage)))
                            .then(if (ThemePrefs.highContrast) Modifier.border(2.dp, Tahoe.Stroke, RoundedCornerShape(18.dp)) else Modifier)
                            .clickable(role = Role.Button) { onCategory(k) }
                            .semantics(mergeDescendants = true) {}) {
                            Icon(categoryIcon(k), null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(36.dp))
                            Text(k.label(lang), Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 14.dp, end = 14.dp), color = Color.White,
                                fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Text(L("home.all_channels"), Modifier.padding(horizontal = 8.dp).a11yHeading(), fontSize = 20.sp, lineHeight = 25.sp, letterSpacing = (-0.2).sp, fontWeight = FontWeight.SemiBold)
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

/** Under the player (Figma 23:400): LiveBadge + equalizer + channel · time, title, live EPG progress/next show, and action pills. */
@Composable
private fun NowPlayingInfo(e: LocalTv.Entry, epg: Epg?, now: Instant) {
    val c = LocalContext.current
    val lang = PhoneRegion.language
    val schedule = remember(epg, e, now.epochSecond / 30) { epg?.schedule(e.name, now, e.channel.tvgId).orEmpty() }
    val show = schedule.firstOrNull()?.takeIf { it.start <= now }
    val nextShow = schedule.firstOrNull { it.start > now }
    val fav = e.channel.url in Channels.favs
    val activeDev = remember(Pairing.tick) { Sync.devices(c).active() }
    val streamStateLabel = when {
        Playback.message == "" -> tech.kousozo.tahoetv.core.I18n.get(lang, "player.buffering")
        Playback.paused -> tech.kousozo.tahoetv.core.I18n.get(lang, "player.paused")
        else -> null
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiveBadge()
                    if (Playback.message == null) NowPlayingIndicator(playing = !Playback.paused)
                    Text(
                        listOfNotNull(e.name, streamStateLabel, show?.let { "${HM.format(it.start)}–${HM.format(it.stop)}" }).joinToString(" · "),
                        color = Tahoe.Dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Playback.format?.let { FormatBadge(it, lang = lang, onClick = { statsSheetOpen = true }) }
                }
                Text(
                    show?.title ?: e.name,
                    fontSize = 30.sp, lineHeight = 35.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.a11yHeading(),
                )
            }
            if (activeDev != null) {
                Spacer(Modifier.width(8.dp))
                RoundGlass(Icons.Rounded.Cast, tech.kousozo.tahoetv.core.I18n.get(lang, "connect.play_on_tv")) {
                    tech.kousozo.tahoetv.core.TvLink.shared(activeDev.host, activeDev.token)
                        .sessionLoad(e.channel.channelId, e.channel.url, e.name)
                }
            }
        }
        if (show != null || nextShow != null) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (show != null) {
                KeepLtr { ProgressBar(progress(show, now), Modifier.fillMaxWidth()) }
            }
            val left = show?.let { java.time.Duration.between(now, it.stop).toMinutes().coerceAtLeast(0) }
            val metaLine = listOfNotNull(
                left?.let { tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_min_left", it.toString()) },
                nextShow?.let { "${tech.kousozo.tahoetv.core.I18n.get(lang, "ch.up_next")}: ${it.title} (${HM.format(it.start)})" },
            ).joinToString(" · ")
            if (metaLine.isNotBlank()) Text(metaLine, color = Tahoe.Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoundGlass(
                if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, tech.kousozo.tahoetv.core.I18n.get(lang, "home.favorites"),
                tint = if (fav) Tahoe.Bad else Tahoe.Text,
                stateLabel = tech.kousozo.tahoetv.core.I18n.get(lang, if (fav) "a11y.favorited" else "a11y.not_favorited"),
            ) { Channels.toggleFav(c, e) }
            RoundGlass(Icons.Rounded.Info, tech.kousozo.tahoetv.core.I18n.get(lang, "live.channel_page_generic")) { Nav.detail = e }
            RoundGlass(Icons.Rounded.Share, tech.kousozo.tahoetv.core.I18n.get(lang, "player.share")) { shareChannel(c, e) }
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
        }, Modifier.size(48.dp)) {
            Icon(Icons.Rounded.Smartphone, tech.kousozo.tahoetv.core.I18n.get(lang, "connect.continue_on_phone"), tint = Tahoe.Accent, modifier = Modifier.size(18.dp))
        }
        IconButton({ if (paused) link.sessionPlay() else link.sessionPause() }, Modifier.size(48.dp)) {
            Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, tech.kousozo.tahoetv.core.I18n.get(lang, if (paused) "player.play" else "player.pause"), tint = Tahoe.Text, modifier = Modifier.size(20.dp))
        }
        IconButton({ link.key("ch_down") }, Modifier.size(48.dp)) {
            Icon(Icons.Rounded.SkipPrevious, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "player.prev_channel"), tint = Tahoe.Dim, modifier = Modifier.size(18.dp))
        }
        IconButton({ link.key("ch_up") }, Modifier.size(48.dp)) {
            Icon(Icons.Rounded.SkipNext, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "player.next_channel"), tint = Tahoe.Dim, modifier = Modifier.size(18.dp))
        }
        IconButton({ link.sessionStop() }, Modifier.size(48.dp)) {
            Icon(Icons.Rounded.Stop, tech.kousozo.tahoetv.core.I18n.get(lang, "connect.stop_tv"), tint = Tahoe.Bad, modifier = Modifier.size(18.dp))
        }
    }
}

private var statsSheetOpen by mutableStateOf(false)

/** "1080p · H.264 · AAC" in a hairline box, like a music app's "AAC · 44,1 kHz". */
@Composable
fun FormatBadge(text: String, lang: String = PhoneRegion.language, onClick: (() -> Unit)? = null) {
    val pill: @Composable () -> Unit = {
        Text(
            text,
            Modifier.clip(RoundedCornerShape(5.dp))
                .border(1.dp, Tahoe.Faint, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
            color = Tahoe.Dim, fontSize = 10.sp, fontFamily = Mono,
        )
    }
    if (onClick == null) pill()
    else Box(
        Modifier.heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = tech.kousozo.tahoetv.core.I18n.get(lang, "player.open_stats") + ": $text" },
        contentAlignment = Alignment.Center,
    ) { pill() }
}

@Composable
private fun ChannelRow(e: LocalTv.Entry, match: Epg.Programme?, epg: Epg?, now: Instant, on: Boolean) {
    val c = LocalContext.current
    val lang = PhoneRegion.language
    val prog = match ?: nowOn(epg, e, now)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(20.dp))
            .background(if (on) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .then(if (on && ThemePrefs.highContrast) Modifier.border(2.dp, Tahoe.Accent, RoundedCornerShape(20.dp)) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).heightIn(min = 56.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable(role = Role.Button) { Playback.play(c, e) }
                .semantics(mergeDescendants = true) {
                    if (on) stateDescription = tech.kousozo.tahoetv.core.I18n.get(lang, "live.badge_playing")
                }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelLogo(e, Modifier.size(64.dp, 44.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(e.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (on) NowPlayingIndicator(playing = !Playback.paused && Playback.message == null)
                }
                Text(prog?.let { pr -> if (pr.start > now) tech.kousozo.tahoetv.core.I18n.get(lang, "live.at_time_title", HM.format(pr.start), pr.title) else pr.title } ?: e.category.label(lang),
                    color = if (match != null) Tahoe.Text else Tahoe.Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                prog?.takeIf { it.start <= now }?.let { pr ->
                    KeepLtr {
                        LinearProgressIndicator({ progress(pr, now) }, Modifier.padding(top = 6.dp).fillMaxWidth(0.6f).height(3.dp).clip(RoundedCornerShape(2.dp)),
                            color = if (on) Tahoe.Text else Tahoe.Dim, trackColor = Tahoe.Stroke)
                    }
                }
            }
            if (e.channel.url in Channels.favs) {
                Icon(Icons.Rounded.Favorite, tech.kousozo.tahoetv.core.I18n.get(lang, "a11y.favorited"), tint = Tahoe.Bad, modifier = Modifier.size(16.dp))
            }
        }
        IconButton({ Nav.detail = e }, Modifier.size(48.dp)) { Icon(Icons.Rounded.MoreHoriz, tech.kousozo.tahoetv.core.I18n.get(lang, "live.channel_page", e.name), tint = Tahoe.Dim) }
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
    if (statsSheetOpen) PlayerStatsSheet { statsSheetOpen = false }
    var visible by remember { mutableStateOf(true) }
    val busy = Playback.message != null
    var liveTick by remember(e.channel.url) { mutableLongStateOf(0L) }
    LaunchedEffect(player, e.channel.url) {
        while (true) {
            delay(1000)
            liveTick++
        }
    }
    var scrubFrac by remember(e.channel.url) { mutableStateOf<Float?>(null) }
    var feedbackPill by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(feedbackPill) {
        if (feedbackPill != null) {
            delay(1500)
            feedbackPill = null
        }
    }
    LaunchedEffect(shownAt, busy, touchExploration, scrubFrac) {
        visible = true
        if (!busy && !touchExploration && scrubFrac == null) {
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
    val langPlayer = PhoneRegion.language
    val tapMod = if (touchExploration) {
        Modifier.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { poke() }
    } else {
        Modifier.pointerInput(busy, zoomFill, langPlayer) {
            detectTapGestures(
                onTap = { if (visible && !busy) visible = false else poke() },
                onDoubleTap = { offset ->
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    when {
                        offset.x < w / 3f -> {
                            player.seekTo(maxOf(0L, player.currentPosition - 10_000L))
                            player.play()
                            feedbackPill = "−10s"
                            poke()
                        }
                        offset.x > w * 2f / 3f -> {
                            seekForward(player, 10_000L)
                            feedbackPill = "+10s"
                            poke()
                        }
                        else -> {
                            zoomFill = !zoomFill
                            feedbackPill = tech.kousozo.tahoetv.core.I18n.get(langPlayer, if (zoomFill) "player.zoom_fill" else "player.zoom_fit")
                            poke()
                        }
                    }
                },
            )
        }
    }
    // swipe left/right = next/previous channel (away from the system back-gesture edges); pinch = fit ↔ zoom (#41)
    var swipeHint by remember { mutableStateOf(0) }
    LaunchedEffect(swipeHint) { if (swipeHint != 0) { delay(900); swipeHint = 0 } }
    val gestureMod = if (touchExploration) Modifier else Modifier.pointerInput(busy, langPlayer) {
        val edge = 32.dp.toPx(); val swipeMin = 72.dp.toPx()
        awaitPointerEventScope { while (true) {
            val down = awaitFirstDown(requireUnconsumed = false)
            val fromEdge = down.position.x < edge || down.position.x > size.width - edge
            var dx = 0f; var dy = 0f; var scale = 1f; var multi = false; var childConsumed = false
            while (true) {
                val ev = awaitPointerEvent()
                val pressed = ev.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (ev.changes.any { it.isConsumed }) {
                    childConsumed = true
                    break
                }
                if (pressed.size >= 2) {
                    multi = true
                    val a = pressed[0]; val b = pressed[1]
                    val now = (a.position - b.position).getDistance(); val prev = (a.previousPosition - b.previousPosition).getDistance()
                    if (prev > 0f) scale *= now / prev
                    ev.changes.forEach { it.consume() }
                } else if (!multi) {
                    val ch = pressed[0]; dx += ch.position.x - ch.previousPosition.x; dy += ch.position.y - ch.previousPosition.y
                    if (kotlin.math.abs(dx) > swipeMin / 3 && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) ch.consume()
                }
            }
            if (!childConsumed) when {
                multi && scale > 1.15f -> {
                    zoomFill = true
                    feedbackPill = tech.kousozo.tahoetv.core.I18n.get(langPlayer, "player.zoom_fill")
                    poke()
                }
                multi && scale < 0.87f -> {
                    zoomFill = false
                    feedbackPill = tech.kousozo.tahoetv.core.I18n.get(langPlayer, "player.zoom_fit")
                    poke()
                }
                !multi && !fromEdge && kotlin.math.abs(dx) > swipeMin && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f -> {
                    val step = if (dx < 0) 1 else -1
                    swipeHint = step; zap(step)
                }
            }
        } }
    }
    Box(Modifier.fillMaxSize().then(gestureMod).then(tapMod)) {
        val lang0 = PhoneRegion.language
        val nowInstant = remember(liveTick) { Instant.now() }
        val showNow = remember(Channels.epg, e, nowInstant.epochSecond / 30) { nowOn(Channels.epg, e, nowInstant) }
        val durMs = remember(liveTick, e.channel.url) { player.duration }
        val posMs = remember(liveTick, e.channel.url) { player.currentPosition }
        val liveOffMs = remember(liveTick, e.channel.url) { player.currentLiveOffset }
        val isLiveStream = remember(liveTick, e.channel.url) { player.isCurrentMediaItemLive }
        val isSeekable = player.isCurrentMediaItemSeekable && durMs > 10_000L
        val atLiveEdge = tech.kousozo.tahoetv.core.LiveSeek.isAtLiveEdge(isLiveStream, liveOffMs)
        val allCh = Channels.entries.orEmpty()
        val idx = allCh.indexOfFirst { it.channel.url == e.channel.url }
        val stepDir = if (swipeHint != 0) swipeHint else 1
        val previewEntry = if (idx >= 0 && allCh.size > 1) allCh[(idx + stepDir + allCh.size) % allCh.size] else null
        val slate = Playback.slate
        val pillShow = swipeHint != 0 || (full && visible && slate == null && !busy && !Nav.inPip && previewEntry != null)
        AnimatedVisibility(
            pillShow && previewEntry != null,
            Modifier.align(if (swipeHint < 0) Alignment.CenterStart else Alignment.CenterEnd).zIndex(2f),
            enter = fadeIn(tween(if (ThemePrefs.reduceMotion) 200 else tech.kousozo.tahoetv.core.Brand.MOTION_FAST, easing = EaseOutTahoe)),
            exit = fadeOut(tween(if (ThemePrefs.reduceMotion) 200 else 180, easing = EaseOutTahoe)),
        ) {
            previewEntry?.let { nextEntry ->
                Row(
                    Modifier
                        .let { if (full) it.displayCutoutPadding() else it }
                        .padding(horizontal = 20.dp)
                        .clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.58f))
                        .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
                        .clickable(role = Role.Button) { poke(); zap(stepDir) }
                        .semantics(mergeDescendants = true) {}
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ChannelArt(nextEntry, Modifier.size(56.dp, 32.dp).clip(RoundedCornerShape(8.dp)), logoScale = 0.78f)
                    Column {
                        Text(
                            tech.kousozo.tahoetv.core.I18n.get(lang0, if (stepDir > 0) "player.next_channel" else "player.prev_channel"),
                            color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp,
                        )
                        val nextShow = nowOn(Channels.epg, nextEntry, nowInstant)?.title
                        Text(
                            listOfNotNull(nextEntry.name, nextShow).joinToString(" · "),
                            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        feedbackPill?.let { msg ->
            Text(
                msg,
                Modifier.align(Alignment.TopCenter).padding(top = 56.dp).zIndex(3f)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.74f))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(50))
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        AndroidView({
            PlayerView(it).apply {
                this.player = player
                useController = false
                subtitleView?.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Playback.subtitleSize.spPhone)
                resizeMode = if (zoomFill) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                layoutParams = ViewGroup.LayoutParams(-1, -1)
            }
        }, Modifier.fillMaxSize(), update = {
            it.player = player
            it.subtitleView?.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Playback.subtitleSize.spPhone)
            it.resizeMode = if (zoomFill) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        })
        if (slate != null) ChannelSlate(e, slate, full, zap)
        else Playback.message?.let {
            if (it.isNotEmpty()) Text(it, Modifier.align(Alignment.Center).semantics { liveRegion = LiveRegionMode.Polite }
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(12.dp), color = Color.White, fontSize = 14.sp)
            else ChannelLoader(e.name, Modifier.fillMaxSize().semantics { liveRegion = LiveRegionMode.Polite; contentDescription = "${tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "player.loading")} ${e.name}" })
        }
        // the slate brings its own actions: the player controls would sit on top of it
        AnimatedVisibility(
            visible && slate == null && !Nav.inPip,
            Modifier.fillMaxSize(),
            enter = fadeIn(tween(if (ThemePrefs.reduceMotion) 200 else tech.kousozo.tahoetv.core.Brand.MOTION_FAST, easing = EaseOutTahoe)),
            exit = fadeOut(tween(if (ThemePrefs.reduceMotion) 200 else 180, easing = EaseOutTahoe)),
        ) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.65f), 0.35f to Color.Black.copy(alpha = 0.10f), 0.65f to Color.Black.copy(alpha = 0.15f), 1f to Color.Black.copy(alpha = 0.75f)))) {
                val lang = PhoneRegion.language
                val actSize = 48.dp // minimum touch target
                val actIcon = if (full) 22.dp else 18.dp
                // Figma 11:66 (landscape) / 23:400 (portrait) top overlay
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .let { if (full) it.statusBarsPadding().displayCutoutPadding() else it }
                        .padding(start = 10.dp, end = 6.dp, top = 6.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (full) {
                        IconButton({ poke(); onFull() }, Modifier.size(actSize)) {
                            Icon(Icons.Rounded.KeyboardArrowDown, tech.kousozo.tahoetv.core.I18n.get(lang, "player.exit_fullscreen"), tint = Color.White, modifier = Modifier.size(26.dp))
                        }
                        Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LiveBadge()
                                Text(tech.kousozo.tahoetv.core.I18n.get(lang, "home.on_now_fmt", e.name).uppercase(),
                                    color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, maxLines = 1)
                            }
                            Text(showNow?.title ?: e.name, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(showNow?.let { "${HM.format(it.start)} – ${HM.format(it.stop)} · ${e.category.label(lang)}" } ?: e.category.label(lang),
                                color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        Row(Modifier.weight(1f).padding(top = 10.dp, start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            LiveBadge(); Spacer(Modifier.width(8.dp))
                            Text(e.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton({
                            poke()
                            val res = Playback.cycleTrack(c, androidx.media3.common.C.TRACK_TYPE_TEXT)
                            android.widget.Toast.makeText(c, tech.kousozo.tahoetv.core.I18n.get(lang, "player.subtitle_toast", res), android.widget.Toast.LENGTH_SHORT).show()
                        }, Modifier.size(actSize)) {
                            Icon(Icons.Rounded.ClosedCaption,
                                tech.kousozo.tahoetv.core.I18n.get(lang, "player.subtitle_cycle", Playback.subtitleLabel ?: "Auto"), tint = Color.White, modifier = Modifier.size(actIcon))
                        }
                        if (full) {
                            IconButton({
                                poke()
                                val res = Playback.cycleTrack(c, androidx.media3.common.C.TRACK_TYPE_AUDIO)
                                android.widget.Toast.makeText(c, tech.kousozo.tahoetv.core.I18n.get(lang, "player.audio_toast", res), android.widget.Toast.LENGTH_SHORT).show()
                            }, Modifier.size(actSize)) {
                                Icon(Icons.Rounded.Audiotrack,
                                    tech.kousozo.tahoetv.core.I18n.get(lang, "player.audio_cycle", Playback.audioLabel ?: "Auto"), tint = Color.White, modifier = Modifier.size(actIcon))
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
                                }, Modifier.size(actSize)) {
                                    Icon(Icons.Rounded.PictureInPictureAlt, tech.kousozo.tahoetv.core.I18n.get(lang, "player.pip"), tint = Color.White, modifier = Modifier.size(actIcon))
                                }
                            }
                        } else {
                            IconButton({ poke(); zoomFill = !zoomFill }, Modifier.size(actSize)) {
                                Icon(Icons.Rounded.AspectRatio, tech.kousozo.tahoetv.core.I18n.get(lang, if (zoomFill) "player.zoom_fit" else "player.zoom_fill"), tint = Color.White, modifier = Modifier.size(actIcon))
                            }
                        }
                        IconButton({ poke(); settings = true }, Modifier.size(actSize)) {
                            Icon(Icons.Rounded.Settings, tech.kousozo.tahoetv.core.I18n.get(lang, "player.settings_desc"), tint = Color.White, modifier = Modifier.size(actIcon))
                        }
                        IconButton({ poke(); onFull() }, Modifier.size(actSize)) {
                            Icon(if (full) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, tech.kousozo.tahoetv.core.I18n.get(lang, if (full) "player.exit_fullscreen" else "player.fullscreen"), tint = Color.White, modifier = Modifier.size(actIcon))
                        }
                    }
                }
                // Figma 11:66 5-button center transport with 72 dp glass play/pause disc
                if (!busy) Row(
                    Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (full) 34.dp else 12.dp),
                ) {
                    IconButton({ poke(); zap(-1) }, Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, tech.kousozo.tahoetv.core.I18n.get(lang, "player.prev_channel"), tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                    IconButton({ poke(); player.seekTo(maxOf(0L, player.currentPosition - 10_000)); player.play() }, Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.Replay10, tech.kousozo.tahoetv.core.I18n.get(lang, "player.rewind10"), tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    Box(
                        Modifier.size(72.dp).clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.18f))
                            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                            .clickable(role = Role.Button) { poke(); Playback.toggle() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (Playback.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                            tech.kousozo.tahoetv.core.I18n.get(lang, if (Playback.paused) "player.play" else "player.pause"),
                            tint = Color.White, modifier = Modifier.size(34.dp),
                        )
                    }
                    IconButton({ poke(); seekForward(player, 10_000) }, Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.Forward10, tech.kousozo.tahoetv.core.I18n.get(lang, "player.forward10"), tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    IconButton({ poke(); zap(1) }, Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.SkipNext, tech.kousozo.tahoetv.core.I18n.get(lang, "player.next_channel"), tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                }
                val progFrac = showNow?.let { progress(it, nowInstant) } ?: 1f
                val liveFrac = if (isSeekable) tech.kousozo.tahoetv.core.LiveSeek.fractionFor(posMs, durMs) else progFrac
                val displayFrac = scrubFrac ?: liveFrac
                var barWidth by remember { mutableIntStateOf(1) }
                val scrubLabel = scrubFrac?.let { f ->
                    tech.kousozo.tahoetv.core.LiveSeek.formatBehind(
                        tech.kousozo.tahoetv.core.LiveSeek.behindLiveMs(
                            durMs,
                            tech.kousozo.tahoetv.core.LiveSeek.positionFor(f, durMs),
                        ),
                    )
                }
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .let { if (full) it.navigationBarsPadding().displayCutoutPadding() else it }
                        .padding(horizontal = 16.dp, vertical = if (full) 10.dp else 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (full || scrubLabel != null || (!atLiveEdge && !busy)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                scrubLabel ?: if (!atLiveEdge && isLiveStream) tech.kousozo.tahoetv.core.LiveSeek.formatBehind(maxOf(0L, liveOffMs)) else HM.format(nowInstant),
                                color = if (scrubLabel != null) Tahoe.Accent else Color.White,
                                fontSize = 13.sp,
                                fontWeight = if (scrubLabel != null) FontWeight.Bold else FontWeight.Normal,
                                fontFamily = Mono,
                            )
                            if (!atLiveEdge && !busy) {
                                Box(
                                    Modifier.heightIn(min = 36.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(Tahoe.Bad)
                                        .clickable(role = Role.Button) {
                                            player.seekToDefaultPosition()
                                            player.play()
                                            scrubFrac = null
                                            poke()
                                        }
                                        .padding(horizontal = 12.dp, vertical = 4.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(tech.kousozo.tahoetv.core.I18n.get(lang, "player.back_to_live"), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            } else if (full && isSeekable) {
                                Text(
                                    tech.kousozo.tahoetv.core.I18n.get(lang, "player.hold_slide_rewind").uppercase(),
                                    color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (full) {
                                Text(Playback.format ?: "LIVE", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = Mono)
                            }
                        }
                    }
                    Box(
                        Modifier.fillMaxWidth().height(48.dp)
                            .onSizeChanged { barWidth = it.width.coerceAtLeast(1) }
                            .pointerInput(isSeekable, durMs, busy, lang) {
                                detectHorizontalDragGestures(
                                    onDragStart = { offset ->
                                        poke()
                                        if (busy) return@detectHorizontalDragGestures
                                        if (isSeekable) {
                                            scrubFrac = (offset.x / barWidth.toFloat()).coerceIn(0f, 1f)
                                        } else {
                                            feedbackPill = tech.kousozo.tahoetv.core.I18n.get(lang, "player.seek_unavailable")
                                        }
                                    },
                                    onHorizontalDrag = { change, _ ->
                                        poke()
                                        if (isSeekable && !busy) {
                                            change.consume()
                                            scrubFrac = (change.position.x / barWidth.toFloat()).coerceIn(0f, 1f)
                                        }
                                    },
                                    onDragEnd = {
                                        val target = scrubFrac
                                        scrubFrac = null
                                        if (target != null && isSeekable && durMs > 0L) {
                                            player.seekTo(tech.kousozo.tahoetv.core.LiveSeek.positionFor(target, durMs))
                                            player.play()
                                        }
                                        poke()
                                    },
                                    onDragCancel = { scrubFrac = null },
                                )
                            }
                            .pointerInput(isSeekable, durMs, busy, lang) {
                                detectTapGestures(
                                    onTap = { offset ->
                                        poke()
                                        if (busy) return@detectTapGestures
                                        if (isSeekable && durMs > 0L) {
                                            val f = (offset.x / barWidth.toFloat()).coerceIn(0f, 1f)
                                            player.seekTo(tech.kousozo.tahoetv.core.LiveSeek.positionFor(f, durMs))
                                            player.play()
                                        } else {
                                            feedbackPill = tech.kousozo.tahoetv.core.I18n.get(lang, "player.seek_unavailable")
                                        }
                                    },
                                )
                            }
                            .semantics {
                                contentDescription = tech.kousozo.tahoetv.core.I18n.get(lang, "player.seekbar_desc")
                                stateDescription = if (atLiveEdge) "LIVE"
                                    else tech.kousozo.tahoetv.core.I18n.get(
                                        lang,
                                        "player.behind_live_fmt",
                                        tech.kousozo.tahoetv.core.LiveSeek.formatBehind(maxOf(0L, liveOffMs)),
                                    )
                                if (isSeekable) {
                                    progressBarRangeInfo = ProgressBarRangeInfo(displayFrac, 0f..1f)
                                    setProgress { target ->
                                        player.seekTo(tech.kousozo.tahoetv.core.LiveSeek.positionFor(target, durMs))
                                        player.play()
                                        poke()
                                        true
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.fillMaxWidth()
                                .height(if (scrubFrac != null) 6.dp else 4.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color.White.copy(alpha = 0.24f)),
                        ) {
                            Box(
                                Modifier.fillMaxWidth(displayFrac.coerceIn(0f, 1f))
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (!atLiveEdge || scrubFrac != null) Tahoe.Warm else Tahoe.Accent),
                            )
                        }
                    }
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

object HM {
    fun format(temporal: java.time.temporal.TemporalAccessor): String =
        DateTimeFormatter.ofPattern("HH.mm").withZone(PhoneRegion.viewer.zone).format(temporal)
}

/** Channel list + guide shared by Beranda, TV Live and Kategori; loaded once per app start. */
object Channels {
    var entries by mutableStateOf<List<LocalTv.Entry>?>(null); private set
    var epg by mutableStateOf<Epg?>(null); private set
    var failed by mutableStateOf(false); private set
    var debugOffline by mutableStateOf(false)
    private var loading = false
    /** Bumped on a country change: a load still running for the old country throws its result away. */
    private var generation = 0

    /** Synchronously populates demo channels/EPG when Demo mode is active (used by debug intent jumps). */
    fun loadDemoSync() {
        if (!tech.kousozo.tahoetv.core.Demo.isActive()) return
        val list = LocalTv.arrange(tech.kousozo.tahoetv.core.Demo.channels(), null, PhoneRegion.viewer.countries, PhoneRegion.viewer.sort, PhoneRegion.viewer.city, PhoneRegion.viewer.country)
        if (favs.isEmpty()) favs = list.take(2).map { it.channel.url }.toSet()
        all = list; entries = PhoneRegion.viewer.arrange(list); epg = tech.kousozo.tahoetv.core.Demo.epg(); failed = false; loading = false
    }

    /** Country changed: forget the list; the next screen that shows loads the new one. */
    fun reload(@Suppress("UNUSED_PARAMETER") c: android.content.Context) { generation++; loading = false; entries = null; all = null; epg = null; failed = false; debugOffline = false }

    suspend fun load(c: android.content.Context) {
        if (entries != null || loading) return
        loading = true
        if (tech.kousozo.tahoetv.core.Demo.isActive()) { // sample channels and guide, no network (screenshots)
            val list = LocalTv.arrange(tech.kousozo.tahoetv.core.Demo.channels(), null, PhoneRegion.viewer.countries, PhoneRegion.viewer.sort, PhoneRegion.viewer.city, PhoneRegion.viewer.country)
            if (favs.isEmpty()) favs = list.take(2).map { it.channel.url }.toSet() // demo: two saved channels, in memory only
            all = list; entries = PhoneRegion.viewer.arrange(list); epg = tech.kousozo.tahoetv.core.Demo.epg(); loading = false; return
        }
        val mine = generation
        try { withContext(Dispatchers.IO) {
            val setup = c.getSharedPreferences("setup", 0)
            val viewer = PhoneRegion.viewer
            val country = PhoneRegion.code
            // the onboarding's region; the older setup's only before onboarding (Indonesia, no city chosen yet)
            val province = viewer.nearRegion ?: setup.getString("region", null).takeIf { !viewer.done } ?: setup.getString("city", "").orEmpty().takeIf { it.isNotBlank() }
                ?.let { Web.geocode(it)?.region }?.also { setup.edit().putString("region", it).apply() }
            SourceHealth.init(java.io.File(c.cacheDir, "source-health.tsv"), java.io.File(c.filesDir, "source-banned.txt")) // bans: durable
            tech.kousozo.tahoetv.core.Sources.initCache(c.cacheDir)
            tech.kousozo.tahoetv.core.MediaScraper.initCache(c.cacheDir)
            tech.kousozo.tahoetv.core.Sources.scheduleDailyRefresh { all = null; entries = null }
            val ui = android.os.Handler(android.os.Looper.getMainLooper())
            var showedPriority = false
            val rawAll = runCatching {
                Sources.loadChannels(null, viewer.countries, viewer.channelLanguages) { priorityRaw ->
                    if (mine != generation || priorityRaw.isEmpty()) return@loadChannels
                    val priorityList = LocalTv.arrange(priorityRaw, province, viewer.countries, viewer.sort, viewer.city, viewer.country)
                    if (priorityList.isNotEmpty()) {
                        showedPriority = true
                        ui.post {
                            if (mine == generation) {
                                all = priorityList
                                entries = PhoneRegion.viewer.arrange(priorityList)
                            }
                        }
                    }
                }
            }.getOrNull()
            if (mine != generation) return@withContext // the country changed meanwhile
            val list = if (rawAll.isNullOrEmpty()) null else LocalTv.arrange(rawAll, province, viewer.countries, viewer.sort, viewer.city, viewer.country)
            if (list.isNullOrEmpty()) {
                if (!showedPriority) failed = true // no playlist for this country, or no internet: show the note, not a blank list
                return@withContext
            }
            // Expose the channel list right away so Home & Live TV don't stall waiting for large XMLTV guides.
            ui.post {
                if (mine == generation) {
                    all = list
                    entries = PhoneRegion.viewer.arrange(list)
                }
            }
            tech.kousozo.tahoetv.core.PopularityScraper.prefetchAsync(viewer.countries, list.map { it.name }) {
                ui.post { if (mine == generation) rearrange() }
            }
            SourceHealth.probeAll(SourceHealth.streamsOf(list))
            val channelFilter = list.flatMap { listOfNotNull(it.name, it.channel.tvgId) }.toSet()
            val guide = runCatching {
                Epg.loadPriority(viewer.countries.flatMap { tech.kousozo.tahoetv.core.Region.guides(it) }.distinct(), channelFilter)
            }.getOrNull()
            if (mine == generation) epg = guide
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
    /** A page pushed over the tabs (#179): "settings", "history", "explore", "setup", or null. */
    var page by mutableStateOf<String?>(null)
    fun play(e: LocalTv.Entry) { playUrl = e.channel.url; tab = 1; detail = null; page = null }
    fun category(k: LocalTv.Category?, favourites: Boolean = false) { liveCategory = k; favOnly = favourites; tab = 1; detail = null; page = null }
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
    val t = if (ThemePrefs.reduceMotion) 0f else androidx.compose.animation.core.rememberInfiniteTransition(label = "load").animateFloat(0f, 12f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1000, easing = androidx.compose.animation.core.LinearEasing)), label = "spin").value
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
            Text(tech.kousozo.tahoetv.core.I18n.get(lang, "player.settings"), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.a11yHeading())
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
            Text(tech.kousozo.tahoetv.core.I18n.get(lang, "sub.size_label"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tech.kousozo.tahoetv.core.PlayPrefs.SubtitleSize.entries.forEach { sz ->
                    Chip(sz.label(lang), Playback.subtitleSize == sz) { Playback.setSubtitleSize(c, sz) }
                }
            }
        }
    }
}

/** Technical diagnostics sheet ("Stats for Nerds", #61) on phone & tablet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerStatsSheet(onDismiss: () -> Unit) {
    val c = LocalContext.current
    val lang = PhoneRegion.language
    var snap by remember { mutableStateOf(Playback.statsSnapshot()) }
    LaunchedEffect(Unit) {
        while (true) {
            snap = Playback.statsSnapshot()
            delay(1000)
        }
    }
    ModalBottomSheet(onDismiss, containerColor = Tahoe.Surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tech.kousozo.tahoetv.core.I18n.get(lang, "stats.title"), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.a11yHeading())
            snap?.let { s ->
                tech.kousozo.tahoetv.core.PlayerStats.rows(s, lang).forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(k, color = Tahoe.Dim, fontSize = 13.sp)
                        Spacer(Modifier.width(12.dp))
                        Text(v, color = Tahoe.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Mono)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button({
                        val txt = tech.kousozo.tahoetv.core.PlayerStats.formatText(s, lang)
                        val cm = c.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("Tahoe TV Stats", txt))
                        android.widget.Toast.makeText(c, tech.kousozo.tahoetv.core.I18n.get(lang, "stats.copied"), android.widget.Toast.LENGTH_SHORT).show()
                    }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(tech.kousozo.tahoetv.core.I18n.get(lang, "stats.copy")) }
                    OutlinedButton({
                        val txt = tech.kousozo.tahoetv.core.PlayerStats.formatText(s, lang)
                        tech.kousozo.tahoetv.core.DiagLog.add("stats", txt.replace("\n", " | "))
                        android.widget.Toast.makeText(c, tech.kousozo.tahoetv.core.I18n.get(lang, "stats.log_saved"), android.widget.Toast.LENGTH_SHORT).show()
                    }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text(tech.kousozo.tahoetv.core.I18n.get(lang, "stats.send_log")) }
                }
            }
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
        .clip(RoundedCornerShape(50)).background(if (primary) Color.White else Color.White.copy(alpha = 0.16f))
        .clickable(role = Role.Button, onClick = onClick)
        .semantics(mergeDescendants = true) {}
        .padding(horizontal = if (big) 16.dp else 12.dp, vertical = if (big) 10.dp else 6.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
) {
    val fg = if (primary) Color(0xFF111114) else Color.White
    Icon(icon, null, tint = fg, modifier = Modifier.size(if (big) 18.dp else 14.dp))
    Text(text, color = fg, fontSize = if (big) 14.sp else 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
}
