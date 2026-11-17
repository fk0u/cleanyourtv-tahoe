package tech.kousozo.tahoetv.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The phone's code-free pairing client (#180) against a fake TV that follows the launcher's /pair/request and
 *  /pair/status contract, using the same [PairRequests] rules. */
class NoCodePairProtocolTest {
    @Test fun phoneGetsTokenOnlyAfterTvApproves() {
        val reqs = PairRequests()
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var lastId = ""
        srv.createContext("/pair/request") { ex ->
            val name = org.json.JSONObject(ex.requestBody.readBytes().decodeToString()).optString("deviceName")
            val r = reqs.request(ex.remoteAddress.address.hostAddress, name)!!
            lastId = r.id
            val b = """{"id":"${r.id}","expiresIn":60}""".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        srv.createContext("/pair/status") { ex ->
            val id = ex.requestURI.query.substringAfter("id=")
            val o = when (val st = reqs.status(id)) {
                is PairRequests.Status.Approved -> """{"state":"approved","token":"${st.token}","id":"tv-1","name":"Tahoe TV","role":"owner"}"""
                PairRequests.Status.Pending -> """{"state":"pending"}"""
                PairRequests.Status.Denied -> """{"state":"denied"}"""
                else -> """{"state":"expired"}"""
            }.toByteArray()
            ex.sendResponseHeaders(200, o.size.toLong()); ex.responseBody.use { it.write(o) }
        }
        srv.start()
        try {
            val host = "127.0.0.1:${srv.address.port}"
            val id = TvLink.requestPair(host, "Pixel 10")
            assertEquals(lastId, id)
            assertEquals("pending", TvLink.pairStatus(host, id))
            reqs.approve(id, "secret-token")
            val res = TvLink.pairStatus(host, id)
            assertTrue(res is TvLink.PairResult)
            assertEquals("secret-token", (res as TvLink.PairResult).token)
            assertEquals("tv-1", res.id)
            assertEquals("expired", TvLink.pairStatus(host, id)) // handed out once
        } finally { srv.stop(0) }
    }
}
