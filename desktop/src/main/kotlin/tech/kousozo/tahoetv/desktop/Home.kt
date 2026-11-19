@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package tech.kousozo.tahoetv.desktop

import androidx.compose.animation.Crossfade
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
    val lat = Settings.lat; val lon = Settings.lon
    val viewer = Settings.viewer
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 8.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // row 1: hero + side column
        Row(Modifier.fillMaxWidth().height(360.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            HeroCarousel(heroes, Modifier.weight(2.3f).fillMaxHeight(), onLive)
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatCard(tech.kousozo.tahoetv.core.I18n.get(Settings.lang, "home.weather"), weather?.let { "${it.tempC}°" } ?: "—", weather?.label ?: Settings.city, Icons.Rounded.Cloud, Modifier.weight(1f).fillMaxHeight())
                    PrayerCard(next, now, Modifier.weight(1f).fillMaxHeight())
                }
                LiveCard(Modifier.weight(1f).fillMaxWidth(), onLive)
            }
        }

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
                            Text(hItem.source, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Tahoe.Accent)
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
                    ?: Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Tahoe.Accent.copy(alpha = 0.35f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(1100f, 120f), radius = 700f)))
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
                Text(short(it).take(5), fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color(0xFF111114))
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
