@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package tech.kousozo.tahoetv.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
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

/** Beranda as a bento dashboard: trending hero + side cards (weather, prayer countdown, Live TV), a row of watch tiles,
 *  then featured national channels next to the assistant. */
@Composable
fun HomeScreen(onLive: () -> Unit) {
    var now by remember { mutableStateOf(LocalTime.now(Settings.viewer.zone)) } // the viewer's time zone (onboarding)
    LaunchedEffect(Unit) { while (true) { now = LocalTime.now(Settings.viewer.zone); delay(15_000) } }
    var weather by remember { mutableStateOf<Web.Weather?>(null) }
    var prayer by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var heroes by remember { mutableStateOf<List<Web.Hero>>(emptyList()) }
    val viewer = Settings.viewer
    val lat = viewer.lat ?: Settings.lat; val lon = viewer.lon ?: Settings.lon
    // after onboarding: no city = no weather/prayer; prayer only when switched on, with the chosen method and time zone
    val hasCity = !viewer.done || viewer.lat != null
    val lang = Settings.lang
    LaunchedEffect(lat, lon, viewer, Settings.youtube, Settings.tmdb) {
        withContext(Dispatchers.IO) {
            weather = if (hasCity) Web.weather(lat, lon, lang) else null
            prayer = if (hasCity && (!viewer.done || viewer.prayer)) Web.prayer(lat, lon, if (viewer.done) viewer.prayerMethod else 20, viewer.zone, lang) else emptyList()
            val yt = Settings.youtube.takeIf { it.isNotBlank() }?.let { Web.youtubeTrending(it, viewer.country.ifBlank { "ID" }, lang) }.orEmpty()
            val nf = Settings.tmdb.takeIf { it.isNotBlank() }?.let { Web.netflixPopular(it, viewer.country.ifBlank { "ID" }) }.orEmpty()
            heroes = (0 until maxOf(yt.size, nf.size)).flatMap { listOfNotNull(yt.getOrNull(it), nf.getOrNull(it)) }
        }
    }
    val next = prayer.firstOrNull { LocalTime.parse(it.second) > now } ?: prayer.firstOrNull()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 28.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // the Figma Home: hero + channel table on the left, the overview panel on the right
        FigmaHome(onLive, weather, next, now)

        // row 2: watch tiles
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tech.kousozo.tahoetv.core.I18n.get(Settings.lang, "home.watch"), Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(tech.kousozo.tahoetv.core.I18n.get(Settings.lang, "home.opens_browser"), fontSize = 12.sp, color = Tahoe.Faint)
        }
        BoxWithConstraints {
            val cols = if (maxWidth > 1000.dp) 8 else 4
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SERVICES.chunked(cols).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        row.forEach { (name, url, color) -> WatchTile(name, color, Modifier.weight(1f)) { browse(url) } }
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        // row 3: trending strip ("Sedang ramai", #49) + continue watching + recap + featured channels | assistant
        if (heroes.isNotEmpty()) {
            Glass(Modifier.fillMaxWidth(), radius = 22, padding = 16) {
                Text(tech.kousozo.tahoetv.core.I18n.get(lang, "home.trending_now"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    heroes.take(8).forEach { hItem ->
                        Column(
                            Modifier.width(210.dp).clip(RoundedCornerShape(14.dp)).background(Tahoe.CardHi)
                                .clickable { browse(hItem.url) }.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(hItem.source, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Tahoe.Accent)
                            Text(hItem.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(hItem.sub, fontSize = 11.sp, color = Tahoe.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        val recent = Settings.recentChannels
        if (recent.isNotEmpty()) {
            Glass(Modifier.fillMaxWidth(), radius = 22, padding = 16) {
                Text(tech.kousozo.tahoetv.core.I18n.get(lang, "home.continue_watching"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    recent.take(6).forEach { chName ->
                        Box(
                            Modifier.clip(RoundedCornerShape(14.dp)).background(Tahoe.CardHi)
                                .clickable { DeskNav.query = chName; onLive() }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(chName, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Glass(Modifier.weight(1.6f), radius = 26, padding = 20) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tech.kousozo.tahoetv.core.I18n.get(lang, "home.featured_channels"), Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(tech.kousozo.tahoetv.core.I18n.get(lang, "home.see_all"), Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onLive).padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 12.sp, color = Tahoe.Dim)
                }
                val homeCountry = Settings.countryCode
                val countryLabel = tech.kousozo.tahoetv.core.Region.name(homeCountry, lang)
                val featuredNames = remember(homeCountry) {
                    tech.kousozo.tahoetv.core.CountryCatalog.featuredNames(homeCountry, 7)
                        .ifEmpty { tech.kousozo.tahoetv.core.LocalTv.PRIORITY.take(7).map { PRETTY[it] ?: it } }
                }
                var selectedFeatured by remember(homeCountry) { mutableStateOf(0) }
                featuredNames.forEachIndexed { i, rawName ->
                    val name = PRETTY[rawName.lowercase()] ?: rawName
                    val isSelected = selectedFeatured == i
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(if (isSelected) Tahoe.Accent.copy(alpha = 0.14f) else Color.Transparent)
                            .border(
                                if (isSelected) (if (ThemePrefs.highContrast) 2.dp else 1.dp) else 0.dp,
                                if (isSelected) Tahoe.Accent.copy(alpha = 0.55f) else Color.Transparent,
                                RoundedCornerShape(14.dp),
                            )
                            .combinedClickable(
                                onDoubleClick = { DeskNav.query = name; onLive() },
                                onClick = {
                                    if (isSelected) { DeskNav.query = name; onLive() }
                                    else selectedFeatured = i
                                },
                            )
                            .padding(vertical = 8.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("%02d".format(i + 1), Modifier.width(34.dp), fontSize = 13.sp, color = if (isSelected) Tahoe.Accent else Tahoe.Faint, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                            Text(short(name), color = Color(0xFF111114), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, fontSize = 15.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium)
                            Text(
                                if (isSelected) tech.kousozo.tahoetv.core.I18n.get(lang, "desk.live_preview") + " · " + countryLabel
                                else tech.kousozo.tahoetv.core.I18n.get(lang, "live.featured_live", countryLabel),
                                fontSize = 12.sp,
                                color = if (isSelected) Tahoe.Accent else Tahoe.Faint,
                            )
                        }
                        Box(
                            Modifier.size(36.dp).clip(CircleShape)
                                .background(if (isSelected) Tahoe.Accent else Tahoe.CardHi)
                                .clickable { DeskNav.query = name; onLive() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.PlayArrow, tech.kousozo.tahoetv.core.I18n.get(lang, "home.watch"), tint = if (isSelected) Tahoe.OnAccent else Tahoe.Text, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val recap = remember(lang) {
                    tech.kousozo.tahoetv.core.WatchRecap.summarize(
                        java.io.File(System.getProperty("user.home"), ".tahoetv/recap"),
                        "main", tech.kousozo.tahoetv.core.WatchRecap.currentMonth(), lang,
                    )
                }
                Glass(Modifier.fillMaxWidth(), radius = 22, padding = 16) {
                    Text(tech.kousozo.tahoetv.core.I18n.get(lang, "recap.title"), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Tahoe.Accent)
                    Spacer(Modifier.height(4.dp))
                    Text(recap.tagline, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${recap.totalHoursDecimal} · ${tech.kousozo.tahoetv.core.I18n.get(lang, "recap.sessions", recap.sessionCount)}", fontSize = 12.sp, color = Tahoe.Dim)
                    if (recap.topChannels.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(recap.topChannels.take(3).joinToString(" · ") { "${it.name} (${it.minutes}m)" }, fontSize = 12.sp, color = Tahoe.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(tech.kousozo.tahoetv.core.I18n.get(Settings.lang, "home.ask_tahoe"), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                AssistantPanel()
            }
        }
    }
}

/** Logo-tile text: short names as-is, otherwise word initials plus any number ("Trans TV" → TTV, "Trans7" → T7). */
private fun short(name: String): String {
    if (name.length <= 5) return name.uppercase()
    val words = name.split(' ').filter { it.isNotBlank() }
    val digits = name.filter(Char::isDigit)
    return if (words.size > 1) words.joinToString("") { it.take(1) }.uppercase() + digits.takeIf { words.none { w -> w.any(Char::isDigit) } }.orEmpty()
    else if (digits.isNotEmpty()) name.take(1).uppercase() + digits else name.take(4).uppercase()
}

private val PRETTY = mapOf("trans tv" to "Trans TV", "trans7" to "Trans7", "rcti" to "RCTI", "mnctv" to "MNCTV", "gtv" to "GTV",
    "sctv" to "SCTV", "antv" to "ANTV", "indosiar" to "Indosiar", "tvone" to "tvOne", "btv" to "BTV", "rtv" to "RTV",
    "cnn indonesia" to "CNN Indonesia", "cnbc indonesia" to "CNBC Indonesia")

/** Big trending card: text block left (eyebrow, title, meta, white "Tonton sekarang" pill with a dark play disc,
 *  secondary pill), artwork right fading into the card, dots bottom-right. */
@Composable
private fun HeroCarousel(heroes: List<Web.Hero>, modifier: Modifier, onLive: () -> Unit) {
    val lang = Settings.lang
    var i by remember(heroes) { mutableStateOf(0) }
    LaunchedEffect(heroes) { while (heroes.isNotEmpty()) { delay(8000); i = (i + 1) % heroes.size } }
    Box(modifier.clip(RoundedCornerShape(30.dp)).background(Tahoe.Card)) {
        val h = heroes.getOrNull(i % heroes.size.coerceAtLeast(1))
        Crossfade(
            h,
            animationSpec = tween(if (ThemePrefs.reduceMotion) 180 else tech.kousozo.tahoetv.core.Brand.MOTION_MED, easing = EaseInOutTahoe),
            label = "hero",
        ) { cur ->
            Box(Modifier.fillMaxSize()) {
                cur?.let { c -> rememberImage(c.image)?.let { Image(it, null, Modifier.fillMaxHeight().fillMaxWidth(0.68f).align(Alignment.CenterEnd), contentScale = ContentScale.Crop) } }
                    ?: HeroAurora()
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Tahoe.Card, 0.38f to Tahoe.Card.copy(alpha = 0.92f), 0.7f to Color.Transparent)))
                Column(Modifier.align(Alignment.CenterStart).padding(start = 32.dp, end = 24.dp).widthIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (cur != null) tech.kousozo.tahoetv.core.I18n.get(lang, "home.trending_on", cur.source) else "Tahoe TV", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(if (cur != null) tech.kousozo.tahoetv.core.I18n.get(lang, "home.today_num", i % heroes.size + 1) else tech.kousozo.tahoetv.core.I18n.get(lang, "home.tagline"), fontSize = 12.sp, color = Tahoe.Faint)
                    Spacer(Modifier.height(8.dp))
                    Text(cur?.title ?: tech.kousozo.tahoetv.core.I18n.get(lang, "home.hero_default"), fontSize = 36.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(cur?.sub ?: tech.kousozo.tahoetv.core.I18n.get(lang, "home.hero_sub_default"), fontSize = 13.sp, color = Tahoe.Dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.height(48.dp).clip(RoundedCornerShape(50)).background(Color.White).clickable { if (cur != null) browse(cur.url) else onLive() }
                            .padding(start = 20.dp, end = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (cur != null) tech.kousozo.tahoetv.core.I18n.get(lang, "home.watch_now") else tech.kousozo.tahoetv.core.I18n.get(lang, "home.open_live_tv"), color = Color(0xFF111114), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Spacer(Modifier.width(14.dp))
                            Box(Modifier.size(38.dp).clip(CircleShape).background(Color(0xFF111114)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                        }
                        Session.tv?.let { tv -> if (cur != null) Box(Modifier.height(48.dp).clip(RoundedCornerShape(50)).background(Tahoe.CardHi)
                            .clickable { Thread { runCatching { tv.sh("am start -a android.intent.action.VIEW -d " + tech.kousozo.tahoetv.core.TvAdb.quote(cur.url)) } }.start() }
                            .padding(horizontal = 20.dp), contentAlignment = Alignment.Center) { Text(tech.kousozo.tahoetv.core.I18n.get(lang, "home.play_on_tv"), fontSize = 14.sp) } }
                    }
                }
            }
        }
        if (heroes.size > 1) Row(Modifier.align(Alignment.BottomEnd).padding(22.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            heroes.indices.take(10).forEach { k ->
                Box(Modifier.size(if (k == i) 22.dp else 7.dp, 7.dp).clip(CircleShape).background(if (k == i) Tahoe.Accent else Tahoe.Faint).clickable { i = k })
            }
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, sub: String, icon: ImageVector, modifier: Modifier) =
    Glass(modifier, radius = 26, padding = 18) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 14.sp, color = Tahoe.Dim)
            Icon(icon, null, tint = Tahoe.Accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text(sub, fontSize = 12.sp, color = Tahoe.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

/** Next prayer with a ring that fills as the time approaches (last 3 hours). */
@Composable
private fun PrayerCard(next: Pair<String, String>?, now: LocalTime, modifier: Modifier) = Glass(modifier, radius = 26, padding = 18) {
    val lang = Settings.lang
    Text(tech.kousozo.tahoetv.core.I18n.get(lang, "home.prayer_card"), fontSize = 14.sp, color = Tahoe.Dim)
    val mins = next?.let { val t = LocalTime.parse(it.second); ((t.toSecondOfDay() - now.toSecondOfDay() + 86_400) % 86_400) / 60 } ?: 0
    val f = (1f - mins / 180f).coerceIn(0.03f, 1f)
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(78.dp)) {
            drawArc(Color.White.copy(alpha = 0.08f), 135f, 270f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(9.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
            drawArc(Tahoe.Accent, 135f, 270f * f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(9.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        Text(next?.second ?: "—", fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
    Text(next?.let { tech.kousozo.tahoetv.core.I18n.get(lang, "home.prayer_in_hm", it.first, mins / 60, mins % 60) }
        ?: tech.kousozo.tahoetv.core.I18n.get(lang, "home.prayer_loading"), fontSize = 12.sp, color = Tahoe.Faint, maxLines = 1)
}

/** Accent card that opens TV Live. */
@Composable
private fun LiveCard(modifier: Modifier, onLive: () -> Unit) = Box(
    modifier.clip(RoundedCornerShape(26.dp))
        .background(Brush.linearGradient(listOf(Tahoe.Accent, Color(0xFF2E7BE0))))
        .clickable(onClick = onLive).padding(20.dp),
) {
    val lang = Settings.lang
    val cc = Settings.countryCode
    val chips = remember(cc) {
        tech.kousozo.tahoetv.core.CountryCatalog.featuredNames(cc, 4).ifEmpty { listOf("TRANS", "RCTI", "SCTV", "TVRI") }
    }
    Column(Modifier.align(Alignment.TopStart)) {
        Text(tech.kousozo.tahoetv.core.I18n.get(lang, "nav.live"), color = Tahoe.OnAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(tech.kousozo.tahoetv.core.CountryCatalog.featuredSubtitle(emptyList(), cc, lang), color = Tahoe.OnAccent.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Row(Modifier.align(Alignment.BottomStart), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach {
            Box(Modifier.size(38.dp).clip(CircleShape).background(Color.White).border(2.dp, Tahoe.Accent, CircleShape), contentAlignment = Alignment.Center) {
                Text(short(it).take(5), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111114))
            }
        }
    }
    Box(Modifier.align(Alignment.BottomEnd).size(46.dp).clip(CircleShape).background(Tahoe.OnAccent), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.PlayArrow, tech.kousozo.tahoetv.core.I18n.get(lang, "home.open_live_tv"), tint = Tahoe.Accent)
    }
}

/** "Playlist"-style tile: colour wash, name top-left, play disc bottom-right. */
@Composable
private fun WatchTile(name: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hover by src.collectIsHoveredAsState()
    val s by androidx.compose.animation.core.animateFloatAsState(
        if (hover && !ThemePrefs.reduceMotion) 1.03f else 1f,
        animationSpec = tween(if (ThemePrefs.reduceMotion) 0 else tech.kousozo.tahoetv.core.Brand.MOTION_FAST, easing = EaseOutTahoe),
        label = "tile",
    )
    Box(modifier.aspectRatio(1.15f).scale(s).clip(RoundedCornerShape(22.dp))
        .background(Brush.linearGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.35f), Tahoe.Card)))
        .hoverable(src).clickable(onClick = onClick).padding(14.dp)) {
        Text(name, Modifier.align(Alignment.TopStart), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(tech.kousozo.tahoetv.core.I18n.get(Settings.lang, "common.open"), Modifier.align(Alignment.BottomStart), fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f))
        Box(Modifier.align(Alignment.BottomEnd).size(34.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
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
    val lang = Settings.lang
    fun send() {
        val question = q.trim().ifEmpty { return }
        if (Settings.gemini.isBlank()) { answer = tech.kousozo.tahoetv.core.I18n.get(lang, "home.set_gemini_key"); return }
        busy = true; q = ""
        scope.launch {
            val a = withContext(Dispatchers.IO) {
                runCatching { Web.ask(Settings.gemini, Settings.geminiModel, question, Settings.city, history.takeLast(6), Settings.lang) }.getOrElse { "${tech.kousozo.tahoetv.core.I18n.get(lang, "pair.failed")}: ${it.message}" }
            }
            history += "user" to question; history += "model" to a
            answer = a; busy = false
        }
    }
    Glass(Modifier.fillMaxWidth()) {
        Field(q, { q = it }, tech.kousozo.tahoetv.core.I18n.get(lang, "home.ask_placeholder"),
            Modifier.fillMaxWidth().onEnter { send() },
            trailing = { IconButton(::send, enabled = !busy) { Icon(Icons.AutoMirrored.Rounded.Send, tech.kousozo.tahoetv.core.I18n.get(lang, "common.send"), tint = Tahoe.Accent) } })
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Tahoe.Accent, trackColor = Tahoe.Glass)
        answer?.let { Text(it, fontSize = 15.sp, lineHeight = 22.sp, color = Tahoe.Text) }
    }
}

fun Modifier.onEnter(action: () -> Unit) = onPreviewKeyEvent {
    if (it.key == Key.Enter && it.type == KeyEventType.KeyDown) { action(); true } else false
}

/** Slow drifting glows behind the hero when there is no picture; static with "Kurangi gerakan". */
@Composable
private fun HeroAurora() {
    val t by if (ThemePrefs.reduceMotion) remember { mutableStateOf(0f) } else rememberInfiniteTransition(label = "aurora")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Reverse), label = "t")
    Canvas(Modifier.fillMaxSize()) {
        val a = 2f * Math.PI.toFloat() * t
        fun glow(cx: Float, cy: Float, r: Float, c: Color) =
            drawCircle(Brush.radialGradient(listOf(c, Color.Transparent), androidx.compose.ui.geometry.Offset(cx, cy), r), r, androidx.compose.ui.geometry.Offset(cx, cy))
        glow(size.width * (0.72f + 0.12f * kotlin.math.cos(a)), size.height * (0.3f + 0.15f * kotlin.math.sin(a)), size.width * 0.5f, Tahoe.Accent.copy(alpha = 0.38f))
        glow(size.width * (0.9f - 0.15f * kotlin.math.sin(a)), size.height * (0.85f - 0.12f * kotlin.math.cos(a)), size.width * 0.4f, Color(0xFF8A5CF6).copy(alpha = 0.25f))
    }
}


// ---------------------------------------------------------------------------------------------------------------
// Figma "Desktop / Home": hero for the selected channel, the "Sedang tayang" table, and the Ringkasan panel.

private class OnAir(val entry: tech.kousozo.tahoetv.core.LocalTv.Entry, val programme: tech.kousozo.tahoetv.core.Epg.Programme?)

@Composable
private fun FigmaHome(onLive: () -> Unit, weather: Web.Weather?, next: Pair<String, String>?, now: LocalTime) {
    val lang = Settings.lang
    val viewer = Settings.viewer
    var rows by remember { mutableStateOf<List<OnAir>>(emptyList()) }
    var counts by remember { mutableStateOf(0 to 0) } // channels, live
    var selected by remember { mutableStateOf(0) }
    val hm = remember(viewer.zone) { DateTimeFormatter.ofPattern("HH.mm").withZone(viewer.zone) }
    LaunchedEffect(Settings.playlist, viewer.countries) {
        withContext(Dispatchers.IO) {
            val channels = runCatching { tech.kousozo.tahoetv.core.Sources.loadChannels(Settings.playlist, viewer.countries, viewer.channelLanguages) }.getOrNull().orEmpty()
            if (channels.isEmpty()) return@withContext
            val arranged = viewer.arrange(tech.kousozo.tahoetv.core.LocalTv.arrange(channels, Settings.nearProvince, viewer.countries, viewer.sort, viewer.city, viewer.country))
            val top = arranged.take(4)
            val live = arranged.count { tech.kousozo.tahoetv.core.SourceHealth.status(it.channel.url)?.live != false }
            rows = top.map { OnAir(it, null) }
            counts = arranged.size to live
            val ottEpg = tech.kousozo.tahoetv.core.OttEngine.lastEpgUrl
            val defaultGuides = (listOfNotNull(ottEpg) + viewer.countries.flatMap { tech.kousozo.tahoetv.core.Region.guides(it) }).distinct()
            val urls = Settings.epg.ifBlank { null }?.let(::listOf) ?: defaultGuides
            val epg = runCatching { tech.kousozo.tahoetv.core.Epg.loadPriority(urls, top.flatMap { listOfNotNull(it.name, it.channel.tvgId) }.toSet()) }.getOrNull()
            if (epg != null) {
                val t = java.time.Instant.now()
                rows = top.map { e ->
                    val s = epg.schedule(e.name, t, e.channel.tvgId)
                    OnAir(e, s.firstOrNull()?.takeIf { !it.start.isAfter(t) })
                }
            }
        }
    }
    val cur = rows.getOrNull(selected)
    Row(Modifier.fillMaxWidth().height(700.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f)) {
            // hero
            Text(cur?.programme?.title ?: cur?.entry?.name ?: "Tahoe TV", fontSize = 56.sp, fontWeight = FontWeight.Bold, lineHeight = 59.sp, letterSpacing = (-1.1).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LivePill()
                if (cur != null) Text(cur.entry.name + (cur.programme?.let { " · " + hm.format(it.start) + "–" + hm.format(it.stop) } ?: ""),
                    Modifier.clip(RoundedCornerShape(50)).border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.09f)).padding(horizontal = 10.dp, vertical = 5.dp),
                    fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Tahoe.Dim)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.clip(RoundedCornerShape(50)).background(Tahoe.Text).clickable { cur?.let { DeskNav.query = it.entry.name }; onLive() }.padding(start = 20.dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color(0xFF0D0F14), modifier = Modifier.size(18.dp))
                    Text(tech.kousozo.tahoetv.core.I18n.get(lang, "home.watch"), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFF0D0F14))
                }
                GlassPill(tech.kousozo.tahoetv.core.I18n.get(lang, "desk.hm_multi")) { onLive() }
                GlassPill(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.live_record_btn")) { cur?.let { DeskNav.query = it.entry.name }; onLive() }
            }
            Spacer(Modifier.height(48.dp))
            // table
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tech.kousozo.tahoetv.core.I18n.get(lang, "desk.hm_on_air"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
                Text(tech.kousozo.tahoetv.core.I18n.get(lang, "desk.hm_counts", counts.first, counts.second), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Tahoe.Accent)
                Spacer(Modifier.weight(1f))
                Row(Modifier.clip(RoundedCornerShape(50)).border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.09f)).padding(4.dp)) {
                    listOf("desk.hm_list", "desk.hm_grid", "desk.hm_timeline").forEachIndexed { i, k ->
                        Text(tech.kousozo.tahoetv.core.I18n.get(lang, k),
                            Modifier.clip(RoundedCornerShape(50)).background(if (i == 0) Tahoe.Text else Color.Transparent).clickable { if (i > 0) onLive() }.padding(horizontal = 14.dp, vertical = 6.dp),
                            fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (i == 0) Color(0xFF0D0F14) else Tahoe.Dim)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.2f)))
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                listOf("desk.hm_no" to 0.5f, "desk.hm_channel" to 2.2f, "desk.hm_programme" to 2.6f, "desk.hm_sources" to 1.4f, "desk.hm_status" to 1.1f).forEach { (k, w) ->
                    Text(tech.kousozo.tahoetv.core.I18n.get(lang, k), Modifier.weight(w), fontSize = 12.sp, color = Tahoe.Faint, maxLines = 1)
                }
            }
            rows.forEachIndexed { i, r ->
                val on = i == selected
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) Color.White.copy(alpha = 0.07f) else Color.Transparent)
                    .combinedClickable(onDoubleClick = { DeskNav.query = r.entry.name; onLive() }, onClick = { if (on) { DeskNav.query = r.entry.name; onLive() } else selected = i })
                    .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("%02d".format(i + 1), Modifier.weight(0.5f), fontSize = 16.sp, color = Tahoe.Faint, maxLines = 1)
                    Row(Modifier.weight(2.2f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(28.dp, 20.dp).clip(RoundedCornerShape(6.dp)).background(Brush.linearGradient(listOf(tint(r.entry.name, 0.45f), tint(r.entry.name, 1f)))))
                        Text(r.entry.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(r.programme?.title ?: "", Modifier.weight(2.6f), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(tech.kousozo.tahoetv.core.I18n.get(lang, "desk.hm_src_fmt", 1 + r.entry.alternates.size), Modifier.weight(1.4f), fontSize = 16.sp, maxLines = 1)
                    val alive = tech.kousozo.tahoetv.core.SourceHealth.status(r.entry.channel.url)?.live != false
                    Box(Modifier.weight(1.1f)) { if (alive) LivePill() else Text(tech.kousozo.tahoetv.core.I18n.get(lang, "desk.hm_unavailable"), fontSize = 14.sp, color = Tahoe.Faint) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(tech.kousozo.tahoetv.core.I18n.get(lang, "desk.hm_click"), fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
        }
        OverviewPanel(cur, weather, next, now, onLive)
    }
}

private fun tint(name: String, f: Float): Color {
    val h = name.hashCode()
    return Color(red = (((h shr 16) and 255) / 255f * 0.7f + 0.15f) * f, green = (((h shr 8) and 255) / 255f * 0.7f + 0.15f) * f, blue = ((h and 255) / 255f * 0.7f + 0.2f) * f, alpha = 1f)
}

@Composable
private fun LivePill() = Row(
    Modifier.clip(RoundedCornerShape(50)).background(LiveFill).padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
) {
    Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
    Text("LIVE", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White, letterSpacing = 1.3.sp)
}

@Composable
private fun GlassPill(label: String, onClick: () -> Unit) = Text(
    label,
    Modifier.clip(RoundedCornerShape(50)).border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.09f)).clickable(onClick = onClick)
        .padding(start = 20.dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
    fontSize = 16.sp, fontWeight = FontWeight.Medium,
)

@Composable
private fun OverviewPanel(cur: OnAir?, weather: Web.Weather?, next: Pair<String, String>?, now: LocalTime, onLive: () -> Unit) {
    val lang = Settings.lang
    Column(
        Modifier.width(376.dp).fillMaxHeight().clip(RoundedCornerShape(28.dp)).background(Tahoe.Card).border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(28.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(tech.kousozo.tahoetv.core.I18n.get(lang, "desk.hm_overview"), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        // a real picture of what is on: the programme's own image from the web (Wikipedia / image search), the channel's colours until it loads
        val shotUrl by produceState<String?>(null, cur?.entry?.name, cur?.programme?.title) {
            val title = cur?.programme?.title
            value = if (title.isNullOrBlank() || cur == null) null
            else withContext(Dispatchers.IO) { runCatching { tech.kousozo.tahoetv.core.MediaScraper.programmeThumbnail(title, cur.entry.name) }.getOrNull() }
        }
        val shot = rememberImage(shotUrl)
        val logoUrl by produceState(cur?.entry?.channel?.logo, cur?.entry?.name) {
            val e = cur?.entry
            if (e != null && value.isNullOrBlank()) value = withContext(Dispatchers.IO) { runCatching { tech.kousozo.tahoetv.core.MediaScraper.channelLogo(e.name, e.channel.logo) }.getOrNull() }
        }
        val logo = rememberImage(logoUrl)
        val airFrac = cur?.programme?.let { p ->
            val span = (p.stop.epochSecond - p.start.epochSecond).coerceAtLeast(1)
            ((java.time.Instant.now().epochSecond - p.start.epochSecond).toFloat() / span).coerceIn(0f, 1f)
        }
        Box(Modifier.fillMaxWidth().height(189.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onLive)
            .background(Brush.linearGradient(listOf(tint(cur?.entry?.name ?: "Tahoe", 0.5f), tint(cur?.entry?.name ?: "Tahoe", 1f))))) {
            if (shot != null) {
                Image(shot, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))))
            }
            Box(Modifier.padding(12.dp)) { LivePill() }
            if (shot == null) {
                if (logo != null) Box(Modifier.align(Alignment.Center).size(width = 140.dp, height = 76.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.92f)), contentAlignment = Alignment.Center) {
                    Image(logo, null, Modifier.padding(10.dp).fillMaxSize(), contentScale = ContentScale.Fit)
                } else Text(cur?.entry?.name ?: "", Modifier.align(Alignment.Center), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.9f))
            }
            if (airFrac != null) Box(Modifier.align(Alignment.BottomStart).padding(12.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.2f))) {
                Box(Modifier.fillMaxWidth(airFrac.coerceAtLeast(0.02f)).fillMaxHeight().background(Tahoe.Accent))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tech.kousozo.tahoetv.core.I18n.get(lang, "desk.hm_channel_name"), fontSize = 12.sp, color = Tahoe.Faint)
            Text(cur?.entry?.name ?: "—", fontSize = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().height(120.dp)) {
            StatCard(tech.kousozo.tahoetv.core.I18n.get(lang, "home.weather"), weather?.let { "${it.tempC}°" } ?: "—", weather?.label ?: Settings.city, Icons.Rounded.Cloud, Modifier.weight(1f).fillMaxHeight())
            run {
                val prayerOn = Settings.viewer.let { !it.done || it.prayer } // switched off in onboarding: say so instead of a dash
                StatCard(tech.kousozo.tahoetv.core.I18n.get(lang, "home.prayer_card"), next?.second ?: tech.kousozo.tahoetv.core.I18n.get(lang, if (prayerOn) "home.prayer_loading" else "common.off"), next?.first.orEmpty(), Icons.Rounded.Bedtime, Modifier.weight(1f).fillMaxHeight())
            }
        }
        Column(
            Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(22.dp)).background(Brush.verticalGradient(listOf(Color(0xFFD91A26), Color(0xFF8C0D1A)))).clickable(onClick = onLive).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
        ) {
            Text("#1", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.35f), letterSpacing = (-1.1).sp)
            Text(tech.kousozo.tahoetv.core.I18n.get(lang, "desk.hm_top"), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.8f))
            Text(cur?.let { (it.programme?.title ?: it.entry.name) + " · " + it.entry.name } ?: "", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
