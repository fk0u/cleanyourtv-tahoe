package tech.kousozo.tahoetv

import android.content.Context
import android.net.ConnectivityManager
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

    /** Phone's IPv4 on the active network, e.g. "192.168.18.20". */
    fun localAddress(c: Context): String? {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val lp = cm.activeNetwork?.let { cm.getLinkProperties(it) } ?: return null
        return lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    suspend fun run(c: Context, found: (Found) -> Unit) = withContext(Dispatchers.IO) {
        val me = localAddress(c) ?: return@withContext
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
