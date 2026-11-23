package tech.kousozo.tahoetv

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.kousozo.tahoetv.core.Updates

/** OTA for the phone app: newest GitHub release, its changelog, and install through Android's installer. */
private val APK_CANDIDATES = Updates.REMOTE_ASSETS

/** Pop-up update modal backed by ControlPlane policy + GitHub/Server release metadata. */
@Composable
fun UpdateModalDialog() {
    if (!PushHelper.showUpdateModal) return
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val r = PushHelper.latestRelease
    val pol = PushHelper.policy
    val (current, currentCode) = remember { PushHelper.appVersion(c) }
    val hasRemoteApk = r != null && r.hasAnyAsset(APK_CANDIDATES)
    val newerAvailable = r != null && Updates.newer(r.version, current)
    val mandatory = (pol.minVersionCode > 0 && currentCode < pol.minVersionCode) ||
        (pol.forceModal && newerAvailable) ||
        (r?.forceModal == true && newerAvailable)

    var status by remember(r?.version) { mutableStateOf("") }
    var progress by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    val lang = PhoneRegion.language

    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!mandatory && !busy) PushHelper.dismissUpdateModal(c) },
        containerColor = Tahoe.Surface,
        titleContentColor = Tahoe.Text,
        textContentColor = Tahoe.Text,
        title = {
            val titleText = pol.modalTitle.ifBlank {
                if (r != null && newerAvailable) tech.kousozo.tahoetv.core.I18n.get(lang, "update.modal_title", r.version)
                else tech.kousozo.tahoetv.core.I18n.get(lang, "update.info_title")
            }
            Text(titleText, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, fontSize = 18.sp)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (r != null) tech.kousozo.tahoetv.core.I18n.get(lang, "update.installed_latest", current, r.version)
                    else tech.kousozo.tahoetv.core.I18n.get(lang, "update.installed_only", current),
                    fontSize = 12.sp,
                    color = Tahoe.Dim,
                    fontFamily = Mono,
                )
                if (pol.modalMessage.isNotBlank()) {
                    Text(pol.modalMessage, fontSize = 14.sp, color = Tahoe.Text)
                }
                if (r != null && r.notes.isNotBlank()) {
                    Text(r.notes.take(400), fontSize = 13.sp, color = Tahoe.Dim)
                } else if (r == null) {
                    Text(tech.kousozo.tahoetv.core.I18n.get(lang, "update.checking_server"), fontSize = 13.sp, color = Tahoe.Dim)
                }
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { ((progress ?: 0).coerceIn(0, 100)) / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = Tahoe.Accent,
                        trackColor = Tahoe.Glass,
                    )
                }
                if (status.isNotBlank()) {
                    Text(status, fontSize = 12.sp, color = Tahoe.Accent)
                }
            }
        },
        confirmButton = {
            if (hasRemoteApk && r != null && (newerAvailable || mandatory)) {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val f = File(c.cacheDir, "update-remote.apk")
                            runCatching {
                                status = tech.kousozo.tahoetv.core.I18n.get(lang, "update.preparing")
                                val (_, url, hash) = withContext(Dispatchers.IO) { r.resolveDownload(APK_CANDIDATES) }
                                withContext(Dispatchers.IO) {
                                    Updates.download(url, f, hash) { p ->
                                        progress = p
                                        status = tech.kousozo.tahoetv.core.I18n.get(lang, "update.downloading_pct", p)
                                    }
                                }
                                status = tech.kousozo.tahoetv.core.I18n.get(lang, "update.install_confirm")
                                withContext(Dispatchers.IO) { install(c, f) }
                            }.onFailure {
                                status = tech.kousozo.tahoetv.core.I18n.get(lang, "update.failed", it.message ?: "")
                            }
                            busy = false
                        }
                    },
                ) { Text(tech.kousozo.tahoetv.core.I18n.get(lang, if (busy) "update.downloading" else "update.now")) }
            } else {
                TextButton({
                    if (!mandatory) PushHelper.dismissUpdateModal(c)
                    else if (r != null) PushHelper.openSafeUrl(c, r.page)
                }) { Text(tech.kousozo.tahoetv.core.I18n.get(lang, if (mandatory) "update.open_page" else "common.close"), color = Tahoe.Accent) }
            }
        },
        dismissButton = {
            if (!mandatory && !busy) {
                TextButton({ PushHelper.dismissUpdateModal(c) }) {
                    Text(tech.kousozo.tahoetv.core.I18n.get(lang, "common.later"), color = Tahoe.Dim)
                }
            }
        },
    )
}

/** After the app was updated: the new version's release notes, once. A fresh install only records its version. */
@Composable
fun WhatsNewDialog() {
    val c = LocalContext.current
    val lang = PhoneRegion.language
    val (current, _) = remember { PushHelper.appVersion(c) }
    val prefs = remember { c.getSharedPreferences("whatsnew", Context.MODE_PRIVATE) }
    var release by remember { mutableStateOf<Updates.Release?>(null) }
    LaunchedEffect(current) {
        when (tech.kousozo.tahoetv.core.WhatsNew.decide(prefs.getString("seen", null), current)) {
            tech.kousozo.tahoetv.core.WhatsNew.Decision.RECORD_ONLY -> prefs.edit().putString("seen", current).apply()
            tech.kousozo.tahoetv.core.WhatsNew.Decision.NOTHING -> {}
            tech.kousozo.tahoetv.core.WhatsNew.Decision.SHOW -> {
                val r = withContext(Dispatchers.IO) { runCatching { Updates.latest() }.getOrNull() } ?: return@LaunchedEffect // offline: next start
                if (tech.kousozo.tahoetv.core.WhatsNew.notesApply(r.version, current)) release = r
                else prefs.edit().putString("seen", current).apply() // no notes for this build
            }
        }
    }
    val r = release ?: return
    AlertDialog(
        onDismissRequest = { prefs.edit().putString("seen", current).apply(); release = null },
        containerColor = Tahoe.Surface,
        titleContentColor = Tahoe.Text,
        textContentColor = Tahoe.Text,
        title = { Text(tech.kousozo.tahoetv.core.I18n.get(lang, "tv.about_updated", r.version), fontWeight = FontWeight.ExtraBold, fontSize = 18.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r.date.isNotBlank()) Text(r.date, fontSize = 12.sp, color = Tahoe.Dim, fontFamily = Mono)
                Text(tech.kousozo.tahoetv.core.WhatsNew.plainNotes(r.notes), fontSize = 13.sp, color = Tahoe.Dim)
            }
        },
        confirmButton = {
            TextButton({ prefs.edit().putString("seen", current).apply(); release = null }) {
                Text(tech.kousozo.tahoetv.core.I18n.get(lang, "common.close"), color = Tahoe.Accent)
            }
        },
    )
}

@Composable
fun UpdateCard() {
    val c = LocalContext.current
    val lang = PhoneRegion.language
    val current = remember { runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull().orEmpty() }
    var latest by remember { mutableStateOf<Updates.Release?>(null) }
    var state by remember(lang) { mutableStateOf(tech.kousozo.tahoetv.core.I18n.get(lang, "update.checking")) }
    var showNotes by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(lang) {
        withContext(Dispatchers.IO) { runCatching { Updates.latest() } }
            .onSuccess {
                latest = it
                state = if (Updates.newer(it.version, current) && it.hasAnyAsset(APK_CANDIDATES))
                    tech.kousozo.tahoetv.core.I18n.get(lang, "update.version_available", it.version)
                else tech.kousozo.tahoetv.core.I18n.get(lang, "update.up_to_date", current)
            }
            .onFailure { state = tech.kousozo.tahoetv.core.I18n.get(lang, "update.check_failed") }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Icon(Icons.Rounded.SystemUpdate, null, tint = Tahoe.Accent); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Tahoe TV $current", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(state, color = Tahoe.Dim, fontSize = 12.sp)
            }
            val r = latest
            if (r != null && Updates.newer(r.version, current) && r.hasAnyAsset(APK_CANDIDATES)) Button({
                scope.launch {
                    val f = File(c.cacheDir, "update.apk")
                    runCatching {
                        val (_, url, hash) = withContext(Dispatchers.IO) { r.resolveDownload(APK_CANDIDATES) }
                        withContext(Dispatchers.IO) { Updates.download(url, f, hash) { p -> state = tech.kousozo.tahoetv.core.I18n.get(lang, "update.downloading_pct", p) } }
                        state = tech.kousozo.tahoetv.core.I18n.get(lang, "update.install_confirm")
                        withContext(Dispatchers.IO) { install(c, f) }
                    }.onFailure { state = tech.kousozo.tahoetv.core.I18n.get(lang, "update.failed", it.message ?: "") }
                }
            }) { Text(tech.kousozo.tahoetv.core.I18n.get(lang, "update.btn")) }
        }
        latest?.let { r -> TextButton({ showNotes = !showNotes }) { Text(if (showNotes) tech.kousozo.tahoetv.core.I18n.get(lang, "update.hide_notes") else tech.kousozo.tahoetv.core.I18n.get(lang, "update.whats_new", r.version)) } }
        if (showNotes) latest?.let { Text(it.notes.replace(Regex("(?m)^#+\\s*"), "").replace("**", ""), fontSize = 13.sp, color = Tahoe.Dim) }
    }
}

/** Throws when [apk] is not a genuine update of this app (other package, unsigned, or signed by someone else). */
@Suppress("DEPRECATION")
private fun requireGenuineUpdate(c: Context, apk: File) {
    val pm = c.packageManager
    val flags = if (android.os.Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES else android.content.pm.PackageManager.GET_SIGNATURES
    fun hex(s: android.content.pm.Signature) = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    fun current(pi: android.content.pm.PackageInfo): Set<String> =
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.signingInfo?.apkContentsSigners?.map(::hex)?.toSet().orEmpty()
        else pi.signatures?.map(::hex)?.toSet().orEmpty()
    fun history(pi: android.content.pm.PackageInfo): Set<String> =
        if (android.os.Build.VERSION.SDK_INT >= 28 && pi.signingInfo?.hasMultipleSigners() == false) pi.signingInfo?.signingCertificateHistory?.map(::hex)?.toSet().orEmpty() else emptySet()
    val mine = pm.getPackageInfo(c.packageName, flags)
    val cand = pm.getPackageArchiveInfo(apk.absolutePath, flags)
    val v = tech.kousozo.tahoetv.core.ApkTrust.verdict(c.packageName, cand?.packageName, current(mine), cand?.let(::current).orEmpty(), cand?.let(::history).orEmpty())
    if (v != tech.kousozo.tahoetv.core.ApkTrust.Verdict.OK) {
        apk.delete()
        tech.kousozo.tahoetv.core.DiagLog.add("update", "refused ${apk.name}: $v")
        throw SecurityException(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "update.untrusted"))
    }
}

fun install(c: Context, apk: File) {
    requireGenuineUpdate(c, apk)
    val pi = c.packageManager.packageInstaller
    val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
    pi.openSession(id).use { s ->
        s.openWrite("tahoe", 0, apk.length()).use { o -> apk.inputStream().use { it.copyTo(o) }; s.fsync(o) }
        val doneIntent = Intent(c, OtaDone::class.java).setPackage(c.packageName)
        s.commit(PendingIntent.getBroadcast(c, id, doneIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE).intentSender)
    }
}

class OtaDone : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                @Suppress("DEPRECATION") (i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> Toast.makeText(c, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "update.failed_pkg", i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""), Toast.LENGTH_LONG).show()
        }
    }
}
