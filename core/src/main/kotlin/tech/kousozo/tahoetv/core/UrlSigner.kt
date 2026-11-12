package tech.kousozo.tahoetv.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Signs the URLs a relay writes into the playlists it serves, so the relay only ever fetches URLs it wrote itself.
 *  Without it, `/segment?u=<any URL>` fetched anything for anyone on the LAN (router pages, services on
 *  127.0.0.1), and with `Access-Control-Allow-Origin: *` any web page could read the answer.
 *  The key is random per process: links from a previous run stop working, which only means re-opening the channel. */
class UrlSigner(key: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }) {
    private val spec = SecretKeySpec(key, "HmacSHA256")

    /** Signature for [url] in the context of [scope] (e.g. the channel id), base64url, 22 chars (128 bits). */
    fun sign(scope: String, url: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(spec) }
        val d = mac.doFinal("$scope\n$url".toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(d.copyOf(16))
    }

    fun verify(scope: String, url: String, sig: String?): Boolean =
        sig != null && MessageDigest.isEqual(sign(scope, url).toByteArray(), sig.toByteArray())
}
