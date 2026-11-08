package tech.kousozo.tahoetv.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.prefs.Preferences

/** User settings in the OS preference store (macOS plist / Windows registry), observable by Compose. */
object Settings {
    private val p = Preferences.userRoot().node("tech/kousozo/tahoetv")

    private class Pref(private val key: String, default: String) {
        var state = mutableStateOf(p.get(key, default))
        fun set(v: String) { state.value = v; p.put(key, v) }
    }
    private val prefs = mutableMapOf<String, Pref>()
    private fun pref(key: String, default: String = "") = prefs.getOrPut(key) { Pref(key, default) }

    var name get() = pref("name").state.value; set(v) = pref("name").set(v)
    var city get() = pref("city", "Samarinda").state.value; set(v) = pref("city").set(v)
    var province get() = pref("province", "Kalimantan Timur").state.value; set(v) = pref("province").set(v)
    var lat get() = pref("lat", "-0.5022").state.value.toDoubleOrNull() ?: -0.5022; set(v) = pref("lat").set("$v")
    var lon get() = pref("lon", "117.1536").state.value.toDoubleOrNull() ?: 117.1536; set(v) = pref("lon").set("$v")
    var gemini get() = pref("gemini").state.value; set(v) = pref("gemini").set(v)
    var geminiModel get() = pref("gemini_model", "gemini-3.5-flash-lite").state.value; set(v) = pref("gemini_model").set(v)
    var youtube get() = pref("youtube").state.value; set(v) = pref("youtube").set(v)
    var tmdb get() = pref("tmdb").state.value; set(v) = pref("tmdb").set(v)
    var playlist get() = pref("playlist", tech.kousozo.tahoetv.core.M3u.DEFAULT).state.value; set(v) = pref("playlist").set(v)
    var epg get() = pref("epg", tech.kousozo.tahoetv.core.Epg.DEFAULT).state.value; set(v) = pref("epg").set(v)
    var tvHost get() = pref("tv_host").state.value; set(v) = pref("tv_host").set(v)
    var favorites: Set<String>
        get() = pref("fav").state.value.split('\n').filter { it.isNotBlank() }.toSet()
        set(v) = pref("fav").set(v.joinToString("\n"))

    /** ADB key of this computer (the TV remembers it after "Always allow"). */
    val adbDir = File(System.getProperty("user.home"), ".tahoetv/adb")
}
