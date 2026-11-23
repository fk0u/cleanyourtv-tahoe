package tech.kousozo.tahoetv.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import java.awt.Desktop
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.kousozo.tahoetv.core.ControlPlane
import tech.kousozo.tahoetv.core.DiagLog
import tech.kousozo.tahoetv.core.I18n
import tech.kousozo.tahoetv.core.TahoeVersion
import tech.kousozo.tahoetv.core.Updates

/**
 * Desktop telemetry, real-time SSE + periodic push notifications, native OS alerts,
 * and release update modal backed by https://tahoe.kousozo.tech.
 */
object DesktopPush {
    private val storageDir = File(System.getProperty("user.home"), ".tahoetv").apply { mkdirs() }

    var activeBanner by mutableStateOf<ControlPlane.PushNotification?>(null)
        private set
    var inbox by mutableStateOf<List<ControlPlane.PushNotification>>(emptyList())
        private set
    var unreadCount by mutableIntStateOf(0)
        private set
    var showInbox by mutableStateOf(false)
    var latestRelease by mutableStateOf<Updates.Release?>(null)
        private set
    var showUpdateModal by mutableStateOf(false)
        private set
    var policy by mutableStateOf(ControlPlane.currentPolicy)
        private set

    @Volatile
    private var streamHandle: AutoCloseable? = null

    @Volatile
    private var trayIcon: TrayIcon? = null

    fun appVersion(): Pair<String, Int> {
        val pkgVer = System.getProperty("jpackage.app-version")
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("dev", ignoreCase = true) }
        return (pkgVer ?: TahoeVersion.VERSION) to TahoeVersion.VERSION_CODE
    }

    fun profile(): ControlPlane.DeviceProfile {
        val (ver, code) = appVersion()
        val host = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("").trim()
        val osName = System.getProperty("os.name").orEmpty().trim()
        val model = if (host.isNotBlank()) "$osName ($host)" else osName
        return ControlPlane.DeviceProfile(
            deviceId = ControlPlane.deviceId(storageDir),
            appRole = "desktop",
            platform = ControlPlane.detectDesktopPlatform(),
            osVersion = System.getProperty("os.version").orEmpty(),
            deviceModel = model,
            appVersion = ver,
            versionCode = code,
            arch = System.getProperty("os.arch").orEmpty(),
            country = Settings.countryCode,
            lang = Settings.lang,
        )
    }

    @Synchronized
    fun start() {
        streamHandle?.close()
        ControlPlane.loadCachedConfig(storageDir)
        policy = ControlPlane.currentPolicy

        val listener = object : ControlPlane.PushListener {
            override fun onNotification(notification: ControlPlane.PushNotification) {
                deliverNotification(notification)
            }

            override fun onPolicyUpdated(
                policy: ControlPlane.UpdatePolicy,
                overrides: ControlPlane.ChannelOverrides,
            ) {
                SwingUtilities.invokeLater { this@DesktopPush.policy = policy }
                checkUpdateModal(manual = false)
            }
        }
        streamHandle = ControlPlane.startPushStream(storageDir, profile(), listener)
        checkUpdateModal(manual = false)
    }

    @Synchronized
    fun stop() {
        runCatching { streamHandle?.close() }
        streamHandle = null
        runCatching {
            trayIcon?.let { if (SystemTray.isSupported()) SystemTray.getSystemTray().remove(it) }
            trayIcon = null
        }
    }

    fun checkUpdateModal(manual: Boolean = false) {
        Thread({
            val prof = profile()
            val rel = runCatching { Updates.latest() }.getOrNull()
            SwingUtilities.invokeLater {
                if (rel != null) latestRelease = rel
                policy = ControlPlane.currentPolicy
                val show = ControlPlane.shouldShowUpdateModal(storageDir, prof.appVersion, prof.versionCode, rel)
                if (manual || show) showUpdateModal = true
            }
        }, "tahoe-desktop-update").apply {
            isDaemon = true
            start()
        }
    }

    fun dismissUpdateModal() {
        val rel = latestRelease
        val (curVer, curCode) = appVersion()
        val pol = policy
        val newer = rel != null && Updates.newer(rel.version, curVer)
        val mandatory = (pol.minVersionCode > 0 && curCode < pol.minVersionCode) ||
            (pol.forceModal && newer) ||
            (rel?.forceModal == true && newer)
        if (mandatory) return
        rel?.version?.takeIf { it.isNotBlank() }?.let { ControlPlane.markUpdateModalDismissed(storageDir, it) }
        showUpdateModal = false
    }

    fun dismissBanner() {
        val cur = activeBanner ?: return
        activeBanner = null
        ControlPlane.ackNotificationAsync(ControlPlane.deviceId(storageDir), cur.id, "read")
    }

    fun markAllRead() {
        unreadCount = 0
        val id = ControlPlane.deviceId(storageDir)
        inbox.take(5).forEach { ControlPlane.ackNotificationAsync(id, it.id, "read") }
    }

    fun desktopInstallerAsset(rel: Updates.Release?): Pair<String, String>? {
        rel ?: return null
        val plat = ControlPlane.detectDesktopPlatform()
        val exts = when (plat) {
            "macos_intel", "macos_arm64" -> listOf(".dmg")
            "windows" -> listOf(".msi", ".exe")
            else -> listOf(".deb", ".appimage", ".tar.gz")
        }
        val candidates = rel.assets.keys.filter { name ->
            val low = name.lowercase()
            exts.any { low.endsWith(it) }
        }
        val chosen = when {
            plat == "macos_intel" -> candidates.firstOrNull { "intel" in it.lowercase() || "x64" in it.lowercase() || "x86_64" in it.lowercase() }
            plat == "macos_arm64" -> candidates.firstOrNull { "arm" in it.lowercase() || "apple-silicon" in it.lowercase() || "aarch64" in it.lowercase() }
            else -> null
        } ?: candidates.firstOrNull() ?: return null
        return chosen to rel.assets.getValue(chosen)
    }

    fun openSafeUrl(url: String) {
        val parsed = runCatching { URI(url.trim()) }.getOrNull() ?: return
        val scheme = parsed.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            runCatching { Desktop.getDesktop().browse(parsed) }
        }
    }

    fun postLocalNotification(title: String, body: String, kind: String = "info") {
        val n = ControlPlane.PushNotification(
            id = -System.currentTimeMillis(),
            title = title,
            body = body,
            kind = kind,
            createdAt = System.currentTimeMillis(),
        )
        deliverNotification(n)
    }

    private fun deliverNotification(n: ControlPlane.PushNotification) {
        showOsNotification(n)
        SwingUtilities.invokeLater {
            inbox = (listOf(n) + inbox.filterNot { it.id == n.id }).take(50)
            unreadCount += 1
            activeBanner = n
            if (n.kind == "update" || n.forceModal) {
                checkUpdateModal(manual = true)
            }
        }
    }

    private fun showOsNotification(n: ControlPlane.PushNotification) {
        Thread({
            runCatching {
                val os = System.getProperty("os.name").orEmpty().lowercase()
                if ("mac" in os || "darwin" in os) {
                    ProcessBuilder(
                        "osascript",
                        "-e",
                        "on run argv\ndisplay notification (item 1 of argv) with title (item 2 of argv)\nend run",
                        n.body.take(220),
                        "Tahoe TV — ${n.title.take(80)}",
                    ).start()
                    return@runCatching
                }
                if (SystemTray.isSupported()) {
                    val tray = SystemTray.getSystemTray()
                    val icon = trayIcon ?: run {
                        val img = DesktopPush::class.java.getResourceAsStream("/icon.png")?.use(ImageIO::read)
                            ?: BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
                        TrayIcon(img, "Tahoe TV").apply { isImageAutoSize = true }.also {
                            tray.add(it)
                            trayIcon = it
                        }
                    }
                    icon.displayMessage("Tahoe TV — ${n.title}", n.body, TrayIcon.MessageType.INFO)
                }
            }.onFailure { DiagLog.error("desktop-push", "OS notification failed", it) }
        }, "tahoe-os-notif").apply {
            isDaemon = true
            start()
        }
    }
}

@Composable
fun DesktopPushBanner(modifier: Modifier = Modifier) {
    val lang = Settings.lang
    val banner = DesktopPush.activeBanner
    LaunchedEffect(banner?.id) {
        if (banner != null && !banner.forceModal) {
            delay(10_000)
            if (DesktopPush.activeBanner?.id == banner.id) DesktopPush.dismissBanner()
        }
    }
    AnimatedVisibility(
        visible = banner != null,
        modifier = modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        banner?.let { n ->
            val accent = when (n.kind) {
                "update" -> Tahoe.Accent
                "warning", "urgent" -> Tahoe.Bad
                else -> Tahoe.Good
            }
            Surface(
                modifier = Modifier
                    .padding(top = 14.dp, end = 24.dp)
                    .widthIn(max = 460.dp)
                    .clickable {
                        DesktopPush.dismissBanner()
                        if (n.kind == "update") DesktopPush.checkUpdateModal(manual = true)
                        else if (n.actionUrl.isNotBlank()) DesktopPush.openSafeUrl(n.actionUrl)
                        else DesktopPush.showInbox = true
                    }
                    .semantics { contentDescription = "Tahoe TV: ${n.title}" },
                shape = RoundedCornerShape(18.dp),
                color = Tahoe.Surface.copy(alpha = 0.96f),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
                shadowElevation = 18.dp,
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                        Icon(if (n.kind == "update") Icons.Rounded.SystemUpdate else Icons.Rounded.Campaign, null, tint = accent)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(n.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Tahoe.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(n.body, fontSize = 12.sp, color = Tahoe.Dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton({
                        DesktopPush.dismissBanner()
                        if (n.kind == "update") DesktopPush.checkUpdateModal(manual = true)
                        else if (n.actionUrl.isNotBlank()) DesktopPush.openSafeUrl(n.actionUrl)
                        else DesktopPush.showInbox = true
                    }) {
                        Text(n.actionLabel.ifBlank { I18n.get(lang, "desktop.push_view") }, color = Tahoe.Accent, fontWeight = FontWeight.SemiBold)
                    }
                    IconButton({ DesktopPush.dismissBanner() }) {
                        Icon(Icons.Rounded.Close, I18n.get(lang, "desktop.push_close"), tint = Tahoe.Dim, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun DesktopNotificationDialog() {
    if (!DesktopPush.showInbox) return
    val lang = Settings.lang
    LaunchedEffect(Unit) { DesktopPush.markAllRead() }
    val fmt = remember(lang) { SimpleDateFormat("dd MMM HH:mm", Locale.forLanguageTag(lang)) }

    AlertDialog(
        onDismissRequest = { DesktopPush.showInbox = false },
        containerColor = Tahoe.Surface,
        titleContentColor = Tahoe.Text,
        textContentColor = Tahoe.Text,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Notifications, null, tint = Tahoe.Accent)
                Text(I18n.get(lang, "desktop.inbox_title"), fontWeight = FontWeight.Bold, fontSize = 19.sp)
            }
        },
        text = {
            if (DesktopPush.inbox.isEmpty()) {
                Text(I18n.get(lang, "desktop.inbox_empty", ControlPlane.baseUrl), color = Tahoe.Dim, fontSize = 14.sp)
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(DesktopPush.inbox, key = { it.id }) { n ->
                        Glass(onClick = {
                            if (n.kind == "update") {
                                DesktopPush.showInbox = false
                                DesktopPush.checkUpdateModal(manual = true)
                            } else if (n.actionUrl.isNotBlank()) {
                                DesktopPush.openSafeUrl(n.actionUrl)
                            }
                        }, padding = 14) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(n.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                if (n.createdAt > 0L) Text(fmt.format(Date(n.createdAt)), fontSize = 11.sp, color = Tahoe.Faint)
                            }
                            Text(n.body, fontSize = 13.sp, color = Tahoe.Dim)
                            if (n.actionLabel.isNotBlank()) Text(n.actionLabel, fontSize = 12.sp, color = Tahoe.Accent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton({
                DesktopPush.showInbox = false
                DesktopPush.checkUpdateModal(manual = true)
            }) { Text(I18n.get(lang, "desktop.check_updates"), color = Tahoe.Accent) }
        },
        dismissButton = {
            TextButton({ DesktopPush.showInbox = false }) { Text(I18n.get(lang, "desktop.close"), color = Tahoe.Dim) }
        },
    )
}

/** After the app was updated: the new version's release notes, once. A fresh install only records its version.
 *  `--whatsnew-from=<version>` (test aid) pretends the previously seen version was that one. */
@Composable
fun DesktopWhatsNew(pretendSeen: String? = null) {
    val lang = Settings.lang
    val (current, _) = remember { DesktopPush.appVersion() }
    val seenFile = remember { File(System.getProperty("user.home"), ".tahoetv/whatsnew_seen") }
    var release by remember { mutableStateOf<Updates.Release?>(null) }
    fun markSeen() { runCatching { seenFile.parentFile.mkdirs(); seenFile.writeText(current) } }
    LaunchedEffect(current) {
        val seen = pretendSeen ?: runCatching { seenFile.readText().trim() }.getOrNull()
        when (tech.kousozo.tahoetv.core.WhatsNew.decide(seen, current)) {
            tech.kousozo.tahoetv.core.WhatsNew.Decision.RECORD_ONLY -> markSeen()
            tech.kousozo.tahoetv.core.WhatsNew.Decision.NOTHING -> {}
            tech.kousozo.tahoetv.core.WhatsNew.Decision.SHOW -> {
                val r = withContext(Dispatchers.IO) { runCatching { Updates.latest() }.getOrNull() } ?: return@LaunchedEffect // offline: next start
                if (tech.kousozo.tahoetv.core.WhatsNew.notesApply(r.version, current)) release = r else markSeen()
            }
        }
    }
    val r = release ?: return
    AlertDialog(
        onDismissRequest = { markSeen(); release = null },
        containerColor = Tahoe.Surface,
        titleContentColor = Tahoe.Text,
        textContentColor = Tahoe.Text,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.SystemUpdate, null, tint = Tahoe.Accent)
                Text(I18n.get(lang, "tv.about_updated", r.version), fontWeight = FontWeight.Bold, fontSize = 19.sp)
            }
        },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r.date.isNotBlank()) Text(r.date, color = Tahoe.Dim, fontSize = 12.sp)
                Text(tech.kousozo.tahoetv.core.WhatsNew.plainNotes(r.notes), color = Tahoe.Dim, fontSize = 13.sp)
            }
        },
        confirmButton = { PrimaryButton(I18n.get(lang, "common.close"), Icons.Rounded.Check) { markSeen(); release = null } },
    )
}

@Composable
fun DesktopUpdateModal() {
    if (!DesktopPush.showUpdateModal) return
    val lang = Settings.lang
    val scope = rememberCoroutineScope()
    val r = DesktopPush.latestRelease
    val pol = DesktopPush.policy
    val (currentVer, currentCode) = remember { DesktopPush.appVersion() }
    val newerAvailable = r != null && Updates.newer(r.version, currentVer)
    val mandatory = (pol.minVersionCode > 0 && currentCode < pol.minVersionCode) ||
        (pol.forceModal && newerAvailable) ||
        (r?.forceModal == true && newerAvailable)
    val installer = remember(r?.version) { DesktopPush.desktopInstallerAsset(r) }

    var status by remember(r?.version) { mutableStateOf("") }
    var progress by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!mandatory && !busy) DesktopPush.dismissUpdateModal() },
        containerColor = Tahoe.Surface,
        titleContentColor = Tahoe.Text,
        textContentColor = Tahoe.Text,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.SystemUpdate, null, tint = Tahoe.Accent)
                Text(
                    pol.modalTitle.ifBlank {
                        if (r != null && newerAvailable) I18n.get(lang, "desktop.modal_title_ver", r.version)
                        else I18n.get(lang, "desktop.modal_title_cur")
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 19.sp,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    I18n.get(lang, "desktop.modal_meta", currentVer, r?.version ?: currentVer, ControlPlane.detectDesktopPlatform()),
                    color = Tahoe.Dim,
                    fontSize = 12.sp,
                )
                if (pol.modalMessage.isNotBlank()) Text(pol.modalMessage, color = Tahoe.Text, fontSize = 14.sp)
                if (r != null && r.notes.isNotBlank()) Text(r.notes.take(450), color = Tahoe.Dim, fontSize = 13.sp)
                else if (r == null) Text(I18n.get(lang, "desktop.modal_offline"), color = Tahoe.Dim, fontSize = 13.sp)

                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { ((progress ?: 0).coerceIn(0, 100)) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = Tahoe.Accent,
                        trackColor = Tahoe.Glass,
                    )
                }
                if (status.isNotBlank()) Text(status, color = Tahoe.Accent, fontSize = 12.sp)
            }
        },
        confirmButton = {
            if (installer != null && r != null && (newerAvailable || mandatory)) {
                PrimaryButton(if (busy) I18n.get(lang, "desktop.updates_downloading", progress ?: 0) else I18n.get(lang, "desktop.modal_download_btn", installer.first), Icons.Rounded.Download, enabled = !busy) {
                    busy = true
                    scope.launch {
                        val outDir = File(System.getProperty("user.home"), "Downloads").apply { mkdirs() }
                        val outFile = File(outDir, installer.first)
                        runCatching {
                            status = I18n.get(lang, "desktop.modal_sha")
                            val (_, url, sha) = withContext(Dispatchers.IO) { r.resolveDownload(listOf(installer.first)) }
                            withContext(Dispatchers.IO) {
                                Updates.download(url, outFile, sha) { pct ->
                                    progress = pct
                                    status = I18n.get(lang, "desktop.modal_dl_pct", pct)
                                }
                            }
                            status = I18n.get(lang, "desktop.modal_saved", outFile.absolutePath)
                            runCatching { Desktop.getDesktop().open(outFile) }
                        }.onFailure {
                            status = I18n.get(lang, "desktop.modal_fail", it.message ?: "")
                        }
                        busy = false
                    }
                }
            } else {
                PrimaryButton(if (r != null) I18n.get(lang, "desktop.modal_open_page") else I18n.get(lang, "desktop.close"), Icons.Rounded.OpenInBrowser) {
                    if (r != null) DesktopPush.openSafeUrl(r.page)
                    if (!mandatory) DesktopPush.dismissUpdateModal()
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r != null && installer != null) {
                    TextButton({ DesktopPush.openSafeUrl(r.page) }) { Text(I18n.get(lang, "desktop.modal_all_dl"), color = Tahoe.Accent) }
                }
                if (!mandatory && !busy) {
                    TextButton({ DesktopPush.dismissUpdateModal() }) { Text(I18n.get(lang, "desktop.modal_later"), color = Tahoe.Dim) }
                }
            }
        },
    )
}
