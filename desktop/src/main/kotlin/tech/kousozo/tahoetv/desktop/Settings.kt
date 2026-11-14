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
    private var demoViewer = mutableStateOf<tech.kousozo.tahoetv.core.Viewer?>(null)

    var name
        get() = if (tech.kousozo.tahoetv.core.Demo.isActive()) tech.kousozo.tahoetv.core.Demo.profileName(viewer.language) else pref("name").state.value
        set(v) { if (!tech.kousozo.tahoetv.core.Demo.isActive()) pref("name").set(v) }
    /** Onboarding choices (#77): language, country, city, time zone, prayer, channel countries, categories, favourites.
     *  Blank country = onboarding not done yet. Saving also updates the older single settings (city, favourites…). */
    var viewer: tech.kousozo.tahoetv.core.Viewer
        get() = if (tech.kousozo.tahoetv.core.Demo.isActive()) (demoViewer.value ?: tech.kousozo.tahoetv.core.Demo.viewer())
            else pref("viewer").state.value.takeIf { it.isNotBlank() }?.let { tech.kousozo.tahoetv.core.Viewer.fromJson(it) }
            ?: pref("country").state.value.let { old -> // from the earlier two-step setup
                tech.kousozo.tahoetv.core.Region.country(old)?.let {
                    // keep what the older settings already had: city, coordinates, province, favourites
                    tech.kousozo.tahoetv.core.Viewer.suggested(it, tech.kousozo.tahoetv.core.Region.language(pref("language").state.value))
                        .copy(city = city, lat = lat, lon = lon, region = province, favorites = pref("fav").state.value.split('\n').filter { f -> f.isNotBlank() })
                }
                    ?: tech.kousozo.tahoetv.core.Viewer(language = tech.kousozo.tahoetv.core.Region.language(java.util.Locale.getDefault().language))
            }
        set(v) {
            if (tech.kousozo.tahoetv.core.Demo.isActive()) {
                demoViewer.value = v
                return
            }
            pref("viewer").set(v.toJson()); pref("country").set(v.country); pref("language").set(v.language)
            val la = v.lat; val lo = v.lon
            if (la != null && lo != null) { city = v.city; lat = la; lon = lo; province = v.region }
            pref("fav").set(v.favorites.joinToString("\n"))
        }
    val country get() = viewer.country
    /** The home country: the chosen one, or Indonesia until it's chosen. */
    val countryCode get() = tech.kousozo.tahoetv.core.Region.country(country) ?: tech.kousozo.tahoetv.core.Region.HOME
    val lang get() = viewer.language
    /** Province for "near you": only Indonesia's channel lists are tagged by province. */
    val nearProvince get() = viewer.nearRegion ?: (if (!viewer.done) province else "")
    /** Live TV: highest video quality, and distance from live (shared options with the TV and phone). */
    var quality get() = tech.kousozo.tahoetv.core.PlayPrefs.Quality.of(pref("quality", "auto").state.value); set(v) = pref("quality").set(v.key)
    var latency get() = tech.kousozo.tahoetv.core.PlayPrefs.Latency.of(pref("latency", "standard").state.value); set(v) = pref("latency").set(v.key)
    var city
        get() = if (tech.kousozo.tahoetv.core.Demo.isActive()) viewer.city else pref("city", "").state.value
        set(v) { if (!tech.kousozo.tahoetv.core.Demo.isActive()) pref("city").set(v) }
    var province get() = pref("province", "").state.value; set(v) = pref("province").set(v)
    var lat get() = pref("lat", "").state.value.toDoubleOrNull() ?: 0.0; set(v) = pref("lat").set("$v")
    var lon get() = pref("lon", "").state.value.toDoubleOrNull() ?: 0.0; set(v) = pref("lon").set("$v")
    var gemini get() = pref("gemini").state.value; set(v) = pref("gemini").set(v)
    var geminiModel get() = pref("gemini_model", "gemini-3.5-flash-lite").state.value; set(v) = pref("gemini_model").set(v)
    var youtube get() = pref("youtube").state.value; set(v) = pref("youtube").set(v)
    var tmdb get() = pref("tmdb").state.value; set(v) = pref("tmdb").set(v)
    /** Blank = built-in sources (iptv-org + dhanytv free TV); the old single default counts as blank. */
    var playlist get() = pref("playlist").state.value.takeUnless { it == tech.kousozo.tahoetv.core.M3u.DEFAULT }.orEmpty(); set(v) = pref("playlist").set(v)
    var epg get() = pref("epg").state.value.takeUnless { it == tech.kousozo.tahoetv.core.Epg.DEFAULT }.orEmpty(); set(v) = pref("epg").set(v)
    var tvHost get() = pref("tv_host").state.value; set(v) = pref("tv_host").set(v)
    var favorites: Set<String>
        get() = if (tech.kousozo.tahoetv.core.Demo.isActive()) viewer.favorites.toSet() else pref("fav").state.value.split('\n').filter { it.isNotBlank() }.toSet()
        set(v) {
            if (tech.kousozo.tahoetv.core.Demo.isActive()) {
                val vw = viewer; val kept = vw.favorites.filter { it in v }
                demoViewer.value = vw.copy(favorites = kept + (v - kept.toSet()))
                return
            }
            pref("fav").set(v.joinToString("\n"))
            // keep the onboarding's order; new ones last
            val vw = viewer; val kept = vw.favorites.filter { it in v }
            pref("viewer").set(vw.copy(favorites = kept + (v - kept.toSet())).toJson())
        }

    var iptvPort get() = pref("iptv_port", "8088").state.value.toIntOrNull() ?: 8088; set(v) = pref("iptv_port").set("$v")
    var iptvAutoStart get() = pref("iptv_autostart", "false").state.value == "true"; set(v) = pref("iptv_autostart").set("$v")

    /** ADB key of this computer (the TV remembers it after "Always allow"). */
    val adbDir = File(System.getProperty("user.home"), ".tahoetv/adb")
}
