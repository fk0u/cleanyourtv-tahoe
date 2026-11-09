package tech.kousozo.tahoetv.core

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import org.json.JSONObject

/** OTA: the latest GitHub release of Tahoe TV, its notes (the in-app changelog) and its installers.
 *  Release APKs are signed with one key since 2.9.2, so they install over the current app. */
object Updates {
    const val REPO = "fk0u/cleanyourtv-tahoe"
    const val PAGE = "https://github.com/$REPO/releases"

    class Release(val version: String, val notes: String, val page: String, val date: String, val assets: Map<String, String>)

    /** Blocks; throws when offline. */
    @JvmStatic fun latest(): Release {
        val o = JSONObject(Web.get("https://api.github.com/repos/$REPO/releases/latest", 8000))
        val assets = o.optJSONArray("assets")?.let { a -> (0 until a.length()).associate { a.getJSONObject(it).let { x -> x.getString("name") to x.getString("browser_download_url") } } }.orEmpty()
        return Release(o.getString("tag_name").removePrefix("v"), o.optString("body"), o.optString("html_url", PAGE), o.optString("published_at").take(10), assets)
    }

    /** "2.10.0" > "2.9.3"; suffixes like "-beta" are ignored. */
    @JvmStatic fun newer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) { val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }; if (x != y) return x > y }
        return false
    }

    /** Downloads [url] to [out] (follows GitHub's redirect), reporting 0–100. Blocks. */
    @JvmStatic @JvmOverloads fun download(url: String, out: File, progress: ((Int) -> Unit)? = null) {
        var u = url
        repeat(5) {
            val c = URI(u).toURL().openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = 10_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "TahoeTV")
            if (c.responseCode in 301..308) { u = c.getHeaderField("Location"); c.disconnect(); return@repeat }
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            val tmp = File(out.path + ".part")
            c.inputStream.use { i -> tmp.outputStream().use { o ->
                val buf = ByteArray(64 * 1024); var done = 0L; var n: Int
                while (i.read(buf).also { n = it } > 0) { o.write(buf, 0, n); done += n; if (total > 0) progress?.invoke((done * 100 / total).toInt()) }
            } }
            if (!tmp.renameTo(out)) throw java.io.IOException("rename failed")
            return
        }
        throw java.io.IOException("too many redirects")
    }
}
