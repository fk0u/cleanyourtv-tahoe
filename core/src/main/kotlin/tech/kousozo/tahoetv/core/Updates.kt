package tech.kousozo.tahoetv.core

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import org.json.JSONObject

/** OTA: the latest GitHub release of Tahoe TV, its notes (the in-app changelog) and its installers.
 *  Release APKs are signed with one key since 2.9.2, so they install over the current app. */
object Updates {
    const val REPO = "fk0u/cleanyourtv-tahoe"
    const val PAGE = "https://github.com/$REPO/releases"
    const val CONTROL_PLANE_RELEASE = "https://tahoe.kousozo.tech/api/releases/latest"

    /** Clear everyday APK filenames first, with fallback to historical pre-3.4 asset names for older releases. */
    @JvmField val LAUNCHER_ASSETS = listOf("TahoeTV-App-for-TV-and-Phone.apk", "tahoe-launcher.apk")
    @JvmField val REMOTE_ASSETS = listOf("TahoeTV-Phone-Remote-and-Setup.apk", "tahoe-tv.apk")

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

    /** "2.10.0" > "2.9.3"; suffixes like "-beta" are ignored. */
    @JvmStatic fun newer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) { val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }; if (x != y) return x > y }
        return false
    }

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
