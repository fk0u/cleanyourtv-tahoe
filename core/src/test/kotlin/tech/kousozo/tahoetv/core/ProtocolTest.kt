package tech.kousozo.tahoetv.core

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {
    @Test fun roundTripV2EnvelopeAndCapabilities() {
        val hello = Protocol.Hello(
            proto = Protocol.VERSION,
            role = "controller",
            caps = listOf("remote", "connect"),
            name = "Pixel",
        )
        val raw = Protocol.encode(hello, id = "c-1", ts = 123456L, from = "phone-1").toString()
        val decoded = Protocol.decode(raw)
        assertNotNull(decoded)
        assertEquals(Protocol.VERSION, decoded.envelope.v)
        assertEquals("c-1", decoded.envelope.id)
        assertEquals("hello", decoded.envelope.type)
        assertEquals(123456L, decoded.envelope.ts)
        assertEquals("phone-1", decoded.envelope.from)
        val msg = assertIs<Protocol.Hello>(decoded.message)
        assertEquals(listOf("remote", "connect"), msg.caps)
        assertEquals("Pixel", msg.name)

        val welcomeRaw = Protocol.encode(
            Protocol.Welcome(proto = 2, deviceId = "tv-uuid", name = "Tahoe TV", ver = "3.4.0", caps = listOf("hls", "hevc")),
            id = "s-1",
        ).toString()
        val w = assertIs<Protocol.Welcome>(Protocol.decode(welcomeRaw)!!.message)
        assertEquals("tv-uuid", w.deviceId)
        assertEquals(listOf("hls", "hevc"), w.caps)
    }

    @Test fun legacyV1MessagesStillDecodeCleanly() {
        val decKey = Protocol.decode("""{"t":"key","k":"ch_up"}""")
        assertNotNull(decKey)
        assertEquals(1, decKey.envelope.v)
        assertEquals("ch_up", assertIs<Protocol.Key>(decKey.message).key)

        val decVol = Protocol.decode("""{"t":"volume_set","v":42}""")
        assertEquals(42, assertIs<Protocol.VolumeSet>(decVol!!.message).percent)

        val decState = Protocol.decode("""{"t":"state","volume":60,"muted":false,"channel":"TVRI Nasional","number":1,"sleep":30}""")
        val ds = assertIs<Protocol.DeviceState>(decState!!.message)
        assertEquals(60, ds.volume)
        assertEquals(30, ds.sleep)
        assertEquals("TVRI Nasional", ds.session?.channel)
        assertEquals(1, ds.session?.number)
        assertTrue(ds.session?.playing == true)
    }

    @Test fun volumeSetAndViewerAvoidEnvelopeVersionCollision() {
        val volEncoded = Protocol.encode(Protocol.VolumeSet(85), id = "c-9", withLegacy = true)
        assertEquals(Protocol.VERSION, volEncoded.getInt("v"))
        val volDecoded = assertIs<Protocol.VolumeSet>(Protocol.decode(volEncoded.toString())!!.message)
        assertEquals(85, volDecoded.percent)

        val viewerEncoded = Protocol.encode(Protocol.ViewerSync("""{"city":"Jakarta"}"""), id = "c-10", withLegacy = true)
        assertEquals(Protocol.VERSION, viewerEncoded.getInt("v"))
        val viewerDecoded = assertIs<Protocol.ViewerSync>(Protocol.decode(viewerEncoded.toString())!!.message)
        assertEquals("""{"city":"Jakarta"}""", viewerDecoded.json)
    }

    @Test fun sessionLoadControlAckErrorAndVersionCheck() {
        val loadRaw = Protocol.encode(
            Protocol.SessionLoad(channelId = "kompastv.id", url = "https://example.com/live.m3u8", name = "Kompas TV", startAt = 0L),
            id = "c-2",
        ).toString()
        val load = assertIs<Protocol.SessionLoad>(Protocol.decode(loadRaw)!!.message)
        assertEquals("kompastv.id", load.channelId)
        assertEquals("Kompas TV", load.name)

        val play = assertIs<Protocol.SessionControl>(
            Protocol.decode(Protocol.encode(Protocol.SessionControl("play"), id = "c-3").toString())!!.message
        )
        assertEquals("play", play.action)

        val ack = assertIs<Protocol.Ack>(Protocol.decode(Protocol.ack("c-2", rev = 7L, id = "s-2").toString())!!.message)
        assertEquals("c-2", ack.ackId)
        assertEquals(7L, ack.rev)

        val err = assertIs<Protocol.Error>(
            Protocol.decode(Protocol.error("c-4", "unsupported_version", "please update", id = "s-3").toString())!!.message
        )
        assertEquals("unsupported_version", err.code)

        assertTrue(Protocol.isVersionSupported(1))
        assertTrue(Protocol.isVersionSupported(2))
        assertFalse(Protocol.isVersionSupported(0))
        assertFalse(Protocol.isVersionSupported(3))

        val unknown = assertIs<Protocol.Unknown>(
            Protocol.decode("""{"v":2,"id":"x","type":"future.cmd","ts":1,"body":{"a":1}}""")!!.message
        )
        assertEquals("future.cmd", unknown.rawType)
        assertNull(Protocol.decode("not json"))
    }

    @Test fun mergedDeviceAndSessionStatePreservesBothV1AndV2Fields() {
        val session = Protocol.SessionState(
            rev = 12L,
            channelId = "metrotv.id",
            channel = "Metro TV",
            number = 4,
            playing = true,
            paused = false,
        )
        val device = Protocol.DeviceState(volume = 55, muted = false, sleep = 15, session = session)
        val wire = Protocol.toLegacyStateJson(device, session, id = "s-10").toString()
        val legacyObj = JSONObject(wire)
        assertEquals("state", legacyObj.getString("t"))
        assertEquals("Metro TV", legacyObj.getString("channel"))
        assertEquals("metrotv.id", legacyObj.getString("channelId"))
        assertEquals(12L, legacyObj.getLong("rev"))

        val decoded = assertIs<Protocol.DeviceState>(Protocol.decode(wire)!!.message)
        assertEquals(55, decoded.volume)
        assertEquals("metrotv.id", decoded.session?.channelId)
        assertEquals(12L, decoded.session?.rev)
        assertTrue(decoded.session?.playing == true)
    }
}
