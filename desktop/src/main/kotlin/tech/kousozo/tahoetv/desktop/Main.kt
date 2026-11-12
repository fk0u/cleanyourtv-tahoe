package tech.kousozo.tahoetv.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import tech.kousozo.tahoetv.core.TvAdb

enum class Screen(val label: String, val icon: ImageVector) {
    Home("Beranda", Icons.Rounded.Home),
    Live("TV Live", Icons.Rounded.LiveTv),
    Recordings("Rekaman", Icons.Rounded.VideoLibrary),
    Tv("TV Rumah", Icons.Rounded.Tv),
    Remote("Remote", Icons.Rounded.SettingsRemote),
    Prefs("Setelan", Icons.Rounded.Settings),
}

/** The ADB session to the home TV, shared by "TV Rumah" (setup) and "Remote & Cermin". */
object Session {
    var tv by mutableStateOf<TvAdb?>(null)
}

fun main(args: Array<String>) {
    tech.kousozo.tahoetv.core.DiagLog.install(java.io.File(System.getProperty("user.home"), ".tahoetv/diag.log"))
    // `Tahoe TV --selftest [report]`: check the core paths without a window (CI on Windows/macOS, support)
    if (args.firstOrNull() == "--selftest") kotlin.system.exitProcess(if (SelfTest.run(args.getOrNull(1)) == 0) 0 else 1)
    app()
}

private fun app() = application {
    LaunchedEffect(Unit) {
        Recordings.boot() // scheduled recordings start on time while the app is open
        if (Settings.iptvAutoStart) { // loads the channel list over the network: not on the UI thread
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { IptvServer.start(Settings.iptvPort) } }
        }
    }
    val icon = remember { Screen::class.java.getResourceAsStream("/icon.png")?.use { BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap()) } }
    Window(
        onCloseRequest = {
            Session.tv?.close()
            PlayerWindow.close()
            IptvServer.stop()
            exitApplication()
        }, title = "Tahoe TV", icon = icon,
        state = rememberWindowState(size = DpSize(1280.dp, 820.dp)),
    ) {
        window.minimumSize = java.awt.Dimension(980, 640)
        TahoeTheme { App() }
    }
}

/** Cross-screen requests (top-bar search → TV Live). */
object DeskNav {
    var screen by mutableStateOf(Screen.Home)
    var query by mutableStateOf("")
    /** Settings → Wilayah & bahasa → Ubah: the first-run setup again (the saved country stays preselected). */
    var regionSetup by mutableStateOf(false)
}

@Composable
fun App(initial: Screen = Screen.Home, askRegion: Boolean = true) { // askRegion = false: snapshots skip the first-run setup
    LaunchedEffect(initial) { DeskNav.screen = initial }
    // first run (or "Ubah wilayah" in Settings): language + country before anything else
    if (askRegion && (Settings.country.isBlank() || DeskNav.regionSetup)) { SetupScreen(onDone = { DeskNav.regionSetup = false; DeskNav.screen = Screen.Live }); return }
    Box(Modifier.fillMaxSize()) {
        Aurora()
        Row(Modifier.fillMaxSize()) {
            Rail(DeskNav.screen) { DeskNav.screen = it }
            Column(Modifier.weight(1f)) {
                TopBar()
                AnimatedContent(DeskNav.screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen", modifier = Modifier.weight(1f)) { s ->
                    when (s) {
                        Screen.Home -> HomeScreen(onLive = { DeskNav.screen = Screen.Live })
                        Screen.Live -> LiveScreen()
                        Screen.Recordings -> RecordingsScreen()
                        Screen.Tv -> HomeTvScreen(onRemote = { DeskNav.screen = Screen.Remote })
                        Screen.Remote -> RemoteScreen(onSetup = { DeskNav.screen = Screen.Tv })
                        Screen.Prefs -> SettingsScreen()
                    }
                }
            }
        }
    }
}

/** Slim icon rail: logo on top, one round button per screen (active = filled accent), TV status + version at the bottom. */
@Composable
private fun Rail(selected: Screen, onSelect: (Screen) -> Unit) = Column(
    Modifier.width(84.dp).fillMaxHeight().padding(vertical = 18.dp, horizontal = 12.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
) {
    BrandTile(46.dp)
    Spacer(Modifier.height(26.dp))
    Column(Modifier.clip(RoundedCornerShape(30.dp)).background(Tahoe.Card).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Screen.entries.forEach { s ->
            val on = s == selected
            val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val hover by src.collectIsHoveredAsState()
            Box(Modifier.size(46.dp).clip(CircleShape)
                .background(if (on) Tahoe.Accent else if (hover) Tahoe.CardHi else Color.Transparent)
                .hoverable(src).clickable { onSelect(s) }, contentAlignment = Alignment.Center) {
                Icon(s.icon, s.label, tint = if (on) Tahoe.OnAccent else Tahoe.Dim, modifier = Modifier.size(21.dp))
            }
        }
    }
    Spacer(Modifier.weight(1f))
    val tv = Session.tv
    Box(Modifier.size(46.dp).clip(CircleShape).background(Tahoe.Card), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Cast, if (tv != null) tv.model else "TV belum tersambung", tint = if (tv != null) Tahoe.Good else Tahoe.Faint, modifier = Modifier.size(20.dp))
    }
    Spacer(Modifier.height(8.dp))
    Text("v" + (System.getProperty("jpackage.app-version") ?: "dev"), fontSize = 10.sp, color = Tahoe.Faint)
}

/** Greeting + search pill + clock + quick "Tonton Live" button. Search jumps to TV Live with the query. */
@Composable
private fun TopBar() {
    var now by remember { mutableStateOf(java.time.LocalTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = java.time.LocalTime.now(); kotlinx.coroutines.delay(15_000) } }
    var q by remember { mutableStateOf("") }
    val hello = when (now.hour) { in 4..10 -> "Selamat pagi"; in 11..14 -> "Selamat siang"; in 15..17 -> "Selamat sore"; else -> "Selamat malam" }
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 24.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Halo, ${Settings.name.ifBlank { "Tahoe" }}!", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("$hello · mau nonton apa hari ini?", fontSize = 13.sp, color = Tahoe.Dim)
        }
        Row(Modifier.width(340.dp).height(44.dp).clip(RoundedCornerShape(50)).background(Tahoe.Card)
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(50)).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, null, tint = Tahoe.Faint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            androidx.compose.foundation.text.BasicTextField(q, { q = it }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Tahoe.Text, fontSize = 14.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Tahoe.Accent),
                modifier = Modifier.weight(1f).onEnter { DeskNav.query = q.trim(); DeskNav.screen = Screen.Live },
                decorationBox = { inner -> Box { if (q.isEmpty()) Text("Cari saluran atau acara…", color = Tahoe.Faint, fontSize = 14.sp); inner() } })
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.height(44.dp).clip(RoundedCornerShape(50)).background(Tahoe.Card).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(now.format(java.time.format.DateTimeFormatter.ofPattern("HH.mm")), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(12.dp))
        Row(Modifier.height(44.dp).clip(RoundedCornerShape(50)).background(Tahoe.Accent).clickable { DeskNav.screen = Screen.Live }.padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.LiveTv, null, tint = Tahoe.OnAccent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Tonton Live", color = Tahoe.OnAccent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}
