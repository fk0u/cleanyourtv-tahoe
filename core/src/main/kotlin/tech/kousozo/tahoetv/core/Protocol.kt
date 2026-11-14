package tech.kousozo.tahoetv.core

import org.json.JSONArray
import org.json.JSONObject

/** Versioned JSON envelope and message codec shared by TV (`Companion`), phone (`TvLink`), and desktop (#93, #88).
 *  Supports protocol v2 (`{v, id, type, ts, body}`) while decoding legacy v1 flat `{"t":"..."}` frames cleanly. */
object Protocol {
    const val VERSION = 2
    const val MIN_VERSION = 1

    data class Envelope(
        val v: Int = VERSION,
        val id: String = "",
        val type: String,
        val ts: Long = 0L,
        val body: JSONObject = JSONObject(),
        val from: String = "",
    )

    sealed class Message(val type: String)

    data class Hello(
        val proto: Int = VERSION,
        val role: String = "controller",
        val caps: List<String> = emptyList(),
        val name: String = "",
        val token: String = "",
    ) : Message("hello")

    data class Welcome(
        val proto: Int = VERSION,
        val deviceId: String = "",
        val name: String = "Tahoe TV",
        val ver: String = "",
        val caps: List<String> = emptyList(),
    ) : Message("welcome")

    data class Ack(val ackId: String, val rev: Long = 0L) : Message("ack")
    data class Error(val ackId: String = "", val code: String, val message: String) : Message("error")
    data class Ping(val nonce: Long = 0L) : Message("ping")
    data class Pong(val nonce: Long = 0L) : Message("pong")

    data class Key(val key: String) : Message("remote.key")
    data class ViewerSync(val json: String) : Message("remote.viewer")
    data class ViewerPatch(val patch: JSONObject) : Message("remote.viewer_patch")
    data class Launch(val pkg: String) : Message("remote.launch")
    data class OpenUrl(val url: String) : Message("remote.url")
    data class Ask(val query: String) : Message("remote.ask")
    data class VolumeDelta(val delta: Int) : Message("remote.volume")
    data class VolumeSet(val percent: Int) : Message("remote.volume_set")
    data class TextInput(val text: String) : Message("remote.text")
    object Power : Message("remote.power")
    data class Sleep(val minutes: Int) : Message("remote.sleep")
    data class PhoneNotification(val app: String, val title: String, val text: String) : Message("remote.notif")
    data class PhoneMedia(val stopped: Boolean, val raw: JSONObject) : Message("remote.media")
    object SendLog : Message("remote.send_log")

    data class SessionLoad(
        val channelId: String,
        val url: String = "",
        val name: String = "",
        val startAt: Long = 0L,
    ) : Message("session.load")

    data class SessionControl(val action: String) : Message("session.control")

    data class SessionState(
        val rev: Long = 0L,
        val channelId: String = "",
        val channel: String = "",
        val number: Int = 0,
        val playing: Boolean = false,
        val paused: Boolean = false,
    ) : Message("session.state")

    data class DeviceState(
        val volume: Int = 0,
        val muted: Boolean = false,
        val sleep: Int = 0,
        val session: SessionState? = null,
    ) : Message("device.state")

    data class Unknown(val rawType: String, val rawBody: JSONObject) : Message(rawType)

    data class Decoded(val envelope: Envelope, val message: Message)

    @JvmStatic
    fun isVersionSupported(v: Int): Boolean = v in MIN_VERSION..VERSION

    private fun bodyOf(msg: Message): JSONObject = when (msg) {
        is Hello -> JSONObject()
            .put("proto", msg.proto)
            .put("role", msg.role)
            .put("caps", JSONArray(msg.caps))
            .apply {
                if (msg.name.isNotEmpty()) put("name", msg.name)
                if (msg.token.isNotEmpty()) put("token", msg.token)
            }
        is Welcome -> JSONObject()
            .put("proto", msg.proto)
            .put("deviceId", msg.deviceId)
            .put("name", msg.name)
            .put("ver", msg.ver)
            .put("caps", JSONArray(msg.caps))
        is Ack -> JSONObject().put("ackId", msg.ackId).put("rev", msg.rev)
        is Error -> JSONObject().put("ackId", msg.ackId).put("code", msg.code).put("message", msg.message)
        is Ping -> JSONObject().put("nonce", msg.nonce)
        is Pong -> JSONObject().put("nonce", msg.nonce)
        is Key -> JSONObject().put("k", msg.key)
        is ViewerSync -> JSONObject().put("v", msg.json)
        is ViewerPatch -> JSONObject(msg.patch.toString())
        is Launch -> JSONObject().put("pkg", msg.pkg)
        is OpenUrl -> JSONObject().put("u", msg.url)
        is Ask -> JSONObject().put("q", msg.query)
        is VolumeDelta -> JSONObject().put("d", msg.delta)
        is VolumeSet -> JSONObject().put("percent", msg.percent)
        is TextInput -> JSONObject().put("s", msg.text)
        Power -> JSONObject()
        is Sleep -> JSONObject().put("m", msg.minutes)
        is PhoneNotification -> JSONObject().put("app", msg.app).put("title", msg.title).put("text", msg.text)
        is PhoneMedia -> JSONObject(msg.raw.toString()).put("stopped", msg.stopped)
        SendLog -> JSONObject()
        is SessionLoad -> JSONObject()
            .put("channelId", msg.channelId)
            .put("url", msg.url)
            .put("name", msg.name)
            .put("startAt", msg.startAt)
        is SessionControl -> JSONObject().put("action", msg.action)
        is SessionState -> sessionJson(msg)
        is DeviceState -> JSONObject()
            .put("volume", msg.volume)
            .put("muted", msg.muted)
            .put("sleep", msg.sleep)
            .apply { msg.session?.let { put("session", sessionJson(it)) } }
        is Unknown -> JSONObject(msg.rawBody.toString())
    }

    private fun sessionJson(s: SessionState): JSONObject = JSONObject()
        .put("rev", s.rev)
        .put("channelId", s.channelId)
        .put("channel", s.channel)
        .put("number", s.number)
        .put("playing", s.playing)
        .put("paused", s.paused)

    private fun wireType(msg: Message): String = when (msg) {
        is SessionControl -> when (msg.action) {
            "play", "pause", "stop" -> "session.${msg.action}"
            else -> "session.control"
        }
        else -> msg.type
    }

    @JvmStatic @JvmOverloads
    fun encode(
        msg: Message,
        id: String = "",
        ts: Long = System.currentTimeMillis(),
        from: String = "",
        withLegacy: Boolean = false,
    ): JSONObject {
        val body = bodyOf(msg)
        val env = JSONObject()
            .put("v", VERSION)
            .put("id", id)
            .put("type", wireType(msg))
            .put("ts", ts)
            .put("body", body)
        if (from.isNotEmpty()) env.put("from", from)
        if (withLegacy) attachLegacy(env, msg)
        return env
    }

    private fun attachLegacy(out: JSONObject, msg: Message) {
        when (msg) {
            is Key -> out.put("t", "key").put("k", msg.key)
            // Legacy viewer & volume_set reused top-level "v"; keep v=VERSION in envelope and read from body on v2.
            is ViewerSync -> out.put("t", "viewer")
            is ViewerPatch -> {
                msg.patch.keys().forEach { k -> if (k != "v" && k != "type" && k != "body") out.put(k, msg.patch.get(k)) }
                out.put("t", "viewer_patch")
            }
            is Launch -> out.put("t", "launch").put("pkg", msg.pkg)
            is OpenUrl -> out.put("t", "url").put("u", msg.url)
            is Ask -> out.put("t", "ask").put("q", msg.query)
            is VolumeDelta -> out.put("t", "volume").put("d", msg.delta)
            is VolumeSet -> out.put("t", "volume_set")
            is TextInput -> out.put("t", "text").put("s", msg.text)
            Power -> out.put("t", "power")
            is Sleep -> out.put("t", "sleep").put("m", msg.minutes)
            is PhoneNotification -> out.put("t", "notif").put("app", msg.app).put("title", msg.title).put("text", msg.text)
            is PhoneMedia -> {
                msg.raw.keys().forEach { k -> if (k != "v" && k != "type" && k != "body") out.put(k, msg.raw.get(k)) }
                out.put("t", "media").put("stopped", msg.stopped)
            }
            SendLog -> out.put("t", "send_log")
            else -> Unit
        }
    }

    @JvmStatic @JvmOverloads
    fun ack(ackId: String, rev: Long = 0L, id: String = ""): JSONObject =
        encode(Ack(ackId = ackId, rev = rev), id = id)

    @JvmStatic @JvmOverloads
    fun error(ackId: String, code: String, message: String, id: String = ""): JSONObject =
        encode(Error(ackId = ackId, code = code, message = message), id = id)

    /** Builds a combined v2 `device.state` + legacy `"t":"state"` object so v1 remotes, web UI, and v2 controllers stay in sync. */
    @JvmStatic @JvmOverloads
    fun toLegacyStateJson(device: DeviceState, session: SessionState? = device.session, id: String = ""): JSONObject {
        val merged = device.copy(session = session)
        val env = encode(merged, id = id)
        env.put("t", "state")
            .put("volume", merged.volume)
            .put("muted", merged.muted)
            .put("sleep", merged.sleep)
        if (session != null) {
            env.put("rev", session.rev)
                .put("playing", session.playing)
                .put("paused", session.paused)
            if (session.channel.isNotEmpty()) env.put("channel", session.channel).put("number", session.number)
            if (session.channelId.isNotEmpty()) env.put("channelId", session.channelId)
        }
        return env
    }

    @JvmStatic
    fun decode(raw: String): Decoded? {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (o.has("type") && o.opt("body") is JSONObject) {
            val type = o.optString("type")
            val body = o.optJSONObject("body") ?: JSONObject()
            val env = Envelope(
                v = o.optInt("v", VERSION),
                id = o.optString("id", ""),
                type = type,
                ts = o.optLong("ts", 0L),
                body = body,
                from = o.optString("from", ""),
            )
            return Decoded(env, decodeBody(type, body))
        }
        val t = o.optString("t")
        if (t.isNotEmpty()) {
            val msg = decodeLegacy(t, o)
            val env = Envelope(v = 1, id = o.optString("id", ""), type = msg.type, ts = o.optLong("ts", 0L), body = o)
            return Decoded(env, msg)
        }
        return null
    }

    private fun decodeBody(type: String, body: JSONObject): Message = when (type) {
        "hello" -> Hello(
            proto = body.optInt("proto", VERSION),
            role = body.optString("role", "controller"),
            caps = stringList(body.optJSONArray("caps")),
            name = body.optString("name", ""),
            token = body.optString("token", ""),
        )
        "welcome" -> Welcome(
            proto = body.optInt("proto", VERSION),
            deviceId = body.optString("deviceId", ""),
            name = body.optString("name", "Tahoe TV"),
            ver = body.optString("ver", ""),
            caps = stringList(body.optJSONArray("caps")),
        )
        "ack" -> Ack(ackId = body.optString("ackId", ""), rev = body.optLong("rev", 0L))
        "error" -> Error(
            ackId = body.optString("ackId", ""),
            code = body.optString("code", "error"),
            message = body.optString("message", ""),
        )
        "ping" -> Ping(nonce = body.optLong("nonce", 0L))
        "pong" -> Pong(nonce = body.optLong("nonce", 0L))
        "remote.key", "key" -> Key(body.optString("k", body.optString("key", "")))
        "remote.viewer", "viewer" -> ViewerSync(body.optString("v", body.optString("json", "")))
        "remote.viewer_patch", "viewer_patch" -> ViewerPatch(body)
        "remote.launch", "launch" -> Launch(body.optString("pkg", ""))
        "remote.url", "url" -> OpenUrl(body.optString("u", body.optString("url", "")))
        "remote.ask", "ask" -> Ask(body.optString("q", body.optString("query", "")))
        "remote.volume", "volume" -> VolumeDelta(body.optInt("d", body.optInt("delta", 0)))
        "remote.volume_set", "volume_set" -> VolumeSet(
            if (body.has("percent")) body.optInt("percent") else body.optInt("v", 0)
        )
        "remote.text", "text" -> TextInput(body.optString("s", body.optString("text", "")))
        "remote.power", "power" -> Power
        "remote.sleep", "sleep" -> Sleep(body.optInt("m", body.optInt("minutes", 0)))
        "remote.notif", "notif" -> PhoneNotification(
            app = body.optString("app", ""),
            title = body.optString("title", ""),
            text = body.optString("text", ""),
        )
        "remote.media", "media" -> PhoneMedia(body.optBoolean("stopped", false), body)
        "remote.send_log", "send_log" -> SendLog
        "session.load" -> SessionLoad(
            channelId = body.optString("channelId", ""),
            url = body.optString("url", ""),
            name = body.optString("name", ""),
            startAt = body.optLong("startAt", 0L),
        )
        "session.play" -> SessionControl("play")
        "session.pause" -> SessionControl("pause")
        "session.stop" -> SessionControl("stop")
        "session.control" -> SessionControl(body.optString("action", ""))
        "session.state" -> parseSession(body)
        "device.state" -> DeviceState(
            volume = body.optInt("volume", 0),
            muted = body.optBoolean("muted", false),
            sleep = body.optInt("sleep", 0),
            session = body.optJSONObject("session")?.let(::parseSession),
        )
        else -> Unknown(type, body)
    }

    private fun decodeLegacy(t: String, o: JSONObject): Message = when (t) {
        "state" -> {
            val hasSession = o.has("channel") || o.has("channelId") || o.has("playing")
            val session = if (!hasSession) null else SessionState(
                rev = o.optLong("rev", 0L),
                channelId = o.optString("channelId", ""),
                channel = o.optString("channel", ""),
                number = o.optInt("number", 0),
                playing = o.optBoolean("playing", o.optString("channel", "").isNotEmpty()),
                paused = o.optBoolean("paused", false),
            )
            DeviceState(
                volume = o.optInt("volume", 0),
                muted = o.optBoolean("muted", false),
                sleep = o.optInt("sleep", 0),
                session = session,
            )
        }
        else -> decodeBody(t, o)
    }

    private fun parseSession(o: JSONObject): SessionState = SessionState(
        rev = o.optLong("rev", 0L),
        channelId = o.optString("channelId", ""),
        channel = o.optString("channel", ""),
        number = o.optInt("number", 0),
        playing = o.optBoolean("playing", false),
        paused = o.optBoolean("paused", false),
    )

    private fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return List(arr.length()) { arr.optString(it) }.filter { it.isNotEmpty() }
    }
}
