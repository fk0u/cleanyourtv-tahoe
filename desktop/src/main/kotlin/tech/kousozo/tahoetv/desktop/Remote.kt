package tech.kousozo.tahoetv.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Live view of the TV screen + remote. Keyboard: arrows, Enter = OK, Esc/Backspace = back, H = home, +/- = volume.
 *  Clicking the picture taps that spot on the TV. DRM apps (Netflix etc.) show black: the TV blocks capturing them. */
@Composable
fun RemoteScreen(onSetup: () -> Unit) {
    val tv = Session.tv
    if (tv == null) {
        Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 28.dp, top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Title("Remote & Cermin", "Sambungkan TV dulu di TV Rumah (butuh USB debugging aktif di TV).")
            PrimaryButton("Ke TV Rumah", Icons.Rounded.Tv, onClick = onSetup)
        }
        return
    }
    val scope = rememberCoroutineScope()
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var mirror by remember { mutableStateOf(true) }
    var fps by remember { mutableStateOf(0.0) }
    var app by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tv, mirror) {
        while (isActive && mirror) {
            val t0 = System.nanoTime()
            val img = withContext(Dispatchers.IO) { runCatching { org.jetbrains.skia.Image.makeFromEncoded(tv.screencap()).toComposeImageBitmap() }.getOrNull() }
            if (img != null) frame = img
            fps = 1e9 / (System.nanoTime() - t0)
            delay(30)
        }
    }
    LaunchedEffect(tv) { while (isActive) { app = withContext(Dispatchers.IO) { runCatching { tv.foregroundApp() }.getOrNull() }; delay(3000) } }
    fun key(code: String) = scope.launch(Dispatchers.IO) { runCatching { tv.key(code) } }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Row(
        Modifier.fillMaxSize().padding(start = 12.dp, end = 28.dp, top = 24.dp, bottom = 24.dp).focusRequester(focus).focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val code = when (e.key) {
                    Key.DirectionUp -> "DPAD_UP"; Key.DirectionDown -> "DPAD_DOWN"; Key.DirectionLeft -> "DPAD_LEFT"; Key.DirectionRight -> "DPAD_RIGHT"
                    Key.Enter, Key.Spacebar -> "DPAD_CENTER"; Key.Escape, Key.Backspace -> "BACK"; Key.H -> "HOME"
                    Key.Equals, Key.Plus -> "VOLUME_UP"; Key.Minus -> "VOLUME_DOWN"; else -> null
                } ?: return@onKeyEvent false
                key(code); true
            },
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Title("Remote & Cermin", "${tv.model}" + (app?.let { " · sedang dibuka: ${tv.label(it)}" } ?: ""))
                Spacer(Modifier.weight(1f))
                Text(if (mirror) "%.1f fps".format(fps) else "", color = Tahoe.Faint, fontSize = 12.sp)
                Spacer(Modifier.width(10.dp))
                Switch(mirror, { mirror = it }, colors = SwitchDefaults.colors(checkedTrackColor = Tahoe.Good))
                Spacer(Modifier.width(6.dp)); Text("Cermin layar", fontSize = 14.sp)
            }
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp)).background(Color.Black).border(1.dp, Tahoe.Stroke, RoundedCornerShape(20.dp))) {
                val f = frame
                if (f != null && mirror) Image(f, "Layar TV", Modifier.fillMaxSize().pointerInput(f) {
                    detectTapGestures { p ->
                        val x = (p.x / size.width * f.width).toInt(); val y = (p.y / size.height * f.height).toInt()
                        scope.launch(Dispatchers.IO) { runCatching { tv.tap(x, y) } }
                    }
                }, contentScale = ContentScale.FillBounds)
                else Text(if (mirror) "Mengambil gambar layar…" else "Cermin dimatikan", Modifier.align(Alignment.Center), color = Tahoe.Dim)
            }
            Text("Klik gambar untuk mengetuk di TV. Keyboard: panah, Enter = OK, Esc = kembali, H = Home, +/− = volume. Aplikasi ber-DRM (Netflix dll.) tampil hitam.",
                fontSize = 12.sp, color = Tahoe.Faint)
        }

        Glass(Modifier.width(260.dp), radius = 28, padding = 20) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(200.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.06f)).border(1.dp, Tahoe.Stroke, CircleShape)) {
                    Pad(Icons.Rounded.KeyboardArrowUp, Modifier.align(Alignment.TopCenter)) { key("DPAD_UP") }
                    Pad(Icons.Rounded.KeyboardArrowDown, Modifier.align(Alignment.BottomCenter)) { key("DPAD_DOWN") }
                    Pad(Icons.Rounded.KeyboardArrowLeft, Modifier.align(Alignment.CenterStart)) { key("DPAD_LEFT") }
                    Pad(Icons.Rounded.KeyboardArrowRight, Modifier.align(Alignment.CenterEnd)) { key("DPAD_RIGHT") }
                    Box(Modifier.align(Alignment.Center).size(80.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).clickable { key("DPAD_CENTER") },
                        contentAlignment = Alignment.Center) { Text("OK", fontWeight = FontWeight.Bold) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Round(Icons.AutoMirrored.Rounded.ArrowBack) { key("BACK") }
                Round(Icons.Rounded.Home) { key("HOME") }
                Round(Icons.Rounded.Menu) { key("MENU") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Round(Icons.AutoMirrored.Rounded.VolumeDown) { key("VOLUME_DOWN") }
                Round(Icons.Rounded.VolumeOff) { key("VOLUME_MUTE") }
                Round(Icons.AutoMirrored.Rounded.VolumeUp) { key("VOLUME_UP") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Round(Icons.Rounded.SkipPrevious) { key("MEDIA_PREVIOUS") }
                Round(Icons.Rounded.PlayArrow) { key("MEDIA_PLAY_PAUSE") }
                Round(Icons.Rounded.SkipNext) { key("MEDIA_NEXT") }
            }
            var text by remember { mutableStateOf("") }
            Field(text, { text = it }, "Ketik ke TV", Modifier.fillMaxWidth().onEnter {
                val t = text; text = ""; scope.launch(Dispatchers.IO) { runCatching { tv.text(t) } }
            })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("YouTube" to "com.google.android.youtube.tv", "Netflix" to "com.netflix.ninja", "Vidio" to "com.vidio.android.tv").forEach { (n, pkg) ->
                    Text(n, Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(Tahoe.Glass).clickable {
                        scope.launch(Dispatchers.IO) { runCatching { tv.sh("monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1") } }
                    }.padding(vertical = 8.dp), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun Pad(icon: ImageVector, modifier: Modifier, onClick: () -> Unit) =
    Box(modifier.size(60.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(32.dp)) }

@Composable
private fun Round(icon: ImageVector, onClick: () -> Unit) =
    Box(Modifier.size(52.dp).clip(CircleShape).background(Tahoe.Glass).border(1.dp, Tahoe.Stroke, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) { Icon(icon, null) }

