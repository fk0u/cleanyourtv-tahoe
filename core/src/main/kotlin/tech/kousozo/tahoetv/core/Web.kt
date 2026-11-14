package tech.kousozo.tahoetv.core

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

/** The same public web APIs the TV launcher uses, for the phone and desktop apps. All calls block. */
object Web {
    /** The response body as sent before compression. Some stream servers (CNBC/detik for Trans TV and Trans7) gzip
     *  playlists even when the request never asked for it; the JDK's HttpURLConnection doesn't undo that (Android's
     *  does, ExoPlayer does), so on the desktop the playlist came out as binary and the channel failed. */
    @JvmStatic fun body(c: HttpURLConnection, error: Boolean = false): java.io.InputStream? {
        val raw = (if (error) c.errorStream else c.inputStream) ?: return null
        return when (c.contentEncoding?.trim()?.lowercase()) {
            "gzip", "x-gzip" -> java.util.zip.GZIPInputStream(raw)
            "deflate" -> java.util.zip.InflaterInputStream(raw)
            else -> raw
        }
    }

    /** Sent with every stream request on TV, phone and desktop (some servers answer one client and not another).
     *  Bumped with the release version. */
    const val STREAM_UA = "TahoeTV/3.2.0"

    @JvmOverloads
    fun get(url: String, timeoutMs: Int = 10_000, headers: Map<String, String>? = null): String =
        request("GET", url, null, timeoutMs, headers)

    fun open(url: String, timeoutMs: Int = 30_000): HttpURLConnection =
        openFollowingRedirects(url, timeoutMs)

    /** Opens a GET connection following HTTP redirects (including cross-protocol http <-> https, which
     *  HttpURLConnection skips by default), resolving relative Location headers up to 5 hops. */
    @JvmOverloads
    fun openFollowingRedirects(
        url: String,
        timeoutMs: Int = 30_000,
        headers: Map<String, String> = emptyMap(),
        range: String? = null,
        connectTimeoutMs: Int = 8000,
        userAgent: String = STREAM_UA,
    ): HttpURLConnection {
        var u = url
        repeat(6) {
            val c = URI(u).toURL().openConnection() as HttpURLConnection
            c.connectTimeout = connectTimeoutMs; c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false; c.requestMethod = "GET"
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Accept-Encoding", "gzip, deflate")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (!range.isNullOrEmpty()) c.setRequestProperty("Range", range)
            val code = c.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val loc = c.getHeaderField("Location")?.takeIf { it.isNotBlank() }
                    ?: run { c.disconnect(); throw java.io.IOException("Redirect without Location from $u") }
                c.disconnect()
                u = URI(u).resolve(loc).toString()
            } else {
                return c
            }
        }
        throw java.io.IOException("Too many redirects: $url")
    }

    fun stream(url: String, timeoutMs: Int = 30_000): java.io.InputStream {
        val c = openFollowingRedirects(url, timeoutMs)
        val code = c.responseCode
        if (code >= 400) {
            c.disconnect()
            throw java.io.IOException("HTTP $code")
        }
        val input = body(c) ?: run { c.disconnect(); throw java.io.IOException("Empty body") }
        return if (url.endsWith(".gz", ignoreCase = true) && c.contentEncoding?.lowercase() != "gzip") {
            java.util.zip.GZIPInputStream(input)
        } else {
            input
        }
    }

    @JvmOverloads
    fun request(method: String, url: String, body: String?, timeoutMs: Int = 20_000, headers: Map<String, String>? = null): String {
        val c = if (method.equals("GET", ignoreCase = true) && body == null) {
            openFollowingRedirects(url, timeoutMs, headers.orEmpty(), userAgent = "TahoeTV")
        } else {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = timeoutMs; requestMethod = method
                setRequestProperty("User-Agent", "TahoeTV")
                headers?.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) {
                    doOutput = true; setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(body.toByteArray()) }
                }
            }
        }
        try {
            val code = c.responseCode
            val text = body(c, code >= 400)?.bufferedReader()?.readText().orEmpty()
            if (code >= 400) throw java.io.IOException("HTTP $code")
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** `region` = province (admin1), used for "near you" local TV. */
    data class Place(val name: String, val label: String, val lat: Double, val lon: Double, val region: String = "")

    @JvmOverloads
    fun geocode(q: String, lang: String = "id"): Place? = runCatching {
        val r = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=" + enc(lang) + "&name=" + enc(q))).getJSONArray("results").getJSONObject(0)
        Place(r.getString("name"), listOf(r.getString("name"), r.optString("admin1"), r.optString("country")).filter { it.isNotBlank() }.joinToString(", "),
            r.getDouble("latitude"), r.getDouble("longitude"), r.optString("admin1"))
    }.getOrNull()

    /** A city found by [searchPlaces], with its IANA time zone and country code. */
    data class City(val name: String, val label: String, val lat: Double, val lon: Double, val region: String, val country: String, val timeZone: String)

    /** Cities matching [q] (up to 10), names in [language]: for the onboarding's city step. */
    fun searchPlaces(q: String, language: String = "en", country: String? = null): List<City> = runCatching {
        val a = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=10&language=" + enc(language) + "&name=" + enc(q) +
            (country?.takeIf { it.isNotBlank() }?.let { "&countryCode=" + enc(it) } ?: ""))).optJSONArray("results") ?: return@runCatching emptyList()
        (0 until a.length()).map { i -> a.getJSONObject(i) }.map { r ->
            City(r.getString("name"), listOf(r.getString("name"), r.optString("admin1"), r.optString("country")).filter { it.isNotBlank() }.joinToString(", "),
                r.getDouble("latitude"), r.getDouble("longitude"), r.optString("admin1"), r.optString("country_code"), r.optString("timezone"))
        }
    }.getOrDefault(emptyList())

    data class Weather(val tempC: Int, val code: Int, val label: String)

    @JvmOverloads
    fun weather(lat: Double, lon: Double, lang: String = "id"): Weather? = runCatching {
        val cur = JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&timezone=auto"))
            .getJSONObject("current")
        val code = cur.getInt("weather_code")
        Weather(Math.round(cur.getDouble("temperature_2m")).toInt(), code, weatherLabel(code, lang))
    }.getOrNull()

    @JvmOverloads
    fun weatherLabel(code: Int, lang: String = "id") = when (lang) {
        "en" -> when (code) {
            0 -> "Clear"; 1, 2 -> "Partly cloudy"; 3 -> "Cloudy"; 45, 48 -> "Foggy"
            in 51..57 -> "Drizzle"; in 61..67, in 80..82 -> "Rain"; in 71..77, 85, 86 -> "Snow"; in 95..99 -> "Thunderstorm"; else -> "—"
        }
        "zh" -> when (code) {
            0 -> "晴"; 1, 2 -> "多云"; 3 -> "阴"; 45, 48 -> "有雾"
            in 51..57 -> "毛毛雨"; in 61..67, in 80..82 -> "雨"; in 71..77, 85, 86 -> "雪"; in 95..99 -> "雷暴"; else -> "—"
        }
        else -> when (code) {
            0 -> "Cerah"; 1, 2 -> "Cerah berawan"; 3 -> "Berawan"; 45, 48 -> "Berkabut"
            in 51..57 -> "Gerimis"; in 61..67, in 80..82 -> "Hujan"; in 71..77, 85, 86 -> "Salju"; in 95..99 -> "Badai petir"; else -> "—"
        }
    }

    /** Today's prayer times for the place: ordered name → "HH:mm" (local time there). [method] is an Aladhan id
     *  ([Prayer.METHODS]; Kemenag RI = 20); "today" is the date in [zone], the place's time zone. */
    @JvmOverloads
    fun prayer(lat: Double, lon: Double, method: Int = 20, zone: java.time.ZoneId = java.time.ZoneId.systemDefault(), lang: String = "id"): List<Pair<String, String>> = runCatching {
        val t = JSONObject(get("https://api.aladhan.com/v1/timings/" + LocalDate.now(zone).format(DateTimeFormatter.ofPattern("dd-MM-yyyy")) +
            "?latitude=$lat&longitude=$lon&method=$method&timezonestring=" + enc(zone.id))).getJSONObject("data").getJSONObject("timings")
        val names = when (lang) {
            "en" -> listOf("Fajr" to "Fajr", "Dhuhr" to "Dhuhr", "Asr" to "Asr", "Maghrib" to "Maghrib", "Isha" to "Isha")
            "zh" -> listOf("晨礼" to "Fajr", "晌礼" to "Dhuhr", "晡礼" to "Asr", "昏礼" to "Maghrib", "宵礼" to "Isha")
            else -> listOf("Subuh" to "Fajr", "Dzuhur" to "Dhuhr", "Ashar" to "Asr", "Maghrib" to "Maghrib", "Isya" to "Isha")
        }
        names.map { (label, key) -> label to t.getString(key).take(5) }
    }.getOrDefault(emptyList())

    data class Hero(val title: String, val sub: String, val image: String, val url: String, val source: String)

    @JvmOverloads
    fun youtubeTrending(key: String, region: String = "ID", lang: String = "id"): List<Hero> = runCatching {
        val items = JSONObject(get("https://www.googleapis.com/youtube/v3/videos?part=snippet,statistics&chart=mostPopular&regionCode=$region&maxResults=10&key=" + enc(key)))
            .getJSONArray("items")
        val viewsSuffix = when (lang) {
            "en" -> " views"
            "zh" -> " 次观看"
            else -> " x ditonton"
        }
        (0 until items.length()).map { items.getJSONObject(it) }.map { it ->
            val sn = it.getJSONObject("snippet"); val th = sn.getJSONObject("thumbnails")
            val img = (th.optJSONObject("maxres") ?: th.optJSONObject("high") ?: th.getJSONObject("default")).getString("url")
            val views = it.optJSONObject("statistics")?.optLong("viewCount") ?: 0
            Hero(sn.getString("title"), sn.getString("channelTitle") + " · " + compact(views, lang) + viewsSuffix, img,
                "https://www.youtube.com/watch?v=" + it.getString("id"), "YouTube")
        }
    }.getOrDefault(emptyList())

    /** Popular on Netflix in [watchRegion] (TMDB discover, provider 8). */
    @JvmOverloads
    fun netflixPopular(key: String, watchRegion: String = "ID", langTag: String = "id-ID"): List<Hero> = runCatching {
        val res = JSONObject(get("https://api.themoviedb.org/3/discover/movie?api_key=" + enc(key) +
            "&language=$langTag&watch_region=$watchRegion&with_watch_providers=8&sort_by=popularity.desc")).getJSONArray("results")
        (0 until res.length()).map { res.getJSONObject(it) }.filter { it.optString("backdrop_path").isNotEmpty() }.take(10).map {
            Hero(it.getString("title"), it.optString("overview").take(140), "https://image.tmdb.org/t/p/w1280" + it.getString("backdrop_path"),
                "https://www.netflix.com/search?q=" + enc(it.getString("title")), "Netflix")
        }
    }.getOrDefault(emptyList())

    @JvmOverloads
    fun compact(n: Long, lang: String = "id") = when (lang) {
        "en" -> when {
            n >= 1_000_000_000 -> "%.1f B".format(java.util.Locale.US, n / 1e9); n >= 1_000_000 -> "%.1f M".format(java.util.Locale.US, n / 1e6); n >= 1000 -> "%.0f K".format(java.util.Locale.US, n / 1e3); else -> "$n"
        }
        "zh" -> when {
            n >= 100_000_000 -> "%.1f 亿".format(java.util.Locale.US, n / 1e8); n >= 10_000 -> "%.1f 万".format(java.util.Locale.US, n / 1e4); n >= 1000 -> "%.0f 千".format(java.util.Locale.US, n / 1e3); else -> "$n"
        }
        else -> when {
            n >= 1_000_000_000 -> "%.1f M".format(java.util.Locale.US, n / 1e9); n >= 1_000_000 -> "%.1f jt".format(java.util.Locale.US, n / 1e6); n >= 1000 -> "%.0f rb".format(java.util.Locale.US, n / 1e3); else -> "$n"
        }
    }.replace(".0 ", " ")

    /** One-shot Gemini answer with Google Search grounding; falls back to no search when the quota says no. */
    @JvmOverloads
    fun ask(key: String, model: String, question: String, city: String, history: List<Pair<String, String>> = emptyList(), lang: String = "id"): String {
        val sys = when (lang) {
            "en" -> "You are an assistant named Tahoe. Answer concisely and warmly in English. Use Google Search for recent facts. User's city: $city."
            "zh" -> "你是名叫 Tahoe 的助手。请用简洁友好的简体中文回答。需要最新信息时使用 Google 搜索。用户城市：$city。"
            else -> "Kamu asisten bernama Tahoe. Jawab singkat dan santai dalam Bahasa Indonesia. Untuk fakta terbaru gunakan pencarian Google. Kota pengguna: $city."
        }
        fun body(search: Boolean) = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
            .put("contents", JSONArray().apply {
                (history + ("user" to question)).forEach { (role, text) -> put(JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text)))) }
            })
            .apply { if (search) put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject()))) }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=" + enc(key)
        val js = try { request("POST", url, body(true).toString()) } catch (e: java.io.IOException) {
            if (e.message?.contains("429") == true || e.message?.contains("400") == true) request("POST", url, body(false).toString()) else throw e
        }
        val parts = JSONObject(js).getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        return (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }.trim()
    }
}
