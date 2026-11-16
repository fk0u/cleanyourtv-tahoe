package tech.kousozo.tahoetv.core

import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IssueEnhancementsTest {
    @Test fun indosiarRejectsDensTvMojiAndBrandMismatch() {
        assertTrue(LocalTv.isMismatchedSource("Indosiar HD", "https://op-group1-swiftservehd-1.dens.tv/h/h207/01.m3u8"))
        assertTrue(LocalTv.isMismatchedSource("Indosiar", "https://cdn.example.com/moji/live.m3u8"))
        assertFalse(LocalTv.isMismatchedSource("Moji TV", "https://op-group1-swiftservehd-1.dens.tv/h/h207/01.m3u8"))
        assertFalse(LocalTv.isMismatchedSource("MNCTV", "https://cdn.rctiplus.id/mnctv/live.m3u8"))

        val fakeIndosiar = M3u.Channel("Indosiar HD", "https://op-group1-swiftservehd-1.dens.tv/h/h207/01.m3u8", null, "National")
        val realIndosiar = M3u.Channel("Indosiar", "https://cdn.vidio.com/indosiar/index.m3u8", null, "National")
        val sctvDead = M3u.Channel("SCTV", "http://202.150.172.59/sctv/index.m3u8", null, "National")
        val sctvLive = M3u.Channel("SCTV", "https://cdn.vidio.com/sctv/index.m3u8", null, "National")

        val arranged = LocalTv.arrange(listOf(fakeIndosiar, realIndosiar, sctvDead, sctvLive), null)
        val indosiar = arranged.first { it.name == "Indosiar" }
        assertEquals(realIndosiar.url, indosiar.channel.url)
        assertTrue(indosiar.alternates.none { "h207" in it.url })

        val sctv = arranged.first { it.name == "SCTV" }
        assertEquals(sctvLive.url, sctv.channel.url)
        assertEquals(listOf(sctvDead.url), sctv.alternates.map { it.url })
    }

    @Test fun hlsProbeDetectsTightLiveWindowsAndTuningAdapts() {
        val wowza2s = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:2
            #EXT-X-MEDIA-SEQUENCE:99123
            #EXTINF:2.0,
            seg1.ts
            #EXTINF:2.0,
            seg2.ts
            #EXTINF:2.0,
            seg3.ts
        """.trimIndent()
        val pm = HlsProbe.inspectMedia(wowza2s, Instant.now())
        assertEquals(HlsProbe.Kind.LIVE, pm.kind)
        assertEquals(2.0, pm.targetDurationSec)
        assertEquals(3, pm.segmentCount)
        assertTrue(pm.tightLiveWindow)

        val deep = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            a.ts
            #EXTINF:6.0,
            b.ts
            #EXTINF:6.0,
            c.ts
        """.trimIndent()
        assertFalse(HlsProbe.inspectMedia(deep, Instant.now()).tightLiveWindow)

        val tightLow = PlayPrefs.tuningFor(PlayPrefs.Latency.Low, tightWindow = true, lightDevice = false)
        assertTrue(tightLow.targetOffsetMs >= 12_000L)
        assertEquals(1.00f, tightLow.maxPlaybackSpeed)

        val lightStd = PlayPrefs.tuningFor(PlayPrefs.Latency.Standard, tightWindow = false, lightDevice = true)
        assertTrue(lightStd.maxBufferMs <= 30_000)
    }

    @Test fun playerStatsRedactsTokensAndFormatsAllLangs() {
        val snap = PlayerStats.Snapshot(
            channelName = "Trans TV",
            sourceIndex = 1,
            sourceTotal = 3,
            streamUrl = "https://user:secret@live.example.com/transtv/index.m3u8?token=abc123&sig=xyz",
            status = "LIVE",
            width = 1920,
            height = 1080,
            fps = 50.0f,
            videoCodec = "H.264",
            audioCodec = "AAC",
            decoderName = "HW · OMX.amlogic.avc",
            videoBitrateKbps = 4500,
            bufferSeconds = 18.4,
            liveOffsetSeconds = 19.8,
            rebufferCount = 1,
            rebufferMs = 1200,
            droppedFrames = 2,
            bandwidthKbps = 12400,
        )
        assertEquals("live.example.com/transtv", snap.redactedHost)
        assertFalse(snap.redactedHost.contains("secret"))
        assertFalse(snap.redactedHost.contains("abc123"))

        listOf("en", "id", "zh").forEach { lang ->
            val txt = PlayerStats.formatText(snap, lang)
            assertTrue("Trans TV" in txt)
            assertTrue("1920×1080" in txt)
            assertTrue("live.example.com/transtv" in txt)
            assertFalse("abc123" in txt)
        }
    }

    @Test fun deviceProfileClassifiesLightStandardAndFull() {
        val box = DeviceProfile.Specs(
            ramMb = 1024, isLowRam = true, cpuCores = 4, apiLevel = 28,
            abi = "armeabi-v7a", widthPx = 1280, heightPx = 720,
        )
        val pBox = DeviceProfile.classify(box)
        assertEquals(DeviceProfile.Tier.LIGHT, pBox.activeTier)
        assertFalse(pBox.enableHeroBlur)
        assertEquals(2, pBox.probeConcurrency)
        assertEquals(PlayPrefs.Quality.P720, pBox.recommendedQuality)

        val shield = DeviceProfile.Specs(
            ramMb = 3072, isLowRam = false, cpuCores = 4, apiLevel = 30,
            abi = "arm64-v8a", widthPx = 3840, heightPx = 2160, hwHevc = true,
        )
        val pShield = DeviceProfile.classify(shield)
        assertEquals(DeviceProfile.Tier.FULL, pShield.activeTier)
        assertTrue(pShield.enableHeroBlur)
        assertEquals(6, pShield.probeConcurrency)

        val forcedLight = DeviceProfile.classify(shield, DeviceProfile.Tier.LIGHT)
        assertEquals(DeviceProfile.Tier.FULL, forcedLight.detectedTier)
        assertEquals(DeviceProfile.Tier.LIGHT, forcedLight.activeTier)
    }

    @Test fun mathGateGeneratesVariedProblemsAndValidates() {
        val rng = Random(42)
        var prev: String? = null
        MathGate.Difficulty.entries.forEach { diff ->
            repeat(10) {
                val p = MathGate.generate(diff, prev, rng)
                assertNotEquals(prev, p.question)
                assertTrue(p.answer > 4)
                assertTrue(MathGate.verify(p, p.answer.toString()))
                assertFalse(MathGate.verify(p, (p.answer + 1).toString()))
                assertFalse(MathGate.verify(p, "abc"))
                prev = p.question
            }
        }
        assertEquals(0, MathGate.lockoutSeconds(0))
        assertEquals(2, MathGate.lockoutSeconds(1))
        assertEquals(30, MathGate.lockoutSeconds(3))
        assertEquals(300, MathGate.lockoutSeconds(5))
    }

    @Test fun channelTrackKeysAndSubtitleSize() {
        assertEquals("rcti.id", PlayPrefs.channelTrackKey("rcti.id", "RCTI"))
        assertEquals("trans_tv", PlayPrefs.channelTrackKey(null, "Trans TV"))
        assertEquals(PlayPrefs.SubtitleSize.Large, PlayPrefs.SubtitleSize.next(PlayPrefs.SubtitleSize.Normal, 1))
        assertEquals(PlayPrefs.SubtitleSize.ExtraLarge, PlayPrefs.SubtitleSize.next(PlayPrefs.SubtitleSize.Normal, -1))
    }
}
