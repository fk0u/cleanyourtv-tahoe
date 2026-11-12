package tech.kousozo.tahoetv.core

import java.net.HttpURLConnection
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
    const val STREAM_UA = "TahoeTV/3.1.0"

    fun get(url: String, timeoutMs: Int = 10_000): String = request("GET", url, null, timeoutMs)

    fun request(method: String, url: String, body: String?, timeoutMs: Int = 20_000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = timeoutMs; c.requestMethod = method
        c.setRequestProperty("User-Agent", "TahoeTV")
        if (body != null) {
            c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = body(c, code >= 400)?.bufferedReader()?.readText().orEmpty()
        if (code >= 400) throw java.io.IOException("HTTP $code")
        return text
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** `region` = province (admin1), used for "near you" local TV. */
    data class Place(val name: String, val label: String, val lat: Double, val lon: Double, val region: String = "")

    fun geocode(q: String): Place? = runCatching {
        val r = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=id&name=" + enc(q))).getJSONArray("results").getJSONObject(0)
        Place(r.getString("name"), listOf(r.getString("name"), r.optString("admin1"), r.optString("country")).filter { it.isNotBlank() }.joinToString(", "),
            r.getDouble("latitude"), r.getDouble("longitude"), r.optString("admin1"))
    }.getOrNull()

    data class Weather(val tempC: Int, val code: Int, val label: String)

    fun weather(lat: Double, lon: Double): Weather? = runCatching {
        val cur = JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&timezone=auto"))
            .getJSONObject("current")
        val code = cur.getInt("weather_code")
        Weather(Math.round(cur.getDouble("temperature_2m")).toInt(), code, weatherLabel(code))
    }.getOrNull()

    fun weatherLabel(code: Int) = when (code) {
        0 -> "Cerah"; 1, 2 -> "Cerah berawan"; 3 -> "Berawan"; 45, 48 -> "Berkabut"
        in 51..57 -> "Gerimis"; in 61..67, in 80..82 -> "Hujan"; in 71..77, 85, 86 -> "Salju"; in 95..99 -> "Badai petir"; else -> "—"
    }

    /** Today's prayer times (Kemenag RI method 20): ordered name → "HH:mm". */
    fun prayer(lat: Double, lon: Double): List<Pair<String, String>> = runCatching {
        val t = JSONObject(get("https://api.aladhan.com/v1/timings/" + LocalDate.now().format(DateTimeFormatter.ofPattern("dd-MM-yyyy")) +
            "?latitude=$lat&longitude=$lon&method=20")).getJSONObject("data").getJSONObject("timings")
        listOf("Subuh" to "Fajr", "Dzuhur" to "Dhuhr", "Ashar" to "Asr", "Maghrib" to "Maghrib", "Isya" to "Isha")
            .map { (id, key) -> id to t.getString(key).take(5) }
    }.getOrDefault(emptyList())

    data class Hero(val title: String, val sub: String, val image: String, val url: String, val source: String)

    fun youtubeTrending(key: String, region: String = "ID"): List<Hero> = runCatching {
        val items = JSONObject(get("https://www.googleapis.com/youtube/v3/videos?part=snippet,statistics&chart=mostPopular&regionCode=$region&maxResults=10&key=" + enc(key)))
            .getJSONArray("items")
        (0 until items.length()).map { items.getJSONObject(it) }.map { it ->
            val sn = it.getJSONObject("snippet"); val th = sn.getJSONObject("thumbnails")
            val img = (th.optJSONObject("maxres") ?: th.optJSONObject("high") ?: th.getJSONObject("default")).getString("url")
            val views = it.optJSONObject("statistics")?.optLong("viewCount") ?: 0
            Hero(sn.getString("title"), sn.getString("channelTitle") + " · " + compact(views) + " x ditonton", img,
                "https://www.youtube.com/watch?v=" + it.getString("id"), "YouTube")
        }
    }.getOrDefault(emptyList())

    /** Popular on Netflix in Indonesia (TMDB discover, provider 8). */
    fun netflixPopular(key: String): List<Hero> = runCatching {
        val res = JSONObject(get("https://api.themoviedb.org/3/discover/movie?api_key=" + enc(key) +
            "&language=id-ID&watch_region=ID&with_watch_providers=8&sort_by=popularity.desc")).getJSONArray("results")
        (0 until res.length()).map { res.getJSONObject(it) }.filter { it.optString("backdrop_path").isNotEmpty() }.take(10).map {
            Hero(it.getString("title"), it.optString("overview").take(140), "https://image.tmdb.org/t/p/w1280" + it.getString("backdrop_path"),
                "https://www.netflix.com/search?q=" + enc(it.getString("title")), "Netflix")
        }
    }.getOrDefault(emptyList())

    fun compact(n: Long) = when {
        n >= 1_000_000_000 -> "%.1f M".format(java.util.Locale.US, n / 1e9); n >= 1_000_000 -> "%.1f jt".format(java.util.Locale.US, n / 1e6); n >= 1000 -> "%.0f rb".format(java.util.Locale.US, n / 1e3); else -> "$n"
    }.replace(".0 ", " ")

    /** One-shot Gemini answer with Google Search grounding; falls back to no search when the quota says no. */
    fun ask(key: String, model: String, question: String, city: String, history: List<Pair<String, String>> = emptyList()): String {
        fun body(search: Boolean) = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                "Kamu asisten bernama Tahoe. Jawab singkat dan santai dalam Bahasa Indonesia. Untuk fakta terbaru gunakan pencarian Google. Kota pengguna: $city."))))
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
