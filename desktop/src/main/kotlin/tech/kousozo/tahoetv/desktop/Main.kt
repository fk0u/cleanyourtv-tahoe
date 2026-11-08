package tech.kousozo.tahoetv.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
    Tv("TV Rumah", Icons.Rounded.Tv),
    Remote("Remote", Icons.Rounded.SettingsRemote),
    Prefs("Setelan", Icons.Rounded.Settings),
}

/** The ADB session to the home TV, shared by "TV Rumah" (setup) and "Remote & Cermin". */
object Session {
    var tv by mutableStateOf<TvAdb?>(null)
}

fun main() = application {
    val icon = remember { Screen::class.java.getResourceAsStream("/icon.png")?.use { BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap()) } }
    Window(
        onCloseRequest = { Session.tv?.close(); PlayerWindow.close(); exitApplication() }, title = "Tahoe TV", icon = icon,
        state = rememberWindowState(size = DpSize(1280.dp, 820.dp)),
    ) {
        window.minimumSize = java.awt.Dimension(980, 640)
        TahoeTheme { App() }
    }
}

@Composable
fun App(initial: Screen = Screen.Home) {
    var screen by remember { mutableStateOf(initial) }
    Box(Modifier.fillMaxSize()) {
        Aurora()
        Row(Modifier.fillMaxSize()) {
            Sidebar(screen) { screen = it }
            AnimatedContent(screen, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen", modifier = Modifier.weight(1f)) { s ->
                when (s) {
                    Screen.Home -> HomeScreen(onLive = { screen = Screen.Live })
                    Screen.Live -> LiveScreen()
                    Screen.Tv -> HomeTvScreen(onRemote = { screen = Screen.Remote })
                    Screen.Remote -> RemoteScreen(onSetup = { screen = Screen.Tv })
                    Screen.Prefs -> SettingsScreen()
                }
            }
        }
    }
}

@Composable
private fun Sidebar(selected: Screen, onSelect: (Screen) -> Unit) = Column(
    Modifier.width(232.dp).fillMaxHeight().padding(12.dp).clip(RoundedCornerShape(24.dp))
        .background(Color(0xFF15151B).copy(alpha = 0.7f)).border(1.dp, Tahoe.GlassBorder, RoundedCornerShape(24.dp)).padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
) {
    Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Tv, null, tint = Tahoe.Accent, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Text("Tahoe TV", fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
    Screen.entries.forEach { s ->
        val on = s == selected
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                .clickable { onSelect(s) }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(s.icon, null, tint = if (on) Tahoe.Accent else Tahoe.Dim, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(s.label, fontSize = 15.sp, color = if (on) Tahoe.Text else Tahoe.Dim, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
    Spacer(Modifier.weight(1f))
    val tv = Session.tv
    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(if (tv != null) Tahoe.Good else Tahoe.Faint))
        Spacer(Modifier.width(8.dp))
        Text(if (tv != null) tv.model else "TV belum tersambung", fontSize = 12.sp, color = Tahoe.Dim, maxLines = 1)
    }
}
