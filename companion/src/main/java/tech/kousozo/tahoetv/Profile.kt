package tech.kousozo.tahoetv

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import tech.kousozo.tahoetv.core.Brand
import tech.kousozo.tahoetv.core.I18n
import tech.kousozo.tahoetv.core.LocalTv

// ============================================================================ motion (redesign 4.0, #178)

/** Enter/exit: ease-out. Never ease-in for UI (emilkowalski/skills). */
val EaseOutTahoe = CubicBezierEasing(Brand.EASE_OUT[0], Brand.EASE_OUT[1], Brand.EASE_OUT[2], Brand.EASE_OUT[3])
/** Movement on screen (hero auto-advance). */
val EaseInOutTahoe = CubicBezierEasing(Brand.EASE_IN_OUT[0], Brand.EASE_IN_OUT[1], Brand.EASE_IN_OUT[2], Brand.EASE_IN_OUT[3])
/** Sheets and page pushes. */
val EaseDrawerTahoe = CubicBezierEasing(Brand.EASE_DRAWER[0], Brand.EASE_DRAWER[1], Brand.EASE_DRAWER[2], Brand.EASE_DRAWER[3])

/** Press feedback: scale to 0.97 while held (120 ms ease-out), skipped with reduced motion. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed && !ThemePrefs.reduceMotion) Brand.PRESS_SCALE else 1f, tween(Brand.MOTION_PRESS, easing = EaseOutTahoe), label = "press")
    return graphicsLayer { scaleX = s; scaleY = s }
}

// ============================================================================ dock

/** Floating dock (#179): Beranda · Live TV · Remote (raised round button) · Watchlist · Profil. */
@Composable
fun Dock(tabs: List<Pair<String, ImageVector>>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) = Box(
    modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().height(92.dp),
) {
    Surface(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(68.dp),
        shape = RoundedCornerShape(34.dp), color = Tahoe.Surface.copy(alpha = 0.95f),
        border = BorderStroke(1.dp, Tahoe.Stroke), shadowElevation = 18.dp,
    ) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            tabs.forEachIndexed { i, (label, icon) ->
                if (i == 2) { Spacer(Modifier.width(76.dp)); return@forEachIndexed }
                val on = i == selected
                val src = remember { MutableInteractionSource() }
                Column(
                    Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(28.dp))
                        .background(if (on) Tahoe.Text.copy(alpha = 0.10f) else Color.Transparent)
                        .selectable(selected = on, interactionSource = src, indication = null, role = Role.Tab) { onSelect(i) }
                        .pressScale(src),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Icon(icon, null, tint = if (on) Tahoe.Text else Tahoe.Faint, modifier = Modifier.size(22.dp))
                    Text(label, fontSize = 11.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (on) Tahoe.Text else Tahoe.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    val src = remember { MutableInteractionSource() }
    val (rLabel, rIcon) = tabs[2]
    Box(
        Modifier.align(Alignment.TopCenter).size(68.dp)
            .shadow(if (selected == 2) 22.dp else 14.dp, CircleShape, ambientColor = Tahoe.Accent, spotColor = Tahoe.Accent)
            .clip(CircleShape).background(Tahoe.Accent).border(5.dp, Tahoe.Bg, CircleShape)
            .selectable(selected = selected == 2, interactionSource = src, indication = null, role = Role.Tab) { onSelect(2) }
            .pressScale(src),
        contentAlignment = Alignment.Center,
    ) { Icon(rIcon, rLabel, tint = Tahoe.OnAccent, modifier = Modifier.size(28.dp)) }
}

// ============================================================================ shared pieces

@Composable
private fun ScreenTitle(title: String, sub: String?) = Column(Modifier.padding(top = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(title, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp)
    if (sub != null) Text(sub, fontSize = 14.sp, color = Tahoe.Dim)
}

/** A channel row with a logo tile, used by Watchlist and Riwayat. */
@Composable
private fun ChannelListRow(e: LocalTv.Entry, now: Instant, trailing: @Composable () -> Unit) {
    val lang = PhoneRegion.language
    val logo = rememberNetImage(e.channel.logo)
    val tint = logo?.let { dominantColor(it) } ?: categoryTint(e.category)
    val src = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(src, null) { Nav.play(e) }.pressScale(src).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(96.dp, 56.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(tint, tint.copy(alpha = 0.5f)))),
            contentAlignment = Alignment.Center) {
            Box(Modifier.size(58.dp, 38.dp).clip(RoundedCornerShape(9.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                if (logo != null) Image(logo, null, Modifier.padding(5.dp).fillMaxSize(), contentScale = ContentScale.Fit)
                else Text(e.name.take(3).uppercase(), color = Color(0xFF141216), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(e.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val on = nowOn(Channels.epg, e, now)
            Text(on?.let { I18n.get(lang, "watchlist.live_now") + " · " + it.title } ?: e.category.label(lang),
                fontSize = 13.sp, color = Tahoe.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
private fun MenuGroup(label: String?, content: @Composable ColumnScope.() -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (label != null) Text(label.uppercase(), fontSize = 11.sp, color = Tahoe.Faint, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp,
        modifier = Modifier.padding(start = 4.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Tahoe.Surface).border(1.dp, Tahoe.Stroke.copy(alpha = 0.3f), RoundedCornerShape(26.dp))
        .padding(vertical = 6.dp), content = content)
}

@Composable
private fun MenuRow(icon: ImageVector, title: String, value: String? = null, tint: Color? = null, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(src, null, onClick = onClick).pressScale(src).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background((tint ?: Tahoe.Text).copy(alpha = if (tint != null) 0.16f else 0.08f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint ?: Tahoe.Text, modifier = Modifier.size(20.dp))
        }
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (value != null) Text(value, fontSize = 14.sp, color = Tahoe.Dim)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Tahoe.Faint)
    }
}

private fun open(c: android.content.Context, url: String) {
    runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private const val REPO = "https://github.com/fk0u/cleanyourtv-tahoe"

// ============================================================================ Watchlist

/** Watchlist tab (#179): saved channels, with what is on now. Tap to play, bookmark to remove. */
@Composable
fun WatchlistScreen() {
    val c = LocalContext.current
    val lang = PhoneRegion.language
    LaunchedEffect(Unit) { Channels.load(c) }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000); now = Instant.now() } }
    val saved = Channels.entries.orEmpty().filter { it.channel.url in Channels.favs }
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = TabInset),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { ScreenTitle(I18n.get(lang, "nav.watchlist"), I18n.get(lang, "watchlist.subtitle")) }
        if (saved.isEmpty()) item { Box(Modifier.padding(top = 12.dp)) { Note(I18n.get(lang, "watchlist.empty"), Icons.Rounded.BookmarkBorder) } }
        items(saved, key = { it.channel.url }) { e ->
            ChannelListRow(e, now) {
                IconButton({ Channels.toggleFav(c, e) }, Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Bookmark, I18n.get(lang, "watchlist.remove", e.name), tint = Tahoe.Accent)
                }
            }
        }
    }
}

// ============================================================================ Profil

/** Profil tab (#179): profile card, watch stats → recap, then activity, help, community and settings. */
@Composable
fun ProfileScreen() {
    val c = LocalContext.current
    val lang = PhoneRegion.language
    val name = remember { c.getSharedPreferences("setup", 0).getString("name", "").orEmpty() }
    val tvCount = remember(Pairing.tick) { Sync.devices(c).devices.size }
    val recap = remember(lang) {
        tech.kousozo.tahoetv.core.WatchRecap.summarize(java.io.File(c.filesDir, "recap"), "main", tech.kousozo.tahoetv.core.WatchRecap.currentMonth(), lang)
    }
    var showRecap by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    if (showRecap) RecapSheet { showRecap = false }
    if (showDevices) TvDevicesSheet({ showDevices = false }) { showDevices = false; Nav.tab = 0 }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = TabInset)) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp))
            .background(Brush.verticalGradient(listOf(Tahoe.Lake.copy(alpha = 0.55f), Tahoe.Bg)))) {
            Column(Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(I18n.get(lang, "profile.hello") + " 👋", fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Color.White.copy(alpha = 0.08f))
                        .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(26.dp)).padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(Modifier.size(72.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Tahoe.Accent, Tahoe.Lake))), contentAlignment = Alignment.Center) {
                        Text(name.trim().split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "T" },
                            fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(I18n.get(lang, "profile.main").uppercase() + " · TAHOE", fontSize = 10.sp, fontFamily = Mono, letterSpacing = 1.sp, color = Tahoe.Dim)
                        Text(name.ifBlank { I18n.get(lang, "profile.main") }, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(I18n.get(lang, "profile.devices", tvCount), fontSize = 12.sp, color = Tahoe.Dim)
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            GlassCard(onClick = { showRecap = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${recap.totalMinutes / 60}", fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = Mono)
                    Text("j", fontSize = 18.sp, color = Tahoe.Dim, modifier = Modifier.padding(start = 2.dp, end = 12.dp))
                    Text("${recap.totalMinutes % 60}", fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = Mono)
                    Text("m", fontSize = 18.sp, color = Tahoe.Dim, modifier = Modifier.padding(start = 2.dp))
                    Spacer(Modifier.weight(1f))
                    Text(I18n.get(lang, "profile.watch_month"), fontSize = 13.sp, color = Tahoe.Dim)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(I18n.get(lang, "profile.see_recap"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Tahoe.Accent)
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Tahoe.Accent)
                }
            }
            MenuGroup(I18n.get(lang, "profile.activity")) {
                MenuRow(Icons.Rounded.History, I18n.get(lang, "profile.history")) { Nav.page = "history" }
                MenuRow(Icons.Rounded.Tv, I18n.get(lang, "profile.connect_tv"), if (tvCount > 0) "$tvCount" else null) { showDevices = true }
                MenuRow(Icons.Rounded.Download, I18n.get(lang, "tv.install_on_tv")) { Nav.page = "setup" }
                MenuRow(Icons.Rounded.GridView, I18n.get(lang, "profile.explore")) { Nav.page = "explore" }
            }
            MenuGroup(I18n.get(lang, "profile.help_group")) {
                MenuRow(Icons.Rounded.Flag, I18n.get(lang, "profile.report")) { Report.send(c) { note -> note?.let { Toast.makeText(c, it, Toast.LENGTH_LONG).show() } } }
                MenuRow(Icons.Rounded.HelpOutline, I18n.get(lang, "profile.help_center")) { open(c, tech.kousozo.tahoetv.core.ControlPlane.baseUrl) }
            }
            MenuGroup(I18n.get(lang, "profile.community")) {
                MenuRow(Icons.Rounded.Star, I18n.get(lang, "profile.star"), tint = Color(Brand.SUN)) { open(c, REPO) }
                MenuRow(Icons.Rounded.Groups, I18n.get(lang, "profile.contributors")) { open(c, "$REPO/graphs/contributors") }
            }
            MenuGroup(null) { MenuRow(Icons.Rounded.Settings, I18n.get(lang, "nav.prefs")) { Nav.page = "settings" } }
            Box(Modifier.fillMaxWidth()) { UpdateCard() } // version, changelog, OTA
        }
    }
}

// ============================================================================ pages over the tabs

@Composable
private fun PageScaffold(title: String, content: @Composable ColumnScope.() -> Unit) {
    BackHandler { Nav.page = null }
    val lang = PhoneRegion.language
    Column(Modifier.fillMaxSize().background(Tahoe.Bg).statusBarsPadding().verticalScroll(rememberScrollState())
        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RoundGlass(Icons.AutoMirrored.Rounded.ArrowBack, I18n.get(lang, "common.back"), size = 48.dp) { Nav.page = null }
        Text(title, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp)
        content()
    }
}

/** Riwayat tontonan (#179): recently watched channels, newest first. */
@Composable
fun HistoryPage() {
    val c = LocalContext.current
    val lang = PhoneRegion.language
    LaunchedEffect(Unit) { Channels.load(c) }
    val recent = Channels.recent(c)
    val now = remember { Instant.now() }
    PageScaffold(I18n.get(lang, "profile.history")) {
        if (recent.isEmpty()) Note(I18n.get(lang, "profile.history_empty"), Icons.Rounded.History)
        recent.forEach { e -> ChannelListRow(e, now) {} }
    }
}

/** Pengaturan (#179): region & language, appearance, channel languages. */
@Composable
fun SettingsPage() {
    val lang = PhoneRegion.language
    PageScaffold(I18n.get(lang, "nav.prefs")) {
        RegionCard()
        AppearanceCard()
    }
}

/** The area under the tabs that hosts [Nav.page]. */
@Composable
fun NavPage(page: String) {
    when (page) {
        "history" -> HistoryPage()
        "settings" -> SettingsPage()
        "explore" -> { BackHandler { Nav.page = null }; Box(Modifier.fillMaxSize().background(Tahoe.Bg)) { CategoriesScreen() } }
        "setup" -> { BackHandler { Nav.page = null }; Box(Modifier.fillMaxSize().background(Tahoe.Bg)) { SetupScreen(onExit = { Nav.page = null }) } }
    }
}
