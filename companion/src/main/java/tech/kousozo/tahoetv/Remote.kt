package tech.kousozo.tahoetv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import kotlinx.coroutines.launch
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import kotlin.math.abs
import tech.kousozo.tahoetv.core.TvLink

/** Phone remote, laid out like a physical one: TV pill on top, a big ring D-pad (tap the ring edges, tap the centre
 *  for OK, or swipe anywhere on it), back/home/mute, volume and channel rockers with Live TV between them, then
 *  shortcut and media rows, and "ask the TV". Talks to the TV over the paired WebSocket (no ADB needed). */
@Composable
fun RemoteScreen() {
    val c = LocalContext.current
    val p = remember { Sync.prefs(c) }
    val devState = remember(Pairing.tick) { Sync.devices(c) }
    val activeDev = devState.active()
    val host = activeDev?.host ?: p.getString("host", "").orEmpty()
    val token = activeDev?.token ?: p.getString("token", "").orEmpty()
    val tvName = activeDev?.name?.ifBlank { "Tahoe TV" } ?: "Tahoe TV"
    var showDevices by remember { mutableStateOf(false) }
    var showTvSettings by remember { mutableStateOf(false) }
    var online by remember { mutableStateOf(false) }
    var tv by remember { mutableStateOf<org.json.JSONObject?>(null) } // volume / channel reported by the TV
    // TvLink calls back on its network thread; change screen state on the main thread only.
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val link = remember(host, token) {
        if (host.isBlank() || token.isEmpty()) null
        else TvLink(host, token, { o -> main.post { online = o } }, tech.kousozo.tahoetv.core.ControlPlane.baseUrl).apply {
            onTvState = { s -> main.post { tv = s } }
            // "Kirim log" pressed on the TV: offer the share sheet here (the TV can't share files itself)
            onCommand = { o -> if (o.optString("t") == "send_log") main.post { Report.send(c) { n -> n?.let { android.widget.Toast.makeText(c, it, android.widget.Toast.LENGTH_LONG).show() } } } }
        }
    }
    DisposableEffect(link) { link?.open(); onDispose { link?.close() } }
    val haptic = LocalHapticFeedback.current
    fun key(k: String) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); link?.key(k) }


    if (showDevices) TvDevicesSheet({ showDevices = false }) { Nav.tab = 0 }
    if (showTvSettings && host.isNotBlank() && token.isNotEmpty()) TvSettingsSheet(host, token) { showTvSettings = false }

    val lang = PhoneRegion.language
    val L = { k: String -> tech.kousozo.tahoetv.core.I18n.get(lang, k) }
    var qr by remember { mutableStateOf(false) }
    var pinFor by remember { mutableStateOf<String?>(null) } // host to pair with a 6-digit code, or "" to type one
    var typing by remember { mutableStateOf(false) }
    val speech = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { link?.ask(it) }
    }
    val scope = rememberCoroutineScope()
    if (qr) QrCodeScannerDialog(onScanned = { q -> qr = false
        scope.launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { TvLink.pairWithInfo(q.host, q.code, lang, android.os.Build.MODEL.orEmpty()) } }
            r.onSuccess { Sync.recordPaired(c, q.host, it); Pairing.tick++ }
            android.widget.Toast.makeText(c, r.exceptionOrNull()?.message ?: L("pair.connected"), android.widget.Toast.LENGTH_SHORT).show()
        } }, onDismiss = { qr = false })
    pinFor?.let { h -> PinPairDialog(h) { pinFor = null } }

    LaunchedEffect(Unit) { TvDiscovery.start(c) }
    val known = devState.devices.mapTo(HashSet()) { it.host }
    val found = TvDiscovery.discovered.filter { it.ip !in known }
    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = TabInset + 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Figma "Mobile / Remote": title, Wi-Fi line, "+" to add a TV
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(L("nav.remote"), fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp,
                    modifier = Modifier.a11yHeading())
                Row(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Wifi, null, tint = Tahoe.Good, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                    Text(tech.kousozo.tahoetv.core.I18n.get(lang, "remote.found_fmt", devState.devices.size + found.size), fontSize = 14.sp, color = Tahoe.Dim,
                        letterSpacing = 0.14.sp, fontWeight = FontWeight.Medium)
                }
            }
            RoundGlass(Icons.Rounded.Add, L("connect.devices")) { showDevices = true }
        }

        // TV cards: saved TVs switch with one tap; TVs found on this Wi-Fi connect without a code (OK on the TV)
        if (devState.devices.isNotEmpty() || found.isNotEmpty()) Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            devState.devices.forEach { d ->
                val on = d.id == activeDev?.id
                TvCardTile(d.name.ifBlank { "Tahoe TV" }, if (on) L("remote.connected") else L("remote.tap_control"), if (on) Tahoe.Good else Tahoe.Faint, on) {
                    if (!on) { Sync.saveDevices(c, devState.select(d.id)); Pairing.tick++ }
                }
            }
            found.forEach { f ->
                TvCardTile(f.name ?: "Tahoe TV", L("remote.tap_connect"), Color(tech.kousozo.tahoetv.core.Brand.SUN), false) {
                    NoCodePair.start(c, f.ip, f.name ?: "Tahoe TV") { pinFor = f.ip }
                }
            }
        }
        // the promise, in green
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Tahoe.Good.copy(alpha = 0.12f)).border(1.dp, Tahoe.Good.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Check, null, tint = Tahoe.Good, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp))
            Text(L("remote.nocode_note"), fontSize = 14.sp, lineHeight = 19.sp)
        }

        // remote panel
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Tahoe.Surface)
            .border(if (ThemePrefs.highContrast) 2.dp else 1.dp, Tahoe.Stroke, RoundedCornerShape(28.dp))
            .padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(Tahoe.Bad.copy(alpha = 0.18f))
                        .then(if (ThemePrefs.highContrast) Modifier.border(2.dp, Tahoe.Bad, CircleShape) else Modifier)
                        .clickable(role = Role.Button) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); link?.power() }
                        .semantics { contentDescription = L("remote.standby") },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.PowerSettingsNew, null, tint = Tahoe.Bad) }
                Column(
                    Modifier.weight(1f).heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .then(
                            if (link != null) Modifier.clickable(role = Role.Button) { showTvSettings = true }
                                .semantics(mergeDescendants = true) {
                                    contentDescription = tech.kousozo.tahoetv.core.I18n.get(lang, "a11y.open_tv_settings", tvName)
                                }
                            else Modifier.semantics(mergeDescendants = true) {},
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(L("remote.controlling"), fontSize = 13.sp, color = Tahoe.Dim)
                    Text(tvName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                RoundKey(Icons.Rounded.Mic, L("remote.speak"), 48.dp) {
                    runCatching { speech.launch(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)) }
                }
            }
            DPad(onKey = ::key)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                LabeledKey(Icons.AutoMirrored.Rounded.ArrowBack, L("common.back")) { key("back") }
                LabeledKey(Icons.Rounded.Home, L("nav.home")) { key("home") }
                LabeledKey(Icons.Rounded.Menu, L("remote.menu")) { key("menu") }
                LabeledKey(Icons.Rounded.Keyboard, L("remote.type")) { typing = !typing }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PillRocker("VOL", Icons.Rounded.Remove, Icons.Rounded.Add, { link?.volume(-1); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                    { link?.volume(1); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }, Modifier.weight(1f))
                PillRocker("CH", Icons.Rounded.KeyboardArrowDown, Icons.Rounded.KeyboardArrowUp, { key("ch_down") }, { key("ch_up") }, Modifier.weight(1f))
                RoundKey(Icons.AutoMirrored.Rounded.VolumeOff, L("remote.mute_label"), 48.dp) { key("mute") }
            }
            if (typing) {
                var typed by remember { mutableStateOf("") }
                Field(typed, { typed = it; link?.text(it) }, L("remote.type_hint"), trailing = {
                    IconButton({ typed = ""; link?.text("") }, Modifier.size(48.dp)) { Icon(Icons.Rounded.Backspace, L("remote.clear"), tint = Tahoe.Dim) }
                })
            }
        }

        // TV on another network: QR or 6-digit code; install Tahoe on a TV
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Tahoe.Surface)
            .border(if (ThemePrefs.highContrast) 2.dp else 1.dp, Tahoe.Stroke, RoundedCornerShape(24.dp))
            .padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(L("remote.other_net_title"), fontSize = 20.sp, lineHeight = 25.sp, letterSpacing = (-0.2).sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.a11yHeading())
            Text(L("remote.other_net_desc"), fontSize = 14.sp, lineHeight = 19.sp, color = Tahoe.Dim)
            IconRow {
                RoundGlass(Icons.Rounded.QrCodeScanner, L("remote.qr_short"), size = 56.dp, filled = true) { qr = true }
                RoundGlass(Icons.Rounded.Dialpad, L("remote.enter_pin"), size = 56.dp) { pinFor = host }
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp))
                    .clickable(role = Role.Button) { Nav.page = "setup" }
                    .semantics(mergeDescendants = true) {}
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Color(tech.kousozo.tahoetv.core.Brand.SUN).copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Download, null, tint = Color(tech.kousozo.tahoetv.core.Brand.SUN), modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(L("tv.install_on_tv"), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(L("remote.install_sub"), fontSize = 13.sp, color = Tahoe.Dim)
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Tahoe.Faint)
            }
        }

        // sleep timer: tap to step Mati → 15 → 30 → 60 → 90 → 120 → Mati; shows the TV's minutes left
        val sleepLeft = tv?.optInt("sleep") ?: 0

        // channel numbers (Live TV): 1–3 digits, the TV tunes 1.5 s after the last one
        var pad by remember { mutableStateOf(false) }
        val sleepText = if (sleepLeft == 0) tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "player.sleep_off") else tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "player.sleep_mins", sleepLeft)
        IconRow {
            RoundGlass(Icons.Rounded.Bedtime, sleepText, tint = if (sleepLeft > 0) Tahoe.Accent else Tahoe.Text, stateLabel = sleepText) {
                link?.sleep(listOf(15, 30, 60, 90, 120).firstOrNull { it > sleepLeft } ?: 0)
            }
            RoundGlass(Icons.Rounded.Dialpad, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, if (pad) "remote.numpad_hide" else "remote.numpad_show"),
                tint = if (pad) Tahoe.Accent else Tahoe.Text) { pad = !pad }
        }
        if (pad) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⟲", "0", "")).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { d ->
                        if (d.isEmpty()) Spacer(Modifier.size(62.dp))
                        else {
                            val desc = if (d == "⟲") tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "remote.prev_ch") else tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "remote.digit", d)
                            Box(Modifier.size(62.dp).clip(CircleShape).background(Tahoe.Glass)
                                .border(if (ThemePrefs.highContrast) 2.dp else 1.dp, Tahoe.Stroke, CircleShape)
                                .semantics { contentDescription = desc }
                                .clickable(role = Role.Button) { key(if (d == "⟲") "prev" else d) }, // ⟲ = previous channel
                                contentAlignment = Alignment.Center) { Text(d, fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
            }
        }

        // media row
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Tahoe.Glass).border(1.dp, Tahoe.Stroke, RoundedCornerShape(24.dp))
            .padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf(
                Triple(Icons.Rounded.FastRewind, "rewind", tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "remote.rewind")),
                Triple(Icons.Rounded.PlayArrow, "play_pause", tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "remote.play_pause")),
                Triple(Icons.Rounded.FastForward, "ffwd", tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "remote.forward")),
                Triple(Icons.Rounded.Menu, "menu", tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "remote.menu")),
            ).forEach { (icon, k, desc) -> IconButton({ key(k) }) { Icon(icon, desc, tint = Tahoe.Text) } }
        }

        // ask the TV
        var q by remember { mutableStateOf("") }
        Field(q, { q = it }, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "remote.ask_hint"), trailing = {
            IconButton({ if (q.isNotBlank()) { link?.ask(q.trim()); q = "" } }) { Icon(Icons.AutoMirrored.Rounded.Send, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "common.send"), tint = Tahoe.Accent) }
        })

        // support: send logs, reset the kids PIN
        var pinDialog by remember { mutableStateOf(false) }
        var sending by remember { mutableStateOf(false) }
        IconRow {
            RoundGlass(Icons.Rounded.BugReport, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, if (sending) "remote.sending_log" else "remote.send_log"), enabled = !sending) {
                sending = true
                Report.send(c) { n -> sending = false; n?.let { android.widget.Toast.makeText(c, it, android.widget.Toast.LENGTH_LONG).show() } }
            }
            if (link != null) RoundGlass(Icons.Rounded.Lock, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "remote.kids_pin")) { pinDialog = true }
        }
        if (pinDialog) KidsPinDialog(host, token) { pinDialog = false }
    }
}

/** Forgotten kids PIN: set a new one on the TV from this paired phone (it holds the TV's token, which is the proof). */
@Composable
private fun KidsPinDialog(host: String, token: String, close: () -> Unit) {
    val c = LocalContext.current
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val lang = PhoneRegion.language
    AlertDialog(onDismissRequest = close, title = { Text(tech.kousozo.tahoetv.core.I18n.get(lang, "remote.pin_reset_title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tech.kousozo.tahoetv.core.I18n.get(lang, "remote.pin_reset_desc"), fontSize = 13.sp, color = Tahoe.Dim)
                Field(a, { a = it.filter(Char::isDigit).take(4) }, tech.kousozo.tahoetv.core.I18n.get(lang, "remote.pin_new_hint"), keyboard = androidx.compose.ui.text.input.KeyboardType.NumberPassword, secret = true)
                Field(b, { b = it.filter(Char::isDigit).take(4) }, tech.kousozo.tahoetv.core.I18n.get(lang, "remote.pin_repeat_hint"), keyboard = androidx.compose.ui.text.input.KeyboardType.NumberPassword, secret = true)
                error?.let { Text(it, color = Tahoe.Bad, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton({
                error = when { a.length != 4 -> tech.kousozo.tahoetv.core.I18n.get(lang, "remote.pin_len_err"); a != b -> tech.kousozo.tahoetv.core.I18n.get(lang, "remote.pin_match_err"); else -> null }
                if (error != null) return@TextButton
                busy = true
                val pin = a // the PIN that was confirmed, even if the field changes while this runs
                Thread {
                    val r = runCatching { TvLink.setKidsPin(host, token, pin) }
                    main.post {
                        busy = false
                        r.fold({ android.widget.Toast.makeText(c, tech.kousozo.tahoetv.core.I18n.get(lang, "remote.pin_saved"), android.widget.Toast.LENGTH_LONG).show(); close() },
                            { error = it.message ?: tech.kousozo.tahoetv.core.I18n.get(lang, "pair.failed") })
                    }
                }.start()
            }, enabled = !busy) { Text(tech.kousozo.tahoetv.core.I18n.get(lang, if (busy) "remote.saving" else "common.save")) }
        },
        dismissButton = { TextButton(close) { Text(tech.kousozo.tahoetv.core.I18n.get(lang, "common.cancel")) } })
}

/** Ring D-pad: accessible buttons on the ring (kept LTR in RTL locales), OK in the middle; swipe anywhere moves too. */
@Composable
private fun DPad(onKey: (String) -> Unit) {
    val c = LocalContext.current
    val lang = PhoneRegion.language
    val am = remember { c.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager }
    var touchExploration by remember { mutableStateOf(am?.isTouchExplorationEnabled == true) }
    DisposableEffect(am) {
        if (am == null) return@DisposableEffect onDispose {}
        val l = android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener { touchExploration = it }
        am.addTouchExplorationStateChangeListener(l)
        onDispose { am.removeTouchExplorationStateChangeListener(l) }
    }
    var acc by remember { mutableStateOf(Offset.Zero) }
    val ring = 240.dp
    KeepLtr {
        Box(
            Modifier.size(ring).clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.04f))))
                .border(if (ThemePrefs.highContrast) 2.dp else 1.dp, Tahoe.Stroke, CircleShape)
                .then(
                    if (touchExploration) Modifier
                    else Modifier.pointerInput(Unit) {
                        detectDragGestures(onDragEnd = { acc = Offset.Zero }) { ch, d ->
                            ch.consume(); acc += d
                            if (abs(acc.x) > 160f || abs(acc.y) > 160f) {
                                onKey(if (abs(acc.x) > abs(acc.y)) (if (acc.x > 0) "right" else "left") else (if (acc.y > 0) "down" else "up"))
                                acc = Offset.Zero
                            }
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            @Composable fun Arrow(icon: ImageVector, label: String, k: String, a: Alignment) {
                val src = remember { MutableInteractionSource() }
                val down by src.collectIsPressedAsState()
                val s by animateFloatAsState(
                    if (down && !ThemePrefs.reduceMotion) 1.25f else 1f,
                    tween(if (ThemePrefs.reduceMotion) 0 else tech.kousozo.tahoetv.core.Brand.MOTION_PRESS, easing = EaseOutTahoe),
                    label = "arrow",
                )
                Box(
                    Modifier.align(a).size(66.dp).clip(CircleShape)
                        .clickable(src, null, role = Role.Button) { onKey(k) }
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, tint = if (down) Tahoe.Accent else Tahoe.Dim, modifier = Modifier.size(30.dp).scale(s))
                }
            }
            Arrow(Icons.Rounded.KeyboardArrowUp, tech.kousozo.tahoetv.core.I18n.get(lang, "remote.dir_up"), "up", Alignment.TopCenter)
            Arrow(Icons.Rounded.KeyboardArrowDown, tech.kousozo.tahoetv.core.I18n.get(lang, "remote.dir_down"), "down", Alignment.BottomCenter)
            Arrow(Icons.Rounded.KeyboardArrowLeft, tech.kousozo.tahoetv.core.I18n.get(lang, "remote.dir_left"), "left", Alignment.CenterStart)
            Arrow(Icons.Rounded.KeyboardArrowRight, tech.kousozo.tahoetv.core.I18n.get(lang, "remote.dir_right"), "right", Alignment.CenterEnd)

            val okSrc = remember { MutableInteractionSource() }
            val okDown by okSrc.collectIsPressedAsState()
            val s by animateFloatAsState(
                if (okDown && !ThemePrefs.reduceMotion) tech.kousozo.tahoetv.core.Brand.PRESS_SCALE else 1f,
                tween(if (ThemePrefs.reduceMotion) 0 else tech.kousozo.tahoetv.core.Brand.MOTION_PRESS, easing = EaseOutTahoe),
                label = "ok",
            )
            Box(
                Modifier.size(96.dp).scale(s).clip(CircleShape).background(Tahoe.Accent)
                    .then(if (ThemePrefs.highContrast) Modifier.border(2.dp, Tahoe.Text, CircleShape) else Modifier)
                    .clickable(okSrc, null, role = Role.Button) { onKey("ok") }
                    .semantics { contentDescription = tech.kousozo.tahoetv.core.I18n.get(lang, "a11y.dpad_ok") },
                contentAlignment = Alignment.Center,
            ) {
                Text("OK", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Tahoe.OnAccent)
            }
        }
    }
}

/** Figma TV card: artwork area, name, status dot. The active TV is the solid (text-coloured) card. */
@Composable
private fun TvCardTile(name: String, status: String, dot: Color, on: Boolean, onClick: () -> Unit) {
    val lang = PhoneRegion.language
    val fg = if (on) Tahoe.Bg else Tahoe.Text
    Column(
        Modifier.width(158.dp).clip(RoundedCornerShape(22.dp)).background(if (on) Tahoe.Text else Tahoe.Surface)
            .border(if (ThemePrefs.highContrast) 2.dp else 1.dp, if (on) Color.Transparent else Tahoe.Stroke, RoundedCornerShape(22.dp))
            .selectable(selected = on, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) {
                stateDescription = tech.kousozo.tahoetv.core.I18n.get(lang, if (on) "a11y.selected" else "a11y.not_selected")
            }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(74.dp).clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(Color(tech.kousozo.tahoetv.core.Brand.LAKE), Color(tech.kousozo.tahoetv.core.Brand.LAKE_DEEP), Tahoe.Stage))),
            contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Tv, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(30.dp)) }
        Text(name, Modifier.padding(horizontal = 4.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(dot)); Spacer(Modifier.width(6.dp))
            Text(status, fontSize = 12.sp, color = fg.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Round key with a caption under it (Kembali · Beranda · Menu · Ketik). */
@Composable
private fun LabeledKey(icon: ImageVector, label: String, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val down by src.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (down && !ThemePrefs.reduceMotion) tech.kousozo.tahoetv.core.Brand.PRESS_SCALE else 1f,
        tween(if (ThemePrefs.reduceMotion) 0 else tech.kousozo.tahoetv.core.Brand.MOTION_PRESS, easing = EaseOutTahoe),
        label = "labeledKey",
    )
    Column(
        Modifier.clip(RoundedCornerShape(16.dp))
            .clickable(src, null, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(52.dp).scale(s).clip(CircleShape).background(Tahoe.Glass)
                .border(if (ThemePrefs.highContrast) 2.dp else 1.dp, Tahoe.Stroke, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Tahoe.Text) }
        Text(label, fontSize = 12.sp, color = Tahoe.Dim, maxLines = 1)
    }
}

/** Figma VOL / CH pill: − LABEL + side by side. */
@Composable
private fun PillRocker(label: String, downIcon: ImageVector, upIcon: ImageVector, down: () -> Unit, up: () -> Unit, modifier: Modifier) = Row(
    modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(Tahoe.Glass).border(1.dp, Tahoe.Stroke, RoundedCornerShape(24.dp)),
    verticalAlignment = Alignment.CenterVertically,
) {
    IconButton(down) { Icon(downIcon, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "remote.rocker_down", label), tint = Tahoe.Text) }
    Text(label, Modifier.weight(1f), fontSize = 11.sp, color = Tahoe.Dim, fontWeight = FontWeight.SemiBold, letterSpacing = 1.32.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    IconButton(up) { Icon(upIcon, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "remote.rocker_up", label), tint = Tahoe.Text) }
}

/** Pair with a 6-digit code from the TV (Pusat Kontrol → HP), for a TV on another network or without discovery. */
@Composable
internal fun PinPairDialog(host0: String, close: () -> Unit) {
    val c = LocalContext.current
    val lang = PhoneRegion.language
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(host0) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = close, title = { Text(tech.kousozo.tahoetv.core.I18n.get(lang, "remote.enter_pin")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.pair_hint"), fontSize = 13.sp, color = Tahoe.Dim)
                Field(host, { host = it }, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.host_hint"), androidx.compose.ui.text.input.KeyboardType.Uri)
                Field(code, { code = it.filter(Char::isDigit).take(6) }, tech.kousozo.tahoetv.core.I18n.get(lang, "tv.code_hint"), androidx.compose.ui.text.input.KeyboardType.NumberPassword)
            }
        },
        confirmButton = {
            TextButton({
                busy = true
                scope.launch {
                    val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { TvLink.pairWithInfo(host.trim(), code, lang, android.os.Build.MODEL.orEmpty()) }
                    }
                    busy = false
                    r.onSuccess { Sync.recordPaired(c, host.trim(), it); Pairing.tick++; close() }
                    android.widget.Toast.makeText(c, r.exceptionOrNull()?.message ?: tech.kousozo.tahoetv.core.I18n.get(lang, "tv.connected_toast"), android.widget.Toast.LENGTH_SHORT).show()
                }
            }, enabled = !busy && host.isNotBlank() && code.length == 6) { Text(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.connect")) }
        },
        dismissButton = { TextButton(close) { Text(tech.kousozo.tahoetv.core.I18n.get(lang, "common.cancel")) } })
}

/** Tall pill with + / − (or ∧ / ∨) and a caption in the middle. */
@Composable
private fun Rocker(label: String, upIcon: ImageVector, downIcon: ImageVector, up: () -> Unit, down: () -> Unit) = Column(
    Modifier.width(72.dp).height(170.dp).clip(RoundedCornerShape(36.dp)).background(Tahoe.Glass).border(1.dp, Tahoe.Stroke, RoundedCornerShape(36.dp)),
    horizontalAlignment = Alignment.CenterHorizontally,
) {
    IconButton(up, Modifier.weight(1f).fillMaxWidth()) { Icon(upIcon, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "remote.rocker_up", label), tint = Tahoe.Text, modifier = Modifier.size(28.dp)) }
    Text(label, fontSize = 12.sp, color = Tahoe.Faint, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
    IconButton(down, Modifier.weight(1f).fillMaxWidth()) { Icon(downIcon, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "remote.rocker_down", label), tint = Tahoe.Text, modifier = Modifier.size(28.dp)) }
}

@Composable
private fun RoundKey(icon: ImageVector, label: String, size: Dp = 62.dp, tint: Color = Tahoe.Text, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val down by src.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (down && !ThemePrefs.reduceMotion) tech.kousozo.tahoetv.core.Brand.PRESS_SCALE else 1f,
        tween(if (ThemePrefs.reduceMotion) 0 else tech.kousozo.tahoetv.core.Brand.MOTION_PRESS, easing = EaseOutTahoe),
        label = "key",
    )
    Box(Modifier.size(size).scale(s).clip(CircleShape).background(Tahoe.Glass)
        .border(if (ThemePrefs.highContrast) 2.dp else 1.dp, Tahoe.Stroke, CircleShape)
        .clickable(src, null, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label },
        contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint) }
}
