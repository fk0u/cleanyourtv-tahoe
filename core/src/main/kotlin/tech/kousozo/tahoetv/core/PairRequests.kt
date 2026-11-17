package tech.kousozo.tahoetv.core

import java.security.SecureRandom

/**
 * Code-free pairing on the same Wi-Fi (#180). A phone on the TV's local network asks to connect; the TV shows the
 * request on screen and the viewer approves it once with OK. Only then does the phone get a token, so nobody on the
 * network can take control silently. The TV keeps the requests here (pure logic, unit-tested).
 *
 * Limits: a request lives [ttlMs] (60 s); one open request per address; at most [maxOpen] open at once.
 */
class PairRequests(private val ttlMs: Long = 60_000, private val maxOpen: Int = 3, private val rnd: SecureRandom = SecureRandom()) {
    sealed class Status {
        object Pending : Status()
        data class Approved(val token: String) : Status()
        object Denied : Status()
        object Expired : Status()
    }

    data class Request(val id: String, val ip: String, val deviceName: String, val createdAt: Long)

    private class Entry(val req: Request, var status: Status)
    private val entries = LinkedHashMap<String, Entry>()

    /** A new request from [ip], or null when this address already waits or too many are open. */
    @Synchronized fun request(ip: String, deviceName: String, now: Long = System.currentTimeMillis()): Request? {
        sweep(now)
        val open = entries.values.filter { it.status == Status.Pending }
        if (open.size >= maxOpen || open.any { it.req.ip == ip }) return null
        val id = ByteArray(9).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val req = Request(id, ip, deviceName.trim().take(40).ifBlank { "HP" }, now)
        entries[id] = Entry(req, Status.Pending)
        return req
    }

    @Synchronized fun approve(id: String, token: String, now: Long = System.currentTimeMillis()): Boolean {
        sweep(now)
        val e = entries[id] ?: return false
        if (e.status != Status.Pending) return false
        e.status = Status.Approved(token); return true
    }

    @Synchronized fun deny(id: String): Boolean {
        val e = entries[id] ?: return false
        if (e.status != Status.Pending) return false
        e.status = Status.Denied; return true
    }

    /** Status for the phone polling [id]. An approved token is handed out once, then the entry is gone. */
    @Synchronized fun status(id: String, now: Long = System.currentTimeMillis()): Status {
        val e = entries[id] ?: return Status.Expired
        if (e.status == Status.Pending && now - e.req.createdAt > ttlMs) { entries.remove(id); return Status.Expired }
        if (e.status is Status.Approved || e.status == Status.Denied) entries.remove(id)
        return e.status
    }

    @Synchronized fun pending(id: String): Request? = entries[id]?.takeIf { it.status == Status.Pending }?.req

    private fun sweep(now: Long) { entries.values.removeAll { now - it.req.createdAt > ttlMs * 2 } }

    companion object {
        /** Only addresses on the local network may ask (private IPv4 ranges, IPv6 link/site-local, loopback). */
        @JvmStatic fun isLocal(ip: String?): Boolean {
            val a = ip?.trim()?.removePrefix("::ffff:") ?: return false
            if (a == "127.0.0.1" || a == "::1") return true
            val p = a.split('.')
            if (p.size == 4) {
                val o = p.map { it.toIntOrNull() ?: return false }
                return o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168) || (o[0] == 169 && o[1] == 254)
            }
            val l = a.lowercase()
            return l.startsWith("fe80:") || l.startsWith("fc") || l.startsWith("fd")
        }
    }
}
