package tech.kousozo.tahoetv.core

import java.nio.file.Files
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Fase4EnhancementsTest {
    @Test
    fun kidsPolicyScheduleLimitWarningBonusAndChannels() {
        val cfg = KidsPolicy.Config(
            dailyLimitMinutes = 60,
            scheduleEnabled = true,
            allowedStartMin = 360,  // 06:00
            allowedEndMin = 1200,   // 20:00
            extraChannelIds = setOf(KidsPolicy.Config.normalizeKey("TVRI Sport")),
            blockedChannelIds = setOf(KidsPolicy.Config.normalizeKey("Random Horror")),
        )
        assertFalse(cfg.isOutsideSchedule(600))  // 10:00 OK
        assertTrue(cfg.isOutsideSchedule(300))   // 05:00 blocked
        assertTrue(cfg.isOutsideSchedule(1260))  // 21:00 blocked
        assertTrue(cfg.isChannelAllowed(false, "tvri_sport", "TVRI Sport"))
        assertFalse(cfg.isChannelAllowed(true, "random_horror", "Random Horror"))

        val usage = KidsPolicy.UsageState(date = "2026-11-16")
        usage.addChannelSeconds("Mentari TV", 56 * 60, today = "2026-11-16")
        val warnEv = usage.evaluate(cfg, minuteOfDay = 720, today = "2026-11-16")
        assertTrue(warnEv.allowed)
        assertTrue(warnEv.warnFiveMinutes)
        assertEquals(240, warnEv.remainingSeconds)

        usage.markWarned("2026-11-16")
        val warnAgain = usage.evaluate(cfg, minuteOfDay = 720, today = "2026-11-16")
        assertFalse(warnAgain.warnFiveMinutes)

        usage.addAppSeconds("YouTube Kids", 5 * 60, today = "2026-11-16")
        val capEv = usage.evaluate(cfg, minuteOfDay = 720, today = "2026-11-16")
        assertFalse(capEv.allowed)
        assertEquals(KidsPolicy.BlockReason.DAILY_LIMIT, capEv.reason)

        usage.grantBonusMinutes(30, today = "2026-11-16")
        val extEv = usage.evaluate(cfg, minuteOfDay = 720, today = "2026-11-16")
        assertTrue(extEv.allowed)

        // Next day rolls watched time into 7-day history
        val nextEv = usage.evaluate(cfg, minuteOfDay = 720, today = "2026-11-17")
        assertTrue(nextEv.allowed)
        assertEquals(0, nextEv.usedSeconds)
        assertEquals(61, usage.historyMinutes["2026-11-16"])

        val roundTrip = KidsPolicy.UsageState.fromJson(usage.toJsonString(), today = "2026-11-17")
        assertEquals(61, roundTrip.historyMinutes["2026-11-16"])
        val report = roundTrip.reportJson(cfg, minuteOfDay = 720, today = "2026-11-17")
        assertEquals("2026-11-17", report.getString("date"))
        assertEquals(1, report.getJSONArray("history").length())
    }

    @Test
    fun tvSettingsSnapshotRoundTripsWithoutExposingSecrets() {
        val snap = TvSettings.Snapshot(
            userName = "Kou",
            tvName = "Ruang Keluarga",
            language = "id",
            simpleMode = true,
            sortMode = "alphabetical_asc",
            performanceTier = "light",
            subtitleSize = "large",
            wallMode = "default",
            wallRotateMinutes = 10,
            screensaverEnabled = false,
            dockSlots = 5,
            dockPackages = listOf("tv.clean.home", "com.netflix.ninja"),
            allApps = listOf(TvSettings.AppItem("tv.clean.home", "Live TV", dock = true, kidsAllowed = true)),
            playlistUrl = "https://example.com/list.m3u",
            epgUrl = "https://example.com/epg.xml",
            sleepMinutes = 30,
            kidsGateMode = "pin_math",
            kidsMathDifficulty = "medium",
            kidsApps = listOf("tv.clean.home"),
            kidsPolicy = KidsPolicy.Config(dailyLimitMinutes = 90, scheduleEnabled = true),
            hasGeminiKey = true,
            hasYoutubeKey = false,
            hasTmdbKey = true,
            hasHaUrl = false,
        )
        val raw = snap.toJson()
        val parsed = TvSettings.Snapshot.fromJson(raw)
        assertEquals(snap.userName, parsed.userName)
        assertEquals(snap.tvName, parsed.tvName)
        assertEquals(snap.dockSlots, parsed.dockSlots)
        assertEquals(snap.dockPackages, parsed.dockPackages)
        assertEquals(90, parsed.kidsPolicy.dailyLimitMinutes)
        assertTrue(parsed.hasGeminiKey)
        assertFalse(raw.has("gemini_key"))
    }

    @Test
    fun watchRecapRecordsMonthlyTotalsIgnoresShortZapsAndSupportsClear() {
        val dir = Files.createTempDirectory("tahoe-recap").toFile()
        try {
            val zone = ZoneId.of("UTC")
            val ts = java.time.Instant.parse("2026-11-10T20:15:00Z").toEpochMilli()
            // Ignore short zap (<15s) and guest profile
            WatchRecap.record(dir, "rcti", "RCTI", "Sinema", 5, profile = "main", nowMs = ts, zone = zone)
            WatchRecap.record(dir, "sctv", "SCTV", "Liputan 6", 600, profile = "guest", nowMs = ts, zone = zone)
            // Valid main profile sessions
            WatchRecap.record(dir, "rcti", "RCTI", "Timnas Live", 1800, profile = "main", nowMs = ts, zone = zone)
            WatchRecap.record(dir, "rcti", "RCTI", "Timnas Live", 2400, profile = "main", nowMs = ts + 60_000, zone = zone)
            WatchRecap.record(dir, "tvri", "TVRI", "Dunia Dalam Berita", 900, profile = "main", nowMs = ts, zone = zone)

            val s = WatchRecap.summarize(dir, profile = "main", month = "2026-11", lang = "id")
            assertEquals(85, s.totalMinutes)
            assertEquals(3, s.sessionCount)
            assertEquals("RCTI", s.topChannels.first().name)
            assertEquals(70, s.topChannels.first().minutes)
            assertEquals("Timnas Live", s.topShows.first().name)
            assertEquals(20, s.peakHour)
            assertTrue(s.tagline.contains("Timnas Live"))

            // Privacy opt-out blocks recording new events
            WatchRecap.setEnabled(dir, false)
            assertFalse(WatchRecap.isEnabled(dir))
            WatchRecap.record(dir, "tvri", "TVRI", "Musik", 1200, profile = "main", nowMs = ts, zone = zone)
            assertEquals(85, WatchRecap.summarize(dir, profile = "main", month = "2026-11", lang = "id").totalMinutes)

            WatchRecap.clear(dir)
            val empty = WatchRecap.summarize(dir, profile = "main", month = "2026-11", lang = "id")
            assertEquals(0, empty.totalMinutes)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun tvLinkParsesPairingSetupAndShareQrPayloads() {
        val pair = TvLink.parseQrPayload("http://192.168.18.10:8765/?code=482910")
        assertNotNull(pair)
        assertEquals("192.168.18.10", pair!!.host)
        assertEquals("482910", pair.code)
        assertFalse(pair.isSetup)

        val setup = TvLink.parseQrPayload("http://192.168.18.10:8765/setup?code=123456")
        assertNotNull(setup)
        assertEquals("192.168.18.10", setup!!.host)
        assertEquals("123456", setup.code)
        assertTrue(setup.isSetup)

        val share = TvLink.parseQrPayload("tahoe://pair?host=10.0.0.4&code=998877")
        assertNotNull(share)
        assertEquals("10.0.0.4", share!!.host)
        assertEquals("998877", share.code)

        assertNull(TvLink.parseQrPayload("https://example.com/?code=123"))
        assertNull(TvLink.parseQrPayload("not a uri"))
    }
}
