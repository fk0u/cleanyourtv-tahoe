package tech.kousozo.tahoetv.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.json.JSONObject
import kotlin.test.*

class AccountTest {
    @Test fun profileRoundTripKeepsUnknownFieldsAndClampsValues() {
        val p = DisplayProfile.fromJson(JSONObject("""{"displayName":"Kou","wallpaperDim":999,"theme":"neon","accent":"red","textScale":9,"clockSize":"l","futureThing":{"a":1}}"""))
        assertEquals(80, p.wallpaperDim); assertEquals("system", p.theme); assertEquals("", p.accent)
        assertEquals(1.3f, p.textScale); assertEquals("l", p.clockSize)
        val back = DisplayProfile.fromJson(p.toJson())
        assertEquals(1, back.extra.getJSONObject("futureThing").getInt("a"))
        assertEquals(DisplayProfile().toJson().toString(), DisplayProfile.fromJson(null).toJson().toString())
    }

    @Test fun refusesPlainHttpToARemoteServer() {
        assertFailsWith<IllegalArgumentException> { AccountApi("http://example.com") }
        assertFailsWith<IllegalArgumentException> { AccountApi("ftp://example.com") }
        AccountApi("https://example.com"); AccountApi("http://127.0.0.1:3140")
    }

    @Test fun talksToTheServerAndSurfacesErrors() {
        var auth: String? = null
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/api/account/login") { ex ->
            val bad = String(ex.requestBody.readBytes()).contains("nope")
            val out = (if (bad) """{"error":"E-mail or password is wrong."}""" else """{"token":"tt_x","user":{"id":"u1","email":"a@b.cc"}}""").toByteArray()
            ex.sendResponseHeaders(if (bad) 401 else 200, out.size.toLong()); ex.responseBody.use { it.write(out) }
        }
        srv.createContext("/api/account/profile") { ex ->
            auth = ex.requestHeaders.getFirst("Authorization")
            val stale = ex.requestMethod == "PUT"
            val out = (if (stale) """{"error":"changed","rev":4,"settings":{}}""" else """{"rev":3,"settings":{"theme":"dark"}}""").toByteArray()
            ex.sendResponseHeaders(if (stale) 409 else 200, out.size.toLong()); ex.responseBody.use { it.write(out) }
        }
        srv.start()
        try {
            val base = "http://127.0.0.1:${srv.address.port}"
            val s = AccountApi(base).login("a@b.cc", "fine-password")
            assertEquals("tt_x", s.token)
            assertEquals(401, assertFailsWith<AccountException> { AccountApi(base).login("a@b.cc", "nope") }.status)
            val api = AccountApi(base, s.token)
            val prof = api.profile()
            assertEquals(3, prof.rev); assertEquals("dark", prof.settings.theme); assertEquals("Bearer tt_x", auth)
            assertNull(api.saveProfile(prof.settings, 3), "a stale revision returns null")
            assertFailsWith<IllegalArgumentException> { api.downloadFile("../etc/passwd") }
        } finally { srv.stop(0) }
    }

    @Test fun handoffRoundTripsAndOnlyOffersWhatCanPlay() {
        val h = Handoff("rcti.id", "", "RCTI", "Pixel", "phone", 5_000)
        val back = Handoff.fromJson(h.toJson(), 42)!!
        assertEquals("RCTI", back.name); assertEquals("phone", back.kind); assertEquals(5_000L, back.posMs); assertEquals(42L, back.at)
        assertNull(Handoff.fromJson(JSONObject("""{"name":"nothing to play"}""")))
        assertNull(Handoff.fromJson(null))
        assertEquals("phone", Handoff.fromJson(JSONObject("""{"url":"https://x/y.m3u8","kind":"fridge"}"""))!!.kind)
    }
}
