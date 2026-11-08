package tech.kousozo.tahoetv

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Phone-only setup: find the TV on Wi-Fi → connect over network ADB → debloat → personalise → install the bundled
 *  launcher, make it Home, pair this phone → optionally switch debugging off. Also "restore" for a TV running Tahoe.
 *  Lives in a ViewModel so rotating the phone mid-install doesn't lose anything. */
class SetupModel(app: Application) : AndroidViewModel(app) {
    enum class Step { Scan, Connect, Info, Personal, Apply, Done, Restore }

    private val ctx get() = getApplication<Application>()
    private val prefs = app.getSharedPreferences("setup", 0)

    var step by mutableStateOf(Step.Scan); private set
    var host by mutableStateOf(prefs.getString("last_host", "").orEmpty())
    var scanning by mutableStateOf(false); private set
    var noWifi by mutableStateOf(false); private set
    val found = mutableStateListOf<Scan.Found>()
    var error by mutableStateOf<String?>(null); private set

    var tv: Tv? = null; private set
    var version by mutableStateOf<String?>(null); private set
    var installed = emptySet<String>(); private set
    var alreadyOff = emptySet<String>(); private set
    var apps = emptyList<String>(); private set
    val debloat = mutableStateMapOf<String, Boolean>()

    var name by mutableStateOf(prefs.getString("name", "").orEmpty())
    var city by mutableStateOf(prefs.getString("city", "").orEmpty())
    var place by mutableStateOf(prefs.getString("place", "").orEmpty()); private set
    private var lat = prefs.getString("lat", null)?.toDoubleOrNull()
    private var lon = prefs.getString("lon", null)?.toDoubleOrNull()
    var avatar by mutableStateOf(prefs.getString("avatar", "").orEmpty())
    val dock = mutableStateListOf<String>()
    var gemini by mutableStateOf(prefs.getString("gemini", "").orEmpty())
    var youtube by mutableStateOf(prefs.getString("youtube", "").orEmpty())
    var tmdb by mutableStateOf(prefs.getString("tmdb", "").orEmpty())
    var adguard by mutableStateOf(prefs.getBoolean("adguard", false))
    var disableStock by mutableStateOf(true)

    val log = mutableStateListOf<String>()
    var busy by mutableStateOf(false); private set

    init { scan() }

    // ---------------------------------------------------------------- 1. find the TV
    fun scan() {
        if (scanning) return
        step = Step.Scan; found.clear(); noWifi = Scan.localAddress(ctx) == null
        if (noWifi) return
        scanning = true
        viewModelScope.launch {
            Scan.run(ctx) { f -> viewModelScope.launch { found.add(f); found.sortBy { !it.tahoe } } }
            scanning = false
        }
    }

    // ---------------------------------------------------------------- 2. connect (TV shows "Allow USB debugging?")
    fun connect(h: String = host) {
        if (h.isBlank()) return
        host = h.trim(); prefs.edit().putString("last_host", host).apply()
        step = Step.Connect; error = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                tv?.close()
                val t = Tv.connect(ctx, host)
                installed = t.packages(); alreadyOff = t.disabled(); apps = t.tvApps()
                val v = t.installedVersion()
                withContext(Dispatchers.Main) {
                    tv = t; version = v
                    debloat.clear()
                    Tv.CATALOG.filter { it.pkg in installed && !Tv.protectedPkg(it.pkg) && it.pkg !in alreadyOff }
                        .forEach { debloat[it.pkg] = it.default }
                    dock.clear()
                    dock.addAll(prefs.getString("dock", "com.google.android.youtube.tv,com.netflix.ninja,com.spotify.tv.android")!!
                        .split(',').filter { it in apps })
                    step = Step.Info
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { error = e.message ?: e.toString() }
            }
        }
    }

    fun back() { step = when (step) { Step.Personal -> Step.Info; Step.Info, Step.Connect -> Step.Scan; else -> step } }
    fun next() { step = Step.Personal }

    /** Dock choices: apps still enabled after debloat (no point docking PatchWall we're about to switch off). */
    val dockChoices get() = apps.filter { debloat[it] != true && it !in alreadyOff }

    fun toggleDock(pkg: String): Boolean {
        if (pkg in dock) { dock.remove(pkg); return true }
        if (dock.size >= 7) return false
        dock.add(pkg); return true
    }

    fun geocode() {
        val q = city.trim().ifEmpty { return }
        place = "Mencari…"
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching {
                JSONObject(URL("https://geocoding-api.open-meteo.com/v1/search?count=1&language=id&name=" + URLEncoder.encode(q, "UTF-8")).readText())
                    .getJSONArray("results").getJSONObject(0)
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (r == null) { place = "Kota tidak ditemukan"; return@withContext }
                lat = r.getDouble("latitude"); lon = r.getDouble("longitude")
                city = r.getString("name")
                place = listOf(r.getString("name"), r.optString("admin1"), r.optString("country")).filter { it.isNotBlank() }.joinToString(", ")
                prefs.edit().putString("lat", "$lat").putString("lon", "$lon").putString("place", place).apply()
            }
        }
    }

    // ---------------------------------------------------------------- 5. apply
    fun apply() {
        val t = tv ?: return
        prefs.edit().putString("name", name.trim()).putString("city", city.trim()).putString("avatar", avatar.trim())
            .putString("dock", dock.joinToString(",")).putString("gemini", gemini.trim()).putString("youtube", youtube.trim())
            .putString("tmdb", tmdb.trim()).putBoolean("adguard", adguard).apply()
        val disable = debloat.filterValues { it }.keys.toList()
        step = Step.Apply; log.clear(); busy = true; error = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val key = "disabled_" + t.serial
                val record = prefs.getStringSet(key, emptySet())!!.toMutableSet()
                for (pkg in disable) {
                    if (Tv.protectedPkg(pkg)) continue
                    val ok = "disabled" in t.sh("pm disable-user --user 0 $pkg")
                    say((if (ok) "✓ dimatikan " else "✗ ") + t.label(pkg))
                    if (ok) record += pkg
                }
                prefs.edit().putStringSet(key, record).apply()

                if (t.sdk >= 26) {
                    t.installLauncher(ctx, ::say)
                    say("✓ Tahoe TV terpasang")
                    val home = t.makeHome(::say, disableStock)
                    if (disableStock && home == Tv.PKG) record += Tv.STOCK_LAUNCHERS
                    prefs.edit().putStringSet(key, record).apply()
                    say(if (home == Tv.PKG) "✓ Tahoe jadi Home" else "! Home masih $home (cek manual)")

                    val cfg = buildMap {
                        name.trim().takeIf { it.isNotEmpty() }?.let { put("user_name", it) }
                        city.trim().takeIf { it.isNotEmpty() }?.let { put("city", it) }
                        if (lat != null && lon != null) { put("latitude", "$lat"); put("longitude", "$lon") }
                        avatar.trim().takeIf { it.isNotEmpty() }?.let { put("avatar_url", it) }
                        if (dock.isNotEmpty()) put("dock", dock.joinToString(","))
                        gemini.trim().takeIf { it.isNotEmpty() }?.let { put("gemini_key", it) }
                        youtube.trim().takeIf { it.isNotEmpty() }?.let { put("youtube_key", it) }
                        tmdb.trim().takeIf { it.isNotEmpty() }?.let { put("tmdb_key", it) }
                    }
                    val res = t.configure(cfg, pair = true)
                    say("✓ Personalisasi tersimpan (${res.optInt("updated")} setelan)")
                    res.optString("token").takeIf { it.isNotEmpty() }?.let { token ->
                        Sync.prefs(ctx).edit().putString("host", host).putString("token", token).apply()
                        Sync.inst?.connect()
                        say("✓ HP ini tersambung ke TV (notifikasi & musik)")
                    }
                } else say("! Android TV ini terlalu lama untuk launcher Tahoe (butuh 8.0+). Hanya debloat.")
                if (adguard) {
                    t.sh("settings put global private_dns_specifier dns.adguard-dns.com")
                    t.sh("settings put global private_dns_mode hostname")
                    say("✓ DNS AdGuard aktif")
                }
                withContext(Dispatchers.Main) { step = Step.Done }
            } catch (e: Exception) {
                say("✗ Berhenti: ${e.message}")
                withContext(Dispatchers.Main) { error = e.message }
            } finally { withContext(Dispatchers.Main) { busy = false } }
        }
    }

    fun disableDebugging(done: () -> Unit) {
        val t = tv ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { t.sh("settings put global adb_enabled 0") } // the connection drops right after
            withContext(Dispatchers.Main) { done() }
        }
    }

    // ---------------------------------------------------------------- restore
    fun restore() {
        val t = tv ?: return
        step = Step.Restore; log.clear(); busy = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val key = "disabled_" + t.serial
                val record = prefs.getStringSet(key, emptySet())!! + Tv.STOCK_LAUNCHERS
                record.filter { it in installed }.forEach { t.sh("pm enable $it"); say("✓ dinyalakan ${t.label(it)}") }
                t.sh("settings put global private_dns_mode opportunistic")
                t.sh("settings delete secure screensaver_components")
                say(t.sh("pm uninstall ${Tv.PKG}").trim() + " (uninstall Tahoe)")
                t.sh("input keyevent KEYCODE_HOME")
                prefs.edit().remove(key).apply()
                say("✓ Selesai")
            } catch (e: Exception) { say("✗ ${e.message}") }
            finally { withContext(Dispatchers.Main) { busy = false } }
        }
    }

    private fun say(s: String) { viewModelScope.launch(Dispatchers.Main) { log.add(s) } }

    override fun onCleared() { tv?.let { t -> Thread { t.close() }.start() } }
}
