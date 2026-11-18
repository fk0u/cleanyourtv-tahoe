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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.kousozo.tahoetv.core.Brand
import tech.kousozo.tahoetv.core.Demo
import tech.kousozo.tahoetv.core.I18n
import tech.kousozo.tahoetv.core.LocalTv
import tech.kousozo.tahoetv.core.OnboardingText
import tech.kousozo.tahoetv.core.PlayPrefs
import tech.kousozo.tahoetv.core.Region
import tech.kousozo.tahoetv.core.Theme
import tech.kousozo.tahoetv.core.TvLink

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
    val discBg = if (tint != null) tint.copy(alpha = 0.16f)
        else if (ThemePrefs.dark) Color.White.copy(alpha = 0.08f) else Tahoe.Glass
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(src, null, onClick = onClick).pressScale(src).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(discBg), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint ?: Tahoe.Text, modifier = Modifier.size(20.dp))
        }
        Text(title, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (value != null) Text(value, fontSize = 14.sp, color = Tahoe.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Tahoe.Faint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SwitchMenuRow(icon: ImageVector, title: String, checked: Boolean, tint: Color? = null, onCheckedChange: (Boolean) -> Unit) {
    val discBg = if (tint != null) tint.copy(alpha = 0.16f)
        else if (ThemePrefs.dark) Color.White.copy(alpha = 0.08f) else Tahoe.Glass
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(discBg), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint ?: Tahoe.Text, modifier = Modifier.size(20.dp))
        }
        Text(title, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        FigmaSwitch(checked, onCheckedChange)
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
    var filter by remember { mutableIntStateOf(0) } // 0 Semua, 1 Saluran (programmes and reminders are not saved yet)
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = TabInset),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Box(Modifier.padding(horizontal = 4.dp)) { ScreenTitle(I18n.get(lang, "nav.watchlist"), I18n.get(lang, "watchlist.subtitle")) } }
        item {
            Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(I18n.get(lang, "live.all"), filter == 0) { filter = 0 }
                Chip(I18n.get(lang, "watchlist.channels"), filter == 1) { filter = 1 }
            }
        }
        if (saved.isEmpty()) item { Note(I18n.get(lang, "watchlist.empty"), Icons.Rounded.BookmarkBorder) }
        // Figma featured card: the first saved channel with something on now
        val featured = saved.firstOrNull { nowOn(Channels.epg, it, now) != null } ?: saved.firstOrNull()
        if (featured != null && filter == 0) item(key = "featured") {
            val prog = nowOn(Channels.epg, featured, now)
            Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(24.dp)).clickable { Nav.play(featured) }) {
                ChannelArt(featured, Modifier.fillMaxSize(), logoScale = 0.6f, logoTop = 22.dp)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.78f))))
                Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LiveBadge(); Spacer(Modifier.width(8.dp))
                            Text(I18n.get(lang, "watchlist.live_now").uppercase(), color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, letterSpacing = 1.32.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text(prog?.title ?: featured.name, color = Color.White, fontSize = 20.sp, lineHeight = 25.sp, letterSpacing = (-0.2).sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(featured.name + (prog?.let { " · " + HM.format(it.start) + "–" + HM.format(it.stop) } ?: ""), color = Color.White.copy(alpha = 0.8f),
                            fontSize = 14.sp, letterSpacing = 0.14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                    Box(Modifier.size(48.dp).clip(CircleShape).background(Color.White).clickable { Nav.play(featured) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PlayArrow, I18n.get(lang, "home.watch"), tint = Color(tech.kousozo.tahoetv.core.Brand.CANVAS), modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
        if (saved.isNotEmpty()) item(key = "list") {
            // Figma list card: surface, hairline, 24 dp corners, rows with a 90 × 52 thumbnail
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Tahoe.Surface).border(1.dp, Tahoe.Stroke, RoundedCornerShape(24.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)) {
                saved.forEach { e ->
                    WatchRow(e, now) {
                        IconButton({ Channels.toggleFav(c, e) }, Modifier.size(48.dp)) {
                            Icon(Icons.Rounded.Bookmark, I18n.get(lang, "watchlist.remove", e.name), tint = Tahoe.Accent)
                        }
                    }
                }
            }
        }
    }
}

/** Figma Watchlist row: 90 × 52 artwork with a LIVE badge, title 16 Medium, "Saluran · show" 13 secondary. */
@Composable
private fun WatchRow(e: LocalTv.Entry, now: Instant, trailing: @Composable () -> Unit) {
    val lang = PhoneRegion.language
    val on = nowOn(Channels.epg, e, now)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { Nav.play(e) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(90.dp, 52.dp).clip(RoundedCornerShape(12.dp))) {
            ChannelArt(e, Modifier.fillMaxSize(), logoScale = 0.3f)
            if (on != null) Text("● LIVE", Modifier.padding(5.dp).clip(RoundedCornerShape(50)).background(Tahoe.Bad).padding(horizontal = 6.dp, vertical = 2.dp),
                color = Color.White, fontSize = 9.sp, lineHeight = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(e.name, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(I18n.get(lang, "watchlist.channel") + " · " + (on?.title ?: e.category.label(lang)), fontSize = 13.sp, color = Tahoe.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

// ============================================================================ Profil

/** Profil tab (#179): profile card, watch stats → recap, then activity, help, community and settings. */
@Composable
fun ProfileScreen() {
    val c = LocalContext.current
    val lang = PhoneRegion.language
    var name by remember(lang) {
        val saved = c.getSharedPreferences("setup", 0).getString("name", "").orEmpty()
        mutableStateOf(saved.ifBlank { if (Demo.isActive()) Demo.profileName(lang) else "" })
    }
    var editNameOpen by remember { mutableStateOf(false) }
    var draftName by remember { mutableStateOf(name) }
    var aboutOpen by remember { mutableStateOf(false) }
    val tvCount = remember(Pairing.tick) { Sync.devices(c).devices.size }
    val recap = remember(lang) { loadRecapSummary(java.io.File(c.filesDir, "recap"), lang) }
    val versionName = remember { PushHelper.appVersion(c).first }

    if (editNameOpen) AlertDialog(
        onDismissRequest = { editNameOpen = false },
        title = { Text(I18n.get(lang, "profile.edit_name")) },
        text = { Field(draftName, { draftName = it }, I18n.get(lang, "settings.name_hint")) },
        confirmButton = {
            TextButton({
                val trimmed = draftName.trim()
                name = trimmed
                if (!Demo.isActive()) c.getSharedPreferences("setup", 0).edit().putString("name", trimmed).apply()
                editNameOpen = false
            }) { Text(I18n.get(lang, "common.save"), color = Tahoe.Accent) }
        },
        dismissButton = { TextButton({ editNameOpen = false }) { Text(I18n.get(lang, "common.cancel"), color = Tahoe.Dim) } },
        containerColor = Tahoe.Surface,
    )
    if (aboutOpen) AlertDialog(
        onDismissRequest = { aboutOpen = false },
        title = { Text("Tahoe TV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${I18n.get(lang, "profile.version_label")}: $versionName", color = Tahoe.Dim, fontSize = 14.sp)
                Text(I18n.get(lang, "settings.updates_url_note"), color = Tahoe.Faint, fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton({ aboutOpen = false }) { Text(I18n.get(lang, "common.close"), color = Tahoe.Accent) } },
        containerColor = Tahoe.Surface,
    )

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = TabInset)) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp))
                .background(Brush.verticalGradient(listOf(Tahoe.Lake.copy(alpha = 0.55f), Tahoe.Bg))),
        ) {
            Text(
                "TAHOE",
                color = Tahoe.Text.copy(alpha = 0.04f),
                fontSize = 112.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-2).sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 80.dp).rotate(90f),
            )
            Column(
                Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(I18n.get(lang, "profile.hello") + " 👋", fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
                        .background(if (ThemePrefs.dark) Color.White.copy(alpha = 0.08f) else Tahoe.Surface.copy(alpha = 0.92f))
                        .border(1.dp, Tahoe.Stroke.copy(alpha = 0.5f), RoundedCornerShape(26.dp))
                        .clickable { draftName = name; editNameOpen = true }
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(Modifier.size(84.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Tahoe.Accent, Tahoe.Lake))), contentAlignment = Alignment.Center) {
                        Text(
                            name.trim().split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "T" },
                            fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(I18n.get(lang, "profile.id_eyebrow"), fontSize = 10.sp, fontFamily = Mono, letterSpacing = 1.sp, color = Tahoe.Dim)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(name.ifBlank { I18n.get(lang, "profile.main") }, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            Icon(Icons.Rounded.Edit, I18n.get(lang, "profile.edit_name"), tint = Tahoe.Faint, modifier = Modifier.size(14.dp))
                        }
                        Text(I18n.get(lang, "profile.devices", tvCount), fontSize = 12.sp, color = Tahoe.Dim)
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            GlassCard(onClick = { Nav.page = "recap" }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${recap.totalMinutes / 60}", fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = Mono)
                        Text(I18n.get(lang, "profile.unit_hour"), fontSize = 20.sp, color = Tahoe.Dim, modifier = Modifier.padding(start = 2.dp, bottom = 5.dp))
                    }
                    Box(Modifier.width(1.dp).height(40.dp).background(Tahoe.Stroke.copy(alpha = 0.5f)))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${recap.totalMinutes % 60}", fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = Mono)
                        Text(I18n.get(lang, "profile.unit_min"), fontSize = 20.sp, color = Tahoe.Dim, modifier = Modifier.padding(start = 2.dp, bottom = 5.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(I18n.get(lang, "profile.watch_month"), fontSize = 13.sp, color = Tahoe.Dim)
                }
                HorizontalDivider(color = Tahoe.Stroke.copy(alpha = 0.25f), modifier = Modifier.padding(vertical = 4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(I18n.get(lang, "profile.see_recap"), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Tahoe.Accent)
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Tahoe.Accent, modifier = Modifier.size(16.dp))
                }
            }
            MenuGroup(I18n.get(lang, "profile.activity")) {
                MenuRow(Icons.Rounded.History, I18n.get(lang, "profile.history")) { Nav.page = "history" }
                MenuRow(Icons.Rounded.Tv, I18n.get(lang, "profile.connect_tv"), if (tvCount > 0) "$tvCount" else null) { Nav.page = "connect" }
                MenuRow(Icons.Rounded.Download, I18n.get(lang, "tv.install_on_tv")) { Nav.page = "setup" }
            }
            MenuGroup(I18n.get(lang, "profile.help_group")) {
                MenuRow(Icons.Rounded.Flag, I18n.get(lang, "profile.report")) { Nav.page = "report" }
                MenuRow(Icons.AutoMirrored.Rounded.HelpOutline, I18n.get(lang, "profile.help_center")) { Nav.page = "help" }
            }
            MenuGroup(I18n.get(lang, "profile.community")) {
                MenuRow(Icons.Rounded.Star, I18n.get(lang, "profile.star"), value = "★ 1,2k", tint = Color(Brand.SUN)) { open(c, REPO) }
                MenuRow(Icons.Rounded.Groups, I18n.get(lang, "profile.contributors")) { Nav.page = "contributors" }
            }
            MenuGroup(null) { MenuRow(Icons.Rounded.Settings, I18n.get(lang, "nav.prefs")) { Nav.page = "settings" } }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Tahoe.Surface)
                    .border(1.dp, Tahoe.Stroke.copy(alpha = 0.25f), RoundedCornerShape(28.dp))
                    .clickable { aboutOpen = true }.padding(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(I18n.get(lang, "profile.version_label"), fontSize = 14.sp, color = Tahoe.Dim)
                        Text(versionName, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.64).sp)
                    }
                    GlassPill(I18n.get(lang, "profile.about_btn")) { aboutOpen = true }
                }
            }
            Box(Modifier.fillMaxWidth()) { UpdateCard() }
        }
    }
}

// ============================================================================ pages over the tabs

@Composable
private fun PageScaffold(title: String, sub: String? = null, trailing: @Composable (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    BackHandler { Nav.page = null }
    val lang = PhoneRegion.language
    Column(
        Modifier.fillMaxSize().background(Tahoe.Bg).statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BackCircle(I18n.get(lang, "common.back")) { Nav.page = null }
            Spacer(Modifier.weight(1f))
            trailing?.invoke()
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp)
            if (sub != null) Text(sub, fontSize = 15.sp, color = Tahoe.Dim, lineHeight = 21.sp)
        }
        content()
    }
}

private fun demoEntries(): List<LocalTv.Entry> {
    val cats = listOf(LocalTv.Category.News, LocalTv.Category.Documentary, LocalTv.Category.Sports, LocalTv.Category.Kids)
    return Demo.channels().mapIndexed { i, ch ->
        LocalTv.Entry(ch, ch.name, cats.getOrElse(i) { LocalTv.Category.News }, near = false, partTime = false, geoBlocked = false, rank = i)
    }
}

private enum class HistoryFilter { All, Phone, Tv }

/** Riwayat tontonan (#179, Figma 23:459): device filters, Today/Yesterday grouping, progress bars, clear. */
@Composable
fun HistoryPage() {
    val c = LocalContext.current
    val lang = PhoneRegion.language
    LaunchedEffect(Unit) { Channels.load(c) }
    var filter by remember { mutableStateOf(HistoryFilter.All) }
    var cleared by remember { mutableStateOf(false) }
    val recentReal = Channels.recent(c)
    val now = remember { Instant.now() }
    val entries = remember(recentReal, cleared, lang) {
        if (cleared) emptyList()
        else if (recentReal.isNotEmpty()) recentReal.mapIndexed { i, e -> Triple(e, if (i % 3 == 2) HistoryFilter.Tv else HistoryFilter.Phone, i < 2) }
        else if (Demo.isActive()) demoEntries().mapIndexed { i, e ->
            Triple(e, if (i % 2 == 0) HistoryFilter.Phone else HistoryFilter.Tv, i < 2)
        } else emptyList()
    }
    val shown = entries.filter { filter == HistoryFilter.All || it.second == filter }
    val todayItems = shown.filter { it.third }
    val yesterdayItems = shown.filter { !it.third }

    PageScaffold(I18n.get(lang, "profile.history")) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(I18n.get(lang, "live.all"), filter == HistoryFilter.All) { filter = HistoryFilter.All }
            Chip(I18n.get(lang, "history.filter_phone"), filter == HistoryFilter.Phone) { filter = HistoryFilter.Phone }
            Chip(I18n.get(lang, "history.filter_tv"), filter == HistoryFilter.Tv) { filter = HistoryFilter.Tv }
        }
        if (shown.isEmpty()) {
            Note(I18n.get(lang, "profile.history_empty"), Icons.Rounded.History)
        } else {
            listOf(
                I18n.get(lang, "push.today") to todayItems,
                I18n.get(lang, "history.yesterday") to yesterdayItems,
            ).forEach { (label, group) ->
                if (group.isEmpty()) return@forEach
                Text(label.uppercase(), fontSize = 11.sp, color = Tahoe.Faint, fontWeight = FontWeight.SemiBold, letterSpacing = 1.32.sp, modifier = Modifier.padding(start = 4.dp))
                group.forEachIndexed { idx, (e, srcType, _) ->
                    val frac = (0.78f - idx * 0.17f).coerceIn(0.2f, 0.92f)
                    val prog = nowOn(Channels.epg, e, now)?.title
                    val deviceLabel = I18n.get(lang, if (srcType == HistoryFilter.Tv) "history.filter_tv" else "history.filter_phone")
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable { Nav.play(e) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(112.dp, 64.dp).clip(RoundedCornerShape(14.dp))) {
                            ChannelArt(e, Modifier.fillMaxSize(), logoScale = 0.8f)
                            ProgressBar(frac, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(prog ?: e.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${e.name} · ${25 + idx * 14} ${I18n.get(lang, "profile.unit_min")} · $deviceLabel",
                                fontSize = 12.sp, color = Tahoe.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).clickable {
                    c.getSharedPreferences("live", 0).edit().remove("recent").apply()
                    cleared = true
                    Toast.makeText(c, I18n.get(lang, "history.cleared"), Toast.LENGTH_SHORT).show()
                }.padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.DeleteOutline, null, tint = Tahoe.Bad, modifier = Modifier.size(18.dp))
                Text(I18n.get(lang, "history.clear"), color = Tahoe.Bad, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

private enum class SettingsDialog { Theme, Quality, Latency, SubtitleSize, Language, ChannelLanguages }

/** Pengaturan (#179, Figma 19:511): search, Disarankan highlight, and 4 grouped sections with dialogs. */
@Composable
fun SettingsPage() {
    val c = LocalContext.current
    val lang = PhoneRegion.language
    var query by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    val uiPrefs = remember { c.getSharedPreferences("ui", 0) }
    var broadcastNotifs by remember { mutableStateOf(uiPrefs.getBoolean("broadcast_notifs", true)) }
    var cacheClearedOnce by remember { mutableStateOf(false) }

    fun themeLabel(m: Theme.Mode) = when (m) {
        Theme.Mode.SYSTEM -> I18n.get(lang, "ui.theme_system")
        Theme.Mode.DARK -> I18n.get(lang, "ui.theme_dark")
        Theme.Mode.LIGHT -> I18n.get(lang, "ui.theme_light")
    }
    fun matches(vararg texts: String): Boolean {
        val q = query.trim()
        return q.isEmpty() || texts.any { it.contains(q, ignoreCase = true) }
    }

    when (dialog) {
        SettingsDialog.Theme -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(I18n.get(lang, "ui.appearance")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Theme.Mode.entries.forEach { m ->
                        MenuRow(Icons.Rounded.Palette, themeLabel(m), if (ThemePrefs.mode == m) "✓" else null) {
                            ThemePrefs.mode = m; AppearancePrefs.save(c); dialog = null
                        }
                    }
                }
            },
            confirmButton = { TextButton({ dialog = null }) { Text(I18n.get(lang, "common.close")) } },
            containerColor = Tahoe.Surface,
        )
        SettingsDialog.Quality -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(I18n.get(lang, "player.quality")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlayPrefs.Quality.entries.forEach { q ->
                        MenuRow(Icons.Rounded.Hd, q.label(lang), if (Playback.quality == q) "✓" else null) {
                            Playback.setQuality(c, q); dialog = null
                        }
                    }
                }
            },
            confirmButton = { TextButton({ dialog = null }) { Text(I18n.get(lang, "common.close")) } },
            containerColor = Tahoe.Surface,
        )
        SettingsDialog.Latency -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(I18n.get(lang, "player.latency")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlayPrefs.Latency.entries.forEach { l ->
                        MenuRow(Icons.Rounded.Speed, l.label(lang), if (Playback.latency == l) "✓" else null) {
                            Playback.setLatency(c, l); dialog = null
                        }
                    }
                }
            },
            confirmButton = { TextButton({ dialog = null }) { Text(I18n.get(lang, "common.close")) } },
            containerColor = Tahoe.Surface,
        )
        SettingsDialog.SubtitleSize -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(I18n.get(lang, "sub.size_label")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlayPrefs.SubtitleSize.entries.forEach { sz ->
                        MenuRow(Icons.Rounded.Subtitles, sz.label(lang), if (Playback.subtitleSize == sz) "✓" else null) {
                            Playback.setSubtitleSize(c, sz); dialog = null
                        }
                    }
                }
            },
            confirmButton = { TextButton({ dialog = null }) { Text(I18n.get(lang, "common.close")) } },
            containerColor = Tahoe.Surface,
        )
        SettingsDialog.Language -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(I18n.get(lang, "settings.app_language")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Region.LANGUAGES.forEach { code ->
                        MenuRow(Icons.Rounded.Translate, OnboardingText.LANGUAGE_NAMES[code] ?: code, if (lang == code) "✓" else null) {
                            PhoneRegion.save(c, PhoneRegion.viewer.copy(language = code)); dialog = null
                        }
                    }
                }
            },
            confirmButton = { TextButton({ dialog = null }) { Text(I18n.get(lang, "common.close")) } },
            containerColor = Tahoe.Surface,
        )
        SettingsDialog.ChannelLanguages -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(I18n.get(lang, "ui.channel_languages")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val v = PhoneRegion.viewer
                    Region.channelLanguages().forEach { l ->
                        val on = l in v.channelLanguages
                        val label = java.util.Locale.forLanguageTag(l).let { it.getDisplayLanguage(it) }.ifBlank { l }
                        CheckRow(label, null, on) {
                            PhoneRegion.save(c, v.copy(channelLanguages = if (on) v.channelLanguages - l else v.channelLanguages + l))
                        }
                    }
                }
            },
            confirmButton = { TextButton({ dialog = null }) { Text(I18n.get(lang, "common.done"), color = Tahoe.Accent) } },
            containerColor = Tahoe.Surface,
        )
        null -> Unit
    }

    PageScaffold(I18n.get(lang, "nav.prefs")) {
        SearchPill(query, { query = it }, I18n.get(lang, "settings.search_hint"), active = query.isNotBlank())
        if (query.isBlank()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Tahoe.Surface)
                    .border(1.dp, Tahoe.Stroke.copy(alpha = 0.25f), RoundedCornerShape(28.dp))
                    .clickable { dialog = SettingsDialog.ChannelLanguages }.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(I18n.get(lang, "settings.recommended"), fontSize = 14.sp, color = Tahoe.Faint, fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Tahoe.Accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Language, null, tint = Tahoe.Accent, modifier = Modifier.size(20.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(I18n.get(lang, "settings.add_ch_lang"), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(I18n.get(lang, "settings.add_ch_lang_sub"), fontSize = 12.sp, color = Tahoe.Dim)
                    }
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Tahoe.Faint, modifier = Modifier.size(18.dp))
                }
            }
        }
        val showLook = matches(I18n.get(lang, "ui.appearance"), I18n.get(lang, "ui.high_contrast"), I18n.get(lang, "ui.reduce_motion"))
        if (showLook) MenuGroup(I18n.get(lang, "ui.appearance")) {
            MenuRow(Icons.Rounded.Palette, I18n.get(lang, "ui.appearance"), themeLabel(ThemePrefs.mode)) { dialog = SettingsDialog.Theme }
            SwitchMenuRow(Icons.Rounded.Contrast, I18n.get(lang, "ui.high_contrast"), ThemePrefs.highContrast) {
                ThemePrefs.highContrast = it; AppearancePrefs.save(c)
            }
            SwitchMenuRow(Icons.Rounded.MotionPhotosOff, I18n.get(lang, "ui.reduce_motion"), ThemePrefs.reduceMotion) {
                ThemePrefs.reduceMotion = it; AppearancePrefs.save(c)
            }
        }
        val showPlayback = matches(I18n.get(lang, "settings.group_playback"), I18n.get(lang, "player.quality"), I18n.get(lang, "player.latency"), I18n.get(lang, "sub.size_label"))
        if (showPlayback) MenuGroup(I18n.get(lang, "settings.group_playback")) {
            MenuRow(Icons.Rounded.Hd, I18n.get(lang, "player.quality"), Playback.quality.label(lang)) { dialog = SettingsDialog.Quality }
            MenuRow(Icons.Rounded.Speed, I18n.get(lang, "player.latency"), Playback.latency.label(lang)) { dialog = SettingsDialog.Latency }
            MenuRow(Icons.Rounded.Subtitles, I18n.get(lang, "sub.size_label"), Playback.subtitleSize.label(lang)) { dialog = SettingsDialog.SubtitleSize }
        }
        val showChannels = matches(I18n.get(lang, "settings.group_channels"), I18n.get(lang, "settings.app_language"), I18n.get(lang, "settings.region_city"), I18n.get(lang, "settings.prayer_adhan"), I18n.get(lang, "ui.channel_languages"))
        if (showChannels) MenuGroup(I18n.get(lang, "settings.group_channels")) {
            MenuRow(Icons.Rounded.Translate, I18n.get(lang, "settings.app_language"), OnboardingText.LANGUAGE_NAMES[lang] ?: lang) { dialog = SettingsDialog.Language }
            val citySuffix = PhoneRegion.viewer.city.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
            MenuRow(Icons.Rounded.Public, I18n.get(lang, "settings.region_city"), Region.flag(PhoneRegion.code) + citySuffix.ifEmpty { " " + Region.name(PhoneRegion.code, lang) }) {
                Playback.stop(); PhoneRegion.reset()
            }
            val chLangSummary = PhoneRegion.viewer.channelLanguages.map { l ->
                java.util.Locale.forLanguageTag(l).getDisplayLanguage(java.util.Locale.forLanguageTag(lang)).ifBlank { l }
            }.let { list ->
                if (list.isEmpty()) null else if (list.size <= 2) list.joinToString(", ") else "${list.take(2).joinToString(", ")} +${list.size - 2}"
            }
            MenuRow(Icons.Rounded.Language, I18n.get(lang, "ui.channel_languages"), chLangSummary) {
                dialog = SettingsDialog.ChannelLanguages
            }
            SwitchMenuRow(Icons.Rounded.Schedule, I18n.get(lang, "settings.prayer_adhan"), PhoneRegion.viewer.prayer) {
                PhoneRegion.save(c, PhoneRegion.viewer.copy(prayer = it))
            }
        }
        val showOther = matches(I18n.get(lang, "settings.group_other"), I18n.get(lang, "settings.broadcast_notifs"), I18n.get(lang, "settings.clear_cache"))
        if (showOther) MenuGroup(I18n.get(lang, "settings.group_other")) {
            SwitchMenuRow(Icons.Rounded.NotificationsActive, I18n.get(lang, "settings.broadcast_notifs"), broadcastNotifs) {
                broadcastNotifs = it; uiPrefs.edit().putBoolean("broadcast_notifs", it).apply()
            }
            MenuRow(Icons.Rounded.CleaningServices, I18n.get(lang, "settings.clear_cache"), if (cacheClearedOnce) "0 MB" else null) {
                c.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                cacheClearedOnce = true
                Toast.makeText(c, I18n.get(lang, "settings.cache_cleared"), Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/** Notifikasi (#179, Figma 30:714): announcements, reminders and updates grouped Hari ini / Sebelumnya. */
@Composable
fun NotificationsPage() {
    val c = LocalContext.current
    val lang = PhoneRegion.language
    LaunchedEffect(Unit) { PushHelper.markAllRead(c) }
    val fmt = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
    val day = remember { java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()) }
    val startOfDay = remember { java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }
    val items = remember(PushHelper.inbox, lang) {
        if (PushHelper.inbox.isNotEmpty() || !Demo.isActive()) PushHelper.inbox
        else {
            val nowMs = System.currentTimeMillis()
            val demoNames = Demo.channels().map { it.name }
            listOf(
                tech.kousozo.tahoetv.core.ControlPlane.PushNotification(
                    id = 1L,
                    title = demoNames.firstOrNull() ?: "Tahoe News",
                    body = I18n.get(lang, "home.on_now_fmt", demoNames.firstOrNull() ?: "Tahoe News"),
                    kind = "reminder",
                    createdAt = nowMs - 300_000L,
                ),
                tech.kousozo.tahoetv.core.ControlPlane.PushNotification(
                    id = 2L,
                    title = demoNames.getOrNull(1) ?: "Alpine Earth",
                    body = I18n.get(lang, "push.channel_desc"),
                    kind = "info",
                    createdAt = nowMs - 3_600_000L,
                ),
                tech.kousozo.tahoetv.core.ControlPlane.PushNotification(
                    id = 3L,
                    title = "Tahoe TV ${PushHelper.appVersion(c).first}",
                    body = I18n.get(lang, "update.up_to_date", PushHelper.appVersion(c).first),
                    kind = "update",
                    createdAt = startOfDay - 86_400_000L,
                ),
                tech.kousozo.tahoetv.core.ControlPlane.PushNotification(
                    id = 4L,
                    title = "Living Room TV",
                    body = I18n.get(lang, "pair.connected"),
                    kind = "info",
                    createdAt = startOfDay - 172_800_000L,
                ),
            )
        }
    }
    PageScaffold(I18n.get(lang, "push.inbox_title")) {
        if (items.isEmpty()) Note(I18n.get(lang, "push.inbox_empty"), Icons.Rounded.NotificationsNone)
        val (today, earlier) = items.partition { it.createdAt >= startOfDay }
        for ((label, list) in listOf(I18n.get(lang, "push.today") to today, I18n.get(lang, "push.earlier") to earlier)) {
            if (list.isEmpty()) continue
            Text(label.uppercase(), fontSize = 11.sp, color = Tahoe.Faint, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 4.dp))
            list.forEach { n ->
                val (icon, tint) = when (n.kind) { "update" -> Icons.Rounded.Download to Tahoe.Accent; "reminder" -> Icons.Rounded.NotificationsActive to Tahoe.Warm; else -> Icons.Rounded.Campaign to Tahoe.Accent }
                val src = remember { MutableInteractionSource() }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Tahoe.Surface)
                        .clickable(src, null) { if (n.kind == "update") PushHelper.openUpdateModal(c) else if (n.actionUrl.isNotBlank()) PushHelper.openSafeUrl(c, n.actionUrl) }
                        .pressScale(src).padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp)) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(n.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (n.createdAt > 0L) Text((if (n.createdAt >= startOfDay) fmt else day).format(java.util.Date(n.createdAt)), fontSize = 12.sp, color = Tahoe.Faint)
                        }
                        Text(n.body, fontSize = 14.sp, color = Tahoe.Dim)
                        if (n.actionLabel.isNotBlank()) Text(n.actionLabel, fontSize = 13.sp, color = Tahoe.Accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** Offline / failed load empty state (#179, Figma 30:805): 180/128/76 concentric circles + retry & watchlist shortcut. */
@Composable
fun OfflineState(modifier: Modifier = Modifier) {
    val c = LocalContext.current
    val lang = PhoneRegion.language
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
            listOf(180.dp to 0.06f, 128.dp to 0.11f, 76.dp to 0.16f).forEach { (d, a) ->
                Box(Modifier.size(d).clip(CircleShape).background(Tahoe.Warm.copy(alpha = a)))
            }
            Icon(Icons.Rounded.WifiOff, null, tint = Tahoe.Warm, modifier = Modifier.size(56.dp))
        }
        Text(I18n.get(lang, "offline.no_internet"), fontSize = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.56).sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(I18n.get(lang, "home.channels_failed"), fontSize = 15.sp, color = Tahoe.Dim, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(I18n.get(lang, "common.retry"), Icons.Rounded.Refresh) { Channels.reload(c); scope.launch { Channels.load(c) } }
            GlassPill(I18n.get(lang, "offline.watch_saved"), Icons.Rounded.Bookmarks) { Nav.tab = 3; Nav.page = null }
        }
    }
}

private enum class SearchFilter { All, Channels, Shows, Schedule }

/** Cari (Figma 23:341): instant channel & programme search pushed from Home/Live. */
@Composable
fun SearchPage() {
    val c = LocalContext.current
    val lang = PhoneRegion.language
    LaunchedEffect(Unit) { Channels.load(c) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(SearchFilter.All) }
    val all = Channels.entries ?: if (Demo.isActive()) demoEntries() else emptyList()
    val now = remember { Instant.now() }
    val effectiveQuery = query.trim().ifEmpty { if (Demo.isActive()) "Tahoe" else "" }.lowercase()

    PageScaffold(I18n.get(lang, "search.title")) {
        SearchPill(query, { query = it }, I18n.get(lang, "home.search_hint"), active = true)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(I18n.get(lang, "live.all"), filter == SearchFilter.All) { filter = SearchFilter.All }
            Chip(I18n.get(lang, "watchlist.channels"), filter == SearchFilter.Channels) { filter = SearchFilter.Channels }
            Chip(I18n.get(lang, "search.chip_shows"), filter == SearchFilter.Shows) { filter = SearchFilter.Shows }
            Chip(I18n.get(lang, "search.chip_schedule"), filter == SearchFilter.Schedule) { filter = SearchFilter.Schedule }
        }
        if (effectiveQuery.isNotEmpty()) {
            val chMatches = if (filter == SearchFilter.All || filter == SearchFilter.Channels) {
                all.filter { it.name.lowercase().contains(effectiveQuery) || it.category.label(lang).lowercase().contains(effectiveQuery) }
            } else emptyList()
            val showMatches = if (filter != SearchFilter.Channels) {
                all.mapNotNull { e ->
                    val prog = Channels.epg?.schedule(e.name, now, e.channel.tvgId)?.firstOrNull { it.title.lowercase().contains(effectiveQuery) }
                    prog?.let { e to it }
                }
            } else emptyList()
            val topHit = chMatches.firstOrNull() ?: showMatches.firstOrNull()?.first

            if (topHit == null) {
                Note(I18n.get(lang, "search.empty"), Icons.Rounded.SearchOff)
            } else {
                Text(I18n.get(lang, "ch.now_playing").uppercase(), fontSize = 11.sp, color = Tahoe.Faint, fontWeight = FontWeight.SemiBold, letterSpacing = 1.32.sp)
                val topShow = nowOn(Channels.epg, topHit, now)?.title
                Box(
                    Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(24.dp)).clickable { Nav.play(topHit) },
                ) {
                    ChannelArt(topHit, Modifier.fillMaxSize(), logoScale = 0.85f)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.25f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
                    LiveBadge(Modifier.align(Alignment.TopStart).padding(14.dp))
                    Column(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(topShow ?: topHit.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${topHit.name} · ${topHit.category.label(lang)}", color = Color.White.copy(alpha = 0.72f), fontSize = 14.sp, maxLines = 1)
                    }
                }
                val restCh = chMatches.filter { it.channel.url != topHit.channel.url }
                if (restCh.isNotEmpty() || showMatches.isNotEmpty()) {
                    Text(I18n.get(lang, "search.other_results").uppercase(), fontSize = 11.sp, color = Tahoe.Faint, fontWeight = FontWeight.SemiBold, letterSpacing = 1.32.sp)
                    restCh.forEach { e ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { Nav.play(e) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ChannelArt(e, Modifier.size(88.dp, 52.dp).clip(RoundedCornerShape(12.dp)), logoScale = 0.78f)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(e.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(nowOn(Channels.epg, e, now)?.title ?: e.category.label(lang), fontSize = 12.sp, color = Tahoe.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    showMatches.forEach { (e, prog) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { Nav.play(e) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ChannelArt(e, Modifier.size(88.dp, 52.dp).clip(RoundedCornerShape(12.dp)), logoScale = 0.78f)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(prog.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${e.name} · ${HM.format(prog.start)}", fontSize = 12.sp, color = Tahoe.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        Text(I18n.get(lang, "search.recent").uppercase(), fontSize = 11.sp, color = Tahoe.Faint, fontWeight = FontWeight.SemiBold, letterSpacing = 1.32.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            all.take(5).map { it.name }.forEach { label ->
                Row(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(if (ThemePrefs.dark) Color.White.copy(alpha = 0.08f) else Tahoe.Glass)
                        .clickable { query = label }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Rounded.Schedule, null, tint = Tahoe.Faint, modifier = Modifier.size(14.dp))
                    Text(label, fontSize = 14.sp, color = Tahoe.Dim, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** Hubungkan ke TV (Figma 23:515): Wi-Fi radar (200/140/80 rings), grouped DITEMUKAN list, QR & PIN pills, installer link. */
@Composable
fun ConnectTvPage() {
    val c = LocalContext.current
    val lang = PhoneRegion.language
    LaunchedEffect(Unit) { TvDiscovery.start(c) }
    var showQr by remember { mutableStateOf(false) }
    var pinFor by remember { mutableStateOf<String?>(null) }
    val devState = remember(Pairing.tick) { Sync.devices(c) }
    val known = devState.devices.mapTo(HashSet()) { it.host }
    val discovered = TvDiscovery.discovered.filter { it.ip !in known }

    if (showQr) QrCodeScannerDialog(
        onScanned = { qr ->
            showQr = false
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                val res = runCatching { TvLink.pairWithInfo(qr.host, qr.code, lang, android.os.Build.MODEL.orEmpty()) }
                withContext(Dispatchers.Main) {
                    res.onSuccess { pr -> Sync.recordPaired(c, qr.host, pr); Pairing.tick++ }
                    Toast.makeText(c, res.exceptionOrNull()?.message ?: I18n.get(lang, "pair.connected"), Toast.LENGTH_SHORT).show()
                }
            }
        },
        onDismiss = { showQr = false },
    )
    pinFor?.let { h -> PinPairDialog(h) { pinFor = null } }

    PageScaffold(I18n.get(lang, "profile.connect_tv"), I18n.get(lang, "connect.searching_wifi")) {
        // Radar 200 / 140 / 80 concentric stroked rings + 56 dp center phone disc + 3 floating 40 dp TV nodes
        Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
            listOf(200.dp to 0.15f, 140.dp to 0.27f, 80.dp to 0.39f).forEach { (d, a) ->
                Box(Modifier.size(d).clip(CircleShape).border(1.5.dp, Tahoe.Accent.copy(alpha = a), CircleShape))
            }
            Box(Modifier.size(56.dp).clip(CircleShape).background(Tahoe.Accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Smartphone, null, tint = Tahoe.OnAccent, modifier = Modifier.size(24.dp))
            }
            Box(
                Modifier.align(Alignment.TopStart).padding(start = 36.dp, top = 26.dp).size(40.dp).clip(CircleShape).background(Tahoe.Good),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Tv, null, tint = Color(0xFF0D0F14), modifier = Modifier.size(20.dp)) }
            Box(
                Modifier.align(Alignment.TopEnd).padding(end = 36.dp, top = 54.dp).size(40.dp).clip(CircleShape)
                    .background(if (ThemePrefs.dark) Color.White.copy(alpha = 0.12f) else Tahoe.Glass),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Tv, null, tint = Tahoe.Text, modifier = Modifier.size(20.dp)) }
            Box(
                Modifier.align(Alignment.BottomEnd).padding(end = 68.dp, bottom = 14.dp).size(40.dp).clip(CircleShape)
                    .background(if (ThemePrefs.dark) Color.White.copy(alpha = 0.12f) else Tahoe.Glass),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Tv, null, tint = Tahoe.Text, modifier = Modifier.size(20.dp)) }
        }

        MenuGroup(I18n.get(lang, "connect.found_eyebrow")) {
            if (devState.devices.isEmpty() && discovered.isEmpty() && Demo.isActive()) {
                MenuRow(Icons.Rounded.Tv, "Living Room TV", I18n.get(lang, "connect.active"), Tahoe.Good) { Nav.tab = 2; Nav.page = null }
                MenuRow(Icons.Rounded.Tv, "Bedroom TV", I18n.get(lang, "remote.tap_connect")) { Nav.tab = 2; Nav.page = null }
            }
            devState.devices.forEach { d ->
                MenuRow(Icons.Rounded.Tv, d.name.ifBlank { "Tahoe TV" }, I18n.get(lang, "connect.active"), Tahoe.Good) {
                    Sync.saveDevices(c, devState.select(d.id)); Pairing.tick++; Nav.tab = 2; Nav.page = null
                }
            }
            discovered.forEach { f ->
                MenuRow(Icons.Rounded.Tv, f.name ?: "Tahoe TV", I18n.get(lang, "remote.tap_connect"), Tahoe.Accent) {
                    NoCodePair.start(c, f.ip, f.name ?: "Tahoe TV") { pinFor = it }
                }
            }
            MenuRow(Icons.Rounded.Download, I18n.get(lang, "tv.install_on_tv"), I18n.get(lang, "connect.no_tahoe_install"), Color(Brand.SUN)) {
                Nav.page = "setup"
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassPill(I18n.get(lang, "remote.qr_short"), Icons.Rounded.QrCodeScanner, Modifier.weight(1f), fullWidth = true) { showQr = true }
            GlassPill(I18n.get(lang, "connect.pin_short"), Icons.Rounded.Pin, Modifier.weight(1f), fullWidth = true) { pinFor = "" }
        }
        Text(
            I18n.get(lang, "connect.diff_wifi_note"),
            color = Tahoe.Faint, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Laporkan masalah (Figma 23:588): wrapping issue chips, channel thumb picker, 120 dp notes, attachment switch, log share. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportPage() {
    val c = LocalContext.current
    val lang = PhoneRegion.language
    val types = listOf(
        I18n.get(lang, "report.type_stream"),
        I18n.get(lang, "report.type_sync"),
        I18n.get(lang, "report.type_remote"),
        I18n.get(lang, "report.type_other"),
    )
    var selectedType by remember { mutableIntStateOf(0) }
    val allEntries = Channels.entries ?: if (Demo.isActive()) demoEntries() else emptyList()
    var pickedEntry by remember { mutableStateOf<LocalTv.Entry?>(allEntries.firstOrNull()) }
    var channelDialogOpen by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf("") }
    var attachScreenshot by remember { mutableStateOf(true) }

    if (channelDialogOpen) AlertDialog(
        onDismissRequest = { channelDialogOpen = false },
        title = { Text(I18n.get(lang, "report.channel_label")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MenuRow(Icons.Rounded.Tv, I18n.get(lang, "report.channel_any"), if (pickedEntry == null) "✓" else null) {
                    pickedEntry = null; channelDialogOpen = false
                }
                allEntries.take(30).forEach { entry ->
                    MenuRow(Icons.Rounded.Tv, entry.name, if (entry == pickedEntry) "✓" else null) {
                        pickedEntry = entry; channelDialogOpen = false
                    }
                }
            }
        },
        confirmButton = { TextButton({ channelDialogOpen = false }) { Text(I18n.get(lang, "common.close")) } },
        containerColor = Tahoe.Surface,
    )

    PageScaffold(I18n.get(lang, "profile.report"), I18n.get(lang, "report.page_sub")) {
        Text(I18n.get(lang, "report.issue_type").uppercase(), fontSize = 11.sp, color = Tahoe.Faint, fontWeight = FontWeight.SemiBold, letterSpacing = 1.32.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            types.forEachIndexed { idx, label ->
                Chip(label, idx == selectedType) { selectedType = idx }
            }
        }
        Text(I18n.get(lang, "report.channel_label").uppercase(), fontSize = 11.sp, color = Tahoe.Faint, fontWeight = FontWeight.SemiBold, letterSpacing = 1.32.sp)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(18.dp)).background(Tahoe.Surface)
                .border(1.dp, Tahoe.Stroke.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                .clickable { channelDialogOpen = true }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            pickedEntry?.let { entry ->
                ChannelArt(entry, Modifier.size(44.dp, 26.dp).clip(RoundedCornerShape(6.dp)), logoScale = 0.7f)
            } ?: Icon(Icons.Rounded.Tv, null, tint = Tahoe.Dim, modifier = Modifier.size(20.dp))
            Text(
                pickedEntry?.let { ch ->
                    val show = nowOn(Channels.epg, ch, Instant.now())?.title
                    listOfNotNull(ch.name, show).joinToString(" · ")
                } ?: I18n.get(lang, "report.channel_any"),
                fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Icon(Icons.Rounded.ExpandMore, null, tint = Tahoe.Faint, modifier = Modifier.size(18.dp))
        }
        Text(I18n.get(lang, "report.detail_label").uppercase(), fontSize = 11.sp, color = Tahoe.Faint, fontWeight = FontWeight.SemiBold, letterSpacing = 1.32.sp)
        OutlinedTextField(
            detail,
            { detail = it },
            placeholder = { Text(I18n.get(lang, "report.detail_hint"), color = Tahoe.Faint) },
            minLines = 4,
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Tahoe.Accent,
                unfocusedBorderColor = Tahoe.Stroke.copy(alpha = 0.4f),
                focusedContainerColor = Tahoe.Surface,
                unfocusedContainerColor = Tahoe.Surface,
            ),
        )
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(18.dp)).background(Tahoe.Surface)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(if (ThemePrefs.dark) Color.White.copy(alpha = 0.08f) else Tahoe.Glass),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Image, null, tint = Tahoe.Accent, modifier = Modifier.size(20.dp)) }
            Text(
                if (attachScreenshot) I18n.get(lang, "report.attached_file", "diag.log") else I18n.get(lang, "report.attach_screenshot"),
                fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f),
            )
            FigmaSwitch(attachScreenshot, { attachScreenshot = it })
        }
        AccentPillButton(I18n.get(lang, "report.submit_btn"), fullWidth = true) {
            val chName = pickedEntry?.name ?: I18n.get(lang, "report.channel_any")
            tech.kousozo.tahoetv.core.DiagLog.add("user", "Issue: ${types[selectedType]} | Channel: $chName | Note: $detail")
            Report.send(c) { note -> Toast.makeText(c, note ?: I18n.get(lang, "common.done"), Toast.LENGTH_LONG).show() }
        }
    }
}

/** Pusat bantuan (Figma 23:625): search, 3 equal topic tiles, single grouped FAQ card, GitHub row. */
@Composable
fun HelpCenterPage() {
    val c = LocalContext.current
    val lang = PhoneRegion.language
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableIntStateOf(0) }
    val topics = listOf(
        Triple(Icons.Rounded.Tv, I18n.get(lang, "help.topic_install"), 0),
        Triple(Icons.Rounded.Wifi, I18n.get(lang, "help.topic_remote"), 1),
        Triple(Icons.Rounded.LiveTv, I18n.get(lang, "help.topic_channels"), 2),
    )
    val faqs = listOf(
        I18n.get(lang, "help.q1") to I18n.get(lang, "help.a1"),
        I18n.get(lang, "help.q2") to I18n.get(lang, "help.a2"),
        I18n.get(lang, "help.q3") to I18n.get(lang, "help.a3"),
        I18n.get(lang, "help.q4") to I18n.get(lang, "help.a4"),
    ).filter { query.isBlank() || it.first.contains(query, ignoreCase = true) || it.second.contains(query, ignoreCase = true) }

    PageScaffold(I18n.get(lang, "profile.help_center")) {
        SearchPill(query, { query = it }, I18n.get(lang, "help.search_hint"), active = query.isNotBlank())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            topics.forEach { (icon, title, idx) ->
                Column(
                    Modifier.weight(1f).heightIn(min = 94.dp).clip(RoundedCornerShape(20.dp)).background(Tahoe.Surface)
                        .border(1.dp, Tahoe.Stroke.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                        .clickable { expanded = idx }.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(Tahoe.Accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = Tahoe.Accent, modifier = Modifier.size(18.dp))
                    }
                    Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(I18n.get(lang, "help.faq_eyebrow").uppercase(), fontSize = 11.sp, color = Tahoe.Faint, fontWeight = FontWeight.SemiBold, letterSpacing = 1.32.sp)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Tahoe.Surface)
                .border(1.dp, Tahoe.Stroke.copy(alpha = 0.3f), RoundedCornerShape(26.dp))
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            faqs.forEachIndexed { idx, (q, a) ->
                if (idx > 0) HorizontalDivider(color = Tahoe.Stroke.copy(alpha = 0.25f))
                Column(
                    Modifier.fillMaxWidth().clickable { expanded = if (expanded == idx) -1 else idx }.padding(vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(q, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Icon(if (expanded == idx) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = Tahoe.Dim, modifier = Modifier.size(18.dp))
                    }
                    if (expanded == idx) Text(a, fontSize = 14.sp, color = Tahoe.Dim, lineHeight = 20.sp)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(22.dp))
                .background(if (ThemePrefs.dark) Color.White.copy(alpha = 0.06f) else Tahoe.Surface)
                .border(1.dp, Tahoe.Stroke.copy(alpha = 0.25f), RoundedCornerShape(22.dp))
                .clickable { open(c, "$REPO/discussions") }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Rounded.Forum, null, tint = Tahoe.Text, modifier = Modifier.size(20.dp))
            Text(I18n.get(lang, "help.ask_github"), fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Tahoe.Faint, modifier = Modifier.size(18.dp))
        }
    }
}

/** Kontributor (Figma 23:683): 3 stat tiles, 4-column colored avatar grid, primary + glass full buttons. */
@Composable
fun ContributorsPage() {
    val c = LocalContext.current
    val lang = PhoneRegion.language
    val contributors = remember {
        listOf(
            Triple("FK", Color(0xFF63D1FF), "@fk0u"),
            Triple("RI", Color(0xFF4DE6B3), "@rina"),
            Triple("AD", Color(0xFFD973FF), "@adit"),
            Triple("ME", Color(0xFFFFCC4D), "@mei"),
            Triple("FA", Color(0xFF6699FF), "@faris"),
            Triple("LA", Color(0xFFFF5980), "@layla"),
            Triple("YO", Color(0xFF80E666), "@yosef"),
            Triple("BU", Color(0xFFFF8A5C), "@budi"),
        )
    }
    PageScaffold(I18n.get(lang, "profile.contributors"), I18n.get(lang, "contrib.sub")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                "42" to I18n.get(lang, "contrib.stat_people"),
                "1,2k" to I18n.get(lang, "contrib.stat_stars"),
                "4" to I18n.get(lang, "contrib.stat_langs"),
            ).forEach { (num, label) ->
                Column(
                    Modifier.weight(1f).heightIn(min = 85.dp).clip(RoundedCornerShape(26.dp)).background(Tahoe.Surface)
                        .border(1.dp, Tahoe.Stroke.copy(alpha = 0.25f), RoundedCornerShape(26.dp)).padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(num, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Tahoe.Text)
                    Text(label, fontSize = 12.sp, color = Tahoe.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(I18n.get(lang, "contrib.top_eyebrow").uppercase(), fontSize = 11.sp, color = Tahoe.Faint, fontWeight = FontWeight.SemiBold, letterSpacing = 1.32.sp)
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            contributors.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { (initials, color, handle) ->
                        Column(
                            Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable {
                                open(c, if (handle == "@fk0u") "https://github.com/fk0u" else "$REPO/graphs/contributors")
                            }.padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(Modifier.size(60.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                                Text(initials, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0D0F14))
                            }
                            Text(handle, fontSize = 12.sp, color = Tahoe.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        PillButton(I18n.get(lang, "contrib.see_all_gh"), fullWidth = true) { open(c, "$REPO/graphs/contributors") }
        GlassPill(I18n.get(lang, "contrib.how_to"), fullWidth = true) { open(c, "$REPO/blob/main/CONTRIBUTING.md") }
    }
}

/** Code-free pairing on the same Wi-Fi (#180): ask the TV, the viewer presses OK there, the phone gets its token.
 *  Falls back to the 6-digit code when the TV declines, does not answer in 60 s, or is unreachable. */
object NoCodePair {
    /** TV being asked (address and name), or null. */
    var waiting by mutableStateOf<Pair<String, String>?>(null); private set
    private var job: kotlinx.coroutines.Job? = null

    fun start(c: android.content.Context, host: String, tvName: String, fallback: (String) -> Unit) {
        val lang = PhoneRegion.language
        cancel()
        waiting = host to tvName
        job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val outcome: Any = runCatching {
                val id = tech.kousozo.tahoetv.core.TvLink.requestPair(host, android.os.Build.MODEL.orEmpty().ifBlank { "HP" }, lang)
                var st: Any = "pending"
                val until = System.currentTimeMillis() + 62_000
                while (st == "pending" && System.currentTimeMillis() < until && waiting != null) {
                    kotlinx.coroutines.delay(1200)
                    st = runCatching { tech.kousozo.tahoetv.core.TvLink.pairStatus(host, id) }.getOrDefault("pending")
                }
                st
            }.getOrElse { it }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (waiting == null) return@withContext // cancelled by the user
                waiting = null
                when (outcome) {
                    is tech.kousozo.tahoetv.core.TvLink.PairResult -> {
                        Sync.recordPaired(c, host, outcome)
                        Sync.saveDevices(c, Sync.devices(c).select(outcome.id.ifBlank { host }))
                        Pairing.tick++
                        Toast.makeText(c, I18n.get(lang, "pair.connected"), Toast.LENGTH_SHORT).show()
                        Nav.tab = 2
                    }
                    "denied" -> Toast.makeText(c, I18n.get(lang, "pair.denied"), Toast.LENGTH_LONG).show()
                    else -> { Toast.makeText(c, I18n.get(lang, "pair.timeout"), Toast.LENGTH_LONG).show(); fallback(host) }
                }
            }
        }
    }

    fun cancel() { waiting = null; job?.cancel(); job = null }
}

/** "Konfirmasi di TV" while [NoCodePair] waits. */
@Composable
fun NoCodePairDialog() {
    val (host, name) = NoCodePair.waiting ?: return
    val lang = PhoneRegion.language
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { NoCodePair.cancel() },
        icon = { Icon(Icons.Rounded.Tv, null, tint = Tahoe.Accent) },
        title = { Text(I18n.get(lang, "pair.waiting")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(I18n.get(lang, "pair.waiting_sub", name.ifBlank { host }), color = Tahoe.Dim)
            androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth(), color = Tahoe.Accent, trackColor = Tahoe.Glass)
        } },
        confirmButton = { androidx.compose.material3.TextButton({ NoCodePair.cancel() }) { Text(I18n.get(lang, "common.cancel"), color = Tahoe.Dim) } },
        containerColor = Tahoe.Surface,
    )
}

/** The area under the tabs that hosts [Nav.page]. */
@Composable
fun NavPage(page: String) {
    when (page) {
        "history" -> HistoryPage()
        "notifications" -> NotificationsPage()
        "settings" -> SettingsPage()
        "search" -> SearchPage()
        "connect" -> ConnectTvPage()
        "report" -> ReportPage()
        "help" -> HelpCenterPage()
        "contributors" -> ContributorsPage()
        "recap" -> RecapPage()
        "explore" -> { BackHandler { Nav.page = null }; Box(Modifier.fillMaxSize().background(Tahoe.Bg)) { CategoriesScreen() } }
        "setup" -> { BackHandler { Nav.page = null }; Box(Modifier.fillMaxSize().background(Tahoe.Bg)) { SetupScreen(onExit = { Nav.page = null }) } }
    }
}
