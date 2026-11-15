package tech.kousozo.tahoetv.core

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** DRM and manifest helpers shared across `:core`, `:launcher`, `:companion`, and `:desktop`. */
object Drm {
    enum class Scheme { NONE, CLEARKEY, WIDEVINE, PLAYREADY }

    private val HEX_32 = Regex("^[0-9a-fA-F]{32}$")
    private val NON_HEX = Regex("[^0-9a-fA-F]")

    /** True when [url] (before `?` or `|`) points to an MPEG-DASH MPD manifest or [manifestType] declares DASH. */
    @JvmStatic
    @JvmOverloads
    fun isDash(url: String, drmType: String? = null, manifestType: String? = null): Boolean {
        val clean = url.substringBefore('|').substringBefore('?').trim()
        if (clean.endsWith(".mpd", ignoreCase = true) || ".mpd?" in url.lowercase()) return true
        val m = manifestType?.trim()?.lowercase().orEmpty()
        if (m == "mpd" || m == "dash") return true
        val d = drmType?.trim()?.lowercase().orEmpty()
        return d == "mpd" || d == "dash"
    }

    /** Detects the DRM scheme from Kodi/IPTV `license_type` and `license_key` values. */
    @JvmStatic
    fun scheme(drmType: String?, drmKey: String?): Scheme {
        val t = drmType?.trim()?.lowercase().orEmpty()
        return when {
            "clearkey" in t -> Scheme.CLEARKEY
            "widevine" in t -> Scheme.WIDEVINE
            "playready" in t -> Scheme.PLAYREADY
            clearKeyMap(drmKey).isNotEmpty() -> Scheme.CLEARKEY
            else -> Scheme.NONE
        }
    }

    /** Extracts a remote `http(s)://` DRM license server URL from [drmKey], or null for inline ClearKey pairs. */
    @JvmStatic
    fun licenseUrl(drmKey: String?): String? {
        val raw = drmKey?.trim()?.substringBefore('|')?.trim().orEmpty()
        return raw.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
    }

    /**
     * Parses ClearKey `kid:key` hex pairs (comma-separated, UUID-hyphen tolerant) or W3C JWK JSON
     * into normalized lowercase 32-hex `kid -> key` entries.
     */
    @JvmStatic
    fun clearKeyMap(drmKey: String?): Map<String, String> {
        val raw = drmKey?.trim().orEmpty()
        if (raw.isEmpty() || licenseUrl(raw) != null) return emptyMap()
        if (raw.startsWith("{")) return parseJwkMap(raw)

        val clean = raw.substringBefore('|').substringBefore('&').trim()
        val out = LinkedHashMap<String, String>()
        for (pair in clean.split(',')) {
            val parts = pair.trim().split(':', limit = 2)
            if (parts.size != 2) continue
            val kid = normalizeHex16(parts[0]) ?: continue
            val key = normalizeHex16(parts[1]) ?: continue
            out[kid] = key
        }
        return out
    }

    /**
     * Builds a W3C ClearKey JWK Set JSON payload suitable for Android Media3 `LocalMediaDrmCallback`:
     * `{"keys":[{"kty":"oct","kid":"...","k":"..."}],"type":"temporary"}`.
     */
    @JvmStatic
    fun toClearKeyJwk(drmKey: String?): String? {
        val map = clearKeyMap(drmKey)
        if (map.isEmpty()) return null
        val keys = JSONArray()
        val enc = Base64.getUrlEncoder().withoutPadding()
        for ((kidHex, keyHex) in map) {
            keys.put(
                JSONObject()
                    .put("kty", "oct")
                    .put("kid", enc.encodeToString(hexToBytes(kidHex)))
                    .put("k", enc.encodeToString(hexToBytes(keyHex)))
            )
        }
        return JSONObject()
            .put("keys", keys)
            .put("type", "temporary")
            .toString()
    }

    /** Decodes a 32-char hex string into 16 raw bytes. */
    @JvmStatic
    fun hexToBytes(hex: String): ByteArray {
        val clean = normalizeHex16(hex) ?: error("Invalid 128-bit hex key")
        return ByteArray(16) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun parseJwkMap(json: String): Map<String, String> = runCatching {
        val obj = JSONObject(json)
        val arr = obj.optJSONArray("keys") ?: return emptyMap()
        val dec = Base64.getUrlDecoder()
        val out = LinkedHashMap<String, String>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val kidB64 = item.optString("kid").trim()
            val keyB64 = item.optString("k").trim()
            if (kidB64.isEmpty() || keyB64.isEmpty()) continue
            val kidBytes = dec.decode(kidB64)
            val keyBytes = dec.decode(keyB64)
            if (kidBytes.size != 16 || keyBytes.size != 16) continue
            out[bytesToHex(kidBytes)] = bytesToHex(keyBytes)
        }
        out
    }.getOrDefault(emptyMap())

    private fun normalizeHex16(s: String): String? {
        val stripped = NON_HEX.replace(s, "").lowercase()
        return stripped.takeIf { HEX_32.matches(it) }
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
