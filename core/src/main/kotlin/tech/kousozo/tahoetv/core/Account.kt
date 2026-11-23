package tech.kousozo.tahoetv.core

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import org.json.JSONObject

/** The display choices a viewer syncs across their phone, TV and desktop (profile "settings" on the server).
 *  Missing fields keep their default so older apps' data still loads; unknown fields are kept untouched in [extra]. */
data class DisplayProfile @JvmOverloads constructor(
    val displayName: String = "",
    /** Uploaded file ids on the account (see [AccountApi.uploadFile]); blank = none. */
    val avatarId: String = "",
    val wallpaperId: String = "",
    /** 0..80: how much black lies over the wallpaper so text stays readable. */
    val wallpaperDim: Int = 35,
    /** system | light | dark. */
    val theme: String = "system",
    /** Accent colour "#RRGGBB"; blank = the Tahoe blue. */
    val accent: String = "",
    val clock24: Boolean = true,
    val clockSeconds: Boolean = false,
    /** s | m | l */
    val clockSize: String = "m",
    /** 0.85..1.30 */
    val textScale: Float = 1f,
    val reduceMotion: Boolean = false,
    /** comfortable | compact */
    val density: String = "comfortable",
    val greeting: Boolean = true,
    val extra: JSONObject = JSONObject(),
) {
    fun toJson(): JSONObject = JSONObject(extra.toString()).apply {
        put("displayName", displayName); put("avatarId", avatarId); put("wallpaperId", wallpaperId)
        put("wallpaperDim", wallpaperDim); put("theme", theme); put("accent", accent)
        put("clock24", clock24); put("clockSeconds", clockSeconds); put("clockSize", clockSize)
        put("textScale", textScale.toDouble()); put("reduceMotion", reduceMotion); put("density", density); put("greeting", greeting)
    }

    companion object {
        private val KNOWN = setOf("displayName", "avatarId", "wallpaperId", "wallpaperDim", "theme", "accent", "clock24", "clockSeconds",
            "clockSize", "textScale", "reduceMotion", "density", "greeting")
        private val HEX = Regex("^#[0-9A-Fa-f]{6}$")

        @JvmStatic fun fromJson(o: JSONObject?): DisplayProfile {
            if (o == null) return DisplayProfile()
            val d = DisplayProfile()
            val extra = JSONObject(); for (k in o.keys()) if (k !in KNOWN) extra.put(k, o.get(k))
            return DisplayProfile(
                displayName = o.optString("displayName", d.displayName).take(40),
                avatarId = o.optString("avatarId", ""), wallpaperId = o.optString("wallpaperId", ""),
                wallpaperDim = o.optInt("wallpaperDim", d.wallpaperDim).coerceIn(0, 80),
                theme = o.optString("theme", d.theme).takeIf { it in setOf("system", "light", "dark") } ?: d.theme,
                accent = o.optString("accent", "").takeIf { HEX.matches(it) } ?: "",
                clock24 = o.optBoolean("clock24", d.clock24), clockSeconds = o.optBoolean("clockSeconds", d.clockSeconds),
                clockSize = o.optString("clockSize", d.clockSize).takeIf { it in setOf("s", "m", "l") } ?: d.clockSize,
                textScale = o.optDouble("textScale", 1.0).toFloat().coerceIn(0.85f, 1.3f),
                reduceMotion = o.optBoolean("reduceMotion", d.reduceMotion),
                density = o.optString("density", d.density).takeIf { it in setOf("comfortable", "compact") } ?: d.density,
                greeting = o.optBoolean("greeting", d.greeting), extra = extra,
            )
        }
    }
}

class AccountException(val status: Int, message: String) : Exception(message)

/** Talks to the private Tahoe server's account routes (under /api/account). The server address and any invite code come from the build
 *  or the device's settings, never from the repository; [DiagLog.redact] keeps them out of logs. Blocking: call off the UI thread. */
class AccountApi(baseUrl: String, private val token: String? = null, private val device: String = "") {
    private val base = baseUrl.trim().trimEnd('/')

    init {
        val u = runCatching { URI(base) }.getOrNull()
        val local = u?.host == "localhost" || u?.host == "127.0.0.1" || u?.host == "10.0.2.2"
        require(u != null && (u.scheme == "https" || (u.scheme == "http" && local))) { "The account server must use https" }
    }

    class Session(val token: String, val userId: String, val email: String)
    class FileInfo(val id: String, val kind: String, val name: String, val mime: String, val size: Long)
    class Profile(val rev: Int, val settings: DisplayProfile)

    private fun call(method: String, path: String, body: ByteArray? = null, type: String = "application/json"): Pair<Int, ByteArray> {
        val c = URI(base + path).toURL().openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 10_000; c.readTimeout = 30_000
            c.setRequestProperty("Accept", "application/json, image/*")
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", type); c.outputStream.use { it.write(body) } }
            val code = c.responseCode
            val bytes = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            return code to bytes
        } finally { c.disconnect() }
    }

    private fun json(method: String, path: String, body: JSONObject? = null): JSONObject {
        val (code, bytes) = call(method, path, body?.toString()?.toByteArray())
        val o = runCatching { JSONObject(String(bytes)) }.getOrDefault(JSONObject())
        if (code !in 200..299 && code != 409) throw AccountException(code, o.optString("error", "Server error ($code)"))
        return o.put("_status", code)
    }

    private fun session(o: JSONObject) = Session(o.getString("token"), o.getJSONObject("user").getString("id"), o.getJSONObject("user").getString("email"))

    fun register(email: String, password: String, invite: String = ""): Session =
        session(json("POST", "/api/account/register", JSONObject().put("email", email).put("password", password).put("invite", invite).put("device", device)))

    fun login(email: String, password: String): Session =
        session(json("POST", "/api/account/login", JSONObject().put("email", email).put("password", password).put("device", device)))

    fun logout() { json("POST", "/api/account/logout") }
    fun me(): String = json("GET", "/api/account/me").getJSONObject("user").getString("email")
    fun changePassword(old: String, new: String) { json("POST", "/api/account/password", JSONObject().put("old", old).put("new", new)) }
    fun deleteAccount(password: String) { json("POST", "/api/account/delete", JSONObject().put("password", password)) }

    fun profile(): Profile = json("GET", "/api/account/profile").let { Profile(it.optInt("rev"), DisplayProfile.fromJson(it.optJSONObject("settings"))) }

    /** Saves [p]; returns the new revision, or null when another device saved first (call [profile] and merge). */
    fun saveProfile(p: DisplayProfile, baseRev: Int): Int? {
        val o = json("PUT", "/api/account/profile", JSONObject().put("settings", p.toJson()).put("base_rev", baseRev))
        return if (o.optInt("_status") == 409) null else o.getInt("rev")
    }

    fun files(): List<FileInfo> = json("GET", "/api/account/files").getJSONArray("files").let { a ->
        (0 until a.length()).map { val f = a.getJSONObject(it); FileInfo(f.getString("id"), f.getString("kind"), f.getString("name"), f.getString("mime"), f.getLong("size")) }
    }

    /** [kind] is "avatar" (≤ 2 MB) or "wallpaper" (≤ 8 MB); JPEG, PNG or WebP. */
    fun uploadFile(kind: String, bytes: ByteArray, name: String = kind): FileInfo {
        val (code, body) = call("POST", "/api/account/files?kind=${enc(kind)}&name=${enc(name)}", bytes, "application/octet-stream")
        val o = runCatching { JSONObject(String(body)) }.getOrDefault(JSONObject())
        if (code != 201) throw AccountException(code, o.optString("error", "Upload failed ($code)"))
        return FileInfo(o.getString("id"), o.getString("kind"), o.getString("name"), o.getString("mime"), o.getLong("size"))
    }

    fun downloadFile(id: String): ByteArray {
        require(Regex("^[0-9a-f-]{36}$").matches(id)) { "bad file id" }
        val (code, bytes) = call("GET", "/api/account/files/$id")
        if (code != 200) throw AccountException(code, "File not available ($code)")
        return bytes
    }

    fun deleteFile(id: String) { require(Regex("^[0-9a-f-]{36}$").matches(id)); json("DELETE", "/api/account/files/$id") }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
