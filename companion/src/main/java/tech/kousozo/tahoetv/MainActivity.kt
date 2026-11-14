package tech.kousozo.tahoetv

import tech.kousozo.tahoetv.core.Brand
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import java.time.Instant
import tech.kousozo.tahoetv.core.LocalTv
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.kousozo.tahoetv.core.Scan
import tech.kousozo.tahoetv.core.TvLink

/** Five tabs: Beranda, TV Live, Remote (raised centre button), Kategori, Pasang (phone-only setup wizard). */
class MainActivity : ComponentActivity() {
    private val resumed = mutableIntStateOf(0)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Report.install(this)
        if (b == null) pairFrom(intent)
        val dark = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(dark, dark)
        Channels.loadFavs(this)
        Playback.loadPrefs(this)
        PhoneRegion.load(this)
        setContent {
            TahoeTheme {
                if (PhoneRegion.needsSetup) { Box(Modifier.fillMaxSize()) { Backdrop(null); WelcomeScreen() }; return@TahoeTheme }
                Box(Modifier.fillMaxSize()) {
                    // the playing channel (or the hero channel on Beranda) tints the whole app, like album art in a music app
                    val tintLogo = rememberNetImage((Playback.entry ?: Nav.hero)?.channel?.logo)
                    Backdrop(tintLogo?.let { dominantColor(it) })
                    AnimatedContent(Nav.tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
                        when (t) {
                            0 -> HomeScreen(resumed.intValue)
                            1 -> LiveScreen()
                            2 -> RemoteScreen()
                            3 -> CategoriesScreen()
                            else -> SetupScreen(onExit = { Nav.tab = 0 })
                        }
                    }
                    Nav.detail?.let { e -> ChannelScreen(e) { Nav.detail = null } }
                    if (!Nav.full) Column(Modifier.align(Alignment.BottomCenter)) {
                        val lang = PhoneRegion.language
                        MiniPlayer(Nav.tab != 1 || Nav.detail != null)
                        TabBar(listOf(
                            tech.kousozo.tahoetv.core.I18n.get(lang, "nav.home") to Icons.Rounded.Home,
                            tech.kousozo.tahoetv.core.I18n.get(lang, "nav.live_short") to Icons.Rounded.LiveTv,
                            tech.kousozo.tahoetv.core.I18n.get(lang, "nav.remote") to Icons.Rounded.SettingsRemote,
                            tech.kousozo.tahoetv.core.I18n.get(lang, "nav.explore") to Icons.Rounded.GridView,
                            tech.kousozo.tahoetv.core.I18n.get(lang, "nav.install") to Icons.Rounded.Tv,
                        ), Nav.tab, { Nav.tab = it; Nav.detail = null })
                    }
                }
            }
        }
    }

    override fun onResume() { super.onResume(); resumed.intValue++ }

    /** Live TV doesn't play on in the background (no picture-in-picture yet, see #63). */
    override fun onStop() { super.onStop(); Playback.pause() }

    override fun onDestroy() { if (isFinishing) Playback.release(); super.onDestroy() }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); pairFrom(intent) }

    /** Opened from the TV's pairing QR (http://<tv-ip>:8765/?code=123456): pair with that TV right away. */
    private fun pairFrom(i: Intent?) {
        val u = i?.data ?: return
        val host = u.host ?: return
        val code = u.getQueryParameter("code")?.takeIf { it.length == 6 && it.all(Char::isDigit) } ?: return
        if (u.port != 8765) return
        Toast.makeText(this, "Menyambungkan ke TV $host…", Toast.LENGTH_SHORT).show()
        Thread {
            val err = runCatching { TvLink.pair(host, code) }.fold({ token ->
                Sync.prefs(this).edit().putString("host", host).putString("token", token).apply(); null
            }, { it.message ?: "gagal" })
            runOnUiThread {
                if (err == null) { Pairing.tick++; Sync.inst?.connect(); Nav.tab = 2 } // straight to the remote
                Toast.makeText(this, if (err == null) "Tersambung ke TV" else "Gagal menyambung: $err. Minta kode baru di TV (OK).", Toast.LENGTH_LONG).show()
            }
        }.start()
    }
}

/** Bumped when this phone gets paired from outside the Beranda card (QR link), so screens re-read host and token. */
object Pairing { var tick by mutableIntStateOf(0) }

/** Space for the floating tab bar, plus the mini-player when it shows, under scrolling content. */
val TabInset: androidx.compose.ui.unit.Dp
    @Composable get() = if (Playback.entry != null && (Nav.tab != 1 || Nav.detail != null)) 172.dp else 100.dp

// ============================================================================ Beranda

/** Beranda (redesign #60): a full-bleed hero for the channel you watch most recently (its colour tints the app),
 *  then rows like a music app: Terakhir ditonton, Favorit, Jelajahi chips, a bento of numbers, and your TV. */
@Composable
fun HomeScreen(resumeTick: Int) {
    val c = LocalContext.current
    val p = remember { Sync.prefs(c) }
    val scope = rememberCoroutineScope()
    var host by remember(Pairing.tick) { mutableStateOf(p.getString("host", "").orEmpty()) }
    var paired by remember(Pairing.tick) { mutableStateOf(p.getString("token", "").orEmpty().isNotEmpty()) }
    var code by remember { mutableStateOf("") }
    var showPair by remember { mutableStateOf(false) }
    var notif by remember { mutableStateOf(p.getBoolean("notif", true)) }
    var media by remember { mutableStateOf(p.getBoolean("media", true)) }
    val granted = remember(resumeTick) {
        Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners").orEmpty()
            .contains(ComponentName(c, Sync::class.java).flattenToString())
    }
    val lang = PhoneRegion.language
    val name = remember { c.getSharedPreferences("setup", 0).getString("name", "").orEmpty() }
    val hello = tech.kousozo.tahoetv.core.I18n.greeting(lang, LocalTime.now().hour)
    LaunchedEffect(Unit) {
        if (host.isEmpty()) Scan.run(wifiAddress(c)) { f -> if (f.tahoe && host.isEmpty()) scope.launch { host = f.ip } }
    }
    LaunchedEffect(Unit) { Channels.load(c) }
    val entries = Channels.entries
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = Instant.now() } } // shows on now roll over at their end
    val recentWatched = Channels.recent(c)
    val hero = Playback.entry ?: recentWatched.firstOrNull() ?: entries?.firstOrNull()
    LaunchedEffect(hero) { Nav.hero = hero }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = TabInset),
        verticalArrangement = Arrangement.spacedBy(26.dp)) {
        // hero
        Box(Modifier.fillMaxWidth().height(440.dp)) {
            val logo = rememberNetImage(hero?.channel?.logo)
            if (logo != null) Image(logo, null, Modifier.fillMaxSize().blur(70.dp).alpha(0.45f), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Tahoe.Bg)))
            Row(Modifier.statusBarsPadding().padding(start = 20.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BrandMark(30.dp, tint = Color.White)
                Spacer(Modifier.weight(1f))
                RoundGlass(if (paired) Icons.Rounded.Tv else Icons.Rounded.TvOff, tech.kousozo.tahoetv.core.I18n.get(lang, if (paired) "home.tv_connected" else "tv.connect_title"),
                    tint = if (paired) Tahoe.Good else Tahoe.Text) { showPair = !showPair }
                Spacer(Modifier.width(8.dp))
                RoundGlass(Icons.Rounded.SettingsRemote, tech.kousozo.tahoetv.core.I18n.get(lang, "nav.remote")) { Nav.tab = 2 }
            }
            if (hero != null) Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text((hello + if (name.isNotEmpty()) ", $name" else "").uppercase(), color = Tahoe.Dim, fontSize = 12.sp, fontFamily = Mono, letterSpacing = 1.2.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                        if (logo != null) Image(logo, null, Modifier.padding(3.dp).fillMaxSize(), contentScale = ContentScale.Fit)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(when { Playback.entry != null -> tech.kousozo.tahoetv.core.I18n.get(lang, "home.now_watching"); recentWatched.isNotEmpty() -> tech.kousozo.tahoetv.core.I18n.get(lang, "home.continue_watching"); else -> tech.kousozo.tahoetv.core.I18n.get(lang, "home.featured_channel") },
                        fontSize = 14.sp, color = Tahoe.Text.copy(alpha = 0.85f))
                }
                Text(hero.name, fontSize = 44.sp, lineHeight = 46.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                nowOn(Channels.epg, hero, now)?.let { Text(it.title, fontSize = 15.sp, color = Tahoe.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PillButton(tech.kousozo.tahoetv.core.I18n.get(lang, "home.watch"), Icons.Rounded.PlayArrow) { Nav.play(hero) }
                    Spacer(Modifier.width(10.dp))
                    RoundGlass(Icons.Rounded.Info, tech.kousozo.tahoetv.core.I18n.get(lang, "home.channel_page", hero.name)) { Nav.detail = hero }
                }
            }
        }

        // pairing / TV card opens from the TV button
        if (showPair || !paired) TvCard(host, { host = it }, paired, code, { code = it }, showPair, { showPair = it }, granted, notif, { notif = it; p.edit().putBoolean("notif", it).apply() },
            media, { media = it; p.edit().putBoolean("media", it).apply(); Sync.inst?.sendMedia() }) { token ->
            p.edit().putString("host", host.trim()).putString("token", token).apply(); code = ""; paired = true; showPair = false; Sync.inst?.connect()
        }

        val recent = recentWatched.ifEmpty { entries.orEmpty().take(8) }
        if (recent.isNotEmpty()) Shelf(tech.kousozo.tahoetv.core.I18n.get(lang, if (recentWatched.isEmpty()) "home.featured_channels" else "home.recently_watched"), recent, now) { Nav.category(null) }
        val favs = entries.orEmpty().filter { it.channel.url in Channels.favs }
        if (favs.isNotEmpty()) Shelf(tech.kousozo.tahoetv.core.I18n.get(lang, "home.favorites"), favs, now) { Nav.category(null, favourites = true) }

        entries?.let { all ->
            val cats = LocalTv.Category.entries.map { k -> k to all.count { LocalTv.inCategory(it, k) } }.filter { it.second > 0 }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(tech.kousozo.tahoetv.core.I18n.get(lang, "nav.explore")) { Nav.tab = 3 }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    cats.forEach { (k, _) -> Chip(k.label(lang), false) { Nav.category(k) } }
                }
                // bento of numbers
                val near = all.count { it.near }
                Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Bento(tech.kousozo.tahoetv.core.I18n.get(lang, "home.all_channels"), all.size.toString(), Icons.Rounded.LiveTv, Modifier.weight(1f)) { Nav.category(null) }
                    Bento(if (near > 0) tech.kousozo.tahoetv.core.I18n.get(lang, "home.near_you") else tech.kousozo.tahoetv.core.I18n.get(lang, "home.categories"), (if (near > 0) near else cats.size).toString(), Icons.Rounded.Place, Modifier.weight(1f)) {
                        if (near > 0) Nav.category(LocalTv.Category.Near) else Nav.tab = 3
                    }
                }
            }
        } ?: if (Channels.failed) Box(Modifier.padding(horizontal = 20.dp)) { Note(tech.kousozo.tahoetv.core.I18n.get(lang, "home.channels_failed"), Icons.Rounded.ErrorOutline) }
            else LinearProgressIndicator(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), color = Tahoe.Text, trackColor = Tahoe.Glass)

        if (paired && !showPair) Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tech.kousozo.tahoetv.core.I18n.get(lang, "home.your_tv"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            GlassCard(onClick = { showPair = true }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Tahoe.Good.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Tv, null, tint = Tahoe.Good) }
                    Column(Modifier.weight(1f)) {
                        Text(tech.kousozo.tahoetv.core.I18n.get(lang, "home.connected_tv"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("$host · ${Sync.status.value}", color = Tahoe.Dim, fontSize = 12.sp, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    PillButton(tech.kousozo.tahoetv.core.I18n.get(lang, "nav.remote"), Icons.Rounded.SettingsRemote) { Nav.tab = 2 }
                }
            }
        }
        Box(Modifier.padding(horizontal = 20.dp)) {
            GlassCard(onClick = { Playback.stop(); PhoneRegion.reset() }) { // reopens the first-run setup
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(tech.kousozo.tahoetv.core.Region.flag(PhoneRegion.code), fontSize = 26.sp)
                    Column(Modifier.weight(1f)) {
                        Text(tech.kousozo.tahoetv.core.I18n.get(lang, "home.region_language"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(tech.kousozo.tahoetv.core.Region.name(PhoneRegion.code, lang) + " · " +
                            tech.kousozo.tahoetv.core.OnboardingText.LANGUAGE_NAMES.getValue(lang) +
                            " · " + PhoneRegion.viewer.countries.let { l -> l.take(5).joinToString(" ") { tech.kousozo.tahoetv.core.Region.flag(it) } + if (l.size > 5) " +${l.size - 5}" else "" },
                            color = Tahoe.Dim, fontSize = 12.sp, maxLines = 1)
                    }
                    PillButton(tech.kousozo.tahoetv.core.I18n.get(lang, "home.change")) { Playback.stop(); PhoneRegion.reset() }
                }
            }
        }
        Box(Modifier.padding(horizontal = 20.dp)) { UpdateCard() } // version, changelog, OTA
    }
}

/** Pairing + notification settings (was the "TV kamu" card). */
@Composable
private fun TvCard(host: String, setHost: (String) -> Unit, paired: Boolean, code: String, setCode: (String) -> Unit, open: Boolean, setOpen: (Boolean) -> Unit,
                   granted: Boolean, notif: Boolean, setNotif: (Boolean) -> Unit, media: Boolean, setMedia: (Boolean) -> Unit, onPaired: (String) -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val lang = PhoneRegion.language
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tech.kousozo.tahoetv.core.I18n.get(lang, if (paired) "home.your_tv" else "tv.connect_title"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        GlassCard {
            if (!paired && !open) {
                Text(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.connect_desc"), color = Tahoe.Dim, fontSize = 14.sp)
                Row { PillButton(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.connect"), Icons.Rounded.Link) { setOpen(true) }; Spacer(Modifier.width(8.dp)); TextButton({ Nav.tab = 4 }) { Text(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.install_on_tv"), color = Tahoe.Text) } }
                return@GlassCard
            }
            Text(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.pair_hint"), color = Tahoe.Dim, fontSize = 13.sp)
            Field(host, setHost, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.host_hint"), KeyboardType.Uri)
            Field(code, { if (it.length <= 6) setCode(it.filter(Char::isDigit)) }, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.code_hint"), KeyboardType.NumberPassword)
            PrimaryButton(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.connect"), enabled = host.isNotBlank() && code.length == 6, icon = Icons.Rounded.Link) {
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { TvLink.pair(host.trim(), code, lang) } }
                    r.onSuccess(onPaired)
                    Toast.makeText(c, r.exceptionOrNull()?.message ?: tech.kousozo.tahoetv.core.I18n.get(lang, "tv.connected_toast"), Toast.LENGTH_SHORT).show()
                }
            }
            HorizontalDivider(color = Tahoe.Stroke)
            if (!granted) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.notif_needed"), Modifier.weight(1f), fontSize = 14.sp, color = Tahoe.Dim)
                TextButton({ c.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.allow"), color = Tahoe.Text) }
            }
            SwitchRow(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.forward_notifs"), notif, setNotif)
            SwitchRow(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.show_media"), media, setMedia)
            if (host.isNotBlank()) TextButton({ c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://${host.trim()}:8765/"))) }) {
                Icon(Icons.Rounded.SportsEsports, null, tint = Tahoe.Text); Spacer(Modifier.width(8.dp)); Text(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.open_gamepad"), color = Tahoe.Text)
            }
            if (open) TextButton({ setOpen(false) }) { Text(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.close"), color = Tahoe.Dim) }
        }
    }
}

/** A horizontal shelf of square channel cards ("Recently played" style): logo on white, name, show on now. */
@Composable
private fun Shelf(title: String, items: List<LocalTv.Entry>, now: Instant, onAll: () -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    SectionHeader(title, onAll)
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(items, key = { it.channel.url }) { e ->
            val logo = rememberNetImage(e.channel.logo)
            val tint = logo?.let { dominantColor(it) } ?: categoryTint(e.category)
            Column(Modifier.width(128.dp).clip(RoundedCornerShape(18.dp)).clickable { Nav.play(e) }) {
                Box(Modifier.size(128.dp).clip(RoundedCornerShape(18.dp))
                    .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.95f), tint.copy(alpha = 0.45f)))), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(78.dp, 56.dp).clip(RoundedCornerShape(12.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                        if (logo != null) Image(logo, e.name, Modifier.padding(8.dp).fillMaxSize(), contentScale = ContentScale.Fit)
                        else Text(e.name.take(3).uppercase(), color = Color(0xFF141216), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(e.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                Text(nowOn(Channels.epg, e, now)?.title ?: e.category.label, fontSize = 12.sp, color = Tahoe.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A big-number glass card (bento). */
@Composable
private fun Bento(label: String, value: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) = Column(
    modifier.height(108.dp).clip(RoundedCornerShape(22.dp)).background(Tahoe.Glass).clickable(onClick = onClick).padding(14.dp),
    verticalArrangement = Arrangement.SpaceBetween,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Tahoe.Dim, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, color = Tahoe.Dim, maxLines = 1)
    }
    Text(value, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
}

@Composable
private fun SectionHeader(title: String, onAll: () -> Unit) = Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Lihat semua $title", tint = Tahoe.Dim,
        modifier = Modifier.padding(start = 2.dp).size(22.dp).clip(CircleShape).clickable(onClick = onAll))
}

/** Tinted glass card for a category: icon, LIVE badge, label, channel count. Opens TV Live filtered to it. */
@Composable
fun CategoryCard(k: LocalTv.Category, count: Int, modifier: Modifier, big: Boolean = false) {
    val lang = PhoneRegion.language
    val tint = categoryTint(k)
    Box(modifier.clip(RoundedCornerShape(22.dp))
        .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.30f), Color(0xFF0C0E14))))
        .border(1.dp, Brush.verticalGradient(listOf(tint.copy(alpha = 0.55f), Color.White.copy(alpha = 0.04f))), RoundedCornerShape(22.dp))
        .clickable { Nav.category(k) }.padding(14.dp)) {
        Text("● LIVE", Modifier.align(Alignment.TopStart).clip(RoundedCornerShape(6.dp)).background(Tahoe.Bad).padding(horizontal = 6.dp, vertical = 2.dp),
            color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Icon(categoryIcon(k), null, tint = tint, modifier = Modifier.align(if (big) Alignment.Center else Alignment.TopEnd).size(if (big) 64.dp else 26.dp))
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(k.label(lang), fontSize = if (big) 18.sp else 15.sp, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tech.kousozo.tahoetv.core.I18n.get(lang, "home.channel_count", count), fontSize = 12.sp, color = tint, fontWeight = FontWeight.SemiBold)
        }
    }
}

fun categoryIcon(k: LocalTv.Category?): ImageVector = when (k?.name) {
    null -> Icons.Rounded.GridView; "Near" -> Icons.Rounded.Place; "National" -> Icons.Rounded.Flag; "Tvri" -> Icons.Rounded.CellTower
    "Local" -> Icons.Rounded.LocationCity; "News" -> Icons.Rounded.Newspaper; "Religious" -> Icons.Rounded.Mosque; "Kids" -> Icons.Rounded.ChildCare
    "Sports" -> Icons.Rounded.SportsSoccer; "Movies" -> Icons.Rounded.Movie; "Entertainment" -> Icons.Rounded.TheaterComedy
    "Documentary" -> Icons.Rounded.Public; "Music" -> Icons.Rounded.MusicNote; "Lifestyle" -> Icons.Rounded.Restaurant; "Vod" -> Icons.Rounded.VideoLibrary
    else -> Icons.Rounded.LiveTv
}

/** Every tint is a shade around the brand accent so the grid stays one family. */
fun categoryTint(k: LocalTv.Category): Color = Color(Brand.CATEGORY_TINTS[k.ordinal % Brand.CATEGORY_TINTS.size])
