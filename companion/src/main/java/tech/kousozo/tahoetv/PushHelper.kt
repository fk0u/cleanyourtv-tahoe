package tech.kousozo.tahoetv

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import tech.kousozo.tahoetv.core.ControlPlane
import tech.kousozo.tahoetv.core.DiagLog
import tech.kousozo.tahoetv.core.TahoeVersion
import tech.kousozo.tahoetv.core.Updates

/**
 * Push notifications (SSE + periodic JobScheduler fallback), device registry telemetry,
 * and remote update modal state for Tahoe TV Remote (phone).
 */
object PushHelper {
    private const val CHANNEL_ID = "tahoe_push"
    private const val JOB_ID = 9402
    private val main = Handler(Looper.getMainLooper())

    var activeBanner by mutableStateOf<ControlPlane.PushNotification?>(null)
        private set
    var inbox by mutableStateOf<List<ControlPlane.PushNotification>>(emptyList())
        private set
    var unreadCount by mutableIntStateOf(0)
        private set
    var latestRelease by mutableStateOf<Updates.Release?>(null)
        private set
    var showUpdateModal by mutableStateOf(false)
        private set
    var policy by mutableStateOf(ControlPlane.currentPolicy)
        private set

    @Volatile
    private var streamHandle: AutoCloseable? = null

    fun appVersion(c: Context): Pair<String, Int> = runCatching {
        val pkg = c.packageManager.getPackageInfo(c.packageName, 0)
        val name = pkg.versionName?.takeIf { it.isNotBlank() } ?: TahoeVersion.VERSION
        val code = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode.toInt()
        else @Suppress("DEPRECATION") pkg.versionCode
        name to code
    }.getOrDefault(TahoeVersion.VERSION to TahoeVersion.VERSION_CODE)

    fun profile(c: Context): ControlPlane.DeviceProfile {
        val (ver, code) = appVersion(c)
        val model = listOf(Build.MANUFACTURER, Build.MODEL)
            .map { it.orEmpty().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" ")
        return ControlPlane.DeviceProfile(
            deviceId = ControlPlane.deviceId(c.filesDir),
            appRole = "phone_remote",
            platform = "android_phone",
            osVersion = Build.VERSION.RELEASE.orEmpty(),
            deviceModel = model,
            appVersion = ver,
            versionCode = code,
            arch = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            country = PhoneRegion.code,
            lang = PhoneRegion.language,
        )
    }

    @Synchronized
    fun start(c: Context) {
        val appCtx = c.applicationContext
        ensureChannel(appCtx)
        scheduleJob(appCtx)
        streamHandle?.close()

        ControlPlane.loadCachedConfig(appCtx.filesDir)
        policy = ControlPlane.currentPolicy

        val listener = object : ControlPlane.PushListener {
            override fun onNotification(notification: ControlPlane.PushNotification) {
                deliverNotification(appCtx, notification)
            }

            override fun onPolicyUpdated(
                policy: ControlPlane.UpdatePolicy,
                overrides: ControlPlane.ChannelOverrides,
            ) {
                main.post { this@PushHelper.policy = policy }
                checkUpdateModal(appCtx)
            }
        }

        streamHandle = ControlPlane.startPushStream(appCtx.filesDir, profile(appCtx), listener)
        checkUpdateModal(appCtx)
    }

    @Synchronized
    fun stop() {
        runCatching { streamHandle?.close() }
        streamHandle = null
    }

    fun checkUpdateModal(c: Context) {
        val appCtx = c.applicationContext
        Thread({
            val prof = profile(appCtx)
            val rel = runCatching { Updates.latest() }.getOrNull()
            if (rel != null) {
                val show = ControlPlane.shouldShowUpdateModal(appCtx.filesDir, prof.appVersion, prof.versionCode, rel)
                main.post {
                    latestRelease = rel
                    policy = ControlPlane.currentPolicy
                    if (show) showUpdateModal = true
                }
            }
        }, "tahoe-update-check").apply {
            isDaemon = true
            start()
        }
    }

    fun openUpdateModal(c: Context) {
        if (latestRelease != null) {
            showUpdateModal = true
        } else {
            checkUpdateModal(c)
            showUpdateModal = true
        }
    }

    fun dismissUpdateModal(c: Context) {
        val ver = latestRelease?.version.orEmpty()
        val pol = policy
        val (curVer, curCode) = appVersion(c)
        val mandatory = ControlPlane.isMandatory(curVer, curCode, latestRelease)
        if (mandatory) return
        if (ver.isNotBlank()) ControlPlane.markUpdateModalDismissed(c.filesDir, ver)
        showUpdateModal = false
    }

    fun dismissBanner(c: Context) {
        val cur = activeBanner ?: return
        activeBanner = null
        ControlPlane.ackNotificationAsync(ControlPlane.deviceId(c.filesDir), cur.id, "read")
    }

    fun markAllRead(c: Context) {
        unreadCount = 0
        val devId = ControlPlane.deviceId(c.filesDir)
        inbox.take(5).forEach { ControlPlane.ackNotificationAsync(devId, it.id, "read") }
    }

    fun pollPendingOnce(c: Context): Int {
        val appCtx = c.applicationContext
        ensureChannel(appCtx)
        val list = runCatching { ControlPlane.pollPending(appCtx.filesDir, profile(appCtx)) }.getOrDefault(emptyList())
        list.forEach { n ->
            ControlPlane.ackNotificationAsync(ControlPlane.deviceId(appCtx.filesDir), n.id, "delivered")
            deliverNotification(appCtx, n)
        }
        return list.size
    }

    private fun deliverNotification(c: Context, n: ControlPlane.PushNotification) {
        postSystemNotification(c, n)
        main.post {
            val merged = (listOf(n) + inbox.filterNot { it.id == n.id }).take(40)
            inbox = merged
            unreadCount += 1
            activeBanner = n
            if (n.kind == "update" || n.forceModal) {
                checkUpdateModal(c)
            }
        }
    }

    private fun ensureChannel(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val lang = PhoneRegion.language
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, tech.kousozo.tahoetv.core.I18n.get(lang, "push.channel_name"), NotificationManager.IMPORTANCE_HIGH).apply {
                description = tech.kousozo.tahoetv.core.I18n.get(lang, "push.channel_desc")
            }
        )
    }

    private fun postSystemNotification(c: Context, n: ControlPlane.PushNotification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            val intent = Intent(c, MainActivity::class.java).apply {
                setPackage(c.packageName)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("push_id", n.id)
                if (n.kind == "update" || n.forceModal) putExtra("open_update_modal", true)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getActivity(c, (n.id and 0x7FFF).toInt(), intent, flags)
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                android.app.Notification.Builder(c, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                android.app.Notification.Builder(c)
            }
            val notif = builder
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(n.title)
                .setContentText(n.body)
                .setStyle(android.app.Notification.BigTextStyle().bigText(n.body))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            c.getSystemService(NotificationManager::class.java)?.notify(n.id.toInt(), notif)
        }.onFailure { DiagLog.error("push", "system notification failed", it) }
    }

    private fun scheduleJob(c: Context) {
        runCatching {
            val js = c.getSystemService(JobScheduler::class.java) ?: return
            if (js.allPendingJobs.any { it.id == JOB_ID }) return
            val info = JobInfo.Builder(JOB_ID, ComponentName(c, PushJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(30 * 60_000L)
                .setPersisted(true)
                .build()
            js.schedule(info)
        }
    }

    fun openSafeUrl(c: Context, url: String) {
        val parsed = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return
        val scheme = parsed.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, parsed)) }
        }
    }
}

/** Background periodic push check when the app process is asleep. */
class PushJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Thread({
            try {
                PushHelper.pollPendingOnce(applicationContext)
            } finally {
                jobFinished(params, false)
            }
        }, "tahoe-push-job").start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}

/** Floating translucent banner when a push notification arrives while using Tahoe TV Remote. */
@Composable
fun PushBannerBar(onOpenInbox: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalContext.current
    val banner = PushHelper.activeBanner
    LaunchedEffect(banner?.id) {
        if (banner != null && !banner.forceModal) {
            delay(9_000)
            if (PushHelper.activeBanner?.id == banner.id) PushHelper.dismissBanner(c)
        }
    }
    AnimatedVisibility(
        visible = banner != null,
        modifier = modifier,
        enter = if (ThemePrefs.reduceMotion) fadeIn(tween(200))
        else slideInVertically(tween(tech.kousozo.tahoetv.core.Brand.MOTION_SHEET, easing = EaseDrawerTahoe)) { -it } +
            fadeIn(tween(tech.kousozo.tahoetv.core.Brand.MOTION_FAST, easing = EaseOutTahoe)),
        exit = if (ThemePrefs.reduceMotion) fadeOut(tween(200))
        else slideOutVertically(tween(tech.kousozo.tahoetv.core.Brand.MOTION_FAST, easing = EaseOutTahoe)) { -it } +
            fadeOut(tween(180, easing = EaseOutTahoe)),
    ) {
        banner?.let { n ->
            val accent = when (n.kind) {
                "update" -> Tahoe.Accent
                "warning", "urgent" -> Tahoe.Bad
                else -> Tahoe.Good
            }
            Surface(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .fillMaxWidth()
                    .clickable {
                        PushHelper.dismissBanner(c)
                        if (n.kind == "update") PushHelper.openUpdateModal(c)
                        else if (n.actionUrl.isNotBlank()) PushHelper.openSafeUrl(c, n.actionUrl)
                        else onOpenInbox()
                    }
                    .semantics { contentDescription = tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "push.new_aria", n.title) },
                shape = RoundedCornerShape(18.dp),
                color = Tahoe.Surface.copy(alpha = 0.96f),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
                shadowElevation = 16.dp,
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(38.dp).clip(CircleShape).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                        Icon(
                            if (n.kind == "update") Icons.Rounded.SystemUpdate else Icons.Rounded.Campaign,
                            null,
                            tint = accent,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(n.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(n.body, fontSize = 12.sp, color = Tahoe.Dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (n.actionLabel.isNotBlank() || n.kind == "update") {
                        TextButton({
                            PushHelper.dismissBanner(c)
                            if (n.kind == "update") PushHelper.openUpdateModal(c)
                            else if (n.actionUrl.isNotBlank()) PushHelper.openSafeUrl(c, n.actionUrl)
                            else onOpenInbox()
                        }) {
                            Text(n.actionLabel.ifBlank { tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "push.view") }, color = Tahoe.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    IconButton({ PushHelper.dismissBanner(c) }) {
                        Icon(Icons.Rounded.Close, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "push.dismiss"), tint = Tahoe.Dim, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/** Notification history sheet opened from the bell button on Beranda. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationInboxSheet(onDismiss: () -> Unit) {
    val c = LocalContext.current
    LaunchedEffect(Unit) { PushHelper.markAllRead(c) }
    val fmt = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }

    ModalBottomSheet(onDismiss, containerColor = Tahoe.Surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Notifications, null, tint = Tahoe.Accent)
                Spacer(Modifier.width(8.dp))
                Text(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "push.inbox_title"), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                TextButton({ PushHelper.checkUpdateModal(c) }) {
                    Text(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "update.check_btn"), color = Tahoe.Accent)
                }
            }
            if (PushHelper.inbox.isEmpty()) {
                GlassCard {
                    Text(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "push.inbox_empty"), color = Tahoe.Dim, fontSize = 14.sp)
                }
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(PushHelper.inbox, key = { it.id }) { n ->
                        GlassCard(onClick = {
                            if (n.kind == "update") {
                                onDismiss()
                                PushHelper.openUpdateModal(c)
                            } else if (n.actionUrl.isNotBlank()) {
                                PushHelper.openSafeUrl(c, n.actionUrl)
                            }
                        }) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(n.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                if (n.createdAt > 0L) {
                                    Text(fmt.format(Date(n.createdAt)), fontSize = 11.sp, color = Tahoe.Faint, fontFamily = Mono)
                                }
                            }
                            Text(n.body, fontSize = 13.sp, color = Tahoe.Dim)
                            if (n.actionLabel.isNotBlank()) {
                                Text(n.actionLabel, fontSize = 12.sp, color = Tahoe.Accent, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}
