package tech.kousozo.tahoetv.core

import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DemoTest {
    @AfterTest fun tearDown() {
        Demo.reset()
    }

    @Test fun profileNamesAndViewersAcrossLanguages() {
        assertEquals("Alex", Demo.profileName("en"))
        assertEquals("Rina", Demo.profileName("id"))
        assertEquals("小林", Demo.profileName("zh"))

        val en = Demo.viewer("en")
        assertTrue(en.done)
        assertEquals("US", en.country)
        assertEquals("New York", en.city)

        val id = Demo.viewer("id")
        assertEquals("ID", id.country)
        assertEquals("Jakarta", id.city)
        assertTrue(id.prayer)

        val zh = Demo.viewer("zh")
        assertEquals("SG", zh.country)
        assertEquals("Singapore", zh.city)
    }

    @Test fun enableAndSystemPropertyToggleDemoMode() {
        assertFalse(Demo.isActive())
        Demo.enable("id")
        assertTrue(Demo.isActive())
        assertEquals("id", Demo.language)
        assertEquals("Rina", Demo.profileName())

        Demo.reset()
        assertFalse(Demo.isActive())
        System.setProperty("tahoe.demo", "true")
        assertTrue(Demo.isActive())
    }

    @Test fun demoChannelsAndEpgAlignCleanly() {
        val fixedNow = Instant.parse("2026-11-14T12:15:00Z")
        val channels = Demo.channels()
        val epg = Demo.epg(fixedNow)
        assertEquals(4, channels.size)
        val first = channels.first()
        val shows = epg.schedule(first, fixedNow)
        assertTrue(shows.isNotEmpty())
        assertEquals("Evening Headlines", shows.first().title)
    }
}
