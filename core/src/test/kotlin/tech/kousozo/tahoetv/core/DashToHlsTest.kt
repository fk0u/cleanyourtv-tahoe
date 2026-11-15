package tech.kousozo.tahoetv.core

import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DashToHlsTest {
    private val sampleMpd = """
        <?xml version="1.0" encoding="utf-8"?>
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic" availabilityStartTime="2026-01-01T00:00:00Z">
          <Period id="p0">
            <AdaptationSet id="1" contentType="audio" mimeType="audio/mp4" lang="id">
              <SegmentTemplate timescale="48000" initialization="audio-${'$'}RepresentationID$-init.mp4" media="audio-${'$'}RepresentationID$-${'$'}Time$.m4s">
                <SegmentTimeline>
                  <S t="480000" d="96000" r="2" />
                </SegmentTimeline>
              </SegmentTemplate>
              <Representation id="a128" bandwidth="128000" codecs="mp4a.40.2" />
            </AdaptationSet>
            <AdaptationSet id="2" contentType="video" mimeType="video/mp4">
              <SegmentTemplate timescale="90000" initialization="video-${'$'}RepresentationID$-init.mp4" media="video-${'$'}RepresentationID$-${'$'}Number%04d$.m4s" startNumber="10">
                <SegmentTimeline>
                  <S t="900000" d="180000" r="1" />
                  <S d="180000" />
                </SegmentTimeline>
              </SegmentTemplate>
              <Representation id="v480" bandwidth="800000" width="854" height="480" codecs="avc1.4d401e" />
              <Representation id="v1080" bandwidth="4000000" width="1920" height="1080" codecs="avc1.640028" />
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()

    @Test fun convertsMpdToHlsMasterAndMediaWithMaxHeightAndFormatting() {
        val master720 = DashToHls.toMasterM3u8(sampleMpd, "https://cdn.example/live/master.mpd", maxHeight = 720) { rep ->
            "http://127.0.0.1:9999/s?rep=$rep"
        }!!
        assertTrue("#EXT-X-VERSION:7" in master720)
        assertTrue("""#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio"""" in master720)
        val streamLines = master720.lines().filter { it.startsWith("http://") && "v" in it }
        assertEquals("http://127.0.0.1:9999/s?rep=v480", streamLines.first())

        val mediaVideo = DashToHls.toMediaM3u8(sampleMpd, "https://cdn.example/live/master.mpd", "v480") { url, isInit ->
            val tag = if (isInit) "init" else "seg"
            "http://127.0.0.1:9999/s?kind=$tag&u=$url"
        }!!
        assertTrue("""#EXT-X-MAP:URI="http://127.0.0.1:9999/s?kind=init&u=https://cdn.example/live/video-v480-init.mp4"""" in mediaVideo)
        assertTrue("video-v480-0010.m4s" in mediaVideo)
        assertTrue("video-v480-0011.m4s" in mediaVideo)
        assertTrue("video-v480-0012.m4s" in mediaVideo)
        assertTrue("#EXTINF:2.000," in mediaVideo)

        val rewritten = DashToHls.rewriteMpd(sampleMpd, "https://cdn.example/live/master.mpd") { url ->
            "http://127.0.0.1:8088/segment?u=$url"
        }
        assertFalse("\$RepresentationID\$" in rewritten)
        assertTrue("SegmentList" in rewritten)
        assertTrue("http://127.0.0.1:8088/segment?u=https://cdn.example/live/video-v480-0012.m4s" in rewritten)
    }

    @Test fun decryptsInitAndCencMediaSegmentWithSubsamples() {
        val kidHex = "0123456789abcdef0123456789abcdef"
        val keyHex = "fedcba9876543210fedcba9876543210"
        val kidBytes = Drm.hexToBytes(kidHex)
        val keyBytes = Drm.hexToBytes(keyHex)
        val iv = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val iv16 = ByteArray(16).also { System.arraycopy(iv, 0, it, 0, 8) }

        // 1. Build synthetic encrypted init segment: ftyp + pssh + moov(trak(tkhd + mdia(minf(stbl(stsd(encv))))))
        val frma = box("frma", "avc1".toByteArray(Charsets.US_ASCII))
        val schm = box("schm", ByteArray(8).apply {
            "cenc".toByteArray(Charsets.US_ASCII).copyInto(this, 4)
        })
        val tencPayload = ByteArray(24).apply {
            this[6] = 1 // isProtected = 1
            this[7] = 8 // per_sample_iv_size = 8
            kidBytes.copyInto(this, 8)
        }
        val tenc = box("tenc", tencPayload)
        val schi = box("schi", tenc)
        val sinf = box("sinf", concat(frma, schm, schi))
        val avcC = box("avcC", byteArrayOf(1, 0x4D, 0x40, 0x1E))
        val encvPayload = concat(ByteArray(78), avcC, sinf)
        val encv = box("encv", encvPayload)
        val stsd = box("stsd", concat(ByteArray(4), intBe(1), encv))
        val stbl = box("stbl", stsd)
        val minf = box("minf", stbl)
        val mdia = box("mdia", minf)
        val tkhd = box("tkhd", ByteArray(84).apply { intBe(7).copyInto(this, 12) }) // track_ID = 7
        val trak = box("trak", concat(tkhd, mdia))
        val moov = box("moov", concat(trak, box("pssh", ByteArray(16))))
        val initSegment = concat(box("ftyp", "isom".toByteArray(Charsets.US_ASCII)), box("pssh", ByteArray(12)), moov)

        val parsedInfo = CencDecrypt.parseInitInfo(initSegment)
        val track7 = parsedInfo[7]
        assertNotNull(track7)
        assertEquals("cenc", track7.scheme)
        assertEquals(8, track7.ivSize)
        assertEquals(kidHex, track7.kidHex)

        val clearInit = CencDecrypt.decryptInit(initSegment)
        val clearStr = String(clearInit, Charsets.ISO_8859_1)
        assertFalse("pssh" in clearStr)
        assertFalse("sinf" in clearStr)
        assertFalse("encv" in clearStr)
        assertTrue("avc1" in clearStr)
        assertTrue("avcC" in clearStr)

        // 2. Build synthetic encrypted media segment: moof(traf(tfhd, trun, senc)) + mdat(clear_prefix + cipher_body)
        val plain = "NALU_HEADER_0123456789_PAYLOAD_DATA!".toByteArray(Charsets.UTF_8)
        val clearLen = 12
        val encLen = plain.size - clearLen
        val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv16))
        }
        val encryptedTail = cipher.doFinal(plain, clearLen, encLen)
        val samplePayload = plain.copyOfRange(0, clearLen) + encryptedTail

        val tfhd = box("tfhd", concat(ByteArray(4), intBe(7))) // track 7
        val senc = box("senc", concat(
            intBe(0x000002), // flags = subsample encryption
            intBe(1),        // sample_count = 1
            iv,
            shortBe(1),      // subsample_count = 1
            shortBe(clearLen),
            intBe(encLen)
        ))
        // Compute moof size first so trun.data_offset points accurately to mdat payload
        val trunSize = 8 + 16
        val trafSize = 8 + tfhd.size + trunSize + senc.size
        val moofSize = 8 + trafSize
        val dataOffset = moofSize + 8
        val trun = box("trun", concat(
            intBe(0x000201), // data_offset_present | sample_size_present
            intBe(1),
            intBe(dataOffset),
            intBe(samplePayload.size)
        ))
        assertEquals(trunSize, trun.size)
        val moof = box("moof", box("traf", concat(tfhd, trun, senc)))
        assertEquals(moofSize, moof.size)
        val mdat = box("mdat", samplePayload)
        val mediaSeg = concat(moof, mdat)

        val decryptedSeg = CencDecrypt.decryptMedia(mediaSeg, mapOf(kidHex to keyHex), defaultIvSize = 8, parsedInfo)
        val decStr = String(decryptedSeg, Charsets.ISO_8859_1)
        assertFalse("senc" in decStr)

        // Extract mdat payload from the decrypted segment and verify exact plaintext + patched trun offset
        val mdatPos = decStr.indexOf("mdat") - 4
        assertTrue(mdatPos > 0)
        val recoveredPayload = decryptedSeg.copyOfRange(mdatPos + 8, mdatPos + 8 + samplePayload.size)
        assertContentEquals(plain, recoveredPayload)

        val trunPos = decStr.indexOf("trun") - 4
        assertTrue(trunPos > 0)
        val patchedDataOffset = ((decryptedSeg[trunPos + 16].toInt() and 0xFF) shl 24) or
            ((decryptedSeg[trunPos + 17].toInt() and 0xFF) shl 16) or
            ((decryptedSeg[trunPos + 18].toInt() and 0xFF) shl 8) or
            (decryptedSeg[trunPos + 19].toInt() and 0xFF)
        assertEquals(mdatPos + 8, patchedDataOffset)
    }

    @Test fun convertsStaticDurationTemplateWithoutTimeline() {
        val staticMpd = """
            <?xml version="1.0" encoding="utf-8"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT12S">
              <Period>
                <AdaptationSet mimeType="video/mp4">
                  <SegmentTemplate timescale="1000" duration="4000" startNumber="1" initialization="init.mp4" media="seg-${'$'}Number$.m4s" />
                  <Representation id="720" bandwidth="1500000" width="1280" height="720" />
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val media = DashToHls.toMediaM3u8(staticMpd, "https://cdn.example/vod/stream.mpd", "720") { u, _ -> u }!!
        assertTrue("#EXT-X-PLAYLIST-TYPE:VOD" in media)
        assertTrue("#EXT-X-ENDLIST" in media)
        assertTrue("https://cdn.example/vod/seg-1.m4s" in media)
        assertTrue("https://cdn.example/vod/seg-3.m4s" in media)
    }

    private fun box(type: String, payload: ByteArray): ByteArray = concat(intBe(8 + payload.size), type.toByteArray(Charsets.US_ASCII), payload)
    private fun intBe(v: Int) = byteArrayOf(((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
    private fun shortBe(v: Int) = byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
    private fun concat(vararg parts: ByteArray): ByteArray = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
}
