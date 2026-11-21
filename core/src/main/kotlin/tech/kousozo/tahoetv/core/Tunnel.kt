package tech.kousozo.tahoetv.core

import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** End-to-end encryption for the off-LAN tunnel. Both sides derive two unrelated values from the pairing token:
 *  a room name (what the relay sees, so it can match the two sides) and an AES-256 key (which the relay never learns).
 *  Frames are `base64url(iv ‖ ciphertext+tag)`, with the sender's role as authenticated data so a frame can't be bounced back. */
object TunnelCrypto {
    private val rnd = SecureRandom()
    private fun sha(label: String, token: String) = MessageDigest.getInstance("SHA-256").digest("$label:$token".toByteArray())

    fun room(token: String): String = sha("tahoe-tunnel-room-v1", token).joinToString("") { "%02x".format(it) }
    private fun key(token: String) = SecretKeySpec(sha("tahoe-tunnel-key-v1", token), "AES")

    fun seal(token: String, senderRole: String, plain: String): String {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key(token), GCMParameterSpec(128, iv)); updateAAD(senderRole.toByteArray())
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(iv + c.doFinal(plain.toByteArray()))
    }

    /** The plain text, or null when the frame was not sealed with this token by [senderRole] (tampered, wrong key, reflected). */
    fun open(token: String, senderRole: String, frame: String): String? = runCatching {
        val raw = Base64.getUrlDecoder().decode(frame.trim())
        if (raw.size < 12 + 16) return null
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(token), GCMParameterSpec(128, raw, 0, 12)); updateAAD(senderRole.toByteArray())
        }
        String(c.doFinal(raw, 12, raw.size - 12))
    }.getOrNull()
}

/** One side of the tunnel: listens on the relay's event stream (reconnecting by itself) and posts sealed frames to it.
 *  [role] is "tv" or "ctl". Replayed frames are dropped. */
class TunnelClient(
    private val baseUrl: String,
    private val token: String,
    private val role: String,
    private val onFrame: (String) -> Unit,
    private val onPeer: () -> Unit = {},
    private val onState: (Boolean) -> Unit = {},
) {
    private val peerRole = if (role == "tv") "ctl" else "tv"
    private val room = TunnelCrypto.room(token)
    @Volatile private var wanted = false
    @Volatile private var conn: HttpURLConnection? = null
    @Volatile var connected = false
        private set
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "tahoe-tunnel-send").apply { isDaemon = true } }
    private val seen = object : LinkedHashMap<String, Boolean>(300, 0.75f, false) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Boolean>) = size > 256
    }

    private fun url(path: String) = URI("${baseUrl.trimEnd('/')}/api/tunnel/$path?room=$room&role=$role").toURL()

    fun open() {
        if (wanted) return
        wanted = true
        Thread({
            var backoff = 1500L
            while (wanted) {
                val started = System.currentTimeMillis()
                runCatching { listen() }
                setConnected(false)
                if (!wanted) break
                if (System.currentTimeMillis() - started > 20_000) backoff = 1500L
                runCatching { Thread.sleep(backoff) }
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }, "tahoe-tunnel").apply { isDaemon = true }.start()
    }

    private fun listen() {
        val c = (url("stream").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 70_000; setRequestProperty("Accept", "text/event-stream"); setRequestProperty("User-Agent", "TahoeTV")
        }
        conn = c
        if (c.responseCode != 200) return
        setConnected(true)
        var event = "message"
        c.inputStream.bufferedReader().use { r ->
            while (wanted) {
                val line = r.readLine() ?: break
                when {
                    line.startsWith("event:") -> event = line.substring(6).trim()
                    line.startsWith("data:") -> {
                        val data = line.substring(5).trim()
                        if (event == "peer") runCatching { onPeer() }
                        else if (event == "message") accept(data)
                    }
                    line.isEmpty() -> event = "message"
                }
            }
        }
    }

    private fun accept(frame: String) {
        val fresh = synchronized(seen) { seen.put(frame, true) == null }
        if (!fresh) return
        TunnelCrypto.open(token, peerRole, frame)?.let { runCatching { onFrame(it) } }
    }

    private fun setConnected(v: Boolean) { if (connected != v) { connected = v; runCatching { onState(v) } } }

    /** Seals [plain] and posts it to the relay in the background (the other side may still be offline: the relay keeps nothing). */
    fun send(plain: String) {
        sender.execute {
            runCatching {
                val c = (url("send").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 8000; readTimeout = 8000; setRequestProperty("User-Agent", "TahoeTV")
                }
                c.outputStream.use { it.write(TunnelCrypto.seal(token, role, plain).toByteArray()) }
                c.responseCode; c.disconnect()
            }
        }
    }

    fun close() { wanted = false; setConnected(false); runCatching { conn?.disconnect() }; sender.shutdown() }
}
