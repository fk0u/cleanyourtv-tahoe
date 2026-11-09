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
        val dark = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(dark, dark)
        setContent {
            TahoeTheme {
                Box(Modifier.fillMaxSize()) {
                    Aurora()
                    AnimatedContent(Nav.tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
                        when (t) {
                            0 -> HomeScreen(resumed.intValue)
                            1 -> LiveScreen()
                            2 -> RemoteScreen()
                            3 -> CategoriesScreen()
                            else -> SetupScreen(onExit = { Nav.tab = 0 })
                        }
                    }
                    TabBar(listOf("Beranda" to Icons.Rounded.Home, "TV Live" to Icons.Rounded.LiveTv, "Remote" to Icons.Rounded.SettingsRemote,
                        "Kategori" to Icons.Rounded.GridView, "Pasang" to Icons.Rounded.Tv), Nav.tab, { Nav.tab = it }, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }

    override fun onResume() { super.onResume(); resumed.intValue++ }
}

/** Space for the floating tab bar (with its raised centre button) under scrolling content. */
val TabInset = 104.dp

// ============================================================================ Beranda

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(resumeTick: Int) {
    val c = LocalContext.current
    val p = remember { Sync.prefs(c) }
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(p.getString("host", "").orEmpty()) }
    var paired by remember { mutableStateOf(p.getString("token", "").orEmpty().isNotEmpty()) }
    var code by remember { mutableStateOf("") }
    var showPair by remember { mutableStateOf(false) }
    var notif by remember { mutableStateOf(p.getBoolean("notif", true)) }
    var media by remember { mutableStateOf(p.getBoolean("media", true)) }
    val granted = remember(resumeTick) {
        Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners").orEmpty()
            .contains(ComponentName(c, Sync::class.java).flattenToString())
    }
    val name = remember { c.getSharedPreferences("setup", 0).getString("name", "").orEmpty() }
    val hello = when (LocalTime.now().hour) { in 4..10 -> "Selamat pagi"; in 11..14 -> "Selamat siang"; in 15..17 -> "Selamat sore"; else -> "Selamat malam" }
    LaunchedEffect(Unit) {
        if (host.isEmpty()) Scan.run(wifiAddress(c)) { f -> if (f.tahoe && host.isEmpty()) scope.launch { host = f.ip } }
    }
    LaunchedEffect(Unit) { Channels.load(c) }
    val entries = Channels.entries
    val now = Instant.now()

    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = TabInset),
        verticalArrangement = Arrangement.spacedBy(22.dp)) {
        // header
        Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandTile(46.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text((hello + if (name.isNotEmpty()) ", $name" else "").uppercase(), color = Tahoe.Faint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
                Text("Beranda", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
            RoundIcon(if (paired) Icons.Rounded.Tv else Icons.Rounded.TvOff, if (paired) Tahoe.Good else Tahoe.Dim, Tahoe.Glass) { showPair = !showPair }
            Spacer(Modifier.width(10.dp))
            RoundIcon(Icons.Rounded.AutoAwesome, Tahoe.OnAccent, Tahoe.Accent) { Nav.tab = 2 }
        }

        // hero carousel
        val slides = buildList {
            val pick = Channels.recent(c).firstOrNull() ?: entries?.firstOrNull()
            pick?.let { e -> add(Slide("SEDANG TAYANG", e.name, nowOn(Channels.epg, e, now)?.title ?: "Tonton langsung di HP atau TV", e.channel.logo, Icons.Rounded.PlayArrow) { Nav.play(e) }) }
            add(if (paired) Slide("TV KAMU", "Tonton di layar besar", "Remote, Live TV dan asisten dari HP", null, Icons.Rounded.SettingsRemote) { Nav.tab = 2 }
                else Slide("MULAI", "Pasang Tahoe ke TV", "Tanpa laptop, cukup Wi-Fi yang sama", null, Icons.Rounded.Download) { Nav.tab = 4 })
            add(Slide("JELAJAHI", "Pilih kategori saluran", "Nasional, TVRI daerah, TV lokal, berita", null, Icons.Rounded.GridView) { Nav.tab = 3 })
        }
        val pager = rememberPagerState { slides.size }
        LaunchedEffect(slides.size) { while (true) { delay(5500); if (slides.isNotEmpty()) pager.animateScrollToPage((pager.currentPage + 1) % slides.size) } }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HorizontalPager(pager, contentPadding = PaddingValues(horizontal = 20.dp), pageSpacing = 12.dp) { i -> HeroCard(slides[i]) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                repeat(slides.size) { i ->
                    val on = i == pager.currentPage
                    Box(Modifier.padding(horizontal = 3.dp).size(if (on) 18.dp else 6.dp, 6.dp).clip(CircleShape).background(if (on) Tahoe.Text else Tahoe.Faint))
                }
            }
        }

        // recently watched
        val recent = Channels.recent(c).ifEmpty { entries.orEmpty().take(8) }
        if (recent.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader(if (Channels.recent(c).isEmpty()) "Saluran utama" else "Terakhir ditonton") { Nav.category(null) }
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(recent, key = { it.channel.url }) { e ->
                    Column(Modifier.width(66.dp).clickable { Nav.play(e) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(62.dp).clip(CircleShape).background(Color.White).border(2.dp, Tahoe.Accent.copy(alpha = 0.5f), CircleShape),
                            contentAlignment = Alignment.Center) {
                            rememberNetImage(e.channel.logo)?.let { Image(it, e.name, Modifier.padding(10.dp).fillMaxSize(), contentScale = ContentScale.Fit) }
                                ?: Text(e.name.take(3).uppercase(), color = Color(0xFF111114), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(e.name, fontSize = 11.sp, color = Tahoe.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }

        // categories: one tall card + two stacked
        entries?.let { all ->
            val cats = LocalTv.Category.entries.map { k -> k to all.count { LocalTv.inCategory(it, k) } }.filter { it.second > 0 }
            if (cats.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader("Kategori") { Nav.tab = 3 }
                Row(Modifier.padding(horizontal = 20.dp).height(236.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CategoryCard(cats[0].first, cats[0].second, Modifier.weight(1f).fillMaxHeight(), big = true)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        cats.getOrNull(1)?.let { CategoryCard(it.first, it.second, Modifier.weight(1f).fillMaxWidth()) }
                        cats.getOrNull(2)?.let { CategoryCard(it.first, it.second, Modifier.weight(1f).fillMaxWidth()) }
                    }
                }
            }
        } ?: if (Channels.failed) Box(Modifier.padding(horizontal = 20.dp)) { Note("Daftar saluran tidak bisa dimuat. Cek internet.", Icons.Rounded.ErrorOutline) }
            else LinearProgressIndicator(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), color = Tahoe.Accent, trackColor = Tahoe.Glass)

        // TV + phone-to-TV
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("TV kamu", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background((if (paired) Tahoe.Good else Tahoe.Faint).copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Tv, null, tint = if (paired) Tahoe.Good else Tahoe.Dim) }
                    Column(Modifier.weight(1f)) {
                        Text(if (paired) "Tersambung ke TV" else "Belum ada TV", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (paired) "$host · ${Sync.status.value}" else "Pasang Tahoe atau sambungkan dengan kode", color = Tahoe.Dim, fontSize = 12.sp)
                    }
                    TextButton({ showPair = !showPair }) { Text(if (showPair) "Tutup" else if (paired) "Ganti" else "Sambungkan", color = Tahoe.Accent) }
                }
                if (showPair) {
                    Text("Di TV: Pusat Kontrol → HP, lalu masukkan kodenya.", color = Tahoe.Dim, fontSize = 12.sp)
                    Field(host, { host = it }, "Alamat TV (terdeteksi otomatis)", KeyboardType.Uri)
                    Field(code, { if (it.length <= 6) code = it.filter(Char::isDigit) }, "Kode 6 digit", KeyboardType.NumberPassword)
                    PrimaryButton("Sambungkan", enabled = host.isNotBlank() && code.length == 6, icon = Icons.Rounded.Link) {
                        scope.launch {
                            val err = withContext(Dispatchers.IO) {
                                runCatching { TvLink.pair(host.trim(), code) }.fold({ token ->
                                    p.edit().putString("host", host.trim()).putString("token", token).apply(); null
                                }, { it.message })
                            }
                            if (err == null) { code = ""; paired = true; showPair = false; Sync.inst?.connect() }
                            Toast.makeText(c, err ?: "Tersambung", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                HorizontalDivider(color = Tahoe.Stroke)
                if (!granted) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Akses notifikasi belum diizinkan", Modifier.weight(1f), fontSize = 14.sp, color = Tahoe.Dim)
                    TextButton({ c.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Izinkan", color = Tahoe.Accent) }
                }
                SwitchRow("Teruskan notifikasi HP ke TV", notif) { notif = it; p.edit().putBoolean("notif", it).apply() }
                SwitchRow("Tampilkan musik yang diputar", media) { media = it; p.edit().putBoolean("media", it).apply(); Sync.inst?.sendMedia() }
                if (host.isNotBlank()) TextButton({ c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://${host.trim()}:8765/"))) }) {
                    Icon(Icons.Rounded.SportsEsports, null, tint = Tahoe.Accent); Spacer(Modifier.width(8.dp)); Text("Buka gamepad (web)", color = Tahoe.Accent)
                }
            }
            val version = remember { runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() }
            Text("Tahoe TV $version", Modifier.fillMaxWidth(), color = Tahoe.Faint, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

private class Slide(val eyebrow: String, val title: String, val sub: String, val logo: String?, val icon: ImageVector, val go: () -> Unit)

@Composable
private fun HeroCard(s: Slide) = Box(
    Modifier.fillMaxWidth().height(186.dp).clip(RoundedCornerShape(26.dp))
        .background(Brush.linearGradient(listOf(Color(0xFF0E3A52), Color(0xFF0B1B3A), Tahoe.Bg)))
        .border(1.dp, Brush.verticalGradient(listOf(Tahoe.Accent.copy(alpha = 0.55f), Color.White.copy(alpha = 0.04f))), RoundedCornerShape(26.dp))
        .clickable(onClick = s.go),
) {
    // accent light from the top-right corner + artwork
    Box(Modifier.matchParentSize().background(Brush.radialGradient(listOf(Tahoe.Accent.copy(alpha = 0.45f), Color.Transparent),
        center = androidx.compose.ui.geometry.Offset(900f, -80f), radius = 650f)))
    Box(Modifier.align(Alignment.CenterEnd).padding(end = 18.dp).size(112.dp, 84.dp).clip(RoundedCornerShape(18.dp))
        .background(if (s.logo != null) Color.White else Color.White.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
        val img = rememberNetImage(s.logo)
        if (img != null) Image(img, null, Modifier.padding(12.dp).fillMaxSize(), contentScale = ContentScale.Fit)
        else Icon(s.icon, null, tint = Tahoe.Accent, modifier = Modifier.size(44.dp))
    }
    Column(Modifier.align(Alignment.BottomStart).padding(20.dp).fillMaxWidth(0.62f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (s.eyebrow == "SEDANG TAYANG") { LiveDot(); Spacer(Modifier.width(6.dp)) }
            Text(s.eyebrow, fontSize = 11.sp, letterSpacing = 1.2.sp, color = Tahoe.Accent, fontWeight = FontWeight.Bold)
        }
        Text(s.title, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, fontStyle = FontStyle.Italic, lineHeight = 26.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(s.sub, fontSize = 12.sp, color = Tahoe.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LiveDot() = Box(Modifier.size(7.dp).clip(CircleShape).background(Tahoe.Bad))

@Composable
private fun SectionHeader(title: String, onAll: () -> Unit) = Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(title, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
    Row(Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onAll).padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Lihat semua", fontSize = 12.sp, color = Tahoe.Dim)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Tahoe.Dim, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, tint: Color, bg: Color, onClick: () -> Unit) =
    Box(Modifier.size(42.dp).clip(CircleShape).background(bg).border(1.dp, Tahoe.Stroke, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) }

/** Tinted glass card for a category: icon, LIVE badge, label, channel count. Opens TV Live filtered to it. */
@Composable
fun CategoryCard(k: LocalTv.Category, count: Int, modifier: Modifier, big: Boolean = false) {
    val tint = categoryTint(k)
    Box(modifier.clip(RoundedCornerShape(22.dp))
        .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.30f), Color(0xFF0C0E14))))
        .border(1.dp, Brush.verticalGradient(listOf(tint.copy(alpha = 0.55f), Color.White.copy(alpha = 0.04f))), RoundedCornerShape(22.dp))
        .clickable { Nav.category(k) }.padding(14.dp)) {
        Text("● LIVE", Modifier.align(Alignment.TopStart).clip(RoundedCornerShape(6.dp)).background(Tahoe.Bad).padding(horizontal = 6.dp, vertical = 2.dp),
            color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Icon(categoryIcon(k), null, tint = tint, modifier = Modifier.align(if (big) Alignment.Center else Alignment.TopEnd).size(if (big) 64.dp else 26.dp))
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(k.label, fontSize = if (big) 18.sp else 15.sp, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$count saluran", fontSize = 12.sp, color = tint, fontWeight = FontWeight.SemiBold)
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
