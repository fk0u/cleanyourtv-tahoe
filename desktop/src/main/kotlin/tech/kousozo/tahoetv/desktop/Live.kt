@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package tech.kousozo.tahoetv.desktop

import androidx.compose.foundation.Image
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.kousozo.tahoetv.core.Epg
import tech.kousozo.tahoetv.core.LocalTv
import tech.kousozo.tahoetv.core.MediaScraper
import tech.kousozo.tahoetv.core.SourceHealth
import tech.kousozo.tahoetv.core.Search
import tech.kousozo.tahoetv.core.M3u
import tech.kousozo.tahoetv.core.Sources
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/** Live TV from an M3U playlist. Playback via JavaFX media (HLS + H.264/AAC, what most free IPTV streams use). */
@Composable
fun LiveScreen() {
    var channels by remember { mutableStateOf<List<M3u.Channel>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf(DeskNav.query) }
    LaunchedEffect(DeskNav.query) { query = DeskNav.query }   // search from the top bar
    var group by remember { mutableStateOf<String?>(null) } // null = Semua, "★" = favorites, else a LocalTv.Category name
    var playing by remember { mutableStateOf<M3u.Channel?>(null) }
    var favs by remember { mutableStateOf(Settings.favorites) }
    var epg by remember { mutableStateOf<Epg?>(null) }
    var tick by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick = Instant.now() } }
    val epgUrl = Settings.epg
    val viewer = Settings.viewer
    val countries = viewer.countries
    val scope = rememberCoroutineScope()
    var sortMode by remember(viewer.sortMode) { mutableStateOf(viewer.sort) }
    var popularityVersion by remember { mutableStateOf(0) }
    val hm = remember(viewer.zone) { DateTimeFormatter.ofPattern("HH.mm").withZone(viewer.zone) }
    LaunchedEffect(epgUrl, countries, channels) {
        epg = null
        epg = withContext(Dispatchers.IO) {
            runCatching {
                val filter = channels?.flatMap { listOfNotNull(it.name, it.tvgId) }?.toSet()
                val urls = epgUrl.ifBlank { null }?.let(::listOf) ?: countries.flatMap { tech.kousozo.tahoetv.core.Region.guides(it) }.distinct()
                Epg.loadPriority(urls, filter) { partial -> scope.launch { epg = partial } }
            }.getOrNull()
        }
    }
    val playlist = Settings.playlist
    var statusMap by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var checkingDone by remember { mutableStateOf(false) }
    var statusFilter by remember { mutableStateOf<String?>(null) } // null = all, "live", "offline"

    var refreshNonce by remember { mutableStateOf(0) }
    var recheckTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { Sources.scheduleDailyRefresh { refreshNonce++ } }
    LaunchedEffect(Unit) { while (true) { delay(5 * 60_000L); recheckTick++ } }

    LaunchedEffect(recheckTick) {
        if (recheckTick <= 0) return@LaunchedEffect
        val curList = channels ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val todo = SourceHealth.streamsOf(
                viewer.arrange(LocalTv.arrange(curList, Settings.nearProvince, countries, sortMode, viewer.city, viewer.country))
            ).take(400)
            val lock = Any()
            Sources.checkStatus(todo, timeoutMs = 3500) { url, isLive ->
                SourceHealth.report(url, isLive)
                synchronized(lock) { statusMap = statusMap + (url to isLive) }
            }
        }
    }

    LaunchedEffect(playlist, countries, refreshNonce) {
        channels = null; error = null
        statusMap = emptyMap()
        checkingDone = false

        // Load channels immediately without blocking behind IptvServer.start() on fresh/Windows launches.
        withContext(Dispatchers.IO) {
            runCatching { java.io.File(System.getProperty("user.home"), ".tahoetv").also { it.mkdirs() }.let { SourceHealth.init(java.io.File(it, "source-health.tsv")) } }
            runCatching {
                Sources.loadChannels(playlist, countries, Settings.viewer.channelLanguages) { priority ->
                    if (priority.isNotEmpty()) scope.launch { channels = priority }
                }
            }
        }.fold(
            { loaded ->
                channels = loaded
                if (loaded.isEmpty()) {
                    error = tech.kousozo.tahoetv.core.I18n.get(Settings.lang, "live.empty_load")
                    return@fold
                }
                // Seed LAN / Windows WebPlayer server in parallel so channel switches never block or see 0 channels
                Thread({
                    runCatching {
                        IptvServer.seedFromApp(loaded)
                        IptvServer.ensureRunning()
                    }
                }, "tahoe-iptv-seed").apply { isDaemon = true }.start()
                // Check arranged channels & their backups first, reusing any fresh SourceHealth cache
                withContext(Dispatchers.IO) {
                    tech.kousozo.tahoetv.core.PopularityScraper.prefetchAsync(countries, loaded.map { it.name }) {
                        scope.launch { popularityVersion++ }
                    }
                    val effViewer = viewer.copy(sortMode = sortMode.key)
                    val ordered = SourceHealth.streamsOf(effViewer.arrange(LocalTv.arrange(loaded, Settings.nearProvince, countries, sortMode, viewer.city, viewer.country)))
                    val cached = ordered.mapNotNull { c -> SourceHealth.status(c.url)?.let { c.url to it.live } }.toMap()
                    val lock = Any()
                    if (cached.isNotEmpty()) synchronized(lock) { statusMap = statusMap + cached }
                    val todo = ordered.filter { it.url !in cached }.take(1500)
                    Sources.checkStatus(todo, timeoutMs = 3500) { url, isLive ->
                        SourceHealth.report(url, isLive) // the player then starts a source known to work
                        // 32 checker threads report at once: without the lock, read-add-write lost results
                        synchronized(lock) { statusMap = statusMap + (url to isLive) }
                    }
                    checkingDone = true
                }
            },
            { error = tech.kousozo.tahoetv.core.I18n.get(Settings.lang, "live.playlist_error", it.message ?: "") }
        )
    }
    val lang = Settings.lang
    val province = Settings.nearProvince
    // the viewer's categories and favourite order (onboarding) on top of the usual arrangement
    val entries = remember(channels, province, viewer, sortMode, popularityVersion) {
        viewer.copy(sortMode = sortMode.key).arrange(LocalTv.arrange(channels.orEmpty(), province, countries, sortMode, viewer.city, viewer.country))
    }
    val names = remember(entries) { entries.associate { it.channel.url to it.name } }
    val byUrl = remember(entries) { entries.associateBy { it.channel.url } }
    fun isChannelLive(e: LocalTv.Entry): Boolean? {
        val all = (listOf(e.channel) + e.alternates).map { statusMap[it.url] }
        return when {
            all.any { it == true } -> true
            all.all { it != null } -> false
            else -> null
        }
    }
    val liveCount = remember(entries, statusMap) { entries.count { isChannelLive(it) == true } }
    val offlineCount = remember(entries, statusMap) { entries.count { isChannelLive(it) == false } }
    val cats = remember(entries) { LocalTv.Category.entries.filter { c -> entries.any { LocalTv.inCategory(it, c) } } }
    // Search covers names, groups and the guide: "bola" or a show's title finds the channel airing it.
    // Blank queries don't re-run Search.run on every 30s clock tick.
    val searchMinute = if (query.isBlank()) 0L else tick.epochSecond / 60
    val hits = remember(entries, epg, query, searchMinute) { Search.run(entries, epg, query, tick) }
    val matchedShow = remember(hits) { hits.mapNotNull { h -> h.programme?.let { h.entry.channel.url to it } }.toMap() }
    val shown = remember(hits, group, statusFilter, statusMap, favs, query) {
        hits.map { it.entry }
            .filter { e ->
                when (group) {
                    null -> true
                    "★" -> e.channel.url in favs || e.channel.channelId in favs
                    else -> runCatching { LocalTv.inCategory(e, LocalTv.Category.valueOf(group!!)) }.getOrDefault(false)
                }
            }
            .filter { e ->
                when (statusFilter) {
                    "live" -> isChannelLive(e) == true
                    "offline" -> isChannelLive(e) == false
                    else -> true
                }
            }
            .let { l -> if (query.isBlank()) l.sortedByDescending { it.channel.url in favs || it.channel.channelId in favs } else l } // searching: best match first
    }

    var selected by remember { mutableStateOf<LocalTv.Entry?>(null) }   // focused card → detail panel
    var status by remember { mutableStateOf<String?>(null) }
    var viewMode by remember { mutableStateOf(0) } // 0 = List, 1 = Grid, 2 = EPG Timeline
    val searchFocus = remember { FocusRequester() }
    var searchFocused by remember { mutableStateOf(false) }
    val list by rememberUpdatedState(shown)   // channel up/down follows what's on screen
    LaunchedEffect(shown, epg) {
        if (playing != null) {
            PlayerWindow.setChannelCatalog(shown.map { en ->
                val curShow = epg?.schedule(en.name, tick)?.firstOrNull { p -> p.start <= tick }?.title.orEmpty()
                en.name to curShow
            })
        }
    }
    LaunchedEffect(playing) {
        playing?.let { ch ->
            val e = byUrl[ch.url]
            PlayerWindow.play(
                listOf(ch) + e?.alternates.orEmpty(),
                names[ch.url] ?: ch.name,
                e?.let { en -> epg?.schedule(en.name, Instant.now()) }.orEmpty(),
                onSwitch = { d ->
                    javax.swing.SwingUtilities.invokeLater {
                        if (list.isNotEmpty()) {
                            val i = list.indexOfFirst { it.channel.url == playing?.url }
                            val next = list[((if (i < 0) 0 else i + d) % list.size + list.size) % list.size]
                            playing = next.channel; selected = next
                            Settings.rememberRecentChannel(next.name)
                        }
                    }
                },
                onJump = { targetIdx ->
                    javax.swing.SwingUtilities.invokeLater {
                        list.getOrNull(targetIdx)?.let { next ->
                            playing = next.channel; selected = next
                            Settings.rememberRecentChannel(next.name)
                        }
                    }
                },
            ) { s ->
                if (s == PlayerWindow.CLOSED) { playing = null; status = null; return@play } // window closed: clicks select again
                status = s
                if (s == null) statusMap = statusMap + (ch.url to true)
            }
        }
    }
    fun watch(e: LocalTv.Entry) { selected = e; playing = e.channel; Settings.rememberRecentChannel(e.name) }

    Row(
        Modifier.fillMaxSize().padding(start = 12.dp, end = 20.dp, top = 20.dp, bottom = 20.dp)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.Slash -> if (!searchFocused) { searchFocus.requestFocus(); true } else false
                    Key.DirectionDown -> if (shown.isNotEmpty()) {
                        val i = shown.indexOf(selected)
                        selected = shown[((i + 1).coerceIn(0, shown.lastIndex))]
                        true
                    } else false
                    Key.DirectionUp -> if (shown.isNotEmpty()) {
                        val i = shown.indexOf(selected)
                        selected = shown[((if (i <= 0) 0 else i - 1))]
                        true
                    } else false
                    Key.Enter -> if (!searchFocused) {
                        val target = selected ?: shown.firstOrNull()
                        if (target != null) { watch(target); true } else false
                    } else false
                    else -> false
                }
            },
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // ---------------------------------------------------------------- rail: title, search, status filter, categories
        Column(Modifier.width(235.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Title(
                        tech.kousozo.tahoetv.core.I18n.get(lang, "nav.live"),
                        channels?.let {
                            if (!checkingDone && statusMap.size < it.size) {
                                tech.kousozo.tahoetv.core.I18n.get(lang, "live.checking_streams", statusMap.size, it.size)
                            } else {
                                tech.kousozo.tahoetv.core.I18n.get(lang, "live.live_offline_fmt", liveCount, offlineCount) +
                                    (province.takeIf { p -> p.isNotBlank() }?.let { p -> " · $p" } ?: "")
                            }
                        } ?: error ?: tech.kousozo.tahoetv.core.I18n.get(lang, "live.loading_channels")
                    )
                }
                IconButton({ recheckTick++ }, Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.Refresh, tech.kousozo.tahoetv.core.I18n.get(lang, "desk.recheck"), tint = Tahoe.Accent, modifier = Modifier.size(18.dp))
                }
                if (entries.isNotEmpty() && !WebPlayer.preferred) {
                    IconButton({ MultiViewWindow.open(shown.ifEmpty { entries }, selected) }, Modifier.size(34.dp)) {
                        Icon(Icons.Rounded.ViewModule, tech.kousozo.tahoetv.core.I18n.get(lang, "desk.multiview"), tint = Tahoe.Accent, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Field(
                query, { query = it }, tech.kousozo.tahoetv.core.I18n.get(lang, "home.search_hint"),
                Modifier.fillMaxWidth().focusRequester(searchFocus).onFocusChanged { searchFocused = it.isFocused },
                trailing = { Icon(Icons.Rounded.Search, null, tint = Tahoe.Faint) },
            )
            // View mode switch: List / Grid / EPG Timeline (#49)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    Triple(0, tech.kousozo.tahoetv.core.I18n.get(lang, "desk.view_list"), Icons.Rounded.Menu),
                    Triple(1, tech.kousozo.tahoetv.core.I18n.get(lang, "desk.view_grid"), Icons.Rounded.GridView),
                    Triple(2, tech.kousozo.tahoetv.core.I18n.get(lang, "desk.view_timeline"), Icons.Rounded.Schedule),
                ).forEach { (mode, lbl, ic) ->
                    val act = viewMode == mode
                    Row(
                        Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                            .background(if (act) Tahoe.Accent.copy(alpha = 0.24f) else Tahoe.Glass)
                            .clickable { viewMode = mode }.padding(vertical = 5.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(ic, null, tint = if (act) Tahoe.Accent else Tahoe.Dim, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(lbl, fontSize = 11.sp, fontWeight = if (act) FontWeight.Bold else FontWeight.Normal, color = if (act) Tahoe.Accent else Tahoe.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            // Status filter chips (All / Live / Offline)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    null to tech.kousozo.tahoetv.core.I18n.get(lang, "live.all"),
                    "live" to "Live ($liveCount)",
                    "offline" to "Offline ($offlineCount)"
                ).forEach { (stKey, stLabel) ->
                    val active = statusFilter == stKey
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                            .background(if (active) (if (stKey == "offline") Tahoe.Bad.copy(alpha = 0.25f) else Tahoe.Accent.copy(alpha = 0.25f)) else Tahoe.Glass)
                            .clickable { statusFilter = stKey }.padding(vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stLabel,
                            fontSize = 11.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            color = if (active) (if (stKey == "offline") Tahoe.Bad else Tahoe.Accent) else Tahoe.Dim
                        )
                    }
                }
            }

            // Sort chips (Popular / A-Z / Z-A)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LocalTv.SortMode.entries.forEach { mode ->
                    val active = sortMode == mode
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                            .background(if (active) Tahoe.Accent.copy(alpha = 0.22f) else Tahoe.Glass)
                            .clickable {
                                sortMode = mode
                                Settings.viewer = Settings.viewer.copy(sortMode = mode.key)
                            }.padding(vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            mode.label(lang),
                            fontSize = 11.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            color = if (active) Tahoe.Accent else Tahoe.Dim
                        )
                    }
                }
            }

            if (channels == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Tahoe.Accent, trackColor = Tahoe.Glass)
            if (error != null && channels.isNullOrEmpty()) {
                GhostButton(tech.kousozo.tahoetv.core.I18n.get(lang, "live.retry_load"), Icons.Rounded.Refresh, color = Tahoe.Accent) {
                    refreshNonce++
                }
            }

            // Categories list (Scrollable)
            val rows = listOf<Triple<String?, String, Int>>(Triple(null, tech.kousozo.tahoetv.core.I18n.get(lang, "live.all"), entries.size)) +
                cats.map { c -> Triple(c.name, c.label(lang), entries.count { LocalTv.inCategory(it, c) }) } +
                Triple("★", tech.kousozo.tahoetv.core.I18n.get(lang, "live.favorites_star"), entries.count { it.channel.url in favs })

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                rows.forEach { (key, label, n) ->
                    val on = key == group
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (on) Color.White.copy(alpha = 0.13f) else Color.Transparent)
                            .clickable { group = key }.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(if (on) Tahoe.Accent.copy(alpha = 0.25f) else Tahoe.Glass),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(categoryIcon(key), null, tint = if (on) Tahoe.Accent else Tahoe.Dim, modifier = Modifier.size(15.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(label, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                            Text(tech.kousozo.tahoetv.core.I18n.get(lang, "home.channel_count", n), fontSize = 10.sp, color = Tahoe.Faint)
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------------- channel cards (List / Grid / EPG Timeline)
        when (viewMode) {
            1 -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 195.dp),
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(shown, key = { it.channel.url }) { e ->
                    val ch = e.channel
                    val focused = e == selected
                    val on = ch == playing
                    val prog = epg?.schedule(e.name, tick)?.firstOrNull()?.takeIf { it.start <= tick }
                    val live = isChannelLive(e)
                    val hoverSrc = remember(ch.url) { MutableInteractionSource() }
                    val hovered by hoverSrc.collectIsHoveredAsState()
                    Column(
                        Modifier.clip(RoundedCornerShape(18.dp))
                            .hoverable(hoverSrc)
                            .background(if (focused) Color.White.copy(alpha = 0.14f) else Tahoe.Glass)
                            .border(if (ThemePrefs.highContrast) 2.dp else 1.dp, if (focused) Tahoe.Accent.copy(alpha = 0.7f) else Tahoe.Stroke, RoundedCornerShape(18.dp))
                            .combinedClickable(onDoubleClick = { watch(e) }) { if (focused) watch(e) else selected = e }.padding(12.dp), // click = preview, again / double-click = watch (#182)
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.92f)), contentAlignment = Alignment.Center) {
                            val rowLogoUrl by produceState(ch.logo, ch.url, e.name) {
                                if (value.isNullOrBlank()) value = withContext(Dispatchers.IO) { MediaScraper.channelLogo(e.name, ch.logo) }
                            }
                            rememberImage(rowLogoUrl)?.let { Image(it, null, Modifier.padding(8.dp).fillMaxSize(), contentScale = ContentScale.Fit) }
                                ?: Text(e.name.take(3).uppercase(), color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(e.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(prog?.title ?: e.category.label(lang), fontSize = 12.sp, color = Tahoe.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            when {
                                on -> Badge(tech.kousozo.tahoetv.core.I18n.get(lang, "live.badge_playing"), Color(0xFF0A84FF), filled = true)
                                live == true -> Badge("LIVE", Tahoe.Good, filled = true)
                                live == false -> Badge("OFFLINE", Tahoe.Bad, filled = true)
                                else -> Badge("•", Tahoe.Dim)
                            }
                            if (e.channel.drm) Badge("DRM", Color(0xFFFF9F0A))
                        }
                    }
                }
            }

            2 -> EpgTimelinePane(
                entries = shown.take(50),
                epg = epg,
                now = tick,
                hm = hm,
                isChannelLive = ::isChannelLive,
                lang = lang,
                onSelect = { e -> selected = e },
                onWatch = ::watch,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )

            else -> LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(shown, key = { it.channel.url }) { e ->
                val ch = e.channel
                val focused = e == selected
                val on = ch == playing
                val scale by animateFloatAsState(
                    if (focused && !ThemePrefs.reduceMotion) 1.02f else 1f,
                    animationSpec = tween(if (ThemePrefs.reduceMotion) 0 else tech.kousozo.tahoetv.core.Brand.MOTION_FAST, easing = EaseOutTahoe),
                    label = "card",
                )
                val prog = epg?.schedule(e.name, tick)?.firstOrNull()?.takeIf { it.start <= tick }
                val live = isChannelLive(e)
                val hoverSrc = remember(ch.url) { MutableInteractionSource() }
                val hovered by hoverSrc.collectIsHoveredAsState()

                Row(
                    Modifier.fillMaxWidth().scale(scale).alpha(if (on || live != false) 1f else 0.5f).clip(RoundedCornerShape(18.dp))
                        .hoverable(hoverSrc)
                        .background(if (focused) Color.White.copy(alpha = 0.14f) else Tahoe.Glass)
                        .border(if (ThemePrefs.highContrast) 2.dp else 1.dp, if (focused) Tahoe.Accent.copy(alpha = 0.7f) else Tahoe.Stroke, RoundedCornerShape(18.dp))
                        .combinedClickable(onDoubleClick = { watch(e) }) { if (focused) watch(e) else selected = e }.padding(12.dp), // click = preview, again / double-click = watch (#182)
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(110.dp, 72.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.92f)), contentAlignment = Alignment.Center) {
                        val rowLogoUrl by produceState(ch.logo, ch.url, e.name) {
                            if (value.isNullOrBlank()) value = withContext(Dispatchers.IO) { MediaScraper.channelLogo(e.name, ch.logo) }
                        }
                        rememberImage(rowLogoUrl)?.let { Image(it, null, Modifier.padding(8.dp).fillMaxSize(), contentScale = ContentScale.Fit) }
                            ?: Text(e.name.take(3).uppercase(), color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(e.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val found = matchedShow[ch.url]
                        if (found != null) Text("${if (found.start <= tick) tech.kousozo.tahoetv.core.I18n.get(lang, "live.now_airing") else tech.kousozo.tahoetv.core.I18n.get(lang, "live.at_time", hm.format(found.start))} · ${found.title}",
                            fontSize = 13.sp, color = Tahoe.Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        else Text(prog?.title ?: e.category.label(lang), fontSize = 13.sp, color = Tahoe.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                            when {
                                on -> Badge(tech.kousozo.tahoetv.core.I18n.get(lang, "live.badge_playing"), Color(0xFF0A84FF), filled = true)
                                live == true -> Badge("LIVE", Tahoe.Good, filled = true)
                                live == false -> Badge("OFFLINE", Tahoe.Bad, filled = true)
                                else -> Badge(tech.kousozo.tahoetv.core.I18n.get(lang, "live.badge_check"), Tahoe.Dim)
                            }
                            if (e.category == LocalTv.Category.Vod) Badge("VOD", Color(0xFFFF2D55))
                            if (e.channel.drm) Badge("DRM", Color(0xFFFF9F0A))
                            if (e.channel.url.contains(".mpd", true)) Badge("DASH", Color(0xFFBF5AF2))
                            if (e.channel.headers.isNotEmpty()) Badge("RELAY", Tahoe.Accent)
                            if (prog != null) Badge("EPG", Tahoe.Text)
                            if (e.alternates.isNotEmpty()) Badge(tech.kousozo.tahoetv.core.I18n.get(lang, "live.sources", e.alternates.size + 1), Tahoe.Text)
                            if (e.near) Badge(tech.kousozo.tahoetv.core.I18n.get(lang, "live.near_badge"), Tahoe.Accent)
                            if (e.partTime) Badge(tech.kousozo.tahoetv.core.I18n.get(lang, "live.part_time"), Tahoe.Faint)
                        }
                    }
                    val fav = ch.url in favs
                    IconButton({ favs = if (fav) favs - ch.url else favs + ch.url; Settings.favorites = favs }) {
                        Icon(if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder, tech.kousozo.tahoetv.core.I18n.get(lang, "live.favorites_star"), tint = if (fav) Color(0xFFFF9F0A) else Tahoe.Faint)
                    }
                }
            }
        }
        }

        // ---------------------------------------------------------------- detail panel: the clicked (selected) channel, previewed when live
        val curSelected = selected ?: shown.firstOrNull()
        Detail(
            curSelected,
            epg,
            tick,
            playingUrl = playing?.url,
            status = status,
            isLive = curSelected?.let { isChannelLive(it) },
            isHoverPreview = selected != null && selected?.channel != playing && isChannelLive(selected!!) == true, // selected live channel = preview (#182)
            onWatch = ::watch,
            onStop = { PlayerWindow.stop(); playing = null },
            onRecord = { e, title, start, stop -> Recordings.schedule(e.name, title, listOf(e.channel) + e.alternates, start, stop) }
        )
    }
}

/** Right-hand panel: the channel's logo as soft backdrop, "Sedang tayang" with description and progress, actions, and what's next. */
@Composable
private fun Detail(
    e: LocalTv.Entry?,
    epg: Epg?,
    now: Instant,
    playingUrl: String?,
    status: String?,
    isLive: Boolean?,
    isHoverPreview: Boolean = false,
    onWatch: (LocalTv.Entry) -> Unit,
    onStop: () -> Unit,
    onRecord: (LocalTv.Entry, String, Long, Long) -> Unit,
) {
    val hm = remember(Settings.viewer.zone) { DateTimeFormatter.ofPattern("HH.mm").withZone(Settings.viewer.zone) }
    val lang = Settings.lang
    Box(Modifier.width(400.dp).fillMaxHeight().clip(RoundedCornerShape(26.dp)).background(Color(0xFF111118)).border(1.dp, Tahoe.Stroke, RoundedCornerShape(26.dp))) {
        if (e == null) { Text(tech.kousozo.tahoetv.core.I18n.get(lang, "live.select_channel"), Modifier.align(Alignment.Center), color = Tahoe.Dim); return@Box }
        val logoUrl by produceState(e.channel.logo, e.channel.url, e.name) {
            if (value.isNullOrBlank()) value = withContext(Dispatchers.IO) { MediaScraper.channelLogo(e.name, e.channel.logo) }
        }
        val s = epg?.schedule(e.name, now, e.channel.tvgId).orEmpty()
        val cur = s.firstOrNull()?.takeIf { it.start <= now }
        val thumbUrl by produceState<String?>(null, cur?.title, e.name) {
            value = cur?.title?.let { t -> withContext(Dispatchers.IO) { MediaScraper.programmeThumbnail(t, e.name) } }
        }
        val logo = rememberImage(logoUrl)
        val thumb = rememberImage(thumbUrl)
        val backdrop = thumb ?: logo
        if (backdrop != null) Image(backdrop, null, Modifier.fillMaxWidth().height(260.dp).blur(if (thumb != null && !isHoverPreview) 14.dp else if (thumb != null) 4.dp else 28.dp).alpha(if (thumb != null && isHoverPreview) 0.72f else 0.55f), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.25f to Color(0x66111118), 0.45f to Color(0xFF111118))))
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (isHoverPreview) Badge(tech.kousozo.tahoetv.core.I18n.get(lang, "desk.live_preview"), Tahoe.Accent, filled = true)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(120.dp, 76.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.94f)), contentAlignment = Alignment.Center) {
                    if (logo != null) Image(logo, null, Modifier.padding(10.dp).fillMaxSize(), contentScale = ContentScale.Fit)
                    else Text(e.name, color = Color.Black, fontWeight = FontWeight.Bold)
                }
                if (thumb != null) {
                    Image(thumb, cur?.title, Modifier.weight(1f).height(76.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, Tahoe.Stroke, RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
                }
            }
            Spacer(Modifier.height(10.dp))

            // Channel Status Line
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val (stText, stColor) = when (isLive) {
                    true -> tech.kousozo.tahoetv.core.I18n.get(lang, "live.status_live") to Tahoe.Good
                    false -> tech.kousozo.tahoetv.core.I18n.get(lang, "live.status_offline") to Tahoe.Bad
                    null -> tech.kousozo.tahoetv.core.I18n.get(lang, "live.status_checking") to Tahoe.Dim
                }
                Box(Modifier.size(8.dp).clip(CircleShape).background(stColor))
                Text(stText, fontSize = 12.sp, color = stColor, fontWeight = FontWeight.SemiBold)

                if (e.category == LocalTv.Category.Vod) Badge("VOD", Color(0xFFFF2D55))
                if (PlayerWindow.needsBrowserDrm(e.channel)) {
                    Badge(tech.kousozo.tahoetv.core.I18n.get(lang, "live.badge_web"), Tahoe.Accent)
                }
            }

            Text(if (cur != null) tech.kousozo.tahoetv.core.I18n.get(lang, "live.playing_now") else e.category.label(lang).uppercase(), fontSize = 11.sp, letterSpacing = 1.2.sp, color = Tahoe.Faint, fontWeight = FontWeight.SemiBold)
            Text(cur?.title ?: e.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 31.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            cur?.desc?.let { Text(it, fontSize = 13.sp, color = Tahoe.Dim, lineHeight = 19.sp, maxLines = 4, overflow = TextOverflow.Ellipsis) }
            cur?.let { p ->
                val f = ((now.epochSecond - p.start.epochSecond).toFloat() / (p.stop.epochSecond - p.start.epochSecond).coerceAtLeast(1)).coerceIn(0f, 1f)
                KeepLtr {
                    LinearProgressIndicator({ f }, Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp).clip(RoundedCornerShape(2.dp)), color = Tahoe.Text, trackColor = Tahoe.Stroke)
                }
                Row { Text(hm.format(p.start), fontSize = 12.sp, color = Tahoe.Faint); Spacer(Modifier.weight(1f)); Text(hm.format(p.stop), fontSize = 12.sp, color = Tahoe.Faint) }
            }
            val isPlaying = e.channel.url == playingUrl
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isPlaying) PrimaryButton(tech.kousozo.tahoetv.core.I18n.get(lang, "home.watch"), Icons.Rounded.PlayArrow) { onWatch(e) }
                else {
                    PrimaryButton(tech.kousozo.tahoetv.core.I18n.get(lang, "live.show_player"), Icons.Rounded.PictureInPicture) { PlayerWindow.show() }
                    GhostButton(tech.kousozo.tahoetv.core.I18n.get(lang, "desk.mini_player"), Icons.Rounded.PictureInPictureAlt) { PlayerWindow.toggleMiniPlayer() }
                    GhostButton(tech.kousozo.tahoetv.core.I18n.get(lang, "live.stop"), Icons.Rounded.Stop, onClick = onStop)
                }

                if (PlayerWindow.needsBrowserDrm(e.channel)) {
                    GhostButton(tech.kousozo.tahoetv.core.I18n.get(lang, "live.open_web_player"), Icons.Rounded.OpenInBrowser) {
                        runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(IptvServer.webUrl())) }
                    }
                }
            }
            if (isPlaying && status != null) Text(status, fontSize = 12.sp, color = Tahoe.Bad)
            // DVR: the show on now (until it ends, +2 min), or the next hour when the guide has nothing
            fun planned(start: Long) = Recordings.jobs.firstOrNull { it.active && it.channel == e.name && it.start == start }
            val nowStart = cur?.start?.toEpochMilli() ?: (now.toEpochMilli() / 60_000 * 60_000)
            val nowMs = now.toEpochMilli()
            // the show's own job, or any job of this channel running right now (one scheduled a minute early, "1 hour" from earlier)
            val rec = planned(nowStart) ?: Recordings.jobs.firstOrNull { it.active && it.channel == e.name && nowMs in it.start until it.stop }
            val recordable = (listOf(e.channel) + e.alternates).any(Recordings::recordable)
            if (rec != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (rec.state == "recording") tech.kousozo.tahoetv.core.I18n.get(lang, "live.recording_now") else tech.kousozo.tahoetv.core.I18n.get(lang, "live.scheduled"), fontSize = 12.sp, color = Tahoe.Bad, fontWeight = FontWeight.SemiBold)
                TextButton({ Recordings.cancel(rec.id) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(if (rec.state == "recording") tech.kousozo.tahoetv.core.I18n.get(lang, "live.stop_rec") else tech.kousozo.tahoetv.core.I18n.get(lang, "live.cancel_rec"), fontSize = 12.sp, color = Tahoe.Dim)
                }
            }
            else if (recordable) GhostButton(if (cur != null) tech.kousozo.tahoetv.core.I18n.get(lang, "live.record_show") else tech.kousozo.tahoetv.core.I18n.get(lang, "live.record_1h"), Icons.Rounded.FiberManualRecord, color = Tahoe.Bad) {
                if (cur != null) onRecord(e, cur.title, nowStart, cur.stop.toEpochMilli() + 120_000)
                else onRecord(e, tech.kousozo.tahoetv.core.I18n.get(lang, "live.broadcast", e.name), nowStart, nowStart + 3600_000)
            }
            val next = s.filter { it.start > now }.take(4)
            if (next.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(tech.kousozo.tahoetv.core.I18n.get(lang, "live.up_next"), fontSize = 11.sp, letterSpacing = 1.2.sp, color = Tahoe.Faint, fontWeight = FontWeight.SemiBold)
                next.forEach { p ->
                    val start = p.start.toEpochMilli() - 60_000 // a minute early: guides are rarely exact
                    val job = planned(start)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(hm.format(p.start), Modifier.width(52.dp), fontSize = 13.sp, color = Tahoe.Dim)
                        Text(p.title, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (job != null || recordable) IconButton({ if (job != null) Recordings.cancel(job.id) else onRecord(e, p.title, start, p.stop.toEpochMilli() + 120_000) }, Modifier.size(28.dp)) {
                            Icon(if (job != null) Icons.Rounded.FiberManualRecord else Icons.Rounded.RadioButtonUnchecked,
                                if (job != null) tech.kousozo.tahoetv.core.I18n.get(lang, "live.cancel_show_rec", p.title) else tech.kousozo.tahoetv.core.I18n.get(lang, "live.record_show_title", p.title), tint = if (job != null) Tahoe.Bad else Tahoe.Faint, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun categoryIcon(key: String?) = when (key) {
    null -> Icons.Rounded.GridView
    "★" -> Icons.Rounded.Star
    "Near" -> Icons.Rounded.Place
    "National" -> Icons.Rounded.Flag
    "Sports" -> Icons.Rounded.SportsSoccer
    "Movies" -> Icons.Rounded.Movie
    "Entertainment" -> Icons.Rounded.Theaters
    "News" -> Icons.Rounded.Newspaper
    "Kids" -> Icons.Rounded.ChildCare
    "Documentary" -> Icons.Rounded.MenuBook
    "Music" -> Icons.Rounded.MusicNote
    "Lifestyle" -> Icons.Rounded.Spa
    "Religious" -> Icons.Rounded.Mosque
    "Tvri" -> Icons.Rounded.CellTower
    "Local" -> Icons.Rounded.LocationCity
    "Vod" -> Icons.Rounded.VideoLibrary
    else -> Icons.Rounded.Public
}

@Composable
private fun Badge(text: String, color: Color, filled: Boolean = false) = Text(text, Modifier.clip(RoundedCornerShape(5.dp))
    .background(if (filled) color else color.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 2.dp),
    color = if (filled) Color.White else color, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, letterSpacing = 0.4.sp)

/** Hourly scrollable EPG timeline grid across channels (#49). */
@Composable
private fun EpgTimelinePane(
    entries: List<LocalTv.Entry>,
    epg: Epg?,
    now: Instant,
    hm: DateTimeFormatter,
    isChannelLive: (LocalTv.Entry) -> Boolean?,
    lang: String,
    onSelect: (LocalTv.Entry) -> Unit,
    onWatch: (LocalTv.Entry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(tech.kousozo.tahoetv.core.I18n.get(lang, "desk.timeline_empty"), color = Tahoe.Dim)
        }
        return
    }
    val winStart = remember(now.epochSecond / 1800) { Instant.ofEpochSecond((now.epochSecond / 1800) * 1800 - 1800) }
    val totalMinutes = 300L // 5 hours window
    val pxPerMin = 3.6.dp
    val hScroll = rememberScrollState()

    Column(modifier.clip(RoundedCornerShape(20.dp)).background(Tahoe.Glass).border(1.dp, Tahoe.Stroke, RoundedCornerShape(20.dp)).padding(12.dp)) {
        // Time ruler header
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(150.dp))
            Row(Modifier.weight(1f).horizontalScroll(hScroll)) {
                Box(Modifier.width(pxPerMin * totalMinutes.toFloat()).height(24.dp)) {
                    for (m in 0 until totalMinutes.toInt() step 30) {
                        val t = winStart.plusSeconds(m * 60L)
                        Text(
                            hm.format(t),
                            Modifier.offset(x = pxPerMin * m.toFloat()),
                            fontSize = 11.sp,
                            color = Tahoe.Dim,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(entries, key = { "tl_${it.channel.url}" }) { e ->
                val live = isChannelLive(e)
                val progs = epg?.schedule(e.name, winStart, e.channel.tvgId).orEmpty()
                Row(
                    Modifier.fillMaxWidth().height(54.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.width(150.dp).fillMaxHeight().clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .clickable { onWatch(e) }.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(when (live) { true -> Tahoe.Good; false -> Tahoe.Bad; null -> Tahoe.Dim }))
                        Text(e.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f).horizontalScroll(hScroll)) {
                        Box(Modifier.width(pxPerMin * totalMinutes.toFloat()).fillMaxHeight()) {
                            val nowMin = ((now.epochSecond - winStart.epochSecond) / 60.0).toFloat().coerceIn(0f, totalMinutes.toFloat())
                            val visible = progs.filter { it.stop > winStart && it.start < winStart.plusSeconds(totalMinutes * 60) }
                            if (visible.isEmpty()) {
                                Box(
                                    Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                                        .background(Color.White.copy(alpha = 0.04f))
                                        .clickable { onSelect(e) }.padding(horizontal = 12.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    Text(e.category.label(lang), fontSize = 12.sp, color = Tahoe.Dim)
                                }
                            } else {
                                visible.forEach { p ->
                                    val stMin = ((p.start.epochSecond - winStart.epochSecond) / 60.0).toFloat().coerceIn(0f, totalMinutes.toFloat())
                                    val enMin = ((p.stop.epochSecond - winStart.epochSecond) / 60.0).toFloat().coerceIn(stMin + 8f, totalMinutes.toFloat())
                                    val airing = p.start <= now && p.stop > now
                                    Box(
                                        Modifier.offset(x = pxPerMin * stMin).width(pxPerMin * (enMin - stMin) - 3.dp).fillMaxHeight()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (airing) Tahoe.Accent.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.07f))
                                            .border(1.dp, if (airing) Tahoe.Accent.copy(alpha = 0.55f) else Tahoe.Stroke, RoundedCornerShape(10.dp))
                                            .clickable { if (airing) onWatch(e) else onSelect(e) }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        Column {
                                            Text(p.title, fontSize = 12.sp, fontWeight = if (airing) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${hm.format(p.start)}–${hm.format(p.stop)}", fontSize = 10.sp, color = Tahoe.Dim)
                                        }
                                    }
                                }
                            }
                            Box(Modifier.offset(x = pxPerMin * nowMin).width(2.dp).fillMaxHeight().background(Tahoe.Accent))
                        }
                    }
                }
            }
        }
    }
}
