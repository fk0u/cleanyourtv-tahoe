package tech.kousozo.tahoetv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.kousozo.tahoetv.core.Scan
import tech.kousozo.tahoetv.core.TvAdb

/** Find the home TV, then debloat + install + configure Tahoe from this computer (same steps as the phone wizard). */
@Composable
fun HomeTvScreen(onRemote: () -> Unit) {
    val scope = rememberCoroutineScope()
    val found = remember { mutableStateListOf<Scan.Found>() }
    var scanning by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf(Settings.tvHost) }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun scan() {
        if (scanning) return
        found.clear(); scanning = true
        scope.launch { Scan.run(Scan.localAddress()) { f -> scope.launch { found += f; found.sortBy { !it.tahoe } } }; scanning = false }
    }
    fun connect(h: String) {
        host = h; Settings.tvHost = h; connecting = true; error = null
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { Session.tv?.close(); TvAdb.connect(h, Settings.adbDir) } }
                .fold({ Session.tv = it }, { error = it.message ?: "$it" })
            connecting = false
        }
    }
    LaunchedEffect(Unit) { if (Session.tv == null) scan() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 28.dp, top = 24.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val tv = Session.tv
        if (tv == null) {
            Title("TV Rumah", "Pasang dan atur Tahoe di Android TV lewat Wi-Fi. Laptop dan TV harus di jaringan yang sama.")
            Glass(Modifier.fillMaxWidth()) {
                Text("Di TV, nyalakan debugging sekali saja:", fontWeight = FontWeight.SemiBold)
                Text("Setelan → Preferensi Perangkat → Tentang → tekan \"Build\" 7× → kembali → Opsi developer → USB debugging: AKTIF.\n" +
                    "Beberapa TV box (mis. M96) hanya membuka debugging lewat kabel USB; untuk itu pasang Tahoe lewat flashdisk lalu pakai Remote dengan kode.",
                    color = Tahoe.Dim, fontSize = 14.sp, lineHeight = 20.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Section(if (scanning) "Mencari TV…" else if (found.isEmpty()) "Tidak ada TV ditemukan" else "TV di jaringan")
                Spacer(Modifier.weight(1f))
                GhostButton("Pindai ulang", Icons.Rounded.Refresh) { scan() }
            }
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Tahoe.Accent, trackColor = Tahoe.Glass)
            found.forEach { f ->
                Glass(Modifier.fillMaxWidth(), onClick = { if (f.adb) connect(f.ip) else error = "Nyalakan USB debugging di TV ${f.ip} dulu." }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Tv, null, tint = if (f.adb) Tahoe.Accent else Tahoe.Faint, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) { Text(f.label, fontWeight = FontWeight.SemiBold); Text(f.status, color = Tahoe.Dim, fontSize = 13.sp) }
                        Icon(Icons.Rounded.ChevronRight, null, tint = Tahoe.Faint)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(host, { host = it }, "…atau ketik IP TV", Modifier.width(320.dp).onEnter { if (host.isNotBlank()) connect(host.trim()) })
                PrimaryButton("Sambungkan", Icons.Rounded.Link, enabled = host.isNotBlank() && !connecting) { connect(host.trim()) }
            }
            if (connecting) Glass(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Tahoe.Accent, strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(14.dp))
                    Text("Lihat layar TV: centang \"Selalu izinkan dari komputer ini\", lalu pilih OKE.", fontSize = 15.sp)
                }
            }
            error?.let { Text("Gagal: $it", color = Tahoe.Bad, fontSize = 14.sp) }
        } else Manage(tv, onRemote)
    }
}

@Composable
private fun Manage(tv: TvAdb, onRemote: () -> Unit) {
    val scope = rememberCoroutineScope()
    var info by remember(tv) { mutableStateOf<Triple<Set<String>, Set<String>, String?>?>(null) } // installed, disabled, tahoe version
    var apps by remember(tv) { mutableStateOf<List<String>>(emptyList()) }
    val pick = remember(tv) { mutableStateMapOf<String, Boolean>() }
    val log = remember(tv) { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    var disableStock by remember { mutableStateOf(true) }
    var confirmRestore by remember { mutableStateOf(false) }
    LaunchedEffect(tv) {
        withContext(Dispatchers.IO) {
            val inst = tv.packages(); val off = tv.disabled()
            info = Triple(inst, off, tv.installedVersion()); apps = tv.tvApps()
            TvAdb.CATALOG.filter { it.pkg in inst && it.pkg !in off && !TvAdb.protectedPkg(it.pkg) }.forEach { pick[it.pkg] = it.default }
        }
    }
    fun say(s: String) { scope.launch(Dispatchers.Main) { log += s } }
    fun run(block: () -> Unit) { busy = true; log.clear(); scope.launch(Dispatchers.IO) { runCatching(block).onFailure { say("✗ ${it.message}") }; withContext(Dispatchers.Main) { busy = false } } }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Title(tv.model, "Android ${tv.release} (SDK ${tv.sdk})" + (if (tv.lowRam) " · RAM rendah" else "") + " · ${tv.host}" +
            (info?.third?.let { " · Tahoe $it terpasang" } ?: ""))
        Spacer(Modifier.weight(1f))
        GhostButton("Remote & Cermin", Icons.Rounded.SettingsRemote, onClick = onRemote)
        Spacer(Modifier.width(8.dp))
        GhostButton("Putuskan", Icons.Rounded.LinkOff) { Session.tv?.close(); Session.tv = null }
    }
    if (info == null) { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Tahoe.Accent, trackColor = Tahoe.Glass); return }

    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Glass(Modifier.weight(1f)) {
            Text("Matikan aplikasi bawaan", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            val items = TvAdb.CATALOG.filter { it.pkg in pick }
            if (items.isEmpty()) Text("Tidak ada lagi aplikasi bawaan dari daftar yang aktif.", color = Tahoe.Dim)
            items.forEach { b ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { pick[b.pkg] = pick[b.pkg] != true }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(pick[b.pkg] == true, { pick[b.pkg] = it }, colors = CheckboxDefaults.colors(checkedColor = Tahoe.Accent, checkmarkColor = Tahoe.Bg))
                    Column { Text(b.what, fontSize = 14.sp); Text(b.pkg, fontSize = 11.sp, color = Tahoe.Faint) }
                }
            }
        }
        Glass(Modifier.weight(1f)) {
            Text("Tahoe", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("Nama, kota, dan API key diambil dari Setelan app ini.\nDock: ${dockFor(apps).joinToString(", ") { tv.label(it) }.ifEmpty { "bawaan" }}",
                color = Tahoe.Dim, fontSize = 14.sp, lineHeight = 20.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(disableStock, { disableStock = it }, colors = SwitchDefaults.colors(checkedTrackColor = Tahoe.Good))
                Spacer(Modifier.width(10.dp)); Text("Matikan launcher bawaan bila perlu", fontSize = 14.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(if (info?.third != null) "Perbarui & atur" else "Pasang ke TV", Icons.Rounded.Download, enabled = !busy) {
                    run { install(tv, pick.filterValues { it }.keys, dockFor(apps), disableStock, ::say) }
                }
                if (info?.third != null) GhostButton("Pulihkan TV", Icons.Rounded.Restore, Tahoe.Bad) { confirmRestore = true }
            }
            GhostButton("Matikan debugging di TV", Icons.Rounded.Shield) { run { tv.sh("settings put global adb_enabled 0"); say("✓ Debugging dimatikan") }; Session.tv = null }
        }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Tahoe.Accent, trackColor = Tahoe.Glass)
    if (log.isNotEmpty()) Glass(Modifier.fillMaxWidth()) {
        log.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = if (it.startsWith("✗")) Tahoe.Bad else Tahoe.Text) }
    }
    if (confirmRestore) AlertDialog(
        onDismissRequest = { confirmRestore = false },
        title = { Text("Pulihkan TV?") },
        text = { Text("Launcher bawaan dan aplikasi dari daftar debloat dinyalakan lagi, lalu Tahoe di-uninstall.") },
        confirmButton = { TextButton({ confirmRestore = false; run { restore(tv, info!!.first, ::say) } }) { Text("Pulihkan", color = Tahoe.Bad) } },
        dismissButton = { TextButton({ confirmRestore = false }) { Text("Batal") } },
    )
}

private val DOCK = listOf("com.google.android.youtube.tv", "com.netflix.ninja", "com.live_streaming_tv.online_tv", "com.spotify.tv.android", "com.tiktok.tv", "com.vidio.android.tv")
private fun dockFor(apps: List<String>) = DOCK.filter { it in apps }

private fun install(tv: TvAdb, disable: Set<String>, dock: List<String>, disableStock: Boolean, say: (String) -> Unit) {
    disable.filterNot(TvAdb::protectedPkg).forEach { pkg ->
        val ok = "disabled" in tv.sh("pm disable-user --user 0 $pkg"); say((if (ok) "✓ dimatikan " else "✗ ") + tv.label(pkg))
    }
    if (tv.sdk < 26) { say("! Android terlalu lama untuk launcher Tahoe (butuh 8.0+). Hanya debloat."); return }
    val apk = File.createTempFile("tahoe", ".apk").apply { deleteOnExit() }
    Session::class.java.getResourceAsStream("/tahoe-launcher.apk")!!.use { input -> apk.outputStream().use { input.copyTo(it) } }
    tv.installLauncher(apk, say); say("✓ Tahoe TV terpasang")
    val home = tv.makeHome(say, disableStock)
    say(if (home == TvAdb.PKG) "✓ Tahoe jadi Home" else "! Home masih $home (cek manual)")
    val cfg = buildMap {
        Settings.name.takeIf { it.isNotBlank() }?.let { put("user_name", it) }
        put("city", Settings.city); put("province", Settings.province); put("latitude", "${Settings.lat}"); put("longitude", "${Settings.lon}")
        if (dock.isNotEmpty()) put("dock", dock.joinToString(","))
        Settings.gemini.takeIf { it.isNotBlank() }?.let { put("gemini_key", it) }
        Settings.youtube.takeIf { it.isNotBlank() }?.let { put("youtube_key", it) }
        Settings.tmdb.takeIf { it.isNotBlank() }?.let { put("tmdb_key", it) }
    }
    say("✓ Personalisasi tersimpan (${tv.configure(cfg, pair = false).optInt("updated")} setelan)")
}

private fun restore(tv: TvAdb, installed: Set<String>, say: (String) -> Unit) {
    (TvAdb.CATALOG.map { it.pkg } + TvAdb.STOCK_LAUNCHERS).filter { it in installed }.forEach { tv.sh("pm enable $it"); say("✓ dinyalakan ${tv.label(it)}") }
    tv.sh("settings put global private_dns_mode opportunistic"); tv.sh("settings delete secure screensaver_components")
    say(tv.sh("pm uninstall ${TvAdb.PKG}").trim() + " (uninstall Tahoe)")
    tv.sh("input keyevent KEYCODE_HOME"); say("✓ Selesai")
}
