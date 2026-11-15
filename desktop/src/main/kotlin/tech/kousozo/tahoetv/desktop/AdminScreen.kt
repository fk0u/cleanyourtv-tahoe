package tech.kousozo.tahoetv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tech.kousozo.tahoetv.core.ControlPlane

object AdminSessionStore {
    private val file = File(System.getProperty("user.home"), ".tahoetv/admin_session.json")
    var session by mutableStateOf<ControlPlane.AdminSession?>(null)
        private set

    fun load(): ControlPlane.AdminSession? {
        if (!file.isFile) return null
        return runCatching {
            val o = JSONObject(file.readText())
            val tok = o.optString("token")
            val user = o.optString("username", "admin")
            val exp = o.optLong("expires_at", 0L)
            if (tok.isBlank() || (exp > 0L && exp < System.currentTimeMillis())) null
            else ControlPlane.AdminSession(tok, user, exp).also { session = it }
        }.getOrNull()
    }

    fun save(s: ControlPlane.AdminSession) {
        session = s
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(
            JSONObject()
                .put("token", s.token)
                .put("username", s.username)
                .put("expires_at", s.expiresAt)
                .toString()
        )
        runCatching {
            tmp.setReadable(false, false)
            tmp.setReadable(true, true)
            tmp.setWritable(false, false)
            tmp.setWritable(true, true)
        }
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun clear() {
        session = null
        runCatching { file.delete() }
    }
}

@Composable
fun AdminScreen() {
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf(AdminSessionStore.session ?: AdminSessionStore.load()) }
    var verifying by remember { mutableStateOf(session != null) }

    LaunchedEffect(Unit) {
        val existing = AdminSessionStore.load()
        if (existing != null) {
            val verified = withContext(Dispatchers.IO) { runCatching { ControlPlane.AdminApi.verifySession(existing.token) }.getOrNull() }
            if (verified != null) {
                AdminSessionStore.save(verified)
                session = verified
            } else {
                AdminSessionStore.clear()
                session = null
            }
        }
        verifying = false
    }

    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 28.dp, top = 24.dp, bottom = 32.dp)
            .widthIn(max = 980.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Title(
            "Panel Admin Tahoe TV",
            "Kontrol penuh dari macOS Intel / Desktop ke server ${ControlPlane.baseUrl}",
        )

        if (verifying) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Tahoe.Accent, trackColor = Tahoe.Glass)
            return@Column
        }

        val activeSession = session
        if (activeSession == null) {
            AdminLoginCard(onSignedIn = { s ->
                AdminSessionStore.save(s)
                session = s
            })
        } else {
            AdminDashboard(
                session = activeSession,
                onSessionUpdated = { s ->
                    AdminSessionStore.save(s)
                    session = s
                },
                onLogout = {
                    AdminSessionStore.clear()
                    session = null
                },
            )
        }
    }
}

@Composable
private fun AdminLoginCard(onSignedIn: (ControlPlane.AdminSession) -> Unit) {
    val scope = rememberCoroutineScope()
    var serverUrl by remember { mutableStateOf(ControlPlane.baseUrl) }
    var username by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (busy || password.isBlank()) return
        busy = true; err = null
        scope.launch {
            runCatching {
                ControlPlane.baseUrl = serverUrl.trim().ifBlank { ControlPlane.DEFAULT_BASE_URL }
                withContext(Dispatchers.IO) { ControlPlane.AdminApi.login(username.trim(), password) }
            }.onSuccess {
                password = ""
                onSignedIn(it)
            }.onFailure {
                err = it.message ?: "Login gagal"
            }
            busy = false
        }
    }

    Glass(Modifier.widthIn(max = 520.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Rounded.AdminPanelSettings, null, tint = Tahoe.Accent)
            Text("Masuk Admin Server", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "Masuk dengan kredensial pengelola server. Token sesi disimpan lokal terproteksi (0600); kata sandi tidak pernah disimpan.",
            fontSize = 13.sp,
            color = Tahoe.Dim,
        )
        Field(serverUrl, { serverUrl = it }, "URL Control Plane")
        Field(username, { username = it }, "Username")
        Field(password, { password = it }, "Kata sandi", Modifier.fillMaxWidth().onEnter { submit() }, secret = true)
        if (!err.isNullOrBlank()) Text(err!!, color = Tahoe.Bad, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(if (busy) "Memverifikasi…" else "Masuk", Icons.Rounded.LockOpen, enabled = !busy && password.isNotBlank()) {
                submit()
            }
        }
    }
}

@Composable
private fun AdminDashboard(
    session: ControlPlane.AdminSession,
    onSessionUpdated: (ControlPlane.AdminSession) -> Unit,
    onLogout: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) } // 0=Stats, 1=Devices, 2=Push, 3=Config & Security
    var overview by remember { mutableStateOf<ControlPlane.FleetOverview?>(null) }
    var devices by remember { mutableStateOf<List<ControlPlane.DeviceRecord>>(emptyList()) }
    var deviceTotal by remember { mutableStateOf(0) }
    var auditLogs by remember { mutableStateOf<List<ControlPlane.AuditRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var statusMsg by remember { mutableStateOf<String?>(null) }

    var searchQuery by remember { mutableStateOf("") }
    var filterPlatform by remember { mutableStateOf("") }
    var filterRole by remember { mutableStateOf("") }

    fun refreshAll() {
        loading = true; statusMsg = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val ov = ControlPlane.AdminApi.overview(session.token)
                    val (tot, devs) = ControlPlane.AdminApi.listDevices(
                        session.token,
                        query = searchQuery,
                        platform = filterPlatform,
                        role = filterRole,
                        limit = 120,
                    )
                    val logs = ControlPlane.AdminApi.auditLogs(session.token)
                    Triple(ov, tot to devs, logs)
                }
            }.onSuccess { (ov, devPair, logs) ->
                overview = ov
                deviceTotal = devPair.first
                devices = devPair.second
                auditLogs = logs
            }.onFailure {
                val msg = it.message.orEmpty()
                if ("401" in msg || "unauth" in msg.lowercase()) onLogout()
                else statusMsg = "Gagal memuat data server: $msg"
            }
            loading = false
        }
    }

    LaunchedEffect(session.token) { refreshAll() }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            0 to "Statistik Device",
            1 to "Perangkat ($deviceTotal)",
            2 to "Push Notifikasi",
            3 to "Update Modal & Keamanan",
        ).forEach { (idx, label) ->
            val on = tab == idx
            Box(
                Modifier.clip(RoundedCornerShape(50))
                    .background(if (on) Tahoe.Accent else Tahoe.Glass)
                    .border(1.dp, if (on) Tahoe.Accent else Tahoe.Stroke, RoundedCornerShape(50))
                    .clickable { tab = idx }
                    .padding(horizontal = 16.dp, vertical = 9.dp)
                    .semantics { contentDescription = "Tab $label" }
            ) {
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) Tahoe.OnAccent else Tahoe.Text)
            }
        }
        Spacer(Modifier.weight(1f))
        GhostButton("Muat Ulang", Icons.Rounded.Refresh) { refreshAll() }
        GhostButton("Keluar", Icons.Rounded.Logout, color = Tahoe.Bad) { onLogout() }
    }

    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Tahoe.Accent, trackColor = Tahoe.Glass)
    statusMsg?.let { Text(it, color = Tahoe.Accent, fontSize = 13.sp) }

    val ov = overview
    when (tab) {
        0 -> if (ov != null) AdminOverviewTab(ov)
        1 -> AdminDevicesTab(
            devices = devices,
            total = deviceTotal,
            query = searchQuery,
            setQuery = { searchQuery = it },
            platform = filterPlatform,
            setPlatform = { filterPlatform = it },
            role = filterRole,
            setRole = { filterRole = it },
            onSearch = { refreshAll() },
            onDelete = { devId ->
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { ControlPlane.AdminApi.deleteDevice(session.token, devId) }.getOrDefault(false) }
                    statusMsg = if (ok) "Perangkat $devId dihapus." else "Gagal menghapus perangkat."
                    refreshAll()
                }
            },
        )
        2 -> AdminPushTab(
            notifications = ov?.notifications.orEmpty(),
            onSend = { title, body, kind, plat, role, country, url, label, force, hrs ->
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            ControlPlane.AdminApi.sendPushNotification(
                                session.token, title, body, kind,
                                targetPlatform = plat.ifBlank { null },
                                targetRole = role.ifBlank { null },
                                targetCountry = country.ifBlank { null },
                                actionUrl = url.ifBlank { null },
                                actionLabel = label.ifBlank { null },
                                forceModal = force,
                                expiresHours = hrs,
                            )
                        }
                    }.onSuccess { liveDelivered ->
                        statusMsg = "Push terkirim! Live SSE diterima sekarang: $liveDelivered device."
                        refreshAll()
                    }.onFailure { statusMsg = "Gagal kirim push: ${it.message}" }
                }
            },
            onDelete = { id ->
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { ControlPlane.AdminApi.deleteNotification(session.token, id) } }
                    refreshAll()
                }
            },
        )
        3 -> AdminConfigTab(
            initialPolicy = ov?.policy ?: ControlPlane.currentPolicy,
            initialOverrides = ov?.overrides ?: ControlPlane.currentOverrides,
            auditLogs = auditLogs,
            onSaveConfig = { pol, ovr ->
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { ControlPlane.AdminApi.updateConfig(session.token, pol, ovr) }
                    }.onSuccess {
                        statusMsg = "Kebijakan Update Modal & Saluran diperbarui secara real-time (SSE)."
                        refreshAll()
                    }.onFailure { statusMsg = "Gagal menyimpan config: ${it.message}" }
                }
            },
            onChangePassword = { cur, next ->
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) { ControlPlane.AdminApi.changePassword(session.token, cur, next) }
                    }.onSuccess { newSess ->
                        onSessionUpdated(newSess)
                        statusMsg = "Kata sandi admin berhasil diganti dan token diperbarui."
                    }.onFailure { statusMsg = "Gagal ganti kata sandi: ${it.message}" }
                }
            },
        )
    }
}

@Composable
private fun AdminOverviewTab(ov: ControlPlane.FleetOverview) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MetricCard("Total Terpasang", ov.totalInstalled.toString(), "device unik", Tahoe.Accent, Modifier.weight(1f))
        MetricCard("Aktif 24 Jam", ov.active24h.toString(), "${ov.active1h} aktif 1j lalu", Tahoe.Good, Modifier.weight(1f))
        MetricCard("Online Real-Time", ov.onlineSse.toString(), "stream SSE aktif", Color(0xFFBF5AF2), Modifier.weight(1f))
        MetricCard("Aktif 7 Hari", ov.active7d.toString(), "${ov.active30d} dalam 30 hari", Tahoe.Text, Modifier.weight(1f))
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatBreakdownCard("Per Platform", ov.byPlatform, Modifier.weight(1f))
        StatBreakdownCard("Per Jenis Aplikasi", ov.byRole, Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatBreakdownCard("Per Versi App", ov.byVersion, Modifier.weight(1f))
        StatBreakdownCard("Negara & Model Teratas", ov.byCountry + ov.topModels.take(6), Modifier.weight(1f))
    }
}

@Composable
private fun MetricCard(label: String, value: String, sub: String, tint: Color, modifier: Modifier) {
    Glass(modifier) {
        Text(label, fontSize = 12.sp, color = Tahoe.Dim)
        Text(value, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = tint)
        Text(sub, fontSize = 12.sp, color = Tahoe.Faint)
    }
}

@Composable
private fun StatBreakdownCard(title: String, stats: List<ControlPlane.FleetStat>, modifier: Modifier) {
    Glass(modifier) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (stats.isEmpty()) {
            Text("Belum ada data", color = Tahoe.Faint, fontSize = 13.sp)
        } else {
            stats.forEach { st ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(st.label, fontSize = 13.sp, color = Tahoe.Text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (st.active24h > 0) "${st.count} (${st.active24h} 24j)" else st.count.toString(),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Tahoe.Accent,
                    )
                }
            }
        }
    }
}

@Composable
private fun AdminDevicesTab(
    devices: List<ControlPlane.DeviceRecord>,
    total: Int,
    query: String,
    setQuery: (String) -> Unit,
    platform: String,
    setPlatform: (String) -> Unit,
    role: String,
    setRole: (String) -> Unit,
    onSearch: () -> Unit,
    onDelete: (String) -> Unit,
) {
    val fmt = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }
    Glass {
        Text("Perangkat Terdaftar ($total total, menampilkan ${devices.size})", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(query, setQuery, "Cari model, UUID, negara, versi…", Modifier.weight(1f))
            PrimaryButton("Filter", Icons.Rounded.Search) { onSearch() }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("", "android_tv", "android_phone", "macos_intel", "macos_arm64", "windows", "linux").forEach { p ->
                val on = platform == p
                Box(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(if (on) Tahoe.Accent else Tahoe.Glass)
                        .clickable { setPlatform(p); onSearch() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) { Text(if (p.isBlank()) "Semua Platform" else p, fontSize = 12.sp, color = if (on) Tahoe.OnAccent else Tahoe.Text) }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("" to "Semua Role", "tv_home" to "TV & HP (Tahoe TV)", "phone_remote" to "HP Remote", "desktop" to "Desktop").forEach { (r, lbl) ->
                val on = role == r
                Box(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(if (on) Tahoe.Accent else Tahoe.Glass)
                        .clickable { setRole(r); onSearch() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) { Text(lbl, fontSize = 12.sp, color = if (on) Tahoe.OnAccent else Tahoe.Text) }
            }
        }
    }

    devices.forEach { d ->
        Glass(padding = 14) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${d.deviceModel.ifBlank { d.platform }} · ${d.platform} (${d.appRole})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                    Text(
                        "v${d.appVersion} (${d.versionCode}) · OS ${d.osVersion} · ${d.arch} · ${d.country}/${d.lang}",
                        fontSize = 12.sp,
                        color = Tahoe.Dim,
                    )
                    Text(
                        "ID ${d.deviceId.take(16)}… · Terakhir aktif: ${fmt.format(Date(d.lastSeenAt))} · Heartbeat: ${d.heartbeats}×",
                        fontSize = 11.sp,
                        color = Tahoe.Faint,
                    )
                }
                GhostButton("Hapus", Icons.Rounded.DeleteOutline, color = Tahoe.Bad) { onDelete(d.deviceId) }
            }
        }
    }
}

@Composable
private fun AdminPushTab(
    notifications: List<ControlPlane.PushNotification>,
    onSend: (String, String, String, String, String, String, String, String, Boolean, Int) -> Unit,
    onDelete: (Long) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("info") }
    var targetPlat by remember { mutableStateOf("") }
    var targetRole by remember { mutableStateOf("") }
    var targetCountry by remember { mutableStateOf("") }
    var actionUrl by remember { mutableStateOf("") }
    var actionLabel by remember { mutableStateOf("") }
    var forceModal by remember { mutableStateOf(false) }
    var expiresHours by remember { mutableStateOf("72") }

    val fmt = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }

    Glass {
        Text("Kirim Push Notification ke Semua / Target Device", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Field(title, { title = it }, "Judul notifikasi")
        Field(body, { body = it }, "Isi pesan notifikasi")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("info", "update", "warning", "urgent").forEach { k ->
                val on = kind == k
                Box(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(if (on) Tahoe.Accent else Tahoe.Glass)
                        .clickable { kind = k }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) { Text(k.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (on) Tahoe.OnAccent else Tahoe.Text) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(targetPlat, { targetPlat = it }, "Platform (kosong = semua, mis: android_tv)", Modifier.weight(1f))
            Field(targetRole, { targetRole = it }, "Role (tv_home / phone_remote / desktop)", Modifier.weight(1f))
            Field(targetCountry, { targetCountry = it }, "Negara (mis: ID)", Modifier.width(120.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(actionUrl, { actionUrl = it }, "Action URL (opsional https://...)", Modifier.weight(1f))
            Field(actionLabel, { actionLabel = it }, "Label tombol (opsional)", Modifier.width(180.dp))
            Field(expiresHours, { expiresHours = it.filter(Char::isDigit) }, "Jam", Modifier.width(90.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Switch(checked = forceModal, onCheckedChange = { forceModal = it })
            Text("Paksa pop-up modal tampil di aplikasi", fontSize = 13.sp, color = Tahoe.Dim)
            Spacer(Modifier.weight(1f))
            PrimaryButton("Kirim Push Sekarang", Icons.Rounded.Send, enabled = title.isNotBlank() && body.isNotBlank()) {
                onSend(title.trim(), body.trim(), kind, targetPlat.trim(), targetRole.trim(), targetCountry.trim(),
                    actionUrl.trim(), actionLabel.trim(), forceModal, expiresHours.toIntOrNull() ?: 72)
                title = ""; body = ""
            }
        }
    }

    Section("Riwayat Notifikasi Server")
    notifications.forEach { n ->
        Glass(padding = 14) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("[${n.kind.uppercase()}] ${n.title}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(n.body, fontSize = 13.sp, color = Tahoe.Dim)
                    if (n.createdAt > 0L) Text(fmt.format(Date(n.createdAt)), fontSize = 11.sp, color = Tahoe.Faint)
                }
                GhostButton("Hapus", Icons.Rounded.DeleteOutline, color = Tahoe.Bad) { onDelete(n.id) }
            }
        }
    }
}

@Composable
private fun AdminConfigTab(
    initialPolicy: ControlPlane.UpdatePolicy,
    initialOverrides: ControlPlane.ChannelOverrides,
    auditLogs: List<ControlPlane.AuditRecord>,
    onSaveConfig: (ControlPlane.UpdatePolicy, ControlPlane.ChannelOverrides) -> Unit,
    onChangePassword: (String, String) -> Unit,
) {
    var forceModal by remember(initialPolicy) { mutableStateOf(initialPolicy.forceModal) }
    var minCode by remember(initialPolicy) { mutableStateOf(initialPolicy.minVersionCode.toString()) }
    var minVer by remember(initialPolicy) { mutableStateOf(initialPolicy.minVersion) }
    var recVer by remember(initialPolicy) { mutableStateOf(initialPolicy.recommendedVersion) }
    var modalTitle by remember(initialPolicy) { mutableStateOf(initialPolicy.modalTitle) }
    var modalMsg by remember(initialPolicy) { mutableStateOf(initialPolicy.modalMessage) }

    var blockedUrls by remember(initialOverrides) { mutableStateOf(initialOverrides.blockedUrls.joinToString("\n")) }
    var blockedHosts by remember(initialOverrides) { mutableStateOf(initialOverrides.blockedHosts.joinToString("\n")) }
    var announcement by remember(initialOverrides) { mutableStateOf(initialOverrides.announcement) }
    var featuredNotice by remember(initialOverrides) { mutableStateOf(initialOverrides.featuredNotice) }

    var curPw by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }
    val fmt = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }

    Glass {
        Text("Kebijakan Pop-up Update Modal (Real-time SSE)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Switch(checked = forceModal, onCheckedChange = { forceModal = it })
            Text("Paksa tampilkan Pop-up Update Modal di TV, HP & Desktop (tidak bisa ditutup jika versi < minVersion)", fontSize = 13.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(minCode, { minCode = it.filter(Char::isDigit) }, "Min Version Code", Modifier.width(160.dp))
            Field(minVer, { minVer = it }, "Min Version (mis: 3.4.0)", Modifier.weight(1f))
            Field(recVer, { recVer = it }, "Recommended Version", Modifier.weight(1f))
        }
        Field(modalTitle, { modalTitle = it }, "Judul Pop-up Modal")
        Field(modalMsg, { modalMsg = it }, "Pesan Pop-up Modal")

        HorizontalDivider(color = Tahoe.Stroke, modifier = Modifier.padding(vertical = 6.dp))
        Text("Remote Channel Blocklist & Pengumuman", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Field(announcement, { announcement = it }, "Banner pengumuman global")
        Field(featuredNotice, { featuredNotice = it }, "Featured notice")
        Field(blockedHosts, { blockedHosts = it }, "Blocked hosts (satu per baris)")
        Field(blockedUrls, { blockedUrls = it }, "Blocked exact URLs (satu per baris)")

        PrimaryButton("Simpan & Broadcast Real-Time", Icons.Rounded.CloudUpload) {
            val pol = ControlPlane.UpdatePolicy(
                forceModal = forceModal,
                minVersionCode = minCode.toIntOrNull() ?: 0,
                minVersion = minVer.trim(),
                recommendedVersion = recVer.trim(),
                modalTitle = modalTitle.trim(),
                modalMessage = modalMsg.trim(),
            )
            val ovr = ControlPlane.ChannelOverrides(
                blockedUrls = blockedUrls.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                blockedHosts = blockedHosts.lines().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet(),
                announcement = announcement.trim(),
                featuredNotice = featuredNotice.trim(),
            )
            onSaveConfig(pol, ovr)
        }
    }

    Glass {
        Text("Ganti Kata Sandi Admin Server", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(curPw, { curPw = it }, "Kata sandi saat ini", Modifier.weight(1f), secret = true)
            Field(newPw, { newPw = it }, "Kata sandi baru (min 10 karakter)", Modifier.weight(1f), secret = true)
            PrimaryButton("Ganti", Icons.Rounded.Password, enabled = curPw.isNotBlank() && newPw.length >= 10) {
                onChangePassword(curPw, newPw)
                curPw = ""; newPw = ""
            }
        }
    }

    Section("Log Audit Admin Terbaru")
    auditLogs.take(15).forEach { log ->
        Glass(padding = 10) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${log.actor}: ${log.action}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(fmt.format(Date(log.createdAt)), fontSize = 11.sp, color = Tahoe.Faint)
            }
            if (log.details.isNotBlank()) Text(log.details, fontSize = 12.sp, color = Tahoe.Dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
