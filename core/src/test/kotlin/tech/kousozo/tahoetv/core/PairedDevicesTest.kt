package tech.kousozo.tahoetv.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairedDevicesTest {
    @Test fun migratesLegacyHostAndToken() {
        val st = PairedDevices.fromJson(null, legacyHost = "192.168.1.50", legacyToken = "tok-1")
        assertEquals(1, st.devices.size)
        val active = st.active()
        assertEquals("192.168.1.50", active?.host)
        assertEquals("tok-1", active?.token)
        assertEquals("Tahoe TV", active?.name)
        assertEquals("owner", active?.role)
    }

    @Test fun upsertSelectRenameRemoveAndRoundTrip() {
        var st = PairedDevices.State()
            .upsert(PairedDevices.Device(id = "tv-living", host = "192.168.1.10", token = "t1", name = "Living Room"))
            .upsert(PairedDevices.Device(id = "tv-bed", host = "192.168.1.20", token = "t2", name = "Bedroom", role = "member"))
        assertEquals(2, st.devices.size)
        assertEquals("tv-bed", st.active()?.id)

        st = st.select("tv-living").rename("tv-living", "TV Ruang Tamu")
        assertEquals("TV Ruang Tamu", st.active()?.name)

        val restored = PairedDevices.fromJson(st.toJson())
        assertEquals(st, restored)

        st = st.remove("tv-living")
        assertEquals(1, st.devices.size)
        assertEquals("tv-bed", st.active()?.id)

        st = st.remove("tv-bed")
        assertTrue(st.devices.isEmpty())
        assertNull(st.active())
    }

    @Test fun updateHostFollowsMdnsIpChange() {
        val st = PairedDevices.State()
            .upsert(PairedDevices.Device(id = "uuid-1", host = "192.168.1.10", token = "t1", name = "TV"))
            .updateHost("uuid-1", "192.168.1.99", seenAt = 12345L)
        assertEquals("192.168.1.99", st.active()?.host)
        assertEquals(12345L, st.active()?.lastSeen)
    }
}
