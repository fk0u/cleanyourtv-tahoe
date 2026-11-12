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
    private var media: MediaController? = null
    private var retry = 0

    override fun onListenerConnected() {
        Report.install(this) // Android can start this service without the activity: crashes here are logged too
        inst = this
        connect()
        val msm = getSystemService(MediaSessionManager::class.java)
        val me = ComponentName(this, Sync::class.java)
        msm.addOnActiveSessionsChangedListener({ sessions(it) }, me, ui)
        sessions(msm.getActiveSessions(me))
    }

    override fun onListenerDisconnected() { inst = null; ws?.close() }

    fun connect() {
        val p = prefs(this)
        val host = p.getString("host", "").orEmpty(); val token = p.getString("token", "").orEmpty()
        if (host.isEmpty() || token.isEmpty()) { status.value = "Belum dipasangkan"; return }
        if (ws?.isOpen == true) return
        ws = try {
            object : WebSocketClient(URI("ws://$host:8765/ws?token=$token")) {
                override fun onOpen(h: ServerHandshake) { retry = 0; status.value = "Tersambung ke $host"; ui.post { sendMedia() } }
                override fun onMessage(m: String) { ui.post { command(m) } }
                override fun onClose(code: Int, reason: String, remote: Boolean) {
                    status.value = if (reason == "pair first" || reason == "unpaired") "Dilepas dari TV, pasangkan ulang" else "Terputus, menyambung ulang…"
                    if ("pair" !in reason) ui.postDelayed({ connect() }, minOf(30_000L, 1000L shl minOf(retry++, 5)))
                }
                override fun onError(e: Exception) {}
            }.also { it.connect() }
        } catch (e: Exception) { status.value = "Alamat TV tidak valid"; null }
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
    }
}
