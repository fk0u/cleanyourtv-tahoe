package tech.kousozo.tahoetv.core

import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject

/** Remote control of a TV running Tahoe over its paired WebSocket (port 8765). Works without ADB, e.g. on TV boxes
 *  where Tahoe was installed from a USB stick. Uses protocol v2 envelopes ([Protocol]) with v1 compatibility. */
class TvLink(val host: String, private val token: String, private val onState: (Boolean) -> Unit = {}) {
    /** Latest {"t":"state"} from the TV: volume (0–100), muted, channel, channelId, number, playing, rev. */
    @Volatile var onTvState: (JSONObject) -> Unit = {}
    /** Latest typed [Protocol.SessionState] from the TV (#88). */
    @Volatile var onSessionState: (Protocol.SessionState) -> Unit = {}
    /** Latest state JSON observed over this link (for immediate display when switching screens). */
    @Volatile var lastTvState: JSONObject? = null
        private set
    /** Other messages from the TV, e.g. {"t":"send_log"} when "Kirim log" is pressed on the TV. */
    @Volatile var onCommand: (JSONObject) -> Unit = {}
    @Volatile private var ws: WebSocketClient? = null
    @Volatile private var wanted = false
    @Volatile private var peerProto = Protocol.VERSION
    @Volatile private var lastSessionRev = -1L
    private val seq = AtomicLong(0L)
    private val pending = ConcurrentLinkedQueue<String>()
    val connected get() = ws?.isOpen == true

    private fun nextId(): String = "c-${seq.incrementAndGet()}"

    /** Connects and keeps reconnecting (every 2 s) until [close]: the TV may be asleep, restarting, or Wi-Fi dropped. */
    fun open() { wanted = true; connect() }

    /** Never throws: a bad address or a failing connect just retries, it must not crash the app. */
    private fun connect() {
        if (connected || !wanted) return
        try { ws = client().also { it.connect() } } catch (e: Exception) { retry() }
    }

    private fun retry() { if (wanted) Thread { Thread.sleep(2000); connect() }.apply { isDaemon = true }.start() }

    private fun wsUri(): URI {
        val h = host.trim()
        val url = if (h.contains(":")) "ws://$h/ws" else "ws://$h:8765/ws"
        return URI(url)
    }

    private fun client() = object : WebSocketClient(
        wsUri(),
        mapOf("Authorization" to "Bearer $token")
    ) {
            override fun onOpen(h: ServerHandshake) {
                val hello = Protocol.encode(
                    Protocol.Hello(
                        proto = Protocol.VERSION,
                        role = "controller",
                        caps = listOf("remote", "connect"),
                    ),
                    id = nextId(),
                ).toString()
                sendRaw(hello)
                flushPending()
                onState(true)
            }
            override fun onMessage(m: String) {
                val dec = Protocol.decode(m) ?: return
                when (val msg = dec.message) {
                    is Protocol.Welcome -> peerProto = msg.proto
                    is Protocol.Ping -> sendRaw(Protocol.encode(Protocol.Pong(msg.nonce), id = nextId()).toString())
                    is Protocol.Pong, is Protocol.Ack -> Unit
                    is Protocol.Error -> onCommand(JSONObject(dec.envelope.body.toString()).put("t", "error"))
                    is Protocol.DeviceState -> handleDeviceState(msg)
                    is Protocol.SessionState -> handleSessionState(msg)
                    Protocol.SendLog -> onCommand(JSONObject().put("t", "send_log"))
                    else -> {
                        val o = runCatching { JSONObject(m) }.getOrNull() ?: JSONObject(dec.envelope.body.toString())
                        onCommand(o)
                    }
                }
            }
            override fun onClose(code: Int, reason: String, remote: Boolean) { onState(false); retry() }
            override fun onError(e: Exception) {}
        }

    private fun handleDeviceState(ds: Protocol.DeviceState) {
        val s = ds.session
        val sessionToUse = if (s != null && s.rev > 0L && s.rev < lastSessionRev) null else s
        if (sessionToUse != null && sessionToUse.rev > lastSessionRev) lastSessionRev = sessionToUse.rev
        val stateJson = Protocol.toLegacyStateJson(ds, sessionToUse)
        lastTvState = stateJson
        onTvState(stateJson)
        if (sessionToUse != null) onSessionState(sessionToUse)
    }

    private fun handleSessionState(s: Protocol.SessionState) {
        if (s.rev > 0L && s.rev < lastSessionRev) return
        lastSessionRev = s.rev
        val prev = lastTvState
        val ds = Protocol.DeviceState(
            volume = prev?.optInt("volume", 0) ?: 0,
            muted = prev?.optBoolean("muted", false) ?: false,
            sleep = prev?.optInt("sleep", 0) ?: 0,
            session = s,
        )
        val stateJson = Protocol.toLegacyStateJson(ds, s)
        lastTvState = stateJson
        onTvState(stateJson)
        onSessionState(s)
    }

    fun close() { wanted = false; ws?.close(); ws = null }

    private fun sendRaw(msg: String): Boolean {
        val sock = ws ?: return false
        if (!sock.isOpen) return false
        return runCatching { sock.send(msg); true }.getOrElse { false }
    }

    private fun flushPending() {
        while (true) {
            val next = synchronized(pending) { pending.peek() } ?: break
            if (!sendRaw(next)) break
            synchronized(pending) { pending.poll() }
        }
    }

    /** Sends [o] now if connected, or queues up to 64 messages to flush once connected. */
    private fun send(o: JSONObject) {
        val msg = o.toString()
        if (!sendRaw(msg)) {
            synchronized(pending) {
                while (pending.size >= MAX_PENDING) pending.poll()
                pending.offer(msg)
            }
            if (wanted && !connected) connect()
        }
    }

    private fun sendProtocol(msg: Protocol.Message, withLegacy: Boolean = true) =
        send(Protocol.encode(msg, id = nextId(), withLegacy = withLegacy))

    internal fun pendingCount(): Int = pending.size
    internal fun enqueueForTest(o: JSONObject) = send(o)

    /** up, down, left, right, ok, back, home, menu, apps, mute, settings, livetv, ch_up, ch_down, 0–9, play_pause, rewind, ffwd */
    fun key(k: String) = sendProtocol(Protocol.Key(k))
    /** The phone's onboarding choices ([Viewer] JSON): the TV takes them and skips its own onboarding (#77). */
    fun viewer(json: String) = sendProtocol(Protocol.ViewerSync(json))
    /** Delta or region sync patch ([Viewer.diff]) sent over an ordered link queue (#111). */
    fun viewerPatch(patch: JSONObject) = sendProtocol(Protocol.ViewerPatch(patch))
    fun launch(pkg: String) = sendProtocol(Protocol.Launch(pkg))
    fun url(u: String) = sendProtocol(Protocol.OpenUrl(u))
    fun ask(q: String) = sendProtocol(Protocol.Ask(q))
    fun volume(d: Int) = sendProtocol(Protocol.VolumeDelta(d))
    fun volumeSet(percent: Int) = sendProtocol(Protocol.VolumeSet(percent))
    /** Replaces the text of the focused field on the TV. */
    fun text(s: String) = sendProtocol(Protocol.TextInput(s))
    /** Standby ↔ wake. */
    fun power() = sendProtocol(Protocol.Power)
    /** Sleep timer in minutes, 0 = off. */
    fun sleep(minutes: Int) = sendProtocol(Protocol.Sleep(minutes))

    /** Tahoe Connect (#88): tune a specific channel on the TV by stable channelId (fallback url). */
    @JvmOverloads
    fun sessionLoad(channelId: String, url: String = "", name: String = "", startAt: Long = 0L) =
        sendProtocol(Protocol.SessionLoad(channelId = channelId, url = url, name = name, startAt = startAt), withLegacy = false)
    fun sessionPlay() = sendProtocol(Protocol.SessionControl("play"), withLegacy = false)
    fun sessionPause() = sendProtocol(Protocol.SessionControl("pause"), withLegacy = false)
    fun sessionStop() = sendProtocol(Protocol.SessionControl("stop"), withLegacy = false)

    companion object {
        private const val MAX_PENDING = 64
        @Volatile private var sharedLink: TvLink? = null

        /** One app-scoped ordered link per target (host, token), reused instead of throwaway sockets (#96). */
        @JvmStatic @Synchronized fun shared(host: String, token: String): TvLink {
            val h = host.trim()
            val cur = sharedLink
            if (cur != null && cur.host.trim() == h && cur.token == token) {
                if (!cur.connected) cur.open()
                return cur
            }
            cur?.close()
            return TvLink(h, token).also { it.open(); sharedLink = it }
        }

        private fun httpBase(host: String): String {
            val h = host.trim()
            return if (h.contains(":")) "http://$h" else "http://$h:8765"
        }

        /** POST /pair with the 6-digit code shown on the TV. Returns the token, or throws with the TV's error. Blocks. */
        @JvmOverloads
        fun pair(host: String, code: String, lang: String = "id"): String {
            val base = httpBase(host)
            val res = JSONObject(runCatching { Web.request("POST", "$base/pair", JSONObject().put("code", code).toString(), 5000) }
                .getOrElse { throw java.io.IOException(when {
                    "HTTP 429" in "${it.message}" -> I18n.get(lang, "pair.too_many")
                    "HTTP 4" in "${it.message}" -> I18n.get(lang, "pair.invalid_code")
                    else -> I18n.get(lang, "pair.unreachable")
                }) })
            return res.optString("token").ifEmpty { throw java.io.IOException(res.optString("error", I18n.get(lang, "pair.failed"))) }
        }

        /** The TV's diagnostic report for "Send log" (GET /log). Blocks; throws when the TV can't be reached. */
        fun fetchLog(host: String, token: String): String =
            Web.get("${httpBase(host)}/log", 8000, mapOf("Authorization" to "Bearer $token"))

        /** Sets a new 4-digit PIN for the TV's kids profile (forgotten PIN). Only a paired phone holds the token. Blocks. */
        @JvmOverloads
        fun setKidsPin(host: String, token: String, pin: String, lang: String = "id") {
            require(pin.matches(Regex("\\d{4}"))) { I18n.get(lang, "pair.pin_digits") }
            // Auth header avoids exposing the bearer token in URL logs
            runCatching { Web.request("POST", "${httpBase(host)}/kids_pin", JSONObject().put("pin", pin).toString(), 8000, mapOf("Authorization" to "Bearer $token")) }
                .getOrElse { throw java.io.IOException(if ("HTTP 401" in "${it.message}") I18n.get(lang, "pair.not_paired") else I18n.get(lang, "pair.unreachable")) }
        }
    }
}
