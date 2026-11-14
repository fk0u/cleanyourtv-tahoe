package tech.kousozo.tahoetv.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TvLinkAuthTest {
    @Test
    fun testTvLinkSendsAuthorizationHeader() {
        var authHeaderReceived: String? = null
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/log") { ex ->
            authHeaderReceived = ex.requestHeaders.getFirst("Authorization")
            val b = "sample log content".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        server.createContext("/kids_pin") { ex ->
            authHeaderReceived = ex.requestHeaders.getFirst("Authorization")
            val b = """{"ok":true}""".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        server.start()

        val host = "localhost:${server.address.port}"
        val testToken = "test_token_xyz_123"

        try {
            // 1. fetchLog should pass Authorization: Bearer <token>
            val log = TvLink.fetchLog(host, testToken)
            assertEquals("sample log content", log)
            assertEquals("Bearer $testToken", authHeaderReceived)

            // 2. setKidsPin should pass Authorization: Bearer <token>
            authHeaderReceived = null
            TvLink.setKidsPin(host, testToken, "1234")
            assertEquals("Bearer $testToken", authHeaderReceived)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testTvLinkKidsPinValidation() {
        assertFailsWith<IllegalArgumentException> {
            TvLink.setKidsPin("localhost", "token", "12") // must be 4 digits
        }
        assertFailsWith<IllegalArgumentException> {
            TvLink.setKidsPin("localhost", "token", "abcd")
        }
    }
}
