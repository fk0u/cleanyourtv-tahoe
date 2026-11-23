package tech.kousozo.tahoetv.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuOpen
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.TooltipArea
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import tech.kousozo.tahoetv.core.TvAdb

enum class Screen(private val labelKey: String, val icon: ImageVector) {
    Home("nav.home", Icons.Rounded.Home),
    Live("nav.live", Icons.Rounded.LiveTv),
    Recordings("nav.recordings", Icons.Rounded.VideoLibrary),
    Tv("nav.tv", Icons.Rounded.Tv),
    Remote("nav.remote", Icons.Rounded.SettingsRemote),
    Prefs("nav.prefs", Icons.Rounded.Settings),
    Admin("nav.admin", Icons.Rounded.AdminPanelSettings);
    val label: String get() = if (labelKey == "nav.admin") "Admin" else tech.kousozo.tahoetv.core.I18n.get(Settings.lang, labelKey)
}

/** The ADB session to the home TV, shared by "TV Rumah" (setup) and "Remote & Cermin". */
object Session {
    var tv by mutableStateOf<TvAdb?>(null)
}

/** `Tahoe TV --screen=live`: open on that screen (support and screenshots); anything else starts on Home. */
private var startScreen = Screen.Home

fun main(args: Array<String>) {
    if ("--autoplay" in args) DeskNav.autoplay = true
    args.firstOrNull { it.startsWith("--whatsnew-from=") }?.substringAfter('=')?.let { DeskNav.whatsNewFrom = it }
    args.firstOrNull { it.startsWith("--screen=") }?.substringAfter('=')?.let { n -> Screen.entries.firstOrNull { it.name.equals(n, true) }?.let { startScreen = it } }
    tech.kousozo.tahoetv.core.DiagLog.install(java.io.File(System.getProperty("user.home"), ".tahoetv/diag.log"))
    args.firstOrNull { it == "--demo" || it.startsWith("--demo=") }?.let { flag ->
        tech.kousozo.tahoetv.core.Demo.enable(flag.substringAfter('=', "en").ifBlank { "en" })
    }
    // `Tahoe TV --selftest [report]`: check the core paths without a window (CI on Windows/macOS, support)
    if (args.firstOrNull() == "--selftest") kotlin.system.exitProcess(if (SelfTest.run(args.getOrNull(1)) == 0) 0 else 1)
    Recordings.onFinished = { j ->
        val msg = tech.kousozo.tahoetv.core.I18n.get(
            Settings.lang,
            if (j.state == "partial") "rec.done_partial" else "rec.done_notif",
            "${j.channel} — ${j.title}",
        )
        DesktopPush.postLocalNotification(tech.kousozo.tahoetv.core.I18n.get(Settings.lang, "rec.title"), msg, kind = "recording")
    }
    // `Tahoe TV --recorder`: headless scheduled DVR daemon + LAN relay without opening the GUI window (#53)
    if (args.firstOrNull() == "--recorder") {
        runHeadlessRecorder()
        return
    }
    app()
}

private fun runHeadlessRecorder() {
    tech.kousozo.tahoetv.core.DiagLog.add("recorder", "Tahoe headless recorder dimulai")
    Recordings.boot()
    Thread({ runCatching { IptvServer.ensureRunning() } }, "tahoe-rec-httpd").apply { isDaemon = true }.start()
    while (true) {
        runCatching { Thread.sleep(60_000L) }.onFailure { break }
    }
}

private fun app() = application {
    remember { Settings.applyAppearance() }
    LaunchedEffect(Unit) {
        Recordings.boot() // scheduled recordings start on time while the app is open
        DesktopPush.start()
        if (Settings.iptvAutoStart) { // loads the channel list over the network: not on the UI thread
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { IptvServer.start(Settings.iptvPort) } }
        }
    }
    val icon = remember { Screen::class.java.getResourceAsStream("/icon.png")?.use { BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap()) } }
    Window(
        onCloseRequest = {
            DesktopPush.stop()
            Session.tv?.close()
            MultiViewWindow.close()
            PlayerWindow.close()
            IptvServer.stop()
            exitApplication()
        },
        onPreviewKeyEvent = { ev ->
            if (ev.type == KeyEventType.KeyDown && (ev.isMetaPressed || ev.isCtrlPressed)) {
                when {
                    ev.isShiftPressed && ev.key == Key.A -> { DeskNav.screen = Screen.Admin; true }
                    !ev.isShiftPressed && ev.key == Key.One -> { DeskNav.screen = Screen.Home; true }
                    !ev.isShiftPressed && ev.key == Key.Two -> { DeskNav.screen = Screen.Live; true }
                    !ev.isShiftPressed && ev.key == Key.Three -> { DeskNav.screen = Screen.Recordings; true }
                    !ev.isShiftPressed && ev.key == Key.Four -> { DeskNav.screen = Screen.Tv; true }
                    !ev.isShiftPressed && ev.key == Key.Five -> { DeskNav.screen = Screen.Remote; true }
                    !ev.isShiftPressed && ev.key == Key.Six -> { DeskNav.screen = Screen.Prefs; true }
                    else -> false
                }
            } else false
        },
        title = "Tahoe TV", icon = icon,
        state = rememberWindowState(size = DpSize(1280.dp, 820.dp)),
    ) {
        window.minimumSize = java.awt.Dimension(980, 640)
        TahoeTheme { App(initial = startScreen) }
    }
}

/** Cross-screen requests (top-bar search → TV Live). */
object DeskNav {
    var screen by mutableStateOf(Screen.Home)
    var query by mutableStateOf("")
    /** `--autoplay`: the Live screen starts the first channel that is live (support and screenshots). */
    var autoplay by mutableStateOf(false)
    /** `--whatsnew-from=<version>`: pretend that version was the last one seen, to try the What's-new pop-up (support and screenshots). */
    var whatsNewFrom: String? = null
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
                AnimatedContent(
                    DeskNav.screen,
                    transitionSpec = {
                        if (ThemePrefs.reduceMotion) fadeIn(tween(180)) togetherWith fadeOut(tween(180))
                        else fadeIn(tween(tech.kousozo.tahoetv.core.Brand.MOTION_FAST, easing = EaseOutTahoe)) togetherWith
                            fadeOut(tween(160, easing = EaseOutTahoe))
                    },
                    label = "screen",
                    modifier = Modifier.weight(1f),
                ) { s ->
                    when (s) {
                        Screen.Home -> HomeScreen(onLive = { DeskNav.screen = Screen.Live })
                        Screen.Live -> LiveScreen()
                        Screen.Recordings -> RecordingsScreen()
                        Screen.Tv -> HomeTvScreen(onRemote = { DeskNav.screen = Screen.Remote })
                        Screen.Remote -> RemoteScreen(onSetup = { DeskNav.screen = Screen.Tv })
                        Screen.Prefs -> SettingsScreen()
                        Screen.Admin -> AdminScreen()
                    }
                }
            }
        }
        DesktopPushBanner(modifier = Modifier.align(Alignment.TopEnd))
        DesktopNotificationDialog()
        DesktopUpdateModal()
        DesktopWhatsNew(DeskNav.whatsNewFrom)
    }
}

/** Figma rail: 80 wide, logo on top, one 48 px icon button per screen (white when selected, a hover tooltip names it),
 *  and the home-TV status and version at the bottom. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Rail(selected: Screen, onSelect: (Screen) -> Unit) {
    val lang = Settings.lang
    Row(Modifier.width(80.dp).fillMaxHeight()) {
        Column(
            Modifier.weight(1f).fillMaxHeight().background(Color(0xFF07080D).copy(alpha = if (ThemePrefs.dark) 0.9f else 0f)).padding(vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            BrandTile(40.dp)
            Screen.entries.filter { it != Screen.Admin }.forEach { s ->
                val on = s == selected
                val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val hover by src.collectIsHoveredAsState()
                TooltipArea(
                    tooltip = {
                        Surface(shape = RoundedCornerShape(8.dp), color = Tahoe.CardHi, border = BorderStroke(1.dp, Tahoe.Stroke), shadowElevation = 4.dp) {
                            Text(s.label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 13.sp, color = Tahoe.Text)
                        }
                    },
                    delayMillis = 300,
                    tooltipPlacement = androidx.compose.foundation.TooltipPlacement.CursorPoint(offset = androidx.compose.ui.unit.DpOffset(16.dp, 0.dp)),
                ) {
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (on) Tahoe.Text else if (hover) Tahoe.CardHi else Color.Transparent)
                            .hoverable(src)
                            .selectable(selected = on, role = Role.Tab) { onSelect(s) }
                            .semantics(mergeDescendants = true) { contentDescription = s.label },
                        contentAlignment = Alignment.Center,
                    ) { Icon(s.icon, null, tint = if (on) Color(0xFF0D0F14) else Tahoe.Dim, modifier = Modifier.size(22.dp)) }
                }
            }
            Spacer(Modifier.weight(1f))
            val tv = Session.tv
            val tvLabel = if (tv != null) tv.model else tech.kousozo.tahoetv.core.I18n.get(lang, "home.tv_not_connected")
            TooltipArea(
                tooltip = {
                    Surface(shape = RoundedCornerShape(8.dp), color = Tahoe.CardHi, border = BorderStroke(1.dp, Tahoe.Stroke), shadowElevation = 4.dp) {
                        Text(tvLabel, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 13.sp, color = Tahoe.Text)
                    }
                },
                delayMillis = 300,
            ) {
                Icon(Icons.Rounded.Cast, tvLabel, tint = if (tv != null) Tahoe.Good else Tahoe.Faint, modifier = Modifier.size(22.dp))
            }
            Text("v" + (System.getProperty("jpackage.app-version") ?: "dev"), fontSize = 10.sp, color = Tahoe.Faint)
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Tahoe.Stroke))
    }
}

/** Greeting + search pill + clock + quick "Tonton Live" button. Search jumps to TV Live with the query. */
@Composable
private fun TopBar() {
    var now by remember { mutableStateOf(java.time.LocalTime.now(Settings.viewer.zone)) }
    LaunchedEffect(Settings.viewer.zone) { while (true) { now = java.time.LocalTime.now(Settings.viewer.zone); kotlinx.coroutines.delay(15_000) } }
    var q by remember { mutableStateOf("") }
    val lang = Settings.lang
    val hello = tech.kousozo.tahoetv.core.I18n.greeting(lang, now.hour)
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 24.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tech.kousozo.tahoetv.core.I18n.get(lang, "greet.hello", Settings.name.ifBlank { "Tahoe" }), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(tech.kousozo.tahoetv.core.I18n.get(lang, "greet.subtitle", hello), fontSize = 13.sp, color = Tahoe.Dim)
        }
        Row(Modifier.width(340.dp).height(44.dp).clip(RoundedCornerShape(50)).background(Tahoe.Card)
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(50)).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, null, tint = Tahoe.Faint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            androidx.compose.foundation.text.BasicTextField(q, { q = it }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Tahoe.Text, fontSize = 14.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Tahoe.Accent),
                modifier = Modifier.weight(1f).onEnter { DeskNav.query = q.trim(); DeskNav.screen = Screen.Live },
                decorationBox = { inner -> Box { if (q.isEmpty()) Text(tech.kousozo.tahoetv.core.I18n.get(lang, "home.search_hint"), color = Tahoe.Faint, fontSize = 14.sp); inner() } })
        }
        Spacer(Modifier.width(12.dp))
        val rel = DesktopPush.latestRelease
        val (curVer, _) = remember { DesktopPush.appVersion() }
        if (rel != null && tech.kousozo.tahoetv.core.Updates.newer(rel.version, curVer)) {
            Row(
                Modifier.height(44.dp).clip(RoundedCornerShape(50)).background(Tahoe.Accent.copy(alpha = 0.18f))
                    .border(1.dp, Tahoe.Accent.copy(alpha = 0.45f), RoundedCornerShape(50))
                    .clickable { DesktopPush.checkUpdateModal(manual = true) }
                    .padding(horizontal = 14.dp)
                    .semantics { contentDescription = tech.kousozo.tahoetv.core.I18n.get(lang, "home.update_avail") },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.SystemUpdate, null, tint = Tahoe.Accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("v${rel.version}", color = Tahoe.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(10.dp))
        }
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Tahoe.Card)
                .clickable { DesktopPush.showInbox = true }
                .semantics { contentDescription = tech.kousozo.tahoetv.core.I18n.get(lang, "home.notif_desc", DesktopPush.unreadCount.toString()) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Notifications,
                tech.kousozo.tahoetv.core.I18n.get(lang, "nav.inbox"),
                tint = if (DesktopPush.unreadCount > 0) Tahoe.Accent else Tahoe.Text,
                modifier = Modifier.size(20.dp),
            )
            if (DesktopPush.unreadCount > 0) {
                Box(Modifier.align(Alignment.TopEnd).padding(9.dp).size(8.dp).clip(CircleShape).background(Tahoe.Accent))
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.height(44.dp).clip(RoundedCornerShape(50)).background(Tahoe.Card).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(now.format(java.time.format.DateTimeFormatter.ofPattern("HH.mm")), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(12.dp))
        Row(Modifier.height(44.dp).clip(RoundedCornerShape(50)).background(Tahoe.Accent).clickable { DeskNav.screen = Screen.Live }.padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.LiveTv, null, tint = Tahoe.OnAccent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(tech.kousozo.tahoetv.core.I18n.get(lang, "home.watch_live"), color = Tahoe.OnAccent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}
