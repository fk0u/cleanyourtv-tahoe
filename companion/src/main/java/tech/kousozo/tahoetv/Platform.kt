package tech.kousozo.tahoetv

import android.content.Context
import android.net.ConnectivityManager
import java.io.File
import java.net.Inet4Address

/** Phone's IPv4 on the active (Wi-Fi) network, e.g. "192.168.18.20"; null when offline. */
fun wifiAddress(c: Context): String? = runCatching {
    val cm = c.getSystemService(ConnectivityManager::class.java) ?: return@runCatching null
    val lp = cm.activeNetwork?.let { cm.getLinkProperties(it) } ?: return@runCatching null
    lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
}.getOrNull()

/** The launcher APK shipped in assets, copied to a file dadb can push. */
fun bundledLauncher(c: Context): File = File(c.cacheDir, "tahoe-launcher.apk").also { f ->
    c.assets.open("tahoe-launcher.apk").use { input -> f.outputStream().use { input.copyTo(it) } }
}
