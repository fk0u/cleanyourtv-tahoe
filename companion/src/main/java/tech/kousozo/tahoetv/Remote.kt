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
    }
}

@Composable
private fun RoundKey(icon: ImageVector, label: String, onClick: () -> Unit) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Box(Modifier.size(62.dp).clip(CircleShape).background(Tahoe.Glass).border(1.dp, Tahoe.Stroke, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) { Icon(icon, label, tint = Tahoe.Text) }
    Text(label, fontSize = 11.sp, color = Tahoe.Dim, modifier = Modifier.padding(top = 4.dp))
}
