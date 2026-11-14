package tech.kousozo.tahoetv.core

import org.json.JSONArray
import org.json.JSONObject

/** Persistent store for multiple paired Tahoe receivers (TV / desktop) on the companion app (#37, #88). */
object PairedDevices {
    data class Device(
        val id: String,
        val host: String,
        val token: String,
        val name: String,
        val role: String = "owner",
        val lastSeen: Long = 0L,
    )

    data class State(
        val devices: List<Device> = emptyList(),
        val activeId: String = "",
    ) {
        fun active(): Device? =
            devices.firstOrNull { it.id == activeId || it.host == activeId } ?: devices.firstOrNull()

        fun upsert(device: Device): State {
            val key = device.id.ifBlank { device.host }
            val next = devices.filterNot {
                (device.id.isNotBlank() && it.id == device.id) ||
                    it.host == device.host ||
                    (device.token.isNotBlank() && it.token == device.token)
            } + device.copy(id = key)
            return State(devices = next, activeId = key)
        }

        fun select(idOrHost: String): State {
            val match = devices.firstOrNull { it.id == idOrHost || it.host == idOrHost } ?: return this
            return copy(activeId = match.id.ifBlank { match.host })
        }

        fun rename(idOrHost: String, newName: String): State {
            val clean = newName.trim()
            if (clean.isEmpty()) return this
            return copy(devices = devices.map {
                if (it.id == idOrHost || it.host == idOrHost) it.copy(name = clean) else it
            })
        }

        fun remove(idOrHost: String): State {
            val next = devices.filterNot { it.id == idOrHost || it.host == idOrHost }
            val activeStillExists = next.any { it.id == activeId || it.host == activeId }
            val nextActive = if (activeStillExists) activeId else next.firstOrNull()?.let { it.id.ifBlank { it.host } } ?: ""
            return State(devices = next, activeId = nextActive)
        }

        fun updateHost(id: String, newHost: String, seenAt: Long = System.currentTimeMillis()): State {
            if (id.isBlank() || newHost.isBlank()) return this
            var changed = false
            val next = devices.map { d ->
                if (d.id == id && (d.host != newHost || seenAt > d.lastSeen)) {
                    changed = true
                    d.copy(host = newHost, lastSeen = if (seenAt > 0L) seenAt else d.lastSeen)
                } else d
            }
            return if (changed) copy(devices = next) else this
        }

        fun toJson(): String = JSONObject().apply {
            put("activeId", activeId)
            put("devices", JSONArray().apply {
                devices.forEach { d ->
                    put(JSONObject().apply {
                        put("id", d.id)
                        put("host", d.host)
                        put("token", d.token)
                        put("name", d.name)
                        put("role", d.role)
                        put("lastSeen", d.lastSeen)
                    })
                }
            })
        }.toString()
    }

    @JvmStatic @JvmOverloads
    fun fromJson(raw: String?, legacyHost: String = "", legacyToken: String = ""): State {
        val parsed = runCatching {
            if (raw.isNullOrBlank()) return@runCatching null
            val o = JSONObject(raw)
            val arr = o.optJSONArray("devices") ?: JSONArray()
            val list = buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val host = item.optString("host").trim()
                    val token = item.optString("token").trim()
                    if (host.isEmpty() || token.isEmpty()) continue
                    val id = item.optString("id").ifBlank { host }
                    add(Device(
                        id = id,
                        host = host,
                        token = token,
                        name = item.optString("name").ifBlank { "Tahoe TV" },
                        role = item.optString("role").ifBlank { "owner" },
                        lastSeen = item.optLong("lastSeen", 0L),
                    ))
                }
            }
            val active = o.optString("activeId")
            State(devices = list, activeId = if (list.any { it.id == active || it.host == active }) active else list.firstOrNull()?.id.orEmpty())
        }.getOrNull()
        if (parsed != null && parsed.devices.isNotEmpty()) return parsed
        val h = legacyHost.trim()
        return if (h.isNotEmpty() && legacyToken.isNotBlank()) {
            val d = Device(id = h, host = h, token = legacyToken, name = "Tahoe TV", role = "owner")
            State(devices = listOf(d), activeId = h)
        } else {
            State()
        }
    }
}
