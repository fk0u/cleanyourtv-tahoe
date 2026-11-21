package tech.kousozo.tahoetv.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared cross-platform TV settings schema exchanged over `/settings` (paired Phone Control Center & local web setup, #35/#36).
 * Secret keys (Gemini, YouTube, TMDB, Home Assistant token) are write-only over POST and exposed solely as booleans over GET.
 */
object TvSettings {
    data class AppItem(
        val pkg: String,
        val label: String,
        val dock: Boolean = false,
        val kidsAllowed: Boolean = false,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("pkg", pkg)
            put("label", label)
            put("dock", dock)
            put("kidsAllowed", kidsAllowed)
        }

        companion object {
            @JvmStatic
            fun fromJson(o: JSONObject): AppItem = AppItem(
                pkg = o.optString("pkg"),
                label = o.optString("label"),
                dock = o.optBoolean("dock", false),
                kidsAllowed = o.optBoolean("kidsAllowed", false),
            )
        }
    }

    data class Snapshot(
        val userName: String = "",
        val tvName: String = "Tahoe TV",
        val language: String = "id",
        val simpleMode: Boolean = false,
        val sortMode: String = "popularity",
        val performanceTier: String = "auto",
        val subtitleSize: String = "normal",
        val wallMode: String = "personal",
        val wallSource: String = "auto",
        val wallTheme: String = "nature",
        val wallRotateMinutes: Int = 5,
        val screensaverEnabled: Boolean = true,
        val dockSlots: Int = 6,
        val dockPackages: List<String> = emptyList(),
        val allApps: List<AppItem> = emptyList(),
        val playlistUrl: String = "",
        val epgUrl: String = "",
        val ottServerUrl: String = OttEngine.DEFAULT_PRIMARY_OTT_URL,
        val ottUserAgent: String = OttEngine.DEFAULT_OTT_UA,
        val sleepMinutes: Int = 0,
        val kidsGateMode: String = "pin",
        val kidsMathDifficulty: String = "easy",
        val kidsApps: List<String> = emptyList(),
        val kidsPolicy: KidsPolicy.Config = KidsPolicy.Config.DEFAULT,
        val hasGeminiKey: Boolean = false,
        val hasYoutubeKey: Boolean = false,
        val hasTmdbKey: Boolean = false,
        val hasHaUrl: Boolean = false,
        val hasUnsplashKey: Boolean = false,
        val hasPexelsKey: Boolean = false,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("userName", userName)
            put("tvName", tvName)
            put("language", language)
            put("simpleMode", simpleMode)
            put("sortMode", sortMode)
            put("performanceTier", performanceTier)
            put("subtitleSize", subtitleSize)
            put("wallMode", wallMode)
            put("wallSource", wallSource)
            put("wallTheme", wallTheme)
            put("wallRotateMinutes", wallRotateMinutes.coerceIn(1, 120))
            put("screensaverEnabled", screensaverEnabled)
            put("dockSlots", dockSlots.coerceIn(3, 12))
            put("dockPackages", JSONArray(dockPackages))
            put("allApps", JSONArray().also { a -> allApps.forEach { a.put(it.toJson()) } })
            put("playlistUrl", playlistUrl)
            put("epgUrl", epgUrl)
            put("ottServerUrl", ottServerUrl)
            put("ottUserAgent", ottUserAgent)
            put("sleepMinutes", sleepMinutes.coerceAtLeast(0))
            put("kidsGateMode", kidsGateMode)
            put("kidsMathDifficulty", kidsMathDifficulty)
            put("kidsApps", JSONArray(kidsApps))
            put("kidsPolicy", kidsPolicy.toJson())
            put("hasGeminiKey", hasGeminiKey)
            put("hasYoutubeKey", hasYoutubeKey)
            put("hasTmdbKey", hasTmdbKey)
            put("hasHaUrl", hasHaUrl)
            put("hasUnsplashKey", hasUnsplashKey)
            put("hasPexelsKey", hasPexelsKey)
        }

        companion object {
            @JvmStatic
            fun fromJson(o: JSONObject): Snapshot {
                val dockPkgs = ArrayList<String>()
                o.optJSONArray("dockPackages")?.let { arr ->
                    for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { dockPkgs += it }
                }
                val apps = ArrayList<AppItem>()
                o.optJSONArray("allApps")?.let { arr ->
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { apps += AppItem.fromJson(it) }
                }
                val kidsApps = ArrayList<String>()
                o.optJSONArray("kidsApps")?.let { arr ->
                    for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { kidsApps += it }
                }
                return Snapshot(
                    userName = o.optString("userName", ""),
                    tvName = o.optString("tvName", "Tahoe TV").ifBlank { "Tahoe TV" },
                    language = Region.language(o.optString("language", "id")),
                    simpleMode = o.optBoolean("simpleMode", false),
                    sortMode = LocalTv.SortMode.fromKey(o.optString("sortMode")).key,
                    performanceTier = o.optString("performanceTier", "auto"),
                    subtitleSize = PlayPrefs.SubtitleSize.of(o.optString("subtitleSize")).key,
                    wallMode = if (o.optString("wallMode") == "default") "default" else "personal",
                    wallSource = WallpaperProvider.Source.fromKey(o.optString("wallSource", "auto")).key,
                    wallTheme = WallpaperProvider.Theme.fromKey(o.optString("wallTheme", "nature")).key,
                    wallRotateMinutes = o.optInt("wallRotateMinutes", 5).coerceIn(1, 120),
                    screensaverEnabled = o.optBoolean("screensaverEnabled", true),
                    dockSlots = o.optInt("dockSlots", 6).coerceIn(3, 12),
                    dockPackages = dockPkgs,
                    allApps = apps,
                    playlistUrl = o.optString("playlistUrl", ""),
                    epgUrl = o.optString("epgUrl", ""),
                    ottServerUrl = o.optString("ottServerUrl", OttEngine.DEFAULT_PRIMARY_OTT_URL),
                    ottUserAgent = o.optString("ottUserAgent", OttEngine.DEFAULT_OTT_UA),
                    sleepMinutes = o.optInt("sleepMinutes", 0).coerceAtLeast(0),
                    kidsGateMode = MathGate.Mode.of(o.optString("kidsGateMode")).key,
                    kidsMathDifficulty = MathGate.Difficulty.of(o.optString("kidsMathDifficulty")).key,
                    kidsApps = kidsApps,
                    kidsPolicy = KidsPolicy.Config.fromJson(o.optJSONObject("kidsPolicy")),
                    hasGeminiKey = o.optBoolean("hasGeminiKey", false),
                    hasYoutubeKey = o.optBoolean("hasYoutubeKey", false),
                    hasTmdbKey = o.optBoolean("hasTmdbKey", false),
                    hasHaUrl = o.optBoolean("hasHaUrl", false),
                    hasUnsplashKey = o.optBoolean("hasUnsplashKey", false),
                    hasPexelsKey = o.optBoolean("hasPexelsKey", false),
                )
            }
        }
    }
}
