package tech.kousozo.tahoetv.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairRequestsTest {
    @Test fun approvedTokenIsHandedOutOnce() {
        val r = PairRequests()
        val req = assertNotNull(r.request("192.168.1.20", "Pixel", now = 0))
        assertEquals(PairRequests.Status.Pending, r.status(req.id, now = 1000))
        assertTrue(r.approve(req.id, "tok", now = 2000))
        assertEquals(PairRequests.Status.Approved("tok"), r.status(req.id, now = 3000))
        assertEquals(PairRequests.Status.Expired, r.status(req.id, now = 4000)) // gone after delivery
    }

    @Test fun deniedAndExpiredRequestsGiveNoToken() {
        val r = PairRequests(ttlMs = 60_000)
        val a = r.request("10.0.0.5", "A", now = 0)!!
        assertTrue(r.deny(a.id)); assertFalse(r.approve(a.id, "x", now = 1))
        assertEquals(PairRequests.Status.Denied, r.status(a.id, now = 2))
        val b = r.request("10.0.0.6", "B", now = 0)!!
        assertEquals(PairRequests.Status.Expired, r.status(b.id, now = 61_000))
        assertFalse(r.approve(b.id, "x", now = 61_000))
    }

    @Test fun oneOpenRequestPerAddressAndCapped() {
        val r = PairRequests(maxOpen = 2)
        assertNotNull(r.request("192.168.1.2", "A", now = 0))
        assertNull(r.request("192.168.1.2", "A again", now = 1))
        assertNotNull(r.request("192.168.1.3", "B", now = 2))
        assertNull(r.request("192.168.1.4", "C", now = 3))
    }

    @Test fun onlyLocalAddressesMayAsk() {
        listOf("192.168.0.9", "10.1.2.3", "172.20.0.1", "127.0.0.1", "::ffff:192.168.1.5", "fe80::1").forEach { assertTrue(PairRequests.isLocal(it), it) }
        listOf("8.8.8.8", "172.32.0.1", "203.0.113.4", null, "", "2001:db8::1").forEach { assertFalse(PairRequests.isLocal(it), "$it") }
    }

    @Test fun deviceNameIsTrimmedAndBounded() {
        val r = PairRequests()
        assertEquals("HP", r.request("10.0.0.1", "   ", now = 0)!!.deviceName)
        assertEquals(40, r.request("10.0.0.2", "x".repeat(80), now = 0)!!.deviceName.length)
    }
}
