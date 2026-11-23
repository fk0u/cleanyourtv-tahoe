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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
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
        AppearancePrefs.load(this)
        Account.load(applicationContext)
        applySystemBars(true)
        if (isTelevisionOnly()) {
            val lang = tech.kousozo.tahoetv.core.Region.suggest(java.util.Locale.getDefault()).first
            setContent {
                TahoeTheme {
                    PhoneOnlyBlockedScreen(lang)
                }
            }
            return
        }
        if (b == null) pairFrom(intent)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        if (intent?.getBooleanExtra("open_update_modal", false) == true) {
            PushHelper.openUpdateModal(this)
        }
        Channels.loadFavs(this)
        Playback.loadPrefs(this)
        tech.kousozo.tahoetv.core.Sources.initCache(cacheDir)
        PhoneRegion.load(this)
        applyDebugExtras(intent)
        TvDiscovery.start(applicationContext)
        PushHelper.start(applicationContext)
        setContent {
            TahoeTheme {
                val darkBars = ThemePrefs.dark
                LaunchedEffect(darkBars) { applySystemBars(darkBars) }
                if (PhoneRegion.needsSetup) { Box(Modifier.fillMaxSize()) { Backdrop(null); WelcomeScreen(); UpdateModalDialog() /* a fix release is asked for even before onboarding is done */ }; return@TahoeTheme }
                val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
                val lang0 = PhoneRegion.language
                val tabs = listOf(
                    tech.kousozo.tahoetv.core.I18n.get(lang0, "nav.home") to Icons.Rounded.Home,
                    tech.kousozo.tahoetv.core.I18n.get(lang0, "nav.live_short") to Icons.Rounded.LiveTv,
                    tech.kousozo.tahoetv.core.I18n.get(lang0, "nav.remote") to Icons.Rounded.SettingsRemote,
                    tech.kousozo.tahoetv.core.I18n.get(lang0, "nav.watchlist") to Icons.Rounded.Bookmark,
                    tech.kousozo.tahoetv.core.I18n.get(lang0, "nav.profile") to Icons.Rounded.Person,
                )
                val rail = wide && !Nav.full && !Nav.inPip
                Row(Modifier.fillMaxSize()) {
                if (rail) androidx.compose.material3.NavigationRail(
                    Modifier.fillMaxHeight().systemBarsPadding(), containerColor = Tahoe.Surface.copy(alpha = 0.85f), contentColor = Tahoe.Text,
                ) {
                    Spacer(Modifier.height(12.dp))
                    tabs.forEachIndexed { i, (label, icon) ->
                        androidx.compose.material3.NavigationRailItem(selected = Nav.tab == i, onClick = { Nav.tab = i; Nav.detail = null; Nav.page = null },
                            icon = { Icon(icon, null) }, label = { Text(label) })
                    }
                }
                val twoPane = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 840 && !Nav.full && !Nav.inPip
                val rtlDir = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
                var lastDetail by remember { mutableStateOf<LocalTv.Entry?>(null) }
                if (Nav.detail != null) lastDetail = Nav.detail
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    // the playing channel (or the hero channel on Beranda) tints the whole app, like album art in a music app
                    val tintLogo = rememberNetImage(Playback.entry?.channel?.logo) // Figma: flat canvas unless something plays
                    Backdrop(tintLogo?.let { dominantColor(it) })
                    AnimatedContent(
                        Nav.tab,
                        transitionSpec = {
                            if (ThemePrefs.reduceMotion) fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                            else fadeIn(tween(tech.kousozo.tahoetv.core.Brand.MOTION_FAST, easing = EaseOutTahoe)) togetherWith
                                fadeOut(tween(160, easing = EaseOutTahoe))
                        },
                        label = "tab",
                    ) { t ->
                        when (t) {
                            0 -> HomeScreen(resumed.intValue) { Nav.page = "notifications" }
                            1 -> LiveScreen()
                            2 -> RemoteScreen()
                            3 -> WatchlistScreen()
                            else -> ProfileScreen()
                        }
                    }
                    // pages pushed over the tabs (settings, history, categories, install on TV): slide in like a sheet
                    var lastPage by remember { mutableStateOf<String?>(null) }
                    if (Nav.page != null) lastPage = Nav.page
                    OverlayVisibility(
                        Nav.page != null,
                        enter = if (ThemePrefs.reduceMotion) fadeIn(tween(200))
                        else slideInHorizontally(tween(tech.kousozo.tahoetv.core.Brand.MOTION_SHEET, easing = EaseDrawerTahoe)) { rtlDir * it },
                        exit = if (ThemePrefs.reduceMotion) fadeOut(tween(200))
                        else slideOutHorizontally(tween(280, easing = EaseDrawerTahoe)) { rtlDir * it },
                    ) { lastPage?.let { NavPage(it) } }
                    // ≥ 840 dp: the details open as a side pane next to the list instead of covering it (#41)
                    if (!twoPane) {
                        OverlayVisibility(
                            Nav.detail != null,
                            enter = if (ThemePrefs.reduceMotion) fadeIn(tween(200))
                            else slideInVertically(tween(tech.kousozo.tahoetv.core.Brand.MOTION_SHEET, easing = EaseDrawerTahoe)) { it / 6 } +
                                fadeIn(tween(tech.kousozo.tahoetv.core.Brand.MOTION_FAST, easing = EaseOutTahoe)),
                            exit = if (ThemePrefs.reduceMotion) fadeOut(tween(200))
                            else slideOutVertically(tween(280, easing = EaseDrawerTahoe)) { it / 6 } +
                                fadeOut(tween(180, easing = EaseOutTahoe)),
                        ) { lastDetail?.let { e -> ChannelScreen(e) { Nav.detail = null } } }
                    }
                    if (!Nav.full && !Nav.inPip && Nav.page == null) Column(Modifier.align(Alignment.BottomCenter)) {
                        MiniPlayer(Nav.tab != 1 || Nav.detail != null)
                        if (!rail) Dock(tabs, Nav.tab, { Nav.tab = it; Nav.detail = null })
                    }
                    PushBannerBar(onOpenInbox = { Nav.page = "notifications" }, modifier = Modifier.align(Alignment.TopCenter))
                    UpdateModalDialog()
                    WhatsNewDialog()
                    NoCodePairDialog()
                }
                if (twoPane) {
                    AnimatedVisibility(
                        Nav.detail != null,
                        enter = if (ThemePrefs.reduceMotion) fadeIn(tween(200))
                        else slideInHorizontally(tween(tech.kousozo.tahoetv.core.Brand.MOTION_SHEET, easing = EaseDrawerTahoe)) { rtlDir * it } +
                            fadeIn(tween(tech.kousozo.tahoetv.core.Brand.MOTION_FAST, easing = EaseOutTahoe)),
                        exit = if (ThemePrefs.reduceMotion) fadeOut(tween(200))
                        else slideOutHorizontally(tween(280, easing = EaseDrawerTahoe)) { rtlDir * it } +
                            fadeOut(tween(180, easing = EaseOutTahoe)),
                    ) {
                        lastDetail?.let { e ->
                            Box(Modifier.width(420.dp).fillMaxHeight().background(Tahoe.Bg)) { ChannelScreen(e) { Nav.detail = null } }
                        }
                    }
                }
                }
            }
        }
    }

    /** Status/navigation bar icons follow the active theme (#41). */
    private fun applySystemBars(dark: Boolean) {
        val t = android.graphics.Color.TRANSPARENT
        val style = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t)
        runCatching { enableEdgeToEdge(style, style) }
            .onFailure { tech.kousozo.tahoetv.core.DiagLog.error("app", "enableEdgeToEdge", it) }
    }

    override fun onResume() { super.onResume(); Nav.inPip = isInPictureInPictureMode; resumed.intValue++ }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Playback.entry != null && !Playback.paused && android.os.Build.VERSION.SDK_INT >= 26) {
            runCatching {
                enterPictureInPictureMode(
                    android.app.PictureInPictureParams.Builder()
                        .setAspectRatio(android.util.Rational(16, 9))
                        .build()
                )
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        Nav.inPip = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            Nav.tab = 1
            Nav.detail = null
        }
    }

    /** Live TV pauses when leaving unless Picture-in-Picture is active (#63). */
    override fun onStop() {
        super.onStop()
        MobileLivePreview.stop()
        if (!isInPictureInPictureMode) Playback.pause()
    }

    override fun onDestroy() {
        if (isFinishing) {
            MobileLivePreview.release()
            Playback.release()
            TvDiscovery.stop()
            PushHelper.stop()
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pairFrom(intent)
        applyDebugExtras(intent)
        if (intent.getBooleanExtra("open_update_modal", false)) PushHelper.openUpdateModal(this)
    }

    /** Debug-only intent extras so adb can jump to themes, tabs, and sub-pages on a real device (real data only). */
    private fun applyDebugExtras(i: Intent?) {
        if (i == null || (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        i.getStringExtra("theme")?.let { th ->
            when (th.lowercase()) {
                "light" -> { ThemePrefs.mode = tech.kousozo.tahoetv.core.Theme.Mode.LIGHT; ThemePrefs.highContrast = false }
                "dark" -> { ThemePrefs.mode = tech.kousozo.tahoetv.core.Theme.Mode.DARK; ThemePrefs.highContrast = false }
                "hc", "high_contrast" -> { ThemePrefs.mode = tech.kousozo.tahoetv.core.Theme.Mode.DARK; ThemePrefs.highContrast = true }
                "system" -> { ThemePrefs.mode = tech.kousozo.tahoetv.core.Theme.Mode.SYSTEM; ThemePrefs.highContrast = false }
            }
        }
        if (i.hasExtra("tab")) {
            Nav.tab = i.getIntExtra("tab", 0).coerceIn(0, 4)
            Nav.page = null; Nav.detail = null; Nav.full = false; WelcomeDebug.step = 0; Channels.debugOffline = false
        }
        i.getStringExtra("page")?.let { pg ->
            WelcomeDebug.step = 0; Channels.debugOffline = false; Nav.detail = null; Nav.full = false
            when (pg) {
                "onboarding1" -> WelcomeDebug.step = 1
                "onboarding2" -> WelcomeDebug.step = 2
                "onboarding3" -> WelcomeDebug.step = 3
                "offline" -> { Channels.debugOffline = true; Nav.tab = 1; Nav.page = null }
                "channel" -> {
                    Nav.detail = Channels.entries?.firstOrNull()
                    Nav.page = null
                }
                "player_portrait" -> {
                    Nav.tab = 1; Nav.page = null; Nav.full = false
                    Channels.entries?.firstOrNull()?.let { Nav.playUrl = it.channel.url; Playback.play(this, it) }
                }
                "player_landscape" -> {
                    Nav.tab = 1; Nav.page = null; Nav.full = true
                    Channels.entries?.firstOrNull()?.let { Nav.playUrl = it.channel.url; Playback.play(this, it) }
                }
                else -> Nav.page = pg
            }
        }
    }

    /** Opened from the TV's pairing QR (http://<tv-ip>:8765/?code=123456): pair with that TV right away. */
    private fun pairFrom(i: Intent?) {
        val u = i?.data ?: return
        val host = u.host ?: return
        val code = u.getQueryParameter("code")?.takeIf { it.length == 6 && it.all(Char::isDigit) } ?: return
        if (u.port != 8765) return
        Toast.makeText(this, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "pair.connecting", host), Toast.LENGTH_SHORT).show()
        Thread {
            val err = runCatching {
                TvLink.pairWithInfo(host, code, PhoneRegion.language, android.os.Build.MODEL.orEmpty())
            }.fold({ res ->
                Sync.recordPaired(this, host, res); null
            }, { it.message ?: "gagal" })
            runOnUiThread {
                if (err == null) { Nav.tab = 2 } // straight to the remote
                val msg = if (err == null) tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "pair.connected")
                    else tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "pair.connect_failed", err)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    /** Prevents the Phone & Tablet app from exposing phone/tablet workflows when sideloaded onto Android TV. */
    private fun isTelevisionOnly(): Boolean {
        val uiMode = (getSystemService(UI_MODE_SERVICE) as? android.app.UiModeManager)?.currentModeType
            ?: (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK)
        return uiMode == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
            (packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) &&
                !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN))
    }
}

@Composable
private fun PhoneOnlyBlockedScreen(lang: String) {
    val ctx = LocalContext.current
    val activity = ctx as? android.app.Activity
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0F)).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 520.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Tahoe.Surface)
                .border(1.dp, Color(0xFF2A2E42), RoundedCornerShape(20.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Rounded.Smartphone, null, tint = Tahoe.Accent, modifier = Modifier.size(44.dp))
            Text(tech.kousozo.tahoetv.core.I18n.get(lang, "guard.phone_only_title"),
                fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Tahoe.Text, modifier = Modifier.a11yHeading())
            Text(tech.kousozo.tahoetv.core.I18n.get(lang, "guard.phone_only_body"),
                fontSize = 14.sp, color = Tahoe.Dim)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button({
                    runCatching {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(tech.kousozo.tahoetv.core.ControlPlane.baseUrl))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    activity?.finishAndRemoveTask()
                }) {
                    Text(tech.kousozo.tahoetv.core.I18n.get(lang, "guard.open_downloads"))
                }
                OutlinedButton({ activity?.finishAndRemoveTask() }) {
                    Text(tech.kousozo.tahoetv.core.I18n.get(lang, "guard.close"))
                }
            }
        }
    }
}

/** Bumped when this phone gets paired from outside the Beranda card (QR link), so screens re-read host and token. */
object Pairing { var tick by mutableIntStateOf(0) }

/** Space for the floating tab bar, plus the mini-player when it shows, under scrolling content. */
val TabInset: androidx.compose.ui.unit.Dp
    @Composable get() = if (Playback.entry != null && (Nav.tab != 1 || Nav.detail != null)) 190.dp else 120.dp

// ============================================================================ Beranda

/** Beranda (redesign #60): a full-bleed hero for the channel you watch most recently (its colour tints the app),
 *  then rows like a music app: Terakhir ditonton, Favorit, Jelajahi chips, a bento of numbers, and your TV. */
@Composable
fun HomeScreen(resumeTick: Int, onOpenInbox: () -> Unit = {}) {
    val c = LocalContext.current
    val p = remember { Sync.prefs(c) }
    val scope = rememberCoroutineScope()
    val devState = remember(Pairing.tick) { Sync.devices(c) }
    var host by remember(Pairing.tick) { mutableStateOf(devState.active()?.host ?: p.getString("host", "").orEmpty()) }
    var paired by remember(Pairing.tick) { mutableStateOf((devState.active()?.token ?: p.getString("token", "").orEmpty()).isNotEmpty()) }
    var code by remember { mutableStateOf("") }
    var showPair by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var showTvSettings by remember { mutableStateOf(false) }
    var showRecap by remember { mutableStateOf(false) }
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
    // hero: the playing channel, else it cycles every 4 s (or 12 s while live preview plays; off with reduced motion)
    val poolSource = recentWatched.ifEmpty { entries.orEmpty() }
    // highlights = channels with a source we can preview, so every hero channel gets its live picture
    val heroPool = poolSource.filter { MobileLivePreview.candidates(it).isNotEmpty() }.ifEmpty { poolSource }.take(4)
    var heroIdx by remember { mutableIntStateOf(0) }
    val hero = Playback.entry ?: heroPool.getOrNull(heroIdx % maxOf(1, heroPool.size)) ?: entries?.firstOrNull()
    val heroPreviewActive = MobileLivePreview.ready && hero != null && MobileLivePreview.activeUrl == hero.channel.url && Playback.entry == null
    val scroll = rememberScrollState()
    val heroVisible = scroll.value < with(androidx.compose.ui.platform.LocalDensity.current) { 260.dp.toPx() } // scrolled away: stop previewing (saves data and frames)
    LaunchedEffect(Unit) { MobileLivePreview.clearFailures() }
    LaunchedEffect(scroll.isScrollInProgress) {
        if (scroll.isScrollInProgress) MobileLivePreview.setScrolling(true) else { delay(350); MobileLivePreview.setScrolling(false) }
    }
    // the hero moves on only after its picture has played for a while (or has given up), never in the middle of connecting
    LaunchedEffect(heroPool.size, heroIdx, Playback.entry == null, ThemePrefs.reduceMotion, heroVisible) {
        if (Playback.entry != null || heroPool.size < 2 || ThemePrefs.reduceMotion || !heroVisible) return@LaunchedEffect
        val cur = heroPool.getOrNull(heroIdx % heroPool.size) ?: return@LaunchedEffect
        fun playing() = MobileLivePreview.ready && MobileLivePreview.activeUrl == cur.channel.url
        var waited = 0L
        while (waited < 9_000L && !playing() && MobileLivePreview.failedUrl != cur.channel.url && MobileLivePreview.canPreview(cur)) { delay(250); waited += 250 }
        delay(if (playing()) 10_000L else 3_500L)
        heroIdx = (heroIdx + 1) % heroPool.size
    }
    LaunchedEffect(hero) { Nav.hero = hero }

    val L = { k: String -> tech.kousozo.tahoetv.core.I18n.get(lang, k) }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = TabInset),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        // hero (Figma "Mobile / Home", 390 × 520): artwork + optional muted live preview, scrim, top bar, on-now copy, Tonton + Favorit, pager
        Box(Modifier.fillMaxWidth().height(520.dp)) {
            androidx.compose.animation.Crossfade(
                hero,
                Modifier.fillMaxSize(),
                animationSpec = tween(if (ThemePrefs.reduceMotion) 200 else tech.kousozo.tahoetv.core.Brand.MOTION_SLOW, easing = EaseInOutTahoe),
                label = "hero",
            ) { curHero ->
            if (curHero != null) ChannelArt(curHero, Modifier.fillMaxSize(), logoScale = 0.8f, logoTop = 128.dp, programme = nowOn(Channels.epg, curHero, now)?.title)
            }
            // ONE preview surface for the hero channel, in a 16:9 frame so the picture is never cropped or stretched
            if (hero != null) Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).align(Alignment.TopCenter)) {
                LivePreviewSurface(
                    hero,
                    owner = "home",
                    active = Nav.tab == 0 && Nav.page == null && Nav.detail == null && Playback.entry == null && heroVisible,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            run {
                val scrimBrush = if (ThemePrefs.dark) {
                    Brush.verticalGradient(0f to Tahoe.Stage.copy(alpha = 0.7f), 0.25f to Tahoe.Stage.copy(alpha = 0.15f), 0.5f to Tahoe.Stage.copy(alpha = 0.35f), 1f to Tahoe.Bg)
                } else {
                    // Figma 30:823 Light theme scrim fading smoothly to #F6F7F9
                    Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.30f to Color.Black.copy(alpha = 0.10f), 0.62f to Tahoe.Bg.copy(alpha = 0.40f), 1f to Tahoe.Bg)
                }
                Box(Modifier.fillMaxSize().background(scrimBrush))
            }
            androidx.compose.animation.Crossfade(
                hero,
                Modifier.fillMaxSize(),
                animationSpec = tween(if (ThemePrefs.reduceMotion) 200 else tech.kousozo.tahoetv.core.Brand.MOTION_SLOW, easing = EaseInOutTahoe),
                label = "hero-copy",
            ) { curHero ->
            Box(Modifier.fillMaxSize()) {
                if (curHero != null) Column(Modifier.align(Alignment.TopStart).padding(start = 20.dp, end = 20.dp, top = 236.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val prog = nowOn(Channels.epg, curHero, now)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LiveBadge(); Spacer(Modifier.width(8.dp))
                        Text(tech.kousozo.tahoetv.core.I18n.get(lang, "home.on_now_fmt", curHero.name), color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, letterSpacing = 0.14.sp,
                            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(prog?.title ?: curHero.name, color = Color.White, fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    (prog?.desc?.takeIf { it.isNotBlank() } ?: curHero.category.label(lang)).let {
                        Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp, lineHeight = 23.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        PillButton(L("home.watch"), Icons.Rounded.PlayArrow) { Nav.play(curHero) } // the one labelled action
                        val fav = curHero.channel.url in Channels.favs
                        RoundGlass(
                            if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, L("live.favorites_star"),
                            tint = if (fav) Tahoe.Bad else Color.White,
                            stateLabel = tech.kousozo.tahoetv.core.I18n.get(lang, if (fav) "a11y.favorited" else "a11y.not_favorited"),
                        ) { Channels.toggleFav(c, curHero) }
                    }
                }
            }
            }
            // top bar: greeting + question, search and notifications (48 dp icon buttons)
            val topIconTint = if (ThemePrefs.dark) Color.White else Tahoe.Text
            Row(Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(hello + if (name.isNotEmpty()) ", $name" else "", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, letterSpacing = 0.14.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(L("home.what_to_watch"), color = Color.White, fontSize = 20.sp, lineHeight = 25.sp, letterSpacing = (-0.2).sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        modifier = Modifier.a11yHeading())
                }
                RoundGlass(Icons.Rounded.Search, L("home.search_hint"), tint = topIconTint) { Nav.page = "search" }
                Box {
                    RoundGlass(Icons.Rounded.Notifications, tech.kousozo.tahoetv.core.I18n.get(lang, "home.notif_desc", PushHelper.unreadCount), tint = topIconTint) { onOpenInbox() }
                    if (PushHelper.unreadCount > 0) Box(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 12.dp).size(9.dp).clip(CircleShape).background(Tahoe.Warm))
                }
            }
            if (heroPreviewActive) {
                Row(
                    Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        L("preview.live_label"),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.62f))
                            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                    RoundGlass(
                        if (MobileLivePreview.muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                        L(if (MobileLivePreview.muted) "preview.unmute" else "preview.mute"),
                        tint = Color.White,
                    ) { MobileLivePreview.toggleMute() }
                }
            }
            if (Playback.entry == null && heroPool.size > 1) Row(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 0.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                heroPool.indices.forEach { i ->
                    val on = i == heroIdx % heroPool.size
                    val w by androidx.compose.animation.core.animateDpAsState(
                        if (on) 24.dp else 6.dp,
                        tween(if (ThemePrefs.reduceMotion) 0 else tech.kousozo.tahoetv.core.Brand.MOTION_FAST, easing = EaseOutTahoe),
                        label = "dot",
                    )
                    Box(
                        Modifier.sizeIn(minWidth = 24.dp, minHeight = 48.dp)
                            .clickable(role = Role.Tab) { heroIdx = i }
                            .semantics {
                                selected = on
                                contentDescription = tech.kousozo.tahoetv.core.I18n.get(lang, "a11y.hero_slide_fmt", i + 1, heroPool.size, heroPool[i].name)
                            },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Box(Modifier.height(6.dp).width(w).clip(RoundedCornerShape(3.dp)).background(if (on) Tahoe.Text else Tahoe.Faint))
                    }
                }
            }
        }

        // category chips: Semua + categories with channels
        entries?.let { all ->
            val cats = LocalTv.Category.entries.map { k -> k to all.count { LocalTv.inCategory(it, k) } }.filter { it.second > 0 }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(L("live.all"), true) { Nav.category(null) }
                cats.forEach { (k, _) -> Chip(k.label(lang), false) { Nav.category(k) } }
            }
        }

        val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
        LaunchedEffect(resumeTick, Account.signedIn) { Account.checkHandoff(appCtx) }
        HandoffCard()
        val recent = recentWatched.ifEmpty { entries.orEmpty().take(8) }
        if (recent.isNotEmpty()) CardShelf(L(if (recentWatched.isEmpty()) "home.featured_channels" else "home.continue_watching"), recent, now) { Nav.category(null) }
        val favs = entries.orEmpty().filter { it.channel.url in Channels.favs }
        if (favs.isNotEmpty()) CardShelf(L("home.favorites"), favs, now) { Nav.category(null, favourites = true) }
        if (entries == null) { if (Channels.failed) OfflineState()
            else LinearProgressIndicator(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), color = Tahoe.Text, trackColor = Tahoe.Glass) }

        // pairing / TV card opens from the TV button
        val activeToken = devState.active()?.token ?: p.getString("token", "").orEmpty()
        if (showTvSettings && host.isNotBlank() && activeToken.isNotEmpty()) {
            TvSettingsSheet(host, activeToken) { showTvSettings = false }
        }
        if (showRecap) {
            RecapSheet { showRecap = false }
        }
        if (showQrScanner) {
            QrCodeScannerDialog(
                onScanned = { qr ->
                    showQrScanner = false
                    scope.launch {
                        val res = withContext(Dispatchers.IO) {
                            runCatching { TvLink.pairWithInfo(qr.host, qr.code, lang, android.os.Build.MODEL.orEmpty()) }
                        }
                        res.onSuccess { pr ->
                            Sync.recordPaired(c, qr.host, pr)
                            host = qr.host
                            code = ""
                            paired = true
                            showPair = false
                            if (qr.isSetup) showTvSettings = true
                        }
                        Toast.makeText(c, res.exceptionOrNull()?.message ?: tech.kousozo.tahoetv.core.I18n.get(lang, "pair.connected"), Toast.LENGTH_SHORT).show()
                    }
                },
                onDismiss = { showQrScanner = false },
            )
        }
        if (showDevices) TvDevicesSheet({ showDevices = false }, onScanQr = { showDevices = false; showQrScanner = true }) { pickedHost ->
            host = pickedHost
            showPair = true
        }
        if (showPair || !paired) TvCard(host, { host = it }, paired, code, { code = it }, showPair, { showPair = it }, granted, notif, { notif = it; p.edit().putBoolean("notif", it).apply() },
            media, { media = it; p.edit().putBoolean("media", it).apply(); Sync.inst?.sendMedia() }, onScanQr = { showQrScanner = true }) { res ->
            Sync.recordPaired(c, host, res); code = ""; paired = true; showPair = false
        }


        if (paired && !showPair) Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tech.kousozo.tahoetv.core.I18n.get(lang, "home.your_tv"), fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).a11yHeading())
                if (activeToken.isNotEmpty()) {
                    RoundGlass(Icons.Rounded.Tune, tech.kousozo.tahoetv.core.I18n.get(lang, "tvset.open_full")) { showTvSettings = true }
                    Spacer(Modifier.width(8.dp))
                }
                RoundGlass(Icons.Rounded.Devices, tech.kousozo.tahoetv.core.I18n.get(lang, "connect.devices")) { showDevices = true }
            }
            GlassCard(onClick = { showPair = true }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Tahoe.Good.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Tv, null, tint = Tahoe.Good) }
                    Column(Modifier.weight(1f)) {
                        Text(devState.active()?.name?.ifBlank { null } ?: tech.kousozo.tahoetv.core.I18n.get(lang, "home.connected_tv"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("$host · ${Sync.status.value}", color = Tahoe.Dim, fontSize = 12.sp, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    RoundGlass(Icons.Rounded.SettingsRemote, tech.kousozo.tahoetv.core.I18n.get(lang, "nav.remote"), filled = true) { Nav.tab = 2 }
                }
            }
        }
    }
}

/** Pairing + notification settings (was the "TV kamu" card). */
@Composable
private fun TvCard(host: String, setHost: (String) -> Unit, paired: Boolean, code: String, setCode: (String) -> Unit, open: Boolean, setOpen: (Boolean) -> Unit,
                   granted: Boolean, notif: Boolean, setNotif: (Boolean) -> Unit, media: Boolean, setMedia: (Boolean) -> Unit,
                   onScanQr: () -> Unit = {}, onPaired: (TvLink.PairResult) -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val lang = PhoneRegion.language
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tech.kousozo.tahoetv.core.I18n.get(lang, if (paired) "home.your_tv" else "tv.connect_title"), fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.a11yHeading())
        GlassCard {
            if (!paired && !open) {
                Text(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.connect_desc"), color = Tahoe.Dim, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RoundGlass(Icons.Rounded.Tv, tech.kousozo.tahoetv.core.I18n.get(lang, "profile.connect_tv"), filled = true) { Nav.page = "connect" }
                    RoundGlass(Icons.Rounded.QrCodeScanner, tech.kousozo.tahoetv.core.I18n.get(lang, "qr.scan_btn")) { onScanQr() }
                }
                return@GlassCard
            }
            Text(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.pair_hint"), color = Tahoe.Dim, fontSize = 13.sp)
            RoundGlass(Icons.Rounded.QrCodeScanner, tech.kousozo.tahoetv.core.I18n.get(lang, "qr.scan_btn")) { onScanQr() }
            Field(host, setHost, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.host_hint"), KeyboardType.Uri)
            Field(code, { if (it.length <= 6) setCode(it.filter(Char::isDigit)) }, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.code_hint"), KeyboardType.NumberPassword)
            PrimaryButton(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.connect"), enabled = host.isNotBlank() && code.length == 6, icon = Icons.Rounded.Link) {
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching { TvLink.pairWithInfo(host.trim(), code, lang, android.os.Build.MODEL.orEmpty()) }
                    }
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
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (host.isNotBlank()) RoundGlass(Icons.Rounded.SportsEsports, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.open_gamepad")) {
                    c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://${host.trim()}:8765/")))
                }
                if (open) RoundGlass(Icons.Rounded.Close, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.close")) { setOpen(false) }
            }
        }
    }
}

/** Artwork for a channel: a real photo of the show on now (when the web has one), else the channel's colour as a deep gradient;
 *  the logo (playlist, Wikipedia or web) sits on a white tile. */
@Composable
fun ChannelArt(e: LocalTv.Entry, modifier: Modifier, logoScale: Float = 1f, logoTop: androidx.compose.ui.unit.Dp? = null, programme: String? = null) {
    val logo = rememberChannelLogo(e)
    val snapshot = rememberSnapshot(e)
    val photo = snapshot ?: rememberProgrammePhoto(e, programme)
    val tint = logo?.let { dominantColor(it) } ?: categoryTint(e.category)
    Box(modifier.background(Brush.linearGradient(listOf(tint.copy(alpha = 0.95f), Color(Brand.LAKE_DEEP), Tahoe.Stage))),
        contentAlignment = if (logoTop != null) Alignment.TopCenter else Alignment.Center) {
        androidx.compose.animation.AnimatedVisibility(photo != null, enter = androidx.compose.animation.fadeIn(tween(tech.kousozo.tahoetv.core.Brand.MOTION_SLOW)),
            exit = androidx.compose.animation.fadeOut(tween(200)), modifier = Modifier.matchParentSize()) {
            if (photo != null) Box(Modifier.fillMaxSize()) {
                Image(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.35f)))))
            }
        }
        // with a photo the logo shrinks into a small badge so the picture is the star
        val size = if (photo != null) 0.5f else logoScale
        val tileMod = if (photo != null) Modifier.align(Alignment.BottomEnd).padding(10.dp) else Modifier.then(if (logoTop != null) Modifier.padding(top = logoTop) else Modifier)
        Box(tileMod.size(132.dp * size, 88.dp * size).clip(RoundedCornerShape(18.dp * size.coerceAtLeast(0.6f))).background(Color.White), contentAlignment = Alignment.Center) {
            if (logo != null) Image(logo, null, Modifier.padding(14.dp * size).fillMaxSize(), contentScale = ContentScale.Fit)
            else Text(e.name.take(3).uppercase(), color = Color(Brand.CANVAS), fontSize = (22 * size).sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Figma "Channel Card" (280 × 158 artwork): LIVE badge, channel name, programme progress; title and "category · N min left" below. */
@Composable
fun ChannelCard(e: LocalTv.Entry, now: Instant, modifier: Modifier = Modifier) {
    val lang = PhoneRegion.language
    val prog = nowOn(Channels.epg, e, now)
    val playingHere = Playback.entry?.channel?.url == e.channel.url
    Column(
        modifier.width(280.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(role = Role.Button) { Nav.play(e) }
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(280.dp, 158.dp)
                .clip(RoundedCornerShape(18.dp))
                .then(
                    when {
                        playingHere -> Modifier.border(2.dp, Tahoe.Accent, RoundedCornerShape(18.dp))
                        ThemePrefs.highContrast -> Modifier.border(2.dp, Tahoe.Stroke, RoundedCornerShape(18.dp))
                        else -> Modifier
                    }
                ),
        ) {
            ChannelArt(e, Modifier.fillMaxSize(), logoScale = 0.62f, programme = prog?.title)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                LiveBadge()
                if (playingHere) {
                    Row(
                        Modifier.clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.72f))
                            .border(1.dp, Tahoe.Accent.copy(alpha = 0.6f), RoundedCornerShape(50))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        NowPlayingIndicator(playing = !Playback.paused)
                        Text(tech.kousozo.tahoetv.core.I18n.get(lang, "live.badge_playing"), color = Tahoe.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text(e.name, Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 14.dp, bottom = 16.dp), color = Color.White, fontSize = 20.sp, lineHeight = 25.sp,
                letterSpacing = (-0.2).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val frac = prog?.let { (now.epochSecond - it.start.epochSecond).toFloat() / maxOf(1L, it.stop.epochSecond - it.start.epochSecond) } ?: 0f
            KeepLtr {
                ProgressBar(if (prog != null) frac else 0f, Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 14.dp, bottom = 8.dp).fillMaxWidth())
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(prog?.title ?: e.name, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val left = prog?.let { java.time.Duration.between(now, it.stop).toMinutes().coerceAtLeast(0) }
            Text(e.category.label(lang) + (left?.let { " · " + tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_min_left", it.toString()) } ?: " · LIVE"),
                fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.14.sp, color = Tahoe.Dim, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

/** Section of channel cards with a "Lihat semua" link (Figma "Continue watching"). */
@Composable
private fun CardShelf(title: String, items: List<LocalTv.Entry>, now: Instant, onAll: () -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    val lang = PhoneRegion.language
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).a11yHeading(), fontSize = 20.sp, lineHeight = 25.sp, letterSpacing = (-0.2).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(
            Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = onAll)
                .semantics { contentDescription = tech.kousozo.tahoetv.core.I18n.get(lang, "home.see_all_fmt", title) }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(tech.kousozo.tahoetv.core.I18n.get(lang, "home.see_all"),
                color = Tahoe.Dim, fontSize = 14.sp, letterSpacing = 0.14.sp, fontWeight = FontWeight.Medium)
        }
    }
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(items, key = { it.channel.url }) { e -> ChannelCard(e, now) }
    }
}

@Composable
private fun SectionHeader(title: String, onAll: () -> Unit) = Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.a11yHeading())
    IconButton(onClick = onAll, modifier = Modifier.size(48.dp)) {
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "home.see_all_fmt", title), tint = Tahoe.Dim,
            modifier = Modifier.size(22.dp))
    }
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
        Text("● LIVE", Modifier.align(Alignment.TopStart).clip(RoundedCornerShape(6.dp)).background(LiveFill).padding(horizontal = 6.dp, vertical = 2.dp),
            color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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

/** Bottom sheet listing saved paired TVs (switch active / rename / forget) and mDNS-discovered receivers (#88, #37). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvDevicesSheet(onDismiss: () -> Unit, onScanQr: (() -> Unit)? = null, onPickUnpaired: (String) -> Unit) {
    val c = LocalContext.current
    val lang = PhoneRegion.language
    LaunchedEffect(Unit) { TvDiscovery.start(c) }
    var state by remember(Pairing.tick) { mutableStateOf(Sync.devices(c)) }
    var renaming by remember { mutableStateOf<tech.kousozo.tahoetv.core.PairedDevices.Device?>(null) }
    var renameText by remember { mutableStateOf("") }

    renaming?.let { dev ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(tech.kousozo.tahoetv.core.I18n.get(lang, "connect.rename")) },
            text = { Field(renameText, { renameText = it }, dev.name) },
            confirmButton = {
                TextButton({
                    val updated = state.rename(dev.id, renameText)
                    state = updated
                    Sync.saveDevices(c, updated)
                    renaming = null
                }) { Text(tech.kousozo.tahoetv.core.I18n.get(lang, "connect.rename"), color = Tahoe.Accent) }
            },
            dismissButton = {
                TextButton({ renaming = null }) { Text(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.close"), color = Tahoe.Dim) }
            },
        )
    }

    ModalBottomSheet(onDismiss, containerColor = Tahoe.Surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tech.kousozo.tahoetv.core.I18n.get(lang, "connect.devices"), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f).a11yHeading())
                if (onScanQr != null) RoundGlass(Icons.Rounded.QrCodeScanner, tech.kousozo.tahoetv.core.I18n.get(lang, "qr.scan_btn"), onClick = onScanQr)
            }
            state.devices.forEach { d ->
                val isActive = state.active()?.id == d.id
                GlassCard(onClick = {
                    val updated = state.select(d.id)
                    state = updated
                    Sync.saveDevices(c, updated)
                    onDismiss()
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.Tv, null, tint = if (isActive) Tahoe.Good else Tahoe.Dim, modifier = Modifier.size(22.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(d.host + if (isActive) " · ${tech.kousozo.tahoetv.core.I18n.get(lang, "connect.active")}" else "",
                                color = if (isActive) Tahoe.Good else Tahoe.Dim, fontSize = 12.sp, fontFamily = Mono)
                        }
                        IconButton({ renameText = d.name; renaming = d }) {
                            Icon(Icons.Rounded.Edit, tech.kousozo.tahoetv.core.I18n.get(lang, "connect.rename"), tint = Tahoe.Dim)
                        }
                        IconButton({
                            val updated = state.remove(d.id)
                            state = updated
                            Sync.saveDevices(c, updated)
                        }) {
                            Icon(Icons.Rounded.DeleteOutline, tech.kousozo.tahoetv.core.I18n.get(lang, "connect.forget"), tint = Tahoe.Bad)
                        }
                    }
                }
            }
            val knownHosts = state.devices.mapTo(HashSet()) { it.host }
            val extra = TvDiscovery.discovered.filter { it.ip !in knownHosts }
            if (extra.isNotEmpty()) {
                Text(tech.kousozo.tahoetv.core.I18n.get(lang, "connect.discovered"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Tahoe.Dim, modifier = Modifier.padding(top = 6.dp))
                extra.forEach { f ->
                    GlassCard(onClick = { NoCodePair.start(c, f.ip, f.name ?: "Tahoe TV") { ip -> onPickUnpaired(ip) }; onDismiss() }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Rounded.WifiFind, null, tint = Tahoe.Accent, modifier = Modifier.size(20.dp))
                            Column(Modifier.weight(1f)) {
                                Text(f.name ?: "Tahoe TV", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Text(f.ip, color = Tahoe.Dim, fontSize = 12.sp, fontFamily = Mono)
                            }
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Tahoe.Dim)
                        }
                    }
                }
            }
        }
    }
}

/** Theme, high contrast, reduced motion and live preview; saved in the "ui" preferences (#41). */
object AppearancePrefs {
    fun load(c: android.content.Context) {
        val p = c.getSharedPreferences("ui", 0)
        ThemePrefs.mode = tech.kousozo.tahoetv.core.Theme.Mode.of(p.getString("theme", null))
        ThemePrefs.highContrast = p.getBoolean("high_contrast", false)
        val osNoAnim = runCatching { android.provider.Settings.Global.getFloat(c.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
        ThemePrefs.reduceMotion = p.getBoolean("reduce_motion", false) || osNoAnim
        MobileLivePreview.enabled = p.getBoolean("live_preview", true)
    }
    fun save(c: android.content.Context) = c.getSharedPreferences("ui", 0).edit()
        .putString("theme", ThemePrefs.mode.name)
        .putBoolean("high_contrast", ThemePrefs.highContrast)
        .putBoolean("reduce_motion", ThemePrefs.reduceMotion)
        .putBoolean("live_preview", MobileLivePreview.enabled)
        .apply()
}

@Composable
internal fun AppearanceCard() {
    val c = LocalContext.current
    val lang = PhoneRegion.language
    fun t(k: String) = tech.kousozo.tahoetv.core.I18n.get(lang, k)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(t("ui.appearance"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        GlassCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(tech.kousozo.tahoetv.core.Theme.Mode.SYSTEM to "ui.theme_system", tech.kousozo.tahoetv.core.Theme.Mode.DARK to "ui.theme_dark",
                    tech.kousozo.tahoetv.core.Theme.Mode.LIGHT to "ui.theme_light").forEach { (m, k) ->
                    androidx.compose.material3.FilterChip(ThemePrefs.mode == m, { ThemePrefs.mode = m; AppearancePrefs.save(c) }, { Text(t(k)) },
                        Modifier.heightIn(min = 48.dp))
                }
            }
            SwitchRow(t("ui.high_contrast"), ThemePrefs.highContrast) { ThemePrefs.highContrast = it; AppearancePrefs.save(c) }
            SwitchRow(t("ui.reduce_motion"), ThemePrefs.reduceMotion) { ThemePrefs.reduceMotion = it; AppearancePrefs.save(c) }
        }
        // extra channel languages (#71): iptv-org per-language playlists on top of the chosen countries
        Text(t("ui.channel_languages"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        GlassCard {
            Text(t("ui.channel_languages_sub"), color = Tahoe.Dim, fontSize = 13.sp)
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val v = PhoneRegion.viewer
                tech.kousozo.tahoetv.core.Region.channelLanguages().forEach { l ->
                    val on = l in v.channelLanguages
                    val name = java.util.Locale.forLanguageTag(l).let { it.getDisplayLanguage(it) }.ifBlank { l }
                    androidx.compose.material3.FilterChip(on, {
                        PhoneRegion.save(c, v.copy(channelLanguages = if (on) v.channelLanguages - l else v.channelLanguages + l))
                    }, { Text(name) }, Modifier.heightIn(min = 48.dp))
                }
            }
        }
    }
}

/** Standalone AnimatedVisibility helper outside RowScope/ColumnScope to avoid implicit receiver ambiguity. */
@Composable
private fun OverlayVisibility(
    visible: Boolean,
    enter: androidx.compose.animation.EnterTransition,
    exit: androidx.compose.animation.ExitTransition,
    content: @Composable androidx.compose.animation.AnimatedVisibilityScope.() -> Unit,
) {
    AnimatedVisibility(visible = visible, enter = enter, exit = exit, content = content)
}

/** Region & language (moved from Beranda to Pengaturan, #179): reopens the first-run setup. */
@Composable
internal fun RegionCard() {
    val lang = PhoneRegion.language
    GlassCard(onClick = { Playback.stop(); PhoneRegion.reset() }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(tech.kousozo.tahoetv.core.Region.flag(PhoneRegion.code), fontSize = 26.sp)
            Column(Modifier.weight(1f)) {
                Text(tech.kousozo.tahoetv.core.I18n.get(lang, "home.region_language"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(tech.kousozo.tahoetv.core.Region.name(PhoneRegion.code, lang) + " · " +
                    (tech.kousozo.tahoetv.core.OnboardingText.LANGUAGE_NAMES[lang] ?: lang) +
                    " · " + PhoneRegion.viewer.countries.let { l -> l.take(5).joinToString(" ") { tech.kousozo.tahoetv.core.Region.flag(it) } + if (l.size > 5) " +${l.size - 5}" else "" },
                    color = Tahoe.Dim, fontSize = 12.sp, maxLines = 1)
            }
            RoundGlass(Icons.Rounded.SwapHoriz, tech.kousozo.tahoetv.core.I18n.get(lang, "home.change")) { Playback.stop(); PhoneRegion.reset() }
        }
    }
}
