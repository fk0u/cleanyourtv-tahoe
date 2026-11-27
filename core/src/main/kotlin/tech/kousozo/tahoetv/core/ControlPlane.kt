package tech.kousozo.tahoetv.core

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/**
 * Anonymous device registry, live push notifications (SSE + polling), update policy,
 * remote channel overrides, and authenticated admin client backed by https://tahoe.kousozo.tech.
 */
object ControlPlane {
    const val DEFAULT_BASE_URL = "https://tahoe.kousozo.tech"

    @Volatile
    var baseUrl: String = System.getProperty("tahoe.server.url")?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL

    data class DeviceProfile(
        val deviceId: String,
        val appRole: String, // "tv_home" | "phone_remote" | "desktop"
        val platform: String, // "android_tv" | "android_phone" | "macos_intel" | "macos_arm64" | "windows" | "linux"
        val osVersion: String = System.getProperty("os.version").orEmpty(),
        val deviceModel: String = "",
        val appVersion: String = TahoeVersion.VERSION,
        val versionCode: Int = TahoeVersion.VERSION_CODE,
        val arch: String = System.getProperty("os.arch").orEmpty(),
        val country: String = Region.HOME,
        val lang: String = "id",
    )

    data class UpdatePolicy(
        val forceModal: Boolean = false,
        val minVersionCode: Int = 0,
        val minVersion: String = "",
        val recommendedVersion: String = "",
        val modalTitle: String = "",
        val modalMessage: String = "",
    )

    data class ChannelOverrides(
        val blockedUrls: Set<String> = emptySet(),
        val blockedHosts: Set<String> = emptySet(),
        val announcement: String = "",
        val featuredNotice: String = "",
    )

    data class PushNotification(
        val id: Long,
        val title: String,
        val body: String,
        val kind: String = "info",
        val actionUrl: String = "",
        val actionLabel: String = "",
        val forceModal: Boolean = false,
        val createdAt: Long = 0L,
    )

    data class HeartbeatResult(
        val policy: UpdatePolicy,
        val overrides: ChannelOverrides,
        val notifications: List<PushNotification>,
        val release: Updates.Release?,
    )

    @Volatile
    var currentPolicy: UpdatePolicy = UpdatePolicy()
        private set

    @Volatile
    var currentOverrides: ChannelOverrides = ChannelOverrides()
        private set

    private val seenPushIds: MutableSet<Long> = Collections.synchronizedSet(HashSet())

    @JvmStatic
    fun defaultStorageDir(): File {
        val home = System.getProperty("user.home")?.let(::File)
        val dir = if (home != null && home.exists()) File(home, ".tahoetv")
        else File(System.getProperty("java.io.tmpdir"), "tahoetv")
        dir.mkdirs()
        return dir
    }

    @JvmStatic
    @Synchronized
    fun deviceId(storageDir: File? = null): String {
        val dir = storageDir ?: defaultStorageDir()
        dir.mkdirs()
        val f = File(dir, "device_id")
        if (f.isFile) {
            val existing = runCatching { f.readText().trim() }.getOrNull().orEmpty()
            if (existing.length >= 16 && existing.all { it.isLetterOrDigit() || it == '-' }) {
                return existing
            }
        }
        val fresh = UUID.randomUUID().toString()
        atomicWriteText(f, fresh, privateOnly = true)
        return fresh
    }

    @JvmStatic
    fun detectDesktopPlatform(): String {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        return when {
            "mac" in os || "darwin" in os ->
                if ("aarch64" in arch || "arm64" in arch) "macos_arm64" else "macos_intel"
            "win" in os -> "windows"
            else -> "linux"
        }
    }

    @JvmStatic
    fun lastNotificationId(storageDir: File?): Long {
        val f = File(storageDir ?: defaultStorageDir(), "last_notification_id")
        return runCatching { f.readText().trim().toLong() }.getOrDefault(0L).coerceAtLeast(0L)
    }

    @JvmStatic
    @Synchronized
    fun saveLastNotificationId(storageDir: File?, id: Long) {
        if (id <= 0L) return
        val dir = storageDir ?: defaultStorageDir()
        val cur = lastNotificationId(dir)
        if (id > cur) atomicWriteText(File(dir, "last_notification_id"), id.toString())
    }

    @JvmStatic
    fun loadCachedConfig(storageDir: File?) {
        val dir = storageDir ?: defaultStorageDir()
        val pf = File(dir, "update_policy.json")
        if (pf.isFile) {
            runCatching { parsePolicy(JSONObject(pf.readText())) }.getOrNull()?.let { currentPolicy = it }
        }
        val of = File(dir, "channel_overrides.json")
        if (of.isFile) {
            runCatching { parseOverrides(JSONObject(of.readText())) }.getOrNull()?.let { currentOverrides = it }
        }
    }

    @JvmStatic
    fun isUrlBlocked(url: String): Boolean {
        val ov = currentOverrides
        if (ov.blockedUrls.isEmpty() && ov.blockedHosts.isEmpty()) return false
        val norm = url.trim()
        if (norm in ov.blockedUrls) return true
        val host = runCatching { URI(norm).host?.lowercase() }.getOrNull().orEmpty()
        if (host.isNotBlank()) {
            for (h in ov.blockedHosts) {
                if (host == h || host.endsWith(".$h")) return true
            }
        }
        return false
    }

    @JvmStatic
    fun isChannelBlocked(channel: M3u.Channel): Boolean = isUrlBlocked(channel.url)

    /** "Later" on a required release brings the modal back after this long; an optional release is not asked about again. */
    const val REMIND_AFTER_MS = 24 * 60 * 60_000L

    /** Whether to open the update modal by itself. It can always be closed ([markUpdateModalDismissed]). */
    @JvmStatic
    @JvmOverloads
    fun shouldShowUpdateModal(
        storageDir: File?,
        currentVersion: String,
        currentVersionCode: Int,
        release: Updates.Release?,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (release == null) return false
        val mandatory = isMandatory(currentVersion, currentVersionCode, release)
        val policy = currentPolicy
        val forcedByMinVersion = policy.forceModal && policy.minVersion.isNotBlank() && Updates.newer(policy.minVersion, currentVersion)
        val required = mandatory || forcedByMinVersion
        if (!required && !Updates.newer(release.version, currentVersion)) return false
        val file = File(storageDir ?: defaultStorageDir(), "dismissed_modal_version")
        val dismissed = runCatching { file.readText().trim() }.getOrDefault("")
        if (dismissed != release.version) return true
        return required && now - file.lastModified() >= REMIND_AFTER_MS
    }

    /** True when [release] is a required one (reminded daily until installed): below the server's minimum, forced by policy or release, or a fix release. */
    @JvmStatic
    fun isMandatory(currentVersion: String, currentVersionCode: Int, release: Updates.Release?): Boolean {
        if (release == null) return false
        val policy = currentPolicy
        val newerAvailable = Updates.newer(release.version, currentVersion)
        return (policy.minVersionCode > 0 && currentVersionCode < policy.minVersionCode) ||
            (policy.forceModal && newerAvailable) || (release.forceModal && newerAvailable) ||
            Updates.isPatchFix(release.version, currentVersion)
    }

    @JvmStatic
    fun markUpdateModalDismissed(storageDir: File?, version: String) {
        if (version.isBlank()) return
        val dir = storageDir ?: defaultStorageDir()
        atomicWriteText(File(dir, "dismissed_modal_version"), version.trim())
    }

    @JvmStatic
    fun heartbeat(storageDir: File?, profile: DeviceProfile): HeartbeatResult? {
        val dir = storageDir ?: defaultStorageDir()
        val payload = JSONObject()
            .put("device_id", profile.deviceId)
            .put("app_role", profile.appRole)
            .put("platform", profile.platform)
            .put("os_version", profile.osVersion)
            .put("device_model", profile.deviceModel)
            .put("app_version", profile.appVersion)
            .put("version_code", profile.versionCode)
            .put("arch", profile.arch)
            .put("country", profile.country)
            .put("lang", profile.lang)
            .put("last_notification_id", lastNotificationId(dir))

        val res = runCatching { requestJson("POST", "/api/devices/heartbeat", payload) }.getOrNull() ?: return null
        if (!res.optBoolean("ok", false)) return null
        val pol = res.optJSONObject("update_policy")?.let(::parsePolicy) ?: currentPolicy
        val ov = res.optJSONObject("channel_overrides")?.let(::parseOverrides) ?: currentOverrides
        persistConfig(dir, pol, ov)
        val notifs = parseNotificationArray(res.optJSONArray("notifications"))
        notifs.forEach { n ->
            saveLastNotificationId(dir, n.id)
            seenPushIds.add(n.id)
        }
        val relObj = res.optJSONObject("latest_release")
        val release = relObj?.let { parseReleaseObject(it, pol) }
        return HeartbeatResult(pol, ov, notifs, release)
    }

    @JvmStatic
    fun pollPending(storageDir: File?, profile: DeviceProfile): List<PushNotification> {
        val dir = storageDir ?: defaultStorageDir()
        val since = lastNotificationId(dir)
        val q = "device_id=${enc(profile.deviceId)}&platform=${enc(profile.platform)}&app_role=${enc(profile.appRole)}" +
            "&version_code=${profile.versionCode}&country=${enc(profile.country)}&since=$since"
        val res = runCatching { requestJson("GET", "/api/push/pending?$q", null) }.getOrNull() ?: return emptyList()
        res.optJSONObject("update_policy")?.let { pol ->
            val parsed = parsePolicy(pol)
            val ov = res.optJSONObject("channel_overrides")?.let(::parseOverrides) ?: currentOverrides
            persistConfig(dir, parsed, ov)
        }
        val list = parseNotificationArray(res.optJSONArray("notifications"))
        list.forEach { saveLastNotificationId(dir, it.id) }
        return list
    }

    @JvmStatic
    @JvmOverloads
    fun ackNotification(deviceId: String, notificationId: Long, status: String = "delivered"): Boolean {
        if (deviceId.isBlank() || notificationId <= 0L) return false
        val payload = JSONObject()
            .put("device_id", deviceId)
            .put("notification_id", notificationId)
            .put("status", status)
        return runCatching { requestJson("POST", "/api/push/ack", payload).optBoolean("ok", false) }.getOrDefault(false)
    }

    @JvmStatic
    @JvmOverloads
    fun ackNotificationAsync(deviceId: String, notificationId: Long, status: String = "delivered") {
        Thread({ ackNotification(deviceId, notificationId, status) }, "tahoe-push-ack").apply {
            isDaemon = true
            start()
        }
    }

    interface PushListener {
        fun onNotification(notification: PushNotification)
        fun onPolicyUpdated(policy: UpdatePolicy, overrides: ChannelOverrides) {}
    }

    @JvmStatic
    fun startPushStream(
        storageDir: File?,
        profile: DeviceProfile,
        listener: PushListener,
    ): AutoCloseable {
        val dir = storageDir ?: defaultStorageDir()
        loadCachedConfig(dir)
        val running = AtomicBoolean(true)
        val activeCon = arrayOfNulls<HttpURLConnection>(1)

        val worker = Thread({
            var backoffMs = 2_000L
            while (running.get()) {
                try {
                    val hb = heartbeat(dir, profile)
                    if (hb != null) {
                        listener.onPolicyUpdated(hb.policy, hb.overrides)
                        hb.notifications.forEach { n ->
                            if (seenPushIds.add(n.id)) {
                                saveLastNotificationId(dir, n.id)
                                ackNotificationAsync(profile.deviceId, n.id, "delivered")
                                listener.onNotification(n)
                            }
                        }
                    }

                    val since = lastNotificationId(dir)
                    val q = "device_id=${enc(profile.deviceId)}&platform=${enc(profile.platform)}&app_role=${enc(profile.appRole)}" +
                        "&version_code=${profile.versionCode}&country=${enc(profile.country)}&since=$since"
                    val u = URI(baseUrl.trimEnd('/') + "/api/push/stream?$q").toURL()
                    val con = (u.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10_000
                        readTimeout = 70_000
                        setRequestProperty("Accept", "text/event-stream")
                        setRequestProperty("User-Agent", "TahoeTV/${profile.appVersion}")
                        if (since > 0) setRequestProperty("Last-Event-ID", since.toString())
                    }
                    activeCon[0] = con
                    if (con.responseCode in 200..299) {
                        backoffMs = 2_000L
                        BufferedReader(InputStreamReader(con.inputStream, StandardCharsets.UTF_8)).use { br ->
                            var eventName = "message"
                            var eventId = ""
                            val dataBuf = StringBuilder()
                            while (running.get()) {
                                val line = br.readLine() ?: break
                                when {
                                    line.isEmpty() -> {
                                        val payload = dataBuf.toString().trim()
                                        dataBuf.setLength(0)
                                        if (payload.isNotEmpty()) {
                                            handleSseEvent(dir, profile, eventName, eventId, payload, listener)
                                        }
                                        eventName = "message"
                                        eventId = ""
                                    }
                                    line.startsWith(":") -> {
                                        // SSE heartbeat comment
                                    }
                                    line.startsWith("event:") -> eventName = line.substring(6).trim()
                                    line.startsWith("id:") -> eventId = line.substring(3).trim()
                                    line.startsWith("data:") -> {
                                        if (dataBuf.isNotEmpty()) dataBuf.append('\n')
                                        dataBuf.append(line.substring(5).trimStart())
                                    }
                                }
                            }
                        }
                    }
                    con.disconnect()
                } catch (_: Throwable) {
                    // Exponential backoff with periodic /api/push/pending catchup on next wake
                } finally {
                    activeCon[0] = null
                }
                if (!running.get()) break
                try {
                    Thread.sleep(backoffMs)
                } catch (_: InterruptedException) {
                    break
                }
                backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            }
        }, "tahoe-push-sse").apply {
            isDaemon = true
            start()
        }

        return AutoCloseable {
            running.set(false)
            runCatching { activeCon[0]?.disconnect() }
            worker.interrupt()
        }
    }

    private fun handleSseEvent(
        dir: File,
        profile: DeviceProfile,
        eventName: String,
        eventId: String,
        payload: String,
        listener: PushListener,
    ) {
        when (eventName) {
            "push" -> {
                val obj = runCatching { JSONObject(payload) }.getOrNull() ?: return
                val notif = parseNotification(obj)
                if (notif.id <= 0L) return
                saveLastNotificationId(dir, notif.id)
                if (seenPushIds.add(notif.id)) {
                    ackNotificationAsync(profile.deviceId, notif.id, "delivered")
                    listener.onNotification(notif)
                }
            }
            "config" -> {
                val obj = runCatching { JSONObject(payload) }.getOrNull() ?: return
                val pol = obj.optJSONObject("update_policy")?.let(::parsePolicy) ?: currentPolicy
                val ov = obj.optJSONObject("channel_overrides")?.let(::parseOverrides) ?: currentOverrides
                persistConfig(dir, pol, ov)
                listener.onPolicyUpdated(pol, ov)
            }
            else -> {
                eventId.toLongOrNull()?.let { saveLastNotificationId(dir, it) }
            }
        }
    }

    // ========================================================================= Admin Client (macOS Intel / Desktop)

    data class AdminSession(val token: String, val username: String, val expiresAt: Long)

    data class FleetStat(val label: String, val count: Int, val active24h: Int = 0)

    data class DeviceRecord(
        val deviceId: String,
        val appRole: String,
        val platform: String,
        val osVersion: String,
        val deviceModel: String,
        val appVersion: String,
        val versionCode: Int,
        val arch: String,
        val country: String,
        val lang: String,
        val firstSeenAt: Long,
        val lastSeenAt: Long,
        val heartbeats: Int,
    )

    data class AuditRecord(
        val id: Long,
        val actor: String,
        val action: String,
        val details: String,
        val createdAt: Long,
    )

    data class FleetOverview(
        val totalInstalled: Int,
        val active1h: Int,
        val active24h: Int,
        val active7d: Int,
        val active30d: Int,
        val onlineSse: Int,
        val byPlatform: List<FleetStat>,
        val byRole: List<FleetStat>,
        val byVersion: List<FleetStat>,
        val byCountry: List<FleetStat>,
        val topModels: List<FleetStat>,
        val policy: UpdatePolicy,
        val overrides: ChannelOverrides,
        val notifications: List<PushNotification>,
    )

    object AdminApi {
        fun login(username: String, password: String): AdminSession {
            val res = requestJson(
                "POST",
                "/api/admin/login",
                JSONObject().put("username", username.trim()).put("password", password),
            )
            val err = res.optString("error")
            if (err.isNotBlank()) throw IllegalStateException(err)
            val token = res.getString("token")
            return AdminSession(
                token = token,
                username = res.optString("username", username.trim()),
                expiresAt = res.optLong("expires_at", 0L),
            )
        }

        fun verifySession(token: String): AdminSession? = runCatching {
            val res = requestJson("GET", "/api/admin/me", null, bearer = token)
            if (!res.optBoolean("ok", false)) null
            else AdminSession(token, res.getString("username"), res.optLong("expires_at", 0L))
        }.getOrNull()

        fun overview(token: String): FleetOverview {
            val res = requestJson("GET", "/api/admin/overview", null, bearer = token)
            checkAdminError(res)
            val ov = res.getJSONObject("overview")
            val cfg = res.optJSONObject("config") ?: JSONObject()
            val pol = cfg.optJSONObject("update_policy")?.let(::parsePolicy) ?: UpdatePolicy()
            val chOv = cfg.optJSONObject("channel_overrides")?.let(::parseOverrides) ?: ChannelOverrides()
            val notifs = parseNotificationArray(res.optJSONArray("recent_notifications"))
            return FleetOverview(
                totalInstalled = ov.optInt("total_installed", 0),
                active1h = ov.optInt("active_1h", 0),
                active24h = ov.optInt("active_24h", 0),
                active7d = ov.optInt("active_7d", 0),
                active30d = ov.optInt("active_30d", 0),
                onlineSse = ov.optInt("online_sse", 0),
                byPlatform = parseStatList(ov.optJSONArray("by_platform"), "platform"),
                byRole = parseStatList(ov.optJSONArray("by_role"), "app_role"),
                byVersion = parseStatList(ov.optJSONArray("by_version"), "app_version"),
                byCountry = parseStatList(ov.optJSONArray("by_country"), "country"),
                topModels = parseStatList(ov.optJSONArray("top_models"), "model"),
                policy = pol,
                overrides = chOv,
                notifications = notifs,
            )
        }

        fun listDevices(
            token: String,
            query: String = "",
            platform: String = "",
            role: String = "",
            limit: Int = 100,
        ): Pair<Int, List<DeviceRecord>> {
            val q = "q=${enc(query)}&platform=${enc(platform)}&role=${enc(role)}&limit=$limit"
            val res = requestJson("GET", "/api/admin/devices?$q", null, bearer = token)
            checkAdminError(res)
            val total = res.optInt("total", 0)
            val arr = res.optJSONArray("devices") ?: JSONArray()
            val out = (0 until arr.length()).map { i ->
                val d = arr.getJSONObject(i)
                DeviceRecord(
                    deviceId = d.optString("device_id"),
                    appRole = d.optString("app_role"),
                    platform = d.optString("platform"),
                    osVersion = d.optString("os_version"),
                    deviceModel = d.optString("device_model"),
                    appVersion = d.optString("app_version"),
                    versionCode = d.optInt("version_code", 0),
                    arch = d.optString("arch"),
                    country = d.optString("country"),
                    lang = d.optString("lang"),
                    firstSeenAt = d.optLong("first_seen_at", 0L),
                    lastSeenAt = d.optLong("last_seen_at", 0L),
                    heartbeats = d.optInt("heartbeats", 0),
                )
            }
            return total to out
        }

        fun deleteDevice(token: String, deviceId: String): Boolean {
            val res = requestJson("DELETE", "/api/admin/devices/${enc(deviceId)}", null, bearer = token)
            checkAdminError(res)
            return res.optBoolean("ok", false)
        }

        fun listNotifications(token: String): List<PushNotification> {
            val res = requestJson("GET", "/api/admin/notifications", null, bearer = token)
            checkAdminError(res)
            return parseNotificationArray(res.optJSONArray("notifications"))
        }

        fun sendPushNotification(
            token: String,
            title: String,
            body: String,
            kind: String = "info",
            targetPlatform: String? = null,
            targetRole: String? = null,
            targetCountry: String? = null,
            actionUrl: String? = null,
            actionLabel: String? = null,
            forceModal: Boolean = false,
            expiresHours: Int = 72,
        ): Int {
            val payload = JSONObject()
                .put("title", title)
                .put("body", body)
                .put("kind", kind)
                .put("force_modal", forceModal)
                .put("expires_hours", expiresHours)
            if (!targetPlatform.isNullOrBlank()) payload.put("target_platform", targetPlatform)
            if (!targetRole.isNullOrBlank()) payload.put("target_role", targetRole)
            if (!targetCountry.isNullOrBlank()) payload.put("target_country", targetCountry)
            if (!actionUrl.isNullOrBlank()) payload.put("action_url", actionUrl)
            if (!actionLabel.isNullOrBlank()) payload.put("action_label", actionLabel)
            val res = requestJson("POST", "/api/admin/notifications", payload, bearer = token)
            checkAdminError(res)
            return res.optInt("live_sse_delivered", 0)
        }

        fun deleteNotification(token: String, id: Long): Boolean {
            val res = requestJson("DELETE", "/api/admin/notifications/$id", null, bearer = token)
            checkAdminError(res)
            return res.optBoolean("ok", false)
        }

        fun updateConfig(token: String, policy: UpdatePolicy, overrides: ChannelOverrides): Pair<UpdatePolicy, ChannelOverrides> {
            val payload = JSONObject()
                .put(
                    "update_policy",
                    JSONObject()
                        .put("force_modal", policy.forceModal)
                        .put("min_version_code", policy.minVersionCode)
                        .put("min_version", policy.minVersion)
                        .put("recommended_version", policy.recommendedVersion)
                        .put("modal_title", policy.modalTitle)
                        .put("modal_message", policy.modalMessage),
                )
                .put(
                    "channel_overrides",
                    JSONObject()
                        .put("blocked_urls", JSONArray(overrides.blockedUrls.toList()))
                        .put("blocked_hosts", JSONArray(overrides.blockedHosts.toList()))
                        .put("announcement", overrides.announcement)
                        .put("featured_notice", overrides.featuredNotice),
                )
            val res = requestJson("PUT", "/api/admin/config", payload, bearer = token)
            checkAdminError(res)
            val pol = res.optJSONObject("update_policy")?.let(::parsePolicy) ?: policy
            val ov = res.optJSONObject("channel_overrides")?.let(::parseOverrides) ?: overrides
            return pol to ov
        }

        fun auditLogs(token: String): List<AuditRecord> {
            val res = requestJson("GET", "/api/admin/audit", null, bearer = token)
            checkAdminError(res)
            val arr = res.optJSONArray("logs") ?: JSONArray()
            return (0 until arr.length()).map { i ->
                val a = arr.getJSONObject(i)
                AuditRecord(
                    id = a.optLong("id", 0L),
                    actor = a.optString("actor"),
                    action = a.optString("action"),
                    details = a.optString("details"),
                    createdAt = a.optLong("created_at", 0L),
                )
            }
        }

        fun changePassword(token: String, currentPassword: String, newPassword: String): AdminSession {
            val payload = JSONObject()
                .put("current_password", currentPassword)
                .put("new_password", newPassword)
            val res = requestJson("POST", "/api/admin/password", payload, bearer = token)
            checkAdminError(res)
            return AdminSession(
                token = res.getString("token"),
                username = res.optString("username", "admin"),
                expiresAt = res.optLong("expires_at", 0L),
            )
        }
    }

    // ========================================================================= Helpers

    private fun checkAdminError(res: JSONObject) {
        val err = res.optString("error")
        if (err.isNotBlank()) throw IllegalStateException(err)
    }

    private fun requestJson(method: String, pathAndQuery: String, body: JSONObject?, bearer: String? = null): JSONObject {
        val url = URI(baseUrl.trimEnd('/') + pathAndQuery).toURL()
        val con = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = 10_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "TahoeTV/${TahoeVersion.VERSION}")
            setRequestProperty("Accept", "application/json")
            if (!bearer.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                con.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            }
            val code = con.responseCode
            val stream = if (code in 200..299) con.inputStream else (con.errorStream ?: con.inputStream)
            val text = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            if (text.isBlank()) {
                return JSONObject().apply { if (code >= 400) put("error", "HTTP $code") }
            }
            val obj = JSONObject(text)
            if (code >= 400 && !obj.has("error")) obj.put("error", "HTTP $code")
            return obj
        } finally {
            con.disconnect()
        }
    }

    private fun persistConfig(dir: File, policy: UpdatePolicy, overrides: ChannelOverrides) {
        currentPolicy = policy
        currentOverrides = overrides
        atomicWriteText(
            File(dir, "update_policy.json"),
            JSONObject()
                .put("force_modal", policy.forceModal)
                .put("min_version_code", policy.minVersionCode)
                .put("min_version", policy.minVersion)
                .put("recommended_version", policy.recommendedVersion)
                .put("modal_title", policy.modalTitle)
                .put("modal_message", policy.modalMessage)
                .toString(),
        )
        atomicWriteText(
            File(dir, "channel_overrides.json"),
            JSONObject()
                .put("blocked_urls", JSONArray(overrides.blockedUrls.toList()))
                .put("blocked_hosts", JSONArray(overrides.blockedHosts.toList()))
                .put("announcement", overrides.announcement)
                .put("featured_notice", overrides.featuredNotice)
                .toString(),
        )
    }

    private fun parsePolicy(o: JSONObject) = UpdatePolicy(
        forceModal = o.optBoolean("force_modal", false),
        minVersionCode = o.optInt("min_version_code", 0),
        minVersion = o.optString("min_version"),
        recommendedVersion = o.optString("recommended_version"),
        modalTitle = o.optString("modal_title"),
        modalMessage = o.optString("modal_message"),
    )

    private fun parseOverrides(o: JSONObject): ChannelOverrides {
        val urls = mutableSetOf<String>()
        o.optJSONArray("blocked_urls")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let(urls::add)
        }
        val hosts = mutableSetOf<String>()
        o.optJSONArray("blocked_hosts")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).trim().lowercase().takeIf { it.isNotEmpty() }?.let(hosts::add)
        }
        return ChannelOverrides(
            blockedUrls = urls,
            blockedHosts = hosts,
            announcement = o.optString("announcement"),
            featuredNotice = o.optString("featured_notice"),
        )
    }

    private fun parseNotification(o: JSONObject) = PushNotification(
        id = o.optLong("id", 0L),
        title = o.optString("title"),
        body = o.optString("body"),
        kind = o.optString("kind", "info"),
        actionUrl = o.optString("action_url"),
        actionLabel = o.optString("action_label"),
        forceModal = o.optBoolean("force_modal", false) || o.optInt("force_modal", 0) == 1,
        createdAt = o.optLong("created_at", 0L),
    )

    private fun parseNotificationArray(arr: JSONArray?): List<PushNotification> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val n = parseNotification(arr.getJSONObject(i))
            if (n.id > 0L && n.title.isNotBlank()) n else null
        }
    }

    private fun parseReleaseObject(o: JSONObject, pol: UpdatePolicy): Updates.Release? {
        val version = o.optString("version").trim()
        if (version.isBlank()) return null
        val assets = linkedMapOf<String, String>()
        val sha = linkedMapOf<String, String>()
        o.optJSONArray("assets")?.let { arr ->
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                val name = a.optString("name")
                val url = a.optString("url")
                if (name.isNotBlank() && url.isNotBlank()) {
                    assets[name] = url
                    a.optString("sha256").takeIf { it.length == 64 }?.let { sha[name] = it.lowercase() }
                }
            }
        }
        return Updates.Release(
            version = version,
            notes = o.optString("notes"),
            page = o.optString("page", Updates.PAGE),
            date = o.optString("date"),
            assets = assets,
            sha256Map = sha,
            forceModal = pol.forceModal,
            minVersionCode = pol.minVersionCode,
            modalTitle = pol.modalTitle,
            modalMessage = pol.modalMessage,
        )
    }

    private fun parseStatList(arr: JSONArray?, key: String): List<FleetStat> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            FleetStat(
                label = o.optString(key, "unknown"),
                count = o.optInt("count", 0),
                active24h = o.optInt("active_24h", 0),
            )
        }
    }

    private fun enc(v: String): String = URLEncoder.encode(v, StandardCharsets.UTF_8.name())

    private fun atomicWriteText(target: File, content: String, privateOnly: Boolean = false) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content, StandardCharsets.UTF_8)
        if (privateOnly) {
            runCatching {
                tmp.setReadable(false, false)
                tmp.setReadable(true, true)
                tmp.setWritable(false, false)
                tmp.setWritable(true, true)
            }
        }
        if (!tmp.renameTo(target)) {
            target.writeText(content, StandardCharsets.UTF_8)
            tmp.delete()
        }
    }
}
