package tech.kousozo.tahoetv.core

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import org.json.JSONObject

/** OTA: the latest GitHub release of Tahoe TV, its notes (the in-app changelog) and its installers.
 *  Release APKs are signed with one key since 2.9.2, so they install over the current app. */
object Updates {
    const val REPO = "fk0u/tahoe-tv"
    const val PAGE = "https://github.com/$REPO/releases"
    const val CONTROL_PLANE_RELEASE = "https://tahoe.kousozo.tech/api/releases/latest"

    /** Plain-language APK filenames for distinct device roles first, with fallback to earlier release asset names. */
    @JvmField val LAUNCHER_ASSETS = listOf("TahoeTV-Android-TV.apk", "TahoeTV-App-for-TV-and-Phone.apk", "tahoe-launcher.apk")
    @JvmField val REMOTE_ASSETS = listOf("TahoeTV-Phone-and-Tablet.apk", "TahoeTV-Phone-Remote-and-Setup.apk", "tahoe-tv.apk")

    class Release(
        val version: String,
        val notes: String,
        val page: String,
        val date: String,
        val assets: Map<String, String>,
        val sha256Map: Map<String, String> = emptyMap(),
        val forceModal: Boolean = false,
        val minVersionCode: Int = 0,
        val modalTitle: String = "",
        val modalMessage: String = "",
    ) {
        /** Returns the first present asset key among [candidates]. */
        fun pickAsset(candidates: List<String>): String? = candidates.firstOrNull { it in assets }

        fun hasAnyAsset(candidates: List<String>): Boolean = pickAsset(candidates) != null

        /** Fetches and finds the SHA-256 hash for [assetName] if a SHA256SUMS asset exists (or preloaded map). */
        fun checksumFor(assetName: String): String? {
            sha256Map[assetName]?.let { return it }
            return runCatching {
                val url = assets["SHA256SUMS"] ?: return null
                val text = Web.get(url, 10_000)
                parseChecksums(text)[assetName]
            }.getOrNull()
        }

        /** Resolves asset + required SHA-256 (fail-closed if SHA256SUMS exists but entry is missing). */
        fun resolveDownload(candidates: List<String>): Triple<String, String, String?> {
            val name = pickAsset(candidates)
                ?: throw java.io.IOException("Asset pembaruan tidak ditemukan pada rilis $version")
            val url = assets.getValue(name)
            val hash = sha256Map[name] ?: if ("SHA256SUMS" in assets) {
                val text = Web.get(assets.getValue("SHA256SUMS"), 10_000)
                parseChecksums(text)[name]
                    ?: throw java.io.IOException("Checksum SHA-256 untuk $name tidak ditemukan di SHA256SUMS")
            } else null
            return Triple(name, url, hash)
        }
    }

    /** Fetches latest release from Control Plane first (for policy/hashes), falling back to GitHub Releases. Blocks. */
    @JvmStatic fun latest(): Release {
        runCatching {
            val o = JSONObject(Web.get(CONTROL_PLANE_RELEASE, 5000))
            val version = o.optString("version").trim()
            if (version.isNotBlank()) {
                val assets = linkedMapOf<String, String>()
                val sha = linkedMapOf<String, String>()
                o.optJSONArray("assets")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val a = arr.getJSONObject(i)
                        val n = a.optString("name")
                        val u = a.optString("url")
                        if (n.isNotBlank() && u.isNotBlank()) {
                            assets[n] = u
                            a.optString("sha256").takeIf { it.length == 64 }?.let { sha[n] = it.lowercase() }
                        }
                    }
                }
                val pol = o.optJSONObject("update_policy")
                if (assets.isNotEmpty()) {
                    return Release(
                        version = version,
                        notes = o.optString("notes"),
                        page = o.optString("page", PAGE),
                        date = o.optString("date"),
                        assets = assets,
                        sha256Map = sha,
                        forceModal = pol?.optBoolean("force_modal", false) == true,
                        minVersionCode = pol?.optInt("min_version_code", 0) ?: 0,
                        modalTitle = pol?.optString("modal_title").orEmpty(),
                        modalMessage = pol?.optString("modal_message").orEmpty(),
                    )
                }
            }
        }
        val o = JSONObject(Web.get("https://api.github.com/repos/$REPO/releases/latest", 8000))
        val assets = o.optJSONArray("assets")?.let { a -> (0 until a.length()).associate { a.getJSONObject(it).let { x -> x.getString("name") to x.getString("browser_download_url") } } }.orEmpty()
        return Release(o.getString("tag_name").removePrefix("v"), o.optString("body"), o.optString("html_url", PAGE), o.optString("published_at").take(10), assets)
    }

    /** Semantic-version order: "2.10.0" > "2.9.3", and a pre-release sorts below its release ("3.6.0-beta.1" < "3.6.0"),
     *  with pre-release parts compared one by one ("beta.2" > "beta.1", numbers numerically). A leading "v" and "+build" are ignored. */
    @JvmStatic fun compare(x: String, y: String): Int {
        fun split(v: String): Pair<List<Int>, List<String>> {
            val clean = v.trim().removePrefix("v").substringBefore('+')
            val core = clean.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val pre = if (clean.contains('-')) clean.substringAfter('-').split('.', '-').filter { it.isNotEmpty() } else emptyList()
            return core to pre
        }
        val (a, pa) = split(x); val (b, pb) = split(y)
        for (i in 0 until maxOf(a.size, b.size)) {
            val p = a.getOrElse(i) { 0 }; val q = b.getOrElse(i) { 0 }
            if (p != q) return p.compareTo(q)
        }
        if (pa.isEmpty() && pb.isEmpty()) return 0
        if (pa.isEmpty()) return 1   // release beats its own pre-release
        if (pb.isEmpty()) return -1
        for (i in 0 until maxOf(pa.size, pb.size)) {
            if (i >= pa.size) return -1 // fewer parts sort first
            if (i >= pb.size) return 1
            val m = pa[i]; val n = pb[i]
            val mi = m.toIntOrNull(); val ni = n.toIntOrNull()
            val c = when {
                mi != null && ni != null -> mi.compareTo(ni)
                mi != null -> -1        // numbers sort before words
                ni != null -> 1
                else -> m.compareTo(n)
            }
            if (c != 0) return c
        }
        return 0
    }

    /** True when [candidate] is a later version than [current]. */
    @JvmStatic fun newer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    /** Downloads [url] to [out] (follows redirects), reporting 0–100. Blocks. */
    @JvmStatic @JvmOverloads fun download(
        url: String,
        out: File,
        progress: ((Int) -> Unit)? = null
    ) = download(url, out, null, progress)

    /** Downloads [url] to [out] with size and SHA-256 integrity verification. Blocks. */
    @JvmStatic fun download(
        url: String,
        out: File,
        expectedSha256: String?,
        progress: ((Int) -> Unit)? = null
    ) {
        var u = url
        repeat(5) {
            val parsed = URI(u)
            val scheme = parsed.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                throw java.io.IOException("Skema URL unduhan tidak diizinkan: $scheme")
            }
            val c = parsed.toURL().openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "TahoeTV")
            if (c.responseCode in 301..308) {
                val loc = c.getHeaderField("Location") ?: throw java.io.IOException("Redirect without Location")
                val next = parsed.resolve(loc)
                val nextScheme = next.scheme?.lowercase()
                if (nextScheme != "http" && nextScheme != "https") {
                    c.disconnect()
                    throw java.io.IOException("Skema redirect tidak diizinkan: $nextScheme")
                }
                u = next.toString()
                c.disconnect()
                return@repeat
            }
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            val tmp = File(out.path + ".part")
            val digest = if (expectedSha256 != null) MessageDigest.getInstance("SHA-256") else null
            var done = 0L
            try {
                c.inputStream.use { i -> tmp.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var n: Int
                    while (i.read(buf).also { n = it } > 0) {
                        o.write(buf, 0, n)
                        digest?.update(buf, 0, n)
                        done += n
                        if (total > 0) progress?.invoke((done * 100 / total).toInt().coerceIn(0, 100))
                    }
                } }
            } catch (e: Exception) {
                tmp.delete()
                throw java.io.IOException("Unduhan terputus ($done / $total bytes): ${e.message}", e)
            }
            if (total > 0 && done < total) {
                tmp.delete()
                throw java.io.IOException("Unduhan terputus ($done / $total bytes). Coba lagi.")
            }
            if (expectedSha256 != null && digest != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                    tmp.delete()
                    throw java.io.IOException("Verifikasi integritas gagal: SHA-256 tidak cocok")
                }
            }
            if (!tmp.renameTo(out)) {
                try {
                    tmp.copyTo(out, overwrite = true)
                    tmp.delete()
                } catch (e: Exception) {
                    tmp.delete()
                    throw java.io.IOException("rename failed")
                }
            }
            return
        }
        throw java.io.IOException("too many redirects")
    }

    /** Parses a SHA256SUMS file content (e.g. "<hash>  <filename>") into a filename -> hash map. */
    @JvmStatic fun parseChecksums(text: String): Map<String, String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"), limit = 2)
                if (parts.size == 2 && parts[0].length == 64) parts[1].removePrefix("*") to parts[0].lowercase() else null
            }
            .toMap()

    /** Computes SHA-256 hash of a local file. */
    @JvmStatic fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { i ->
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (i.read(buf).also { n = it } > 0) digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
