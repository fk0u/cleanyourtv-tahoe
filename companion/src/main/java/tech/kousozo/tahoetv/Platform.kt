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
}.onFailure { tech.kousozo.tahoetv.core.DiagLog.error("platform", "wifiAddress", it) }.getOrNull()

/** The Android TV Launcher APK shipped in assets (for Android TV only), copied to a file dadb can push. */
fun bundledLauncher(c: Context): File = File(c.cacheDir, "TahoeTV-Android-TV.apk").also { f ->
    val assetName = listOf("TahoeTV-Android-TV.apk", "TahoeTV-App-for-TV-and-Phone.apk", "tahoe-launcher.apk").firstOrNull { name ->
        runCatching { c.assets.open(name).close(); true }.getOrDefault(false)
    } ?: "TahoeTV-Android-TV.apk"
    c.assets.open(assetName).use { input -> f.outputStream().use { input.copyTo(it) } }
}
