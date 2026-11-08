package tech.kousozo.tahoetv.core

import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Finds TVs on the phone's Wi-Fi: every host in the /24 is probed on 5555 (network ADB, needed for setup) and
 *  8765 (Tahoe already installed, confirmed via its public /info). ~254 hosts × 2 ports, 48 at a time, ~3 s. */
object Scan {
    data class Found(val ip: String, val name: String?, val version: String?, val adb: Boolean, val tahoe: Boolean) {
        val label get() = (if (name.isNullOrBlank() || name == ip) "Perangkat" else name.replace(Regex("\\.(lan|local|home)$"), "")) + " · " + ip
        val status get() = when {
            tahoe && adb -> "Tahoe $version · siap dikelola"
            tahoe -> "Tahoe $version · nyalakan debugging untuk mengelola"
            else -> "Debugging aktif · siap dipasang"
        }
    }

    /** This machine's LAN IPv4 (first non-loopback, site-local), e.g. "192.168.18.20". Android passes its Wi-Fi address instead. */
    fun localAddress(): String? = java.net.NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress

    suspend fun run(me: String?, found: (Found) -> Unit) = withContext(Dispatchers.IO) {
        me ?: return@withContext
        val base = me.substringBeforeLast('.') + "."
        val gate = Semaphore(48)
        coroutineScope {
            (1..254).map { base + it }.filter { it != me }.map { ip ->
                async {
                    gate.withPermit {
                        val adb = open(ip, 5555)
                        val info = if (open(ip, 8765)) info(ip) else null
                        if (adb || info != null) {
                            val name = info?.optString("model") ?: runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrNull()
                            found(Found(ip, name, info?.optString("version"), adb, info != null))
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private fun info(ip: String): JSONObject? = runCatching {
        val c = URL("http://$ip:8765/info").openConnection() as HttpURLConnection
        c.connectTimeout = 800; c.readTimeout = 1500
        JSONObject(c.inputStream.bufferedReader().readText()).takeIf { it.optString("app") == "tahoe" }
    }.getOrNull()

    private fun open(ip: String, port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress(ip, port), 400) } }.isSuccess
}
