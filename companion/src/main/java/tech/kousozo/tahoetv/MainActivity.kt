package tech.kousozo.tahoetv

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
import androidx.compose.foundation.layout.*
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

/** Three tabs: Beranda (status, sync), Remote (native remote over the paired link), TV (phone-only setup wizard). */
class MainActivity : ComponentActivity() {
    private val resumed = mutableIntStateOf(0)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val dark = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(dark, dark)
        setContent {
            TahoeTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Box(Modifier.fillMaxSize()) {
                    Aurora()
                    AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
                        when (t) {
                            0 -> HomeScreen(resumed.intValue, onSetup = { tab = 2 }, onRemote = { tab = 1 })
                            1 -> RemoteScreen()
                            else -> SetupScreen(onExit = { tab = 0 })
                        }
                    }
                    TabBar(listOf("Beranda" to Icons.Rounded.Home, "Remote" to Icons.Rounded.SettingsRemote, "TV" to Icons.Rounded.Tv),
                        tab, { tab = it }, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }

    override fun onResume() { super.onResume(); resumed.intValue++ }
}

/** Space for the floating tab bar under scrolling content. */
val TabInset = 88.dp

@Composable
fun HomeScreen(resumeTick: Int, onSetup: () -> Unit, onRemote: () -> Unit) {
    val c = LocalContext.current
    val p = remember { Sync.prefs(c) }
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(p.getString("host", "").orEmpty()) }
    var paired by remember { mutableStateOf(p.getString("token", "").orEmpty().isNotEmpty()) }
    var code by remember { mutableStateOf("") }
    var notif by remember { mutableStateOf(p.getBoolean("notif", true)) }
    var media by remember { mutableStateOf(p.getBoolean("media", true)) }
    val granted = remember(resumeTick) {
        Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners").orEmpty()
            .contains(ComponentName(c, Sync::class.java).flattenToString())
    }
    val name = remember { c.getSharedPreferences("setup", 0).getString("name", "").orEmpty() }
    val hello = when (LocalTime.now().hour) { in 4..10 -> "Selamat pagi"; in 11..14 -> "Selamat siang"; in 15..17 -> "Selamat sore"; else -> "Selamat malam" }

    LaunchedEffect(Unit) { // auto-detect a TV already running Tahoe
        if (host.isEmpty()) Scan.run(wifiAddress(c)) { f -> if (f.tahoe && host.isEmpty()) scope.launch { host = f.ip } }
    }

    Page(hello + (if (name.isNotEmpty()) ", $name" else ""), "Tahoe TV", bottomInset = TabInset) {
        // connection hero
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Rounded.Tv, null, tint = if (paired) Tahoe.Good else Tahoe.Faint, modifier = Modifier.size(40.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (paired) "Tersambung ke TV" else "Belum ada TV", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (paired) "$host · ${Sync.status.value}" else "Pasang Tahoe dari HP atau sambungkan dengan kode.", color = Tahoe.Dim, fontSize = 13.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction("Remote", Icons.Rounded.SettingsRemote, Modifier.weight(1f), onRemote)
            QuickAction("Pasang", Icons.Rounded.Download, Modifier.weight(1f), onSetup)
            QuickAction("Gamepad", Icons.Rounded.SportsEsports, Modifier.weight(1f)) {
                if (host.isNotBlank()) c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://${host.trim()}:8765/")))
            }
        }

        Section("Dari HP ke TV")
        GlassCard {
            if (!granted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.NotificationsOff, null, tint = Tahoe.Faint, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Akses notifikasi belum diizinkan", Modifier.weight(1f), fontSize = 15.sp)
                    TextButton({ c.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Izinkan", color = Tahoe.Accent) }
                }
                HorizontalDivider(color = Tahoe.Stroke)
            }
            SwitchRow("Teruskan notifikasi", notif) { notif = it; p.edit().putBoolean("notif", it).apply() }
            SwitchRow("Tampilkan musik yang diputar", media) { media = it; p.edit().putBoolean("media", it).apply(); Sync.inst?.sendMedia() }
        }

        Section(if (paired) "Ganti TV" else "Sambungkan dengan kode")
        Text("Untuk TV yang sudah memakai Tahoe (termasuk yang dipasang lewat flashdisk): buka Pusat Kontrol → HP di TV.", color = Tahoe.Dim, fontSize = 13.sp)
        Field(host, { host = it }, "Alamat TV (terdeteksi otomatis)", KeyboardType.Uri)
        Field(code, { if (it.length <= 6) code = it.filter(Char::isDigit) }, "Kode 6 digit", KeyboardType.NumberPassword)
        PrimaryButton("Sambungkan", enabled = host.isNotBlank() && code.length == 6, icon = Icons.Rounded.Link) {
            scope.launch {
                val err = withContext(Dispatchers.IO) {
                    runCatching { TvLink.pair(host.trim(), code) }.fold({ token ->
                        p.edit().putString("host", host.trim()).putString("token", token).apply(); null
                    }, { it.message })
                }
                if (err == null) { code = ""; paired = true; Sync.inst?.connect() }
                Toast.makeText(c, err ?: "Tersambung", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun QuickAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) =
    GlassCard(modifier, onClick = onClick) {
        Icon(icon, null, tint = Tahoe.Accent, modifier = Modifier.size(26.dp))
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
