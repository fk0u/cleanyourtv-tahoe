package tech.kousozo.tahoetv.core

import java.net.URI
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject

/** Remote control of a TV running Tahoe over its paired WebSocket (port 8765). Works without ADB, e.g. on TV boxes
 *  where Tahoe was installed from a USB stick. Same messages as the launcher's web remote. */
class TvLink(val host: String, private val token: String, private val onState: (Boolean) -> Unit = {}) {
    /** Latest {"t":"state"} from the TV: volume (0–100), muted, channel, number. */
    @Volatile var onTvState: (JSONObject) -> Unit = {}
    /** Other messages from the TV, e.g. {"t":"send_log"} when "Kirim log" is pressed on the TV. */
    @Volatile var onCommand: (JSONObject) -> Unit = {}
    @Volatile private var ws: WebSocketClient? = null
    @Volatile private var wanted = false
    val connected get() = ws?.isOpen == true

    /** Connects and keeps reconnecting (every 2 s) until [close]: the TV may be asleep, restarting, or Wi-Fi dropped. */
    fun open() { wanted = true; connect() }

    /** Never throws: a bad address or a failing connect just retries, it must not crash the app. */
    private fun connect() {
        if (connected || !wanted) return
        try { ws = client().also { it.connect() } } catch (e: Exception) { retry() }
    }

    private fun retry() { if (wanted) Thread { Thread.sleep(2000); connect() }.apply { isDaemon = true }.start() }

    private fun client() = object : WebSocketClient(URI("ws://${host.trim()}:8765/ws?token=$token")) {
            override fun onOpen(h: ServerHandshake) = onState(true)
            override fun onMessage(m: String) {
                val o = runCatching { JSONObject(m) }.getOrNull() ?: return
                if (o.optString("t") == "state") onTvState(o) else onCommand(o)
            }
            override fun onClose(code: Int, reason: String, remote: Boolean) { onState(false); retry() }
            override fun onError(e: Exception) {}
        }

    fun close() { wanted = false; ws?.close(); ws = null }

    /** Fire and forget. The socket can close between the isOpen check and send (TV to standby, Wi-Fi blip), and
     *  send then throws: that crashed the phone remote, so a lost key is just dropped. */
    private fun send(o: JSONObject) { runCatching { ws?.takeIf { it.isOpen }?.send(o.toString()) } }
    /** up, down, left, right, ok, back, home, menu, apps, mute, settings, livetv, ch_up, ch_down, 0–9, play_pause, rewind, ffwd */
    fun key(k: String) = send(JSONObject().put("t", "key").put("k", k))
    fun launch(pkg: String) = send(JSONObject().put("t", "launch").put("pkg", pkg))
    fun url(u: String) = send(JSONObject().put("t", "url").put("u", u))
    fun ask(q: String) = send(JSONObject().put("t", "ask").put("q", q))
    fun volume(d: Int) = send(JSONObject().put("t", "volume").put("d", d))
    fun volumeSet(percent: Int) = send(JSONObject().put("t", "volume_set").put("v", percent))
    /** Replaces the text of the focused field on the TV. */
    fun text(s: String) = send(JSONObject().put("t", "text").put("s", s))
    /** Standby ↔ wake. */
    fun power() = send(JSONObject().put("t", "power"))
    /** Sleep timer in minutes, 0 = off. */
    fun sleep(minutes: Int) = send(JSONObject().put("t", "sleep").put("m", minutes))

    companion object {
        /** POST /pair with the 6-digit code shown on the TV. Returns the token, or throws with the TV's error. Blocks. */
        fun pair(host: String, code: String): String {
            val res = JSONObject(runCatching { Web.request("POST", "http://$host:8765/pair", JSONObject().put("code", code).toString(), 5000) }
                .getOrElse { throw java.io.IOException(if ("HTTP 4" in "${it.message}") "Kode salah atau kedaluwarsa" else "Tidak bisa menghubungi TV") })
            return res.optString("token").ifEmpty { throw java.io.IOException(res.optString("error", "Gagal")) }
        }

        /** The TV's diagnostic report for "Kirim log" (GET /log). Blocks; throws when the TV can't be reached. */
        fun fetchLog(host: String, token: String): String =
            Web.get("http://${host.trim()}:8765/log?token=" + java.net.URLEncoder.encode(token, "UTF-8"), 8000)

        /** Sets a new 4-digit PIN for the TV's kids profile (forgotten PIN). Only a paired phone holds the token. Blocks. */
        fun setKidsPin(host: String, token: String, pin: String) {
            require(pin.matches(Regex("\\d{4}"))) { "PIN harus 4 angka" }
            runCatching { Web.request("POST", "http://${host.trim()}:8765/kids_pin?token=" + java.net.URLEncoder.encode(token, "UTF-8") + "&pin=$pin", "", 8000) }
                .getOrElse { throw java.io.IOException(if ("HTTP 401" in "${it.message}") "HP ini belum dipasangkan dengan TV" else "Tidak bisa menghubungi TV") }
        }
    }
}
