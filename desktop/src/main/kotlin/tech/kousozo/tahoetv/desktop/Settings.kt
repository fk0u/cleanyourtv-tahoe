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
    /** Region (#71): ISO country ("ID") and app language ("id"/"en"). Blank country = first-run setup not done yet. */
    var country get() = pref("country").state.value; set(v) = pref("country").set(v)
    var language get() = pref("language").state.value; set(v) = pref("language").set(v)
    /** The country to load channels for: the chosen one, or Indonesia until it's chosen. */
    val countryCode get() = tech.kousozo.tahoetv.core.Region.country(country) ?: tech.kousozo.tahoetv.core.Region.HOME
    val lang get() = tech.kousozo.tahoetv.core.Region.language(language.ifBlank { null } ?: java.util.Locale.getDefault().language)
    /** Province for "near you": only Indonesia's channel lists are tagged by province. */
    val nearProvince get() = if (countryCode == tech.kousozo.tahoetv.core.Region.HOME) province else ""
    /** Live TV: highest video quality, and distance from live (shared options with the TV and phone). */
    var quality get() = tech.kousozo.tahoetv.core.PlayPrefs.Quality.of(pref("quality", "auto").state.value); set(v) = pref("quality").set(v.key)
    var latency get() = tech.kousozo.tahoetv.core.PlayPrefs.Latency.of(pref("latency", "standard").state.value); set(v) = pref("latency").set(v.key)
    var city get() = pref("city", "Samarinda").state.value; set(v) = pref("city").set(v)
    var province get() = pref("province", "Kalimantan Timur").state.value; set(v) = pref("province").set(v)
    var lat get() = pref("lat", "-0.5022").state.value.toDoubleOrNull() ?: -0.5022; set(v) = pref("lat").set("$v")
    var lon get() = pref("lon", "117.1536").state.value.toDoubleOrNull() ?: 117.1536; set(v) = pref("lon").set("$v")
    var gemini get() = pref("gemini").state.value; set(v) = pref("gemini").set(v)
    var geminiModel get() = pref("gemini_model", "gemini-3.5-flash-lite").state.value; set(v) = pref("gemini_model").set(v)
    var youtube get() = pref("youtube").state.value; set(v) = pref("youtube").set(v)
    var tmdb get() = pref("tmdb").state.value; set(v) = pref("tmdb").set(v)
    /** Blank = built-in sources (iptv-org + dhanytv free TV); the old single default counts as blank. */
    var playlist get() = pref("playlist").state.value.takeUnless { it == tech.kousozo.tahoetv.core.M3u.DEFAULT }.orEmpty(); set(v) = pref("playlist").set(v)
    var epg get() = pref("epg").state.value.takeUnless { it == tech.kousozo.tahoetv.core.Epg.DEFAULT }.orEmpty(); set(v) = pref("epg").set(v)
    var tvHost get() = pref("tv_host").state.value; set(v) = pref("tv_host").set(v)
    var favorites: Set<String>
        get() = pref("fav").state.value.split('\n').filter { it.isNotBlank() }.toSet()
        set(v) = pref("fav").set(v.joinToString("\n"))

    var iptvPort get() = pref("iptv_port", "8088").state.value.toIntOrNull() ?: 8088; set(v) = pref("iptv_port").set("$v")
    var iptvAutoStart get() = pref("iptv_autostart", "false").state.value == "true"; set(v) = pref("iptv_autostart").set("$v")

    /** ADB key of this computer (the TV remembers it after "Always allow"). */
    val adbDir = File(System.getProperty("user.home"), ".tahoetv/adb")
}
