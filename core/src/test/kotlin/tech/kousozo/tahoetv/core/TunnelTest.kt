package tech.kousozo.tahoetv.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TunnelTest {
    @Test fun sealOpenRoundTrip() {
        val f = TunnelCrypto.seal("tok", "ctl", """{"t":"key","k":"ok"}""")
        assertEquals("""{"t":"key","k":"ok"}""", TunnelCrypto.open("tok", "ctl", f))
    }

    @Test fun rejectsWrongTokenTamperAndReflection() {
        val f = TunnelCrypto.seal("tok", "ctl", "hello")
        assertNull(TunnelCrypto.open("other", "ctl", f))
        assertNull(TunnelCrypto.open("tok", "tv", f)) // a controller's frame bounced back as if from the TV
        val tampered = f.dropLast(2) + (if (f.endsWith("AA")) "BB" else "AA")
        assertNull(TunnelCrypto.open("tok", "ctl", tampered))
        assertNull(TunnelCrypto.open("tok", "ctl", "not base64 !!"))
    }

    @Test fun roomIsStableHexAndUnrelatedToTheKey() {
        val r = TunnelCrypto.room("tok")
        assertEquals(r, TunnelCrypto.room("tok"))
        assertTrue(Regex("[0-9a-f]{64}").matches(r))
        assertNotEquals(r, TunnelCrypto.room("tok2"))
    }

    /** A fake relay (the real one is server/src/tunnel.mjs): a TV and a controller talk through it end to end. */
    @Test fun clientsExchangeFramesThroughARelay() {
        val listeners = CopyOnWriteArrayList<Pair<String, java.io.OutputStream>>()
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/api/tunnel/stream") { ex ->
            val role = ex.requestURI.query.substringAfter("role=")
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.write("event: hello\ndata: {}\n\n".toByteArray()); ex.responseBody.flush()
            listeners.add(role to ex.responseBody)
        }
        http.createContext("/api/tunnel/send") { ex ->
            val role = ex.requestURI.query.substringAfter("role=")
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            listeners.filter { it.first != role }.forEach { it.second.write("data: $body\n\n".toByteArray()); it.second.flush() }
            ex.sendResponseHeaders(200, -1); ex.close()
        }
        http.start()
        val base = "http://127.0.0.1:${http.address.port}"
        val gotTv = CountDownLatch(1); val gotCtl = CountDownLatch(1)
        var atTv = ""; var atCtl = ""
        val tv = TunnelClient(base, "secret", "tv", { atTv = it; gotTv.countDown() })
        val ctl = TunnelClient(base, "secret", "ctl", { atCtl = it; gotCtl.countDown() })
        try {
            tv.open(); ctl.open()
            val end = System.currentTimeMillis() + 5000
            while (listeners.size < 2 && System.currentTimeMillis() < end) Thread.sleep(20)
            ctl.send("key:ok"); tv.send("state:playing")
            assertTrue(gotTv.await(5, TimeUnit.SECONDS) && gotCtl.await(5, TimeUnit.SECONDS))
            assertEquals("key:ok", atTv); assertEquals("state:playing", atCtl)
        } finally { tv.close(); ctl.close(); http.stop(0) }
    }
}
