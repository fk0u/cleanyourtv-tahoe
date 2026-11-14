package tech.kousozo.tahoetv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var online by remember { mutableStateOf(false) }
    var tv by remember { mutableStateOf<org.json.JSONObject?>(null) } // volume / channel reported by the TV
    // TvLink calls back on its network thread; change screen state on the main thread only.
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val link = remember(host, token) {
        if (host.isBlank() || token.isEmpty()) null
        else TvLink(host, token) { o -> main.post { online = o } }.apply {
            onTvState = { s -> main.post { tv = s } }
            // "Kirim log" pressed on the TV: offer the share sheet here (the TV can't share files itself)
            onCommand = { o -> if (o.optString("t") == "send_log") main.post { Report.send(c) { n -> n?.let { android.widget.Toast.makeText(c, it, android.widget.Toast.LENGTH_LONG).show() } } } }
        }
    }
    DisposableEffect(link) { link?.open(); onDispose { link?.close() } }
    val haptic = LocalHapticFeedback.current
    fun key(k: String) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); link?.key(k) }


    if (showDevices) TvDevicesSheet({ showDevices = false }) { Nav.tab = 0 }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = TabInset + 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // TV pill (tap to switch TV / inspect mDNS devices)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (online) Tahoe.Good else Tahoe.Faint))
            Spacer(Modifier.width(10.dp))
            Row(Modifier.weight(1f).clip(RoundedCornerShape(50)).background(Tahoe.Glass).border(1.dp, Tahoe.Stroke, RoundedCornerShape(50))
                .clickable { showDevices = true }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Tv, null, tint = Tahoe.Accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(tvName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val channel = tv?.optString("channel").orEmpty()
                    Text(if (link == null) "Belum dipasangkan" else if (!online) "Menyambung…"
                        else if (channel.isNotEmpty()) "Live · %03d %s".format(tv?.optInt("number") ?: 0, channel) else "Tersambung · $host", fontSize = 12.sp, color = Tahoe.Dim)
                }
                Icon(Icons.Rounded.ExpandMore, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "connect.devices"), tint = Tahoe.Dim, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            RoundKey(Icons.Rounded.Settings, "Setelan TV", 44.dp) { key("settings") }
            Spacer(Modifier.width(8.dp))
            RoundKey(Icons.Rounded.PowerSettingsNew, "Standby TV (menyalakan lagi pakai remote TV)", 44.dp, tint = Color(0xFFFF6B6B)) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); link?.power() }
        }

        if (link == null) Note("Belum tersambung. Sambungkan TV di Beranda (kode dari Pusat Kontrol → HP), atau pasang Tahoe dari tab Pasang.", Icons.Rounded.Info)
        DPad(onKey = ::key)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundKey(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali") { key("back") }
            RoundKey(Icons.Rounded.Home, "Home") { key("home") }
            RoundKey(Icons.AutoMirrored.Rounded.VolumeOff, "Bisu") { key("mute") }
        }

        // rockers: volume | Live TV | channel
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Rocker("VOL", Icons.Rounded.Add, Icons.Rounded.Remove, { link?.volume(1); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                { link?.volume(-1); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) })
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                RoundKey(Icons.Rounded.LiveTv, "Live TV", tint = Color(0xFFFF5A5F)) { key("livetv") }
                RoundKey(Icons.Rounded.Apps, "Aplikasi") { key("apps") }
            }
            Rocker("CH", Icons.Rounded.KeyboardArrowUp, Icons.Rounded.KeyboardArrowDown, { key("ch_up") }, { key("ch_down") })
        }

        // volume slider with the TV's real level
        var vol by remember { mutableFloatStateOf(-1f) }
        LaunchedEffect(tv) { tv?.let { vol = it.optInt("volume").toFloat() } }
        if (vol >= 0) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Tahoe.Glass).border(1.dp, Tahoe.Stroke, RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (tv?.optBoolean("muted") == true) Icons.AutoMirrored.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                if (tv?.optBoolean("muted") == true) "Bisu" else "Volume", tint = Tahoe.Accent)
            Slider(vol, { vol = it }, Modifier.weight(1f).padding(horizontal = 12.dp).semantics { contentDescription = "Volume TV" }, valueRange = 0f..100f,
                onValueChangeFinished = { link?.volumeSet(vol.toInt()) })
            Text("${vol.toInt()}%", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(40.dp))
        }

        // sleep timer: tap to step Mati → 15 → 30 → 60 → 90 → 120 → Mati; shows the TV's minutes left
        val sleepLeft = tv?.optInt("sleep") ?: 0
        TextButton({ link?.sleep(listOf(15, 30, 60, 90, 120).firstOrNull { it > sleepLeft } ?: 0) }) {
            Icon(Icons.Rounded.Bedtime, null); Spacer(Modifier.width(8.dp))
            Text(if (sleepLeft == 0) "Timer tidur: mati" else "Timer tidur: $sleepLeft menit lagi")
        }

        // channel numbers (Live TV): 1–3 digits, the TV tunes 1.5 s after the last one
        var pad by remember { mutableStateOf(false) }
        TextButton({ pad = !pad }) { Icon(Icons.Rounded.Dialpad, null); Spacer(Modifier.width(8.dp)); Text(if (pad) "Tutup nomor saluran" else "Nomor saluran") }
        if (pad) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⟲", "0", "")).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { d ->
                        if (d.isEmpty()) Spacer(Modifier.size(62.dp))
                        else {
                            val desc = if (d == "⟲") "Saluran sebelumnya" else "Angka $d"
                            Box(Modifier.size(62.dp).clip(CircleShape).background(Tahoe.Glass).border(1.dp, Tahoe.Stroke, CircleShape)
                                .semantics { contentDescription = desc }
                                .clickable { key(if (d == "⟲") "prev" else d) }, // ⟲ = previous channel
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
                Triple(Icons.Rounded.FastRewind, "rewind", "Mundur"),
                Triple(Icons.Rounded.PlayArrow, "play_pause", "Putar atau jeda"),
                Triple(Icons.Rounded.FastForward, "ffwd", "Maju"),
                Triple(Icons.Rounded.Menu, "menu", "Menu"),
            ).forEach { (icon, k, desc) -> IconButton({ key(k) }) { Icon(icon, desc, tint = Tahoe.Text) } }
        }

        // type into the TV's focused text field (search boxes in YouTube, Netflix, Tahoe…)
        var typed by remember { mutableStateOf("") }
        Field(typed, { typed = it; link?.text(it) }, "Ketik ke TV (kolom yang dipilih di TV)…", trailing = {
            IconButton({ typed = ""; link?.text("") }) { Icon(Icons.Rounded.Backspace, "Hapus", tint = Tahoe.Dim) }
        })

        // ask the TV
        var q by remember { mutableStateOf("") }
        Field(q, { q = it }, "Tanya asisten di TV…", trailing = {
            IconButton({ if (q.isNotBlank()) { link?.ask(q.trim()); q = "" } }) { Icon(Icons.AutoMirrored.Rounded.Send, "Kirim", tint = Tahoe.Accent) }
        })

        // support: send logs, reset the kids PIN
        var pinDialog by remember { mutableStateOf(false) }
        var sending by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton({
                sending = true
                Report.send(c) { n -> sending = false; n?.let { android.widget.Toast.makeText(c, it, android.widget.Toast.LENGTH_LONG).show() } }
            }, enabled = !sending) { Icon(Icons.Rounded.BugReport, null); Spacer(Modifier.width(8.dp)); Text(if (sending) "Menyiapkan log…" else "Kirim log") }
            if (link != null) TextButton({ pinDialog = true }) { Icon(Icons.Rounded.Lock, null); Spacer(Modifier.width(8.dp)); Text("PIN Anak") }
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
    AlertDialog(onDismissRequest = close, title = { Text("Atur ulang PIN Anak") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("PIN baru untuk keluar dari profil Anak di TV. PIN lama tidak diperlukan.", fontSize = 13.sp, color = Tahoe.Dim)
                Field(a, { a = it.filter(Char::isDigit).take(4) }, "PIN baru (4 angka)", keyboard = androidx.compose.ui.text.input.KeyboardType.NumberPassword, secret = true)
                Field(b, { b = it.filter(Char::isDigit).take(4) }, "Ulangi PIN", keyboard = androidx.compose.ui.text.input.KeyboardType.NumberPassword, secret = true)
                error?.let { Text(it, color = Tahoe.Bad, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton({
                error = when { a.length != 4 -> "PIN harus 4 angka"; a != b -> "PIN tidak sama"; else -> null }
                if (error != null) return@TextButton
                busy = true
                val pin = a // the PIN that was confirmed, even if the field changes while this runs
                Thread {
                    val r = runCatching { TvLink.setKidsPin(host, token, pin) }
                    main.post {
                        busy = false
                        r.fold({ android.widget.Toast.makeText(c, "PIN Anak di TV sudah diganti", android.widget.Toast.LENGTH_LONG).show(); close() },
                            { error = it.message ?: "Gagal" })
                    }
                }.start()
            }, enabled = !busy) { Text(if (busy) "Menyimpan…" else "Simpan") }
        },
        dismissButton = { TextButton(close) { Text("Batal") } })
}

/** Ring D-pad: arrows on the ring, OK in the middle; a swipe anywhere on it also moves (one step per ~60 dp). */
@Composable
private fun DPad(onKey: (String) -> Unit) {
    var acc by remember { mutableStateOf(Offset.Zero) }
    var pressed by remember { mutableStateOf<String?>(null) }
    val ring = 260.dp
    Box(
        Modifier.size(ring).clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.12f), Color.White.copy(alpha = 0.04f))))
            .border(1.dp, Tahoe.Stroke, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { o ->
                        val cx = size.width / 2f; val cy = size.height / 2f; val dx = o.x - cx; val dy = o.y - cy
                        val k = when {
                            dx * dx + dy * dy < (size.width * 0.2f) * (size.width * 0.2f) -> "ok"
                            abs(dx) > abs(dy) -> if (dx > 0) "right" else "left"
                            else -> if (dy > 0) "down" else "up"
                        }
                        pressed = k; tryAwaitRelease(); pressed = null; onKey(k)
                    })
            }
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = { acc = Offset.Zero }) { ch, d ->
                    ch.consume(); acc += d
                    if (abs(acc.x) > 160f || abs(acc.y) > 160f) {
                        onKey(if (abs(acc.x) > abs(acc.y)) (if (acc.x > 0) "right" else "left") else (if (acc.y > 0) "down" else "up"))
                        acc = Offset.Zero
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        @Composable fun Arrow(icon: ImageVector, label: String, k: String, a: Alignment) {
            val s by animateFloatAsState(if (pressed == k) 1.35f else 1f, spring(), label = "arrow")
            Icon(icon, label, tint = if (pressed == k) Tahoe.Accent else Tahoe.Dim, modifier = Modifier.align(a).padding(18.dp).size(30.dp).scale(s))
        }
        Arrow(Icons.Rounded.KeyboardArrowUp, "Atas", "up", Alignment.TopCenter)
        Arrow(Icons.Rounded.KeyboardArrowDown, "Bawah", "down", Alignment.BottomCenter)
        Arrow(Icons.Rounded.KeyboardArrowLeft, "Kiri", "left", Alignment.CenterStart)
        Arrow(Icons.Rounded.KeyboardArrowRight, "Kanan", "right", Alignment.CenterEnd)
        val s by animateFloatAsState(if (pressed == "ok") 0.92f else 1f, spring(), label = "ok")
        Box(Modifier.size(104.dp).scale(s).clip(CircleShape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.10f))))
            .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape), contentAlignment = Alignment.Center) {
            Text("OK", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Tall pill with + / − (or ∧ / ∨) and a caption in the middle. */
@Composable
private fun Rocker(label: String, upIcon: ImageVector, downIcon: ImageVector, up: () -> Unit, down: () -> Unit) = Column(
    Modifier.width(72.dp).height(170.dp).clip(RoundedCornerShape(36.dp)).background(Tahoe.Glass).border(1.dp, Tahoe.Stroke, RoundedCornerShape(36.dp)),
    horizontalAlignment = Alignment.CenterHorizontally,
) {
    IconButton(up, Modifier.weight(1f).fillMaxWidth()) { Icon(upIcon, "$label naik", tint = Tahoe.Text, modifier = Modifier.size(28.dp)) }
    Text(label, fontSize = 12.sp, color = Tahoe.Faint, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
    IconButton(down, Modifier.weight(1f).fillMaxWidth()) { Icon(downIcon, "$label turun", tint = Tahoe.Text, modifier = Modifier.size(28.dp)) }
}

@Composable
private fun RoundKey(icon: ImageVector, label: String, size: Dp = 62.dp, tint: Color = Tahoe.Text, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val down by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (down) 0.9f else 1f, spring(), label = "key")
    Box(Modifier.size(size).scale(s).clip(CircleShape).background(Tahoe.Glass).border(1.dp, Tahoe.Stroke, CircleShape)
        .clickable(src, null, onClick = onClick), contentAlignment = Alignment.Center) { Icon(icon, label, tint = tint) }
}
