package tech.kousozo.tahoetv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import tech.kousozo.tahoetv.core.TvLink
import tech.kousozo.tahoetv.core.Epg
import tech.kousozo.tahoetv.core.LocalTv
import tech.kousozo.tahoetv.core.M3u
import tech.kousozo.tahoetv.core.Web
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Native remote: swipe touchpad (like the Siri Remote) + tap = OK, system keys, volume, app shortcuts, and
 *  "ask the TV" (Gemini on the TV). Uses the paired WebSocket, so it works on any TV running Tahoe, no ADB. */
@Composable
fun RemoteScreen() {
    val c = LocalContext.current
    val p = remember { Sync.prefs(c) }
    val host = p.getString("host", "").orEmpty(); val token = p.getString("token", "").orEmpty()
    var online by remember { mutableStateOf(false) }
    val link = remember(host, token) { if (host.isEmpty() || token.isEmpty()) null else TvLink(host, token) { online = it } }
    DisposableEffect(link) { link?.open(); onDispose { link?.close() } }
    val haptic = LocalHapticFeedback.current
    fun key(k: String) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); link?.key(k) }

    Page("Remote", if (link == null) "Belum tersambung" else if (online) "TV siap" else "Menyambung…", bottomInset = TabInset) {
        if (link == null) { Note("Sambungkan TV dulu di tab Beranda (kode dari Pusat Kontrol → HP), atau pasang Tahoe dari tab TV.", Icons.Rounded.Info); return@Page }

        // touchpad: swipe = arrows, tap = OK
        var acc by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.1f).clip(RoundedCornerShape(32.dp))
                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.04f))))
                .border(1.dp, Tahoe.Stroke, RoundedCornerShape(32.dp))
                .pointerInput(link) { detectTapGestures { key("ok") } }
                .pointerInput(link) {
                    detectDragGestures(onDragEnd = { acc = androidx.compose.ui.geometry.Offset.Zero }) { ch, d ->
                        ch.consume(); acc += d
                        val step = 70f
                        if (abs(acc.x) > step || abs(acc.y) > step) {
                            key(if (abs(acc.x) > abs(acc.y)) (if (acc.x > 0) "right" else "left") else (if (acc.y > 0) "down" else "up"))
                            acc = androidx.compose.ui.geometry.Offset.Zero
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.TouchApp, null, tint = Tahoe.Faint, modifier = Modifier.size(36.dp))
                Text("Geser untuk pindah · ketuk untuk OK", color = Tahoe.Faint, fontSize = 13.sp)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundKey(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali") { key("back") }
            RoundKey(Icons.Rounded.Home, "Home") { key("home") }
            RoundKey(Icons.Rounded.Apps, "Aplikasi") { key("apps") }
            RoundKey(Icons.Rounded.Menu, "Menu") { key("menu") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { SecondaryButton("Volume −", Icons.Rounded.VolumeDown) { link.volume(-1) } }
            Box(Modifier.weight(1f)) { SecondaryButton("Volume +", Icons.Rounded.VolumeUp) { link.volume(1) } }
        }

        Section("Buka di TV")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("YouTube" to "com.google.android.youtube.tv", "Netflix" to "com.netflix.ninja", "Vidio" to "com.vidio.android.tv", "Spotify" to "com.spotify.tv.android")
                .forEach { (n, pkg) ->
                    Text(n, Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Tahoe.Glass).clickable { link.launch(pkg) }.padding(vertical = 12.dp),
                        fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
        }

        Section("Tanya asisten di TV")
        var q by remember { mutableStateOf("") }
        Field(q, { q = it }, "mis. putar lagu santai di YouTube", trailing = {
            IconButton({ if (q.isNotBlank()) { link.ask(q.trim()); q = "" } }) { Icon(Icons.AutoMirrored.Rounded.Send, "Kirim", tint = Tahoe.Accent) }
        })

        WhatsOn { link.launch(BITTV) }
    }
}

const val BITTV = "com.live_streaming_tv.online_tv"

/** What's on now on Indonesian TV, local channels first (same list as the TV's "Jadwal TV"). Tapping opens BitTV on the TV. */
@Composable
private fun WhatsOn(open: (LocalTv.Entry) -> Unit) {
    val c = LocalContext.current
    var entries by remember { mutableStateOf<List<LocalTv.Entry>?>(null) }
    var epg by remember { mutableStateOf<Epg?>(null) }
    var cat by remember { mutableStateOf(LocalTv.Category.Near) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val setup = c.getSharedPreferences("setup", 0)
            val province = setup.getString("region", null) ?: setup.getString("city", "").orEmpty().takeIf { it.isNotBlank() }
                ?.let { Web.geocode(it)?.region }?.also { setup.edit().putString("region", it).apply() }
            entries = runCatching { LocalTv.arrange(M3u.load(M3u.DEFAULT), province) }.getOrNull()
            epg = runCatching { Epg.load() }.getOrNull()
        }
    }
    val list = entries ?: run { Section("Sedang tayang"); LinearProgressIndicator(Modifier.fillMaxWidth(), color = Tahoe.Accent, trackColor = Tahoe.Glass); return }
    val cats = LocalTv.Category.entries.filter { k -> list.any { LocalTv.inCategory(it, k) } }
    if (cat !in cats) cat = cats.firstOrNull() ?: return
    Section("Sedang tayang")
    Text("Ketuk saluran untuk menonton di BitTV pada TV.", color = Tahoe.Dim, fontSize = 13.sp)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        cats.forEach { k ->
            val on = k == cat
            Text(k.label, Modifier.clip(RoundedCornerShape(50)).background(if (on) Tahoe.Text else Tahoe.Glass).clickable { cat = k }
                .padding(horizontal = 12.dp, vertical = 7.dp), color = if (on) Color(0xFF111114) else Tahoe.Text, fontSize = 13.sp)
        }
    }
    val now = Instant.now()
    GlassCard {
        list.filter { LocalTv.inCategory(it, cat) }.take(30).forEach { e ->
            val s = epg?.schedule(e.name, now).orEmpty()
            val p = s.firstOrNull()?.takeIf { it.start <= now }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open(e) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(p?.let { "${it.title} · ${HM.format(it.start)}–${HM.format(it.stop)}" } ?: if (epg == null) "Memuat jadwal…" else e.category.label,
                        color = if (p != null) Tahoe.Accent else Tahoe.Faint, fontSize = 13.sp, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                Icon(Icons.Rounded.PlayCircle, "Tonton", tint = Tahoe.Dim)
            }
        }
    }
}

private val HM = DateTimeFormatter.ofPattern("HH.mm").withZone(ZoneId.systemDefault())

@Composable
private fun RoundKey(icon: ImageVector, label: String, onClick: () -> Unit) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(Modifier.size(62.dp).clip(CircleShape).background(Tahoe.Glass).border(1.dp, Tahoe.Stroke, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) { Icon(icon, label, tint = Tahoe.Text) }
    Text(label, fontSize = 11.sp, color = Tahoe.Dim, modifier = Modifier.padding(top = 4.dp))
}
