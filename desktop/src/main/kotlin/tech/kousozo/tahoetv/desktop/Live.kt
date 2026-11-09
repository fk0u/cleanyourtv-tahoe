package tech.kousozo.tahoetv.desktop

import androidx.compose.foundation.Image
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.draw.alpha
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
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf<String?>(null) } // null = Semua, "★" = favorites, else a LocalTv.Category name
    var playing by remember { mutableStateOf<M3u.Channel?>(null) }
    var favs by remember { mutableStateOf(Settings.favorites) }
    var epg by remember { mutableStateOf<Epg?>(null) }
    var tick by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick = Instant.now() } }
    val epgUrl = Settings.epg
    LaunchedEffect(epgUrl) { epg = null; epg = withContext(Dispatchers.IO) { runCatching { Epg.load(epgUrl.ifBlank { null }?.let(::listOf) ?: Sources.EPG) }.getOrNull() } }
    val playlist = Settings.playlist
    var alive by remember { mutableStateOf<Set<String>?>(null) }   // null = still checking
    LaunchedEffect(playlist) {
        channels = null; error = null
        alive = null
        withContext(Dispatchers.IO) { runCatching { Sources.load(playlist) } }.fold({ channels = it }, { error = "Playlist tidak bisa dimuat: ${it.message}" })
        channels?.let { list -> alive = withContext(Dispatchers.IO) { Sources.alive(list, 8000) } }
    }
    val province = Settings.province
    val entries = remember(channels, province) { LocalTv.arrange(channels.orEmpty(), province) }
    val names = remember(entries) { entries.associate { it.channel.url to it.name } }
    val byUrl = remember(entries) { entries.associateBy { it.channel.url } }
    fun playable(e: LocalTv.Entry) = alive?.let { ok -> (listOf(e.channel) + e.alternates).any { it.url in ok } } ?: true
    val onAir = entries.count(::playable)
    val cats = remember(entries) { LocalTv.Category.entries.filter { c -> entries.any { LocalTv.inCategory(it, c) } } }
    val shown = entries
        .filter { query.isBlank() || it.name.contains(query, true) }
        .filter { e -> when (group) { null -> true; "★" -> e.channel.url in favs; else -> LocalTv.inCategory(e, LocalTv.Category.valueOf(group!!)) } }
        .sortedByDescending { it.channel.url in favs }

    var selected by remember { mutableStateOf<LocalTv.Entry?>(null) }   // focused card → detail panel
    var status by remember { mutableStateOf<String?>(null) }
    val list by rememberUpdatedState(shown)   // channel up/down follows what's on screen
    LaunchedEffect(playing) { playing?.let { ch -> val e = byUrl[ch.url]
        PlayerWindow.play(listOf(ch) + e?.alternates.orEmpty(), names[ch.url] ?: ch.name, e?.let { en -> epg?.schedule(en.name, Instant.now()) }.orEmpty(),
            onSwitch = { d -> javax.swing.SwingUtilities.invokeLater {
                if (list.isNotEmpty()) {
                    val i = list.indexOfFirst { it.channel.url == playing?.url }
                    val next = list[((if (i < 0) 0 else i + d) % list.size + list.size) % list.size]
                    playing = next.channel; selected = next
                }
            } }) { s -> status = s; if (s == null) alive = alive?.plus(ch.url) } } } // it plays → it's on air
    fun watch(e: LocalTv.Entry) { selected = e; playing = e.channel }

    Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 20.dp, top = 20.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        // ---------------------------------------------------------------- rail: title, search, categories
        Column(Modifier.width(230.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Title("TV Live", channels?.let {
                (if (alive == null) "Memeriksa siaran…" else "$onAir mengudara") + (province.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            } ?: error ?: "Memuat saluran…")
            Field(query, { query = it }, "Cari saluran", trailing = { Icon(Icons.Rounded.Search, null, tint = Tahoe.Faint) })
            Spacer(Modifier.height(6.dp))
            if (channels == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Tahoe.Accent, trackColor = Tahoe.Glass)
            val rows = listOf<Triple<String?, String, Int>>(Triple(null, "Semua", entries.size)) +
                cats.map { c -> Triple(c.name, c.label, entries.count { LocalTv.inCategory(it, c) }) } +
                Triple("★", "Favorit", entries.count { it.channel.url in favs })
            rows.forEach { (key, label, n) ->
                val on = key == group
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) Color.White.copy(alpha = 0.13f) else Color.Transparent)
                    .clickable { group = key }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(if (on) Tahoe.Accent.copy(alpha = 0.25f) else Tahoe.Glass), contentAlignment = Alignment.Center) {
                        Icon(categoryIcon(key), null, tint = if (on) Tahoe.Accent else Tahoe.Dim, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(label, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                        Text("$n saluran", fontSize = 11.sp, color = Tahoe.Faint)
                    }
                }
            }
        }

        // ---------------------------------------------------------------- channel cards
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(shown, key = { it.channel.url }) { e ->
                val ch = e.channel
                val focused = e == selected
                val on = ch == playing
                val scale by animateFloatAsState(if (focused) 1.02f else 1f, label = "card")
                val prog = epg?.schedule(e.name, tick)?.firstOrNull()?.takeIf { it.start <= tick }
                Row(
                    Modifier.fillMaxWidth().scale(scale).alpha(if (on || playable(e)) 1f else 0.5f).clip(RoundedCornerShape(18.dp))
                        .background(if (focused) Color.White.copy(alpha = 0.14f) else Tahoe.Glass)
                        .border(1.dp, if (focused) Color.White.copy(alpha = 0.35f) else Tahoe.Stroke, RoundedCornerShape(18.dp))
                        .clickable { if (focused) watch(e) else selected = e }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(110.dp, 72.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.92f)), contentAlignment = Alignment.Center) {
                        rememberImage(ch.logo)?.let { Image(it, null, Modifier.padding(8.dp).fillMaxSize(), contentScale = ContentScale.Fit) }
                            ?: Text(e.name.take(3).uppercase(), color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(e.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(prog?.title ?: e.category.label, fontSize = 13.sp, color = Tahoe.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            if (on) Badge("LIVE", Color(0xFFFF453A), filled = true)
                            if (prog != null) Badge("EPG", Tahoe.Text)
                            if (e.alternates.isNotEmpty()) Badge("${e.alternates.size + 1} sumber", Tahoe.Text)
                            if (e.near) Badge("Dekat", Tahoe.Accent)
                            if (e.partTime) Badge("Tidak 24 jam", Tahoe.Faint)
                            if (!playable(e) && !on) Badge("Offline", Tahoe.Bad)
                        }
                    }
                    val fav = ch.url in favs
                    IconButton({ favs = if (fav) favs - ch.url else favs + ch.url; Settings.favorites = favs }) {
                        Icon(if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favorit", tint = if (fav) Color(0xFFFF9F0A) else Tahoe.Faint)
                    }
                }
            }
        }

        // ---------------------------------------------------------------- detail panel (now playing on the focused channel)
        Detail(selected ?: shown.firstOrNull(), epg, tick, playingUrl = playing?.url, status = status,
            onWatch = ::watch, onStop = { PlayerWindow.stop(); playing = null })
    }
}

/** Right-hand panel: the channel's logo as soft backdrop, "Sedang tayang" with description and progress, actions, and what's next. */
@Composable
private fun Detail(e: LocalTv.Entry?, epg: Epg?, now: Instant, playingUrl: String?, status: String?, onWatch: (LocalTv.Entry) -> Unit, onStop: () -> Unit) {
    Box(Modifier.width(400.dp).fillMaxHeight().clip(RoundedCornerShape(26.dp)).background(Color(0xFF111118)).border(1.dp, Tahoe.Stroke, RoundedCornerShape(26.dp))) {
        if (e == null) { Text("Pilih saluran", Modifier.align(Alignment.Center), color = Tahoe.Dim); return@Box }
        val logo = rememberImage(e.channel.logo)
        if (logo != null) Image(logo, null, Modifier.fillMaxWidth().height(260.dp).blur(28.dp).alpha(0.55f), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.25f to Color(0x66111118), 0.45f to Color(0xFF111118))))
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(150.dp, 96.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.94f)), contentAlignment = Alignment.Center) {
                if (logo != null) Image(logo, null, Modifier.padding(12.dp).fillMaxSize(), contentScale = ContentScale.Fit)
                else Text(e.name, color = Color.Black, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(20.dp))
            val s = epg?.schedule(e.name, now).orEmpty()
            val cur = s.firstOrNull()?.takeIf { it.start <= now }
            Text(if (cur != null) "SEDANG TAYANG" else e.category.label.uppercase(), fontSize = 11.sp, letterSpacing = 1.2.sp, color = Tahoe.Faint, fontWeight = FontWeight.SemiBold)
            Text(cur?.title ?: e.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 31.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            cur?.desc?.let { Text(it, fontSize = 13.sp, color = Tahoe.Dim, lineHeight = 19.sp, maxLines = 4, overflow = TextOverflow.Ellipsis) }
            cur?.let { p ->
                val f = ((now.epochSecond - p.start.epochSecond).toFloat() / (p.stop.epochSecond - p.start.epochSecond).coerceAtLeast(1)).coerceIn(0f, 1f)
                LinearProgressIndicator({ f }, Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp).clip(RoundedCornerShape(2.dp)), color = Tahoe.Text, trackColor = Tahoe.Stroke)
                Row { Text(HM.format(p.start), fontSize = 12.sp, color = Tahoe.Faint); Spacer(Modifier.weight(1f)); Text(HM.format(p.stop), fontSize = 12.sp, color = Tahoe.Faint) }
            }
            val isPlaying = e.channel.url == playingUrl
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isPlaying) PrimaryButton("Tonton", Icons.Rounded.PlayArrow) { onWatch(e) }
                else { PrimaryButton("Tampilkan pemutar", Icons.Rounded.PictureInPicture) { PlayerWindow.show() }; GhostButton("Stop", Icons.Rounded.Stop, onClick = onStop) }
            }
            if (isPlaying && status != null) Text(status, fontSize = 12.sp, color = Tahoe.Bad)
            val next = s.filter { it.start > now }.take(4)
            if (next.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("BERIKUTNYA", fontSize = 11.sp, letterSpacing = 1.2.sp, color = Tahoe.Faint, fontWeight = FontWeight.SemiBold)
                next.forEach { p -> Row { Text(HM.format(p.start), Modifier.width(52.dp), fontSize = 13.sp, color = Tahoe.Dim); Text(p.title, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
            }
        }
    }
}

private fun categoryIcon(key: String?) = when (key) {
    null -> Icons.Rounded.GridView; "★" -> Icons.Rounded.Star
    "Near" -> Icons.Rounded.Place; "National" -> Icons.Rounded.Flag; "Tvri" -> Icons.Rounded.CellTower; "Local" -> Icons.Rounded.LocationCity
    "News" -> Icons.Rounded.Newspaper; "Religious" -> Icons.Rounded.Mosque; "Kids" -> Icons.Rounded.ChildCare; else -> Icons.Rounded.Public
}

@Composable
private fun Badge(text: String, color: Color, filled: Boolean = false) = Text(text, Modifier.clip(RoundedCornerShape(5.dp))
    .background(if (filled) color else color.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 2.dp),
    color = if (filled) Color.White else color, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, letterSpacing = 0.4.sp)

private val HM = DateTimeFormatter.ofPattern("HH.mm").withZone(ZoneId.systemDefault())
