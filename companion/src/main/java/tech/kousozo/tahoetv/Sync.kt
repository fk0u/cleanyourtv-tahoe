package tech.kousozo.tahoetv

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.mutableStateOf
import java.net.URI
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject

/** Runs while notification access is granted: keeps a WebSocket to the TV, forwards notifications and the
 *  playing media session, and applies play/pause/next sent back from the TV's Control Center. */
class Sync : NotificationListenerService() {
    private val ui = Handler(Looper.getMainLooper())
    private var ws: WebSocketClient? = null
    private var connectedHost: String = ""
    private var media: MediaController? = null
    private var msm: MediaSessionManager? = null
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var retry = 0
    @Volatile private var stopped = false

    private fun setStatus(text: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) status.value = text else ui.post { status.value = text }
    }

    private fun unregisterSessions() {
        val listener = sessionsListener ?: return
        sessionsListener = null
        runCatching { msm?.removeOnActiveSessionsChangedListener(listener) }
        runCatching { media?.unregisterCallback(cb) }
        media = null
    }

    override fun onListenerConnected() {
        Report.install(this) // Android can start this service without the activity: crashes here are logged too
        stopped = false
        inst = this
        connect()
        runCatching {
            val mgr = getSystemService(MediaSessionManager::class.java) ?: return@runCatching
            msm = mgr
            unregisterSessions()
            val listener = MediaSessionManager.OnActiveSessionsChangedListener { sessions(it) }
            sessionsListener = listener
            val me = ComponentName(this, Sync::class.java)
            mgr.addOnActiveSessionsChangedListener(listener, me, ui)
            sessions(mgr.getActiveSessions(me))
        }
    }

    override fun onListenerDisconnected() {
        stopped = true
        inst = null
        unregisterSessions()
        ui.removeCallbacksAndMessages(null)
        runCatching { ws?.close() }
        ws = null
    }

    override fun onDestroy() {
        stopped = true
        inst = null
        unregisterSessions()
        ui.removeCallbacksAndMessages(null)
        runCatching { ws?.close() }
        ws = null
        super.onDestroy()
    }

    fun connect() {
        if (stopped) return
        val dev = devices(this).active()
        val p = prefs(this)
        val host = dev?.host ?: p.getString("host", "").orEmpty()
        val token = dev?.token ?: p.getString("token", "").orEmpty()
        if (host.isEmpty() || token.isEmpty()) {
            runCatching { ws?.close() }; ws = null; connectedHost = ""
            setStatus("Belum dipasangkan")
            return
        }
        if (ws?.isOpen == true && connectedHost == host) return
        if (ws != null && connectedHost != host) { runCatching { ws?.close() }; ws = null }
        connectedHost = host
        ws = try {
            object : WebSocketClient(URI("ws://$host:8765/ws?token=$token")) {
                override fun onOpen(h: ServerHandshake) { retry = 0; setStatus("Tersambung ke $host"); ui.post { sendMedia() } }
                override fun onMessage(m: String) { ui.post { command(m) } }
                override fun onClose(code: Int, reason: String, remote: Boolean) {
                    setStatus(if (reason == "pair first" || reason == "unpaired") "Dilepas dari TV, pasangkan ulang" else "Terputus, menyambung ulang…")
                    if (!stopped && "pair" !in reason) ui.postDelayed({ if (!stopped) connect() }, minOf(30_000L, 1000L shl minOf(retry++, 5)))
                }
                override fun onError(e: Exception) {}
            }.also { it.connect() }
        } catch (e: Exception) { setStatus("Alamat TV tidak valid"); null }
    }

    /** The socket can close between isOpen and send (TV standby, Wi-Fi blip); send then throws, so drop it quietly. */
    private fun send(o: JSONObject) { runCatching { ws?.takeIf { it.isOpen }?.send(o.toString()) } }

    private fun command(m: String) = runCatching {
        val o = JSONObject(m)
        val tc = media?.transportControls ?: return@runCatching
        if (o.optString("t") != "media_cmd") return@runCatching
        when (o.optString("c")) { "play" -> tc.play(); "pause" -> tc.pause(); "next" -> tc.skipToNext(); "prev" -> tc.skipToPrevious() }
    }

    private fun appLabel(pkg: String) = packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (sbn.isOngoing || sbn.packageName == packageName || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (!prefs(this).getBoolean("notif", true)) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE) ?: return
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT) ?: ""
        runCatching { send(JSONObject().put("t", "notif").put("app", appLabel(sbn.packageName)).put("title", "$title").put("text", "$text")) }
    }

    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(m: MediaMetadata?) = sendMedia()
        override fun onPlaybackStateChanged(s: PlaybackState?) = sendMedia()
        override fun onSessionDestroyed() { media = null; sendMedia() }
    }

    private fun sessions(list: List<MediaController>?) {
        val pick = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
        media?.unregisterCallback(cb)
        media = pick
        media?.registerCallback(cb, ui)
        sendMedia()
    }

    fun sendMedia() {
        runCatching {
            val m = media?.metadata
            val title = m?.getText(MediaMetadata.METADATA_KEY_TITLE)
            if (title == null || !prefs(this).getBoolean("media", true)) { send(JSONObject().put("t", "media").put("stopped", true)); return }
            val app = appLabel(media!!.packageName)
            send(JSONObject().put("t", "media").put("title", "$title")
                .put("artist", m.getText(MediaMetadata.METADATA_KEY_ARTIST)?.toString() ?: app).put("app", app)
                .put("playing", media?.playbackState?.state == PlaybackState.STATE_PLAYING))
        }
    }

    companion object {
        @Volatile var inst: Sync? = null
        /** Shown on the home screen; Compose observes it. */
        val status = mutableStateOf("Belum tersambung")
        fun prefs(c: Context) = c.getSharedPreferences("tv", Context.MODE_PRIVATE)!!

        fun devices(c: Context): tech.kousozo.tahoetv.core.PairedDevices.State {
            val p = prefs(c)
            return tech.kousozo.tahoetv.core.PairedDevices.fromJson(
                p.getString("devices_json", null),
                p.getString("host", "").orEmpty(),
                p.getString("token", "").orEmpty(),
            )
        }

        fun saveDevices(c: Context, st: tech.kousozo.tahoetv.core.PairedDevices.State) {
            val active = st.active()
            prefs(c).edit()
                .putString("devices_json", st.toJson())
                .putString("host", active?.host.orEmpty())
                .putString("token", active?.token.orEmpty())
                .apply()
            Handler(Looper.getMainLooper()).post {
                Pairing.tick++
                inst?.connect()
            }
        }

        fun recordPaired(c: Context, host: String, res: tech.kousozo.tahoetv.core.TvLink.PairResult) {
            val cleanHost = host.trim()
            val next = devices(c).upsert(
                tech.kousozo.tahoetv.core.PairedDevices.Device(
                    id = res.id.ifBlank { cleanHost },
                    host = cleanHost,
                    token = res.token,
                    name = res.name.ifBlank { "Tahoe TV" },
                    role = res.role.ifBlank { "owner" },
                    lastSeen = System.currentTimeMillis(),
                )
            )
            saveDevices(c, next)
        }
    }
}

/** Discovers `_tahoetv._tcp` receivers on local Wi-Fi via NsdManager (#88, #37). */
object TvDiscovery {
    val discovered = androidx.compose.runtime.mutableStateListOf<tech.kousozo.tahoetv.core.Scan.Found>()
    private var nsd: android.net.nsd.NsdManager? = null
    private var discoveryListener: android.net.nsd.NsdManager.DiscoveryListener? = null
    private val main = Handler(Looper.getMainLooper())

    fun start(ctx: Context) {
        if (discoveryListener != null) return
        val app = ctx.applicationContext
        val mgr = runCatching { app.getSystemService(Context.NSD_SERVICE) as? android.net.nsd.NsdManager }.getOrNull() ?: return
        nsd = mgr
        val listener = object : android.net.nsd.NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(serviceInfo: android.net.nsd.NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains("_tahoetv._tcp")) return
                runCatching {
                    @Suppress("DEPRECATION")
                    mgr.resolveService(serviceInfo, object : android.net.nsd.NsdManager.ResolveListener {
                        override fun onResolveFailed(si: android.net.nsd.NsdServiceInfo, err: Int) {}
                        override fun onServiceResolved(resolved: android.net.nsd.NsdServiceInfo) {
                            runCatching {
                                @Suppress("DEPRECATION")
                                val host = resolved.host?.hostAddress ?: return@runCatching
                                val id = resolved.attributes["id"]?.let { String(it, Charsets.UTF_8) }.orEmpty()
                                val ver = resolved.attributes["ver"]?.let { String(it, Charsets.UTF_8) }
                                val item = tech.kousozo.tahoetv.core.Scan.Found(host, resolved.serviceName, ver, adb = false, tahoe = true)
                                main.post {
                                    runCatching {
                                        discovered.removeAll { it.ip == host || it.name == item.name }
                                        discovered.add(item)
                                        if (id.isNotEmpty()) {
                                            val cur = Sync.devices(app)
                                            val updated = cur.updateHost(id, host)
                                            if (updated != cur) Sync.saveDevices(app, updated)
                                        }
                                    }.onFailure { tech.kousozo.tahoetv.core.DiagLog.error("nsd", "onServiceResolved", it) }
                                }
                            }.onFailure { tech.kousozo.tahoetv.core.DiagLog.error("nsd", "resolveService", it) }
                        }
                    })
                }
            }
            override fun onServiceLost(serviceInfo: android.net.nsd.NsdServiceInfo) {
                runCatching {
                    val name = serviceInfo.serviceName
                    main.post {
                        runCatching { discovered.removeAll { it.name == name } }
                            .onFailure { tech.kousozo.tahoetv.core.DiagLog.error("nsd", "onServiceLost", it) }
                    }
                }
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { stop() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener
        runCatching { mgr.discoverServices("_tahoetv._tcp.", android.net.nsd.NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    fun stop() {
        val l = discoveryListener ?: return
        discoveryListener = null
        runCatching { nsd?.stopServiceDiscovery(l) }
    }
}
