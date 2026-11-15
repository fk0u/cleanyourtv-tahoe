package tech.kousozo.tahoetv.core

import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-JVM ISO BMFF (fMP4) Common Encryption (`cenc` / `cbcs`) decryptor for ClearKey streams.
 * Used by Desktop's local HLS relay so ClearKey MPEG-DASH channels play natively inside PlayerWindow
 * without an external browser or native `mp4decrypt` binary.
 */
object CencDecrypt {

    data class TrackCrypto(
        val trackId: Int,
        val scheme: String,
        val ivSize: Int,
        val kidHex: String?,
        val cryptBlocks: Int = 0,
        val skipBlocks: Int = 0,
        val constantIv: ByteArray? = null
    )

    private data class Box(val type: String, val start: Int, val headerSize: Int, val end: Int) {
        val payloadStart: Int get() = start + headerSize
    }

    private data class Subsample(val clearBytes: Int, val encryptedBytes: Int)
    private data class SampleEnc(val iv: ByteArray, val subsamples: List<Subsample>)

    /** Extracts per-track encryption metadata (`tenc`, `schm`, `tkhd`) from an init (`ftyp`+`moov`) segment. */
    fun parseInitInfo(data: ByteArray): Map<Int, TrackCrypto> {
        val out = LinkedHashMap<Int, TrackCrypto>()
        for (moov in boxes(data, 0, data.size).filter { it.type == "moov" }) {
            for (trak in boxes(data, moov.payloadStart, moov.end).filter { it.type == "trak" }) {
                val trackId = readTrackId(data, trak) ?: continue
                val mdia = firstChild(data, trak, "mdia") ?: continue
                val minf = firstChild(data, mdia, "minf") ?: continue
                val stbl = firstChild(data, minf, "stbl") ?: continue
                val stsd = firstChild(data, stbl, "stsd") ?: continue
                if (stsd.payloadStart + 8 > stsd.end) continue
                for (entry in boxes(data, stsd.payloadStart + 8, stsd.end)) {
                    if (entry.type != "encv" && entry.type != "enca" && entry.type != "encs") continue
                    val fixed = sampleEntryFixedBytes(data, entry) ?: continue
                    val sinf = boxes(data, entry.payloadStart + fixed, entry.end).firstOrNull { it.type == "sinf" } ?: continue
                    val sinfChildren = boxes(data, sinf.payloadStart, sinf.end)
                    val schm = sinfChildren.firstOrNull { it.type == "schm" }
                    val scheme = if (schm != null && schm.payloadStart + 8 <= schm.end) {
                        ascii(data, schm.payloadStart + 4)
                    } else "cenc"
                    val schi = sinfChildren.firstOrNull { it.type == "schi" } ?: continue
                    val tenc = boxes(data, schi.payloadStart, schi.end).firstOrNull { it.type == "tenc" } ?: continue
                    readTenc(data, tenc, trackId, scheme)?.let { out[trackId] = it }
                }
            }
        }
        return out
    }

    /**
     * Converts an encrypted init segment (`encv`/`enca` + `sinf` + `pssh`) into a clear init segment
     * by restoring original codec FourCCs (`frma`) and removing `sinf` / `pssh` boxes.
     */
    fun decryptInit(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size)
        for (b in boxes(data, 0, data.size)) {
            when (b.type) {
                "pssh" -> Unit
                "moov" -> out.write(writeBox("moov", transformMoov(data, b)))
                else -> out.write(data, b.start, b.end - b.start)
            }
        }
        return out.toByteArray()
    }

    /**
     * Decrypts a CENC (`cenc` or `cbcs`) media segment (`moof`+`mdat`) in memory using [clearKeysHex]
     * (`kid -> key` 32-char lowercase hex) and removes `senc`/`saio`/`saiz` boxes while adjusting `trun.data_offset`.
     */
    @JvmOverloads
    fun decryptMedia(
        data: ByteArray,
        clearKeysHex: Map<String, String>,
        defaultIvSize: Int = 8,
        initTracks: Map<Int, TrackCrypto> = emptyMap()
    ): ByteArray {
        if (clearKeysHex.isEmpty()) return data
        val top = boxes(data, 0, data.size)
        if (top.none { it.type == "moof" }) return data

        val out = ByteArrayOutputStream(data.size)
        var i = 0
        while (i < top.size) {
            val b = top[i]
            if (b.type == "moof" && i + 1 < top.size && top[i + 1].type == "mdat") {
                val mdat = top[i + 1]
                val mdatCopy = data.copyOfRange(mdat.start, mdat.end)
                val (newMoof, patchedMdat) = decryptMoofPair(data, b, mdat, mdatCopy, clearKeysHex, defaultIvSize, initTracks)
                out.write(newMoof)
                out.write(patchedMdat)
                i += 2
            } else {
                out.write(data, b.start, b.end - b.start)
                i++
            }
        }
        return out.toByteArray()
    }

    private fun decryptMoofPair(
        src: ByteArray,
        moof: Box,
        mdat: Box,
        mdatBuf: ByteArray,
        clearKeysHex: Map<String, String>,
        defaultIvSize: Int,
        initTracks: Map<Int, TrackCrypto>
    ): Pair<ByteArray, ByteArray> {
        val moofChildren = boxes(src, moof.payloadStart, moof.end)
        val firstPassTrafDeltas = ArrayList<Pair<Box, Int>>()
        var runningOffsetFromMdatPayload = 0

        for (traf in moofChildren.filter { it.type == "traf" }) {
            val trafChildren = boxes(src, traf.payloadStart, traf.end)
            val tfhd = trafChildren.firstOrNull { it.type == "tfhd" }
            val (trackId, defaultSampleSize, baseDataOffset) = parseTfhd(src, tfhd)
            val crypto = initTracks[trackId]
            val keyHex = crypto?.kidHex?.lowercase()?.let { clearKeysHex[it] } ?: clearKeysHex.values.first()
            val keyBytes = Drm.hexToBytes(keyHex)
            val ivPref = crypto?.ivSize?.takeIf { it > 0 } ?: defaultIvSize

            val truns = trafChildren.filter { it.type == "trun" }
            val sampleOffsets = ArrayList<Pair<Int, Int>>() // (offset in mdatBuf, size)
            for (trun in truns) {
                val trunInfo = parseTrun(src, trun, defaultSampleSize)
                var posInSrc = when {
                    trunInfo.dataOffset != null -> (baseDataOffset?.toInt() ?: moof.start) + trunInfo.dataOffset
                    else -> mdat.payloadStart + runningOffsetFromMdatPayload
                }
                for (sz in trunInfo.sampleSizes) {
                    val relInMdat = posInSrc - mdat.start
                    sampleOffsets += relInMdat to sz
                    posInSrc += sz
                    runningOffsetFromMdatPayload = maxOf(runningOffsetFromMdatPayload, posInSrc - mdat.payloadStart)
                }
            }

            val sencBox = trafChildren.firstOrNull { it.type == "senc" }
            if (sencBox != null && sampleOffsets.isNotEmpty()) {
                val samples = parseSenc(src, sencBox, ivPref, sampleOffsets.size)
                val isCbcs = crypto?.scheme == "cbcs"
                for (idx in samples.indices) {
                    if (idx >= sampleOffsets.size) break
                    val (mdatOff, sampleLen) = sampleOffsets[idx]
                    if (mdatOff < 8 || mdatOff + sampleLen > mdatBuf.size) continue
                    val enc = samples[idx]
                    val iv = if (enc.iv.isNotEmpty()) enc.iv else (crypto?.constantIv ?: ByteArray(16))
                    if (isCbcs) {
                        decryptCbcsSample(
                            mdatBuf, mdatOff, sampleLen, keyBytes, iv, enc.subsamples,
                            crypto.cryptBlocks, crypto.skipBlocks
                        )
                    } else {
                        decryptCencSample(mdatBuf, mdatOff, sampleLen, keyBytes, iv, enc.subsamples)
                    }
                }
            } else if (crypto?.scheme == "cbcs" && crypto.constantIv != null) {
                for ((mdatOff, sampleLen) in sampleOffsets) {
                    if (mdatOff < 8 || mdatOff + sampleLen > mdatBuf.size) continue
                    decryptCbcsSample(
                        mdatBuf, mdatOff, sampleLen, keyBytes, crypto.constantIv, emptyList(),
                        crypto.cryptBlocks, crypto.skipBlocks
                    )
                }
            }
            firstPassTrafDeltas += traf to trackId
        }

        // Rebuild moof stripping senc/saio/saiz so unencrypted players don't trip on CENC boxes
        var strippedBytes = 0
        for (c in moofChildren) {
            if (c.type == "traf") {
                for (tc in boxes(src, c.payloadStart, c.end)) {
                    if (isEncryptionOnlyTrafBox(src, tc)) strippedBytes += (tc.end - tc.start)
                }
            } else if (isEncryptionOnlyTrafBox(src, c)) {
                strippedBytes += (c.end - c.start)
            }
        }

        val origMoofSize = moof.end - moof.start
        val moofPayload = ByteArrayOutputStream(moof.end - moof.payloadStart)
        for (c in moofChildren) {
            when {
                c.type == "traf" -> moofPayload.write(writeBox("traf", rebuildTraf(src, c, strippedBytes, origMoofSize)))
                isEncryptionOnlyTrafBox(src, c) -> Unit
                else -> moofPayload.write(src, c.start, c.end - c.start)
            }
        }
        return writeBox("moof", moofPayload.toByteArray()) to mdatBuf
    }

    private fun isEncryptionOnlyTrafBox(src: ByteArray, b: Box): Boolean = when (b.type) {
        "senc", "saio", "saiz" -> true
        "sbgp", "sgpd" -> b.payloadStart + 8 <= b.end && ascii(src, b.payloadStart + 4) == "seig"
        else -> false
    }

    private fun rebuildTraf(src: ByteArray, traf: Box, moofDelta: Int, origMoofSize: Int): ByteArray {
        val out = ByteArrayOutputStream(traf.end - traf.payloadStart)
        for (c in boxes(src, traf.payloadStart, traf.end)) {
            when {
                isEncryptionOnlyTrafBox(src, c) -> Unit
                c.type == "trun" && moofDelta != 0 -> {
                    val copy = src.copyOfRange(c.start, c.end)
                    val flags = i32(copy, 8) and 0x00FFFFFF
                    if ((flags and 0x000001) != 0 && copy.size >= 20) {
                        val origOffset = i32(copy, 16)
                        if (origOffset >= origMoofSize) writeI32(copy, 16, origOffset - moofDelta)
                    }
                    out.write(copy)
                }
                else -> out.write(src, c.start, c.end - c.start)
            }
        }
        return out.toByteArray()
    }

    private fun decryptCencSample(
        buf: ByteArray,
        sampleOff: Int,
        sampleLen: Int,
        key: ByteArray,
        iv: ByteArray,
        subsamples: List<Subsample>
    ) {
        val iv16 = ByteArray(16)
        System.arraycopy(iv, 0, iv16, 0, minOf(16, iv.size))
        val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv16))
        }
        if (subsamples.isEmpty()) {
            val dec = cipher.doFinal(buf, sampleOff, sampleLen)
            System.arraycopy(dec, 0, buf, sampleOff, minOf(sampleLen, dec.size))
            return
        }

        // Gather all protected bytes of this sample so CTR keystream stays continuous across subsamples
        val totalEnc = subsamples.sumOf { it.encryptedBytes }
        if (totalEnc <= 0) return
        val combined = ByteArray(totalEnc)
        var p = sampleOff
        var dst = 0
        for (sub in subsamples) {
            p += sub.clearBytes
            val take = minOf(sub.encryptedBytes, sampleOff + sampleLen - p).coerceAtLeast(0)
            if (take > 0) System.arraycopy(buf, p, combined, dst, take)
            p += take
            dst += take
        }
        val dec = cipher.doFinal(combined, 0, dst)
        p = sampleOff
        var srcIdx = 0
        for (sub in subsamples) {
            p += sub.clearBytes
            val put = minOf(sub.encryptedBytes, sampleOff + sampleLen - p).coerceAtLeast(0)
            if (put > 0) System.arraycopy(dec, srcIdx, buf, p, put)
            p += put
            srcIdx += put
        }
    }

    private fun decryptCbcsSample(
        buf: ByteArray,
        sampleOff: Int,
        sampleLen: Int,
        key: ByteArray,
        iv: ByteArray,
        subsamples: List<Subsample>,
        cryptBlocks: Int,
        skipBlocks: Int
    ) {
        val iv16 = ByteArray(16)
        System.arraycopy(iv, 0, iv16, 0, minOf(16, iv.size))
        val ranges = if (subsamples.isEmpty()) {
            listOf(sampleOff to sampleLen)
        } else {
            var cur = sampleOff
            subsamples.mapNotNull { sub ->
                cur += sub.clearBytes
                val take = minOf(sub.encryptedBytes, sampleOff + sampleLen - cur).coerceAtLeast(0)
                val r = if (take > 0) (cur to take) else null
                cur += take
                r
            }
        }
        val cBlocks = if (cryptBlocks <= 0 && skipBlocks == 0) 1 else cryptBlocks
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv16)
        for ((start, len) in ranges) {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            }
            var off = start
            val end = start + len
            while (off + 16 <= end) {
                val cEnd = minOf(off + cBlocks * 16, end - ((end - off) % 16))
                if (cEnd > off) {
                    val dec = cipher.update(buf, off, cEnd - off)
                    if (dec != null) System.arraycopy(dec, 0, buf, off, dec.size)
                }
                off += (cBlocks + skipBlocks) * 16
            }
        }
    }

    private fun transformMoov(src: ByteArray, moov: Box): ByteArray {
        val out = ByteArrayOutputStream(moov.end - moov.payloadStart)
        for (b in boxes(src, moov.payloadStart, moov.end)) {
            when (b.type) {
                "pssh" -> Unit
                "trak" -> out.write(writeBox("trak", transformContainer(src, b) { c ->
                    if (c.type == "mdia") writeBox("mdia", transformContainer(src, c) { mc ->
                        if (mc.type == "minf") writeBox("minf", transformContainer(src, mc) { mnc ->
                            if (mnc.type == "stbl") writeBox("stbl", transformContainer(src, mnc) { sc ->
                                if (sc.type == "stsd") writeBox("stsd", transformStsd(src, sc)) else src.copyOfRange(sc.start, sc.end)
                            }) else src.copyOfRange(mnc.start, mnc.end)
                        }) else src.copyOfRange(mc.start, mc.end)
                    }) else src.copyOfRange(c.start, c.end)
                }))
                else -> out.write(src, b.start, b.end - b.start)
            }
        }
        return out.toByteArray()
    }

    private fun transformContainer(src: ByteArray, container: Box, childTransform: (Box) -> ByteArray): ByteArray {
        val out = ByteArrayOutputStream(container.end - container.payloadStart)
        for (c in boxes(src, container.payloadStart, container.end)) {
            out.write(childTransform(c))
        }
        return out.toByteArray()
    }

    private fun transformStsd(src: ByteArray, stsd: Box): ByteArray {
        if (stsd.payloadStart + 8 > stsd.end) return src.copyOfRange(stsd.payloadStart, stsd.end)
        val out = ByteArrayOutputStream(stsd.end - stsd.payloadStart)
        out.write(src, stsd.payloadStart, 8) // version/flags + entry_count
        for (entry in boxes(src, stsd.payloadStart + 8, stsd.end)) {
            if (entry.type != "encv" && entry.type != "enca" && entry.type != "encs") {
                out.write(src, entry.start, entry.end - entry.start)
                continue
            }
            val fixed = sampleEntryFixedBytes(src, entry)
            if (fixed == null) {
                out.write(src, entry.start, entry.end - entry.start)
                continue
            }
            val children = boxes(src, entry.payloadStart + fixed, entry.end)
            val sinf = children.firstOrNull { it.type == "sinf" }
            val frmaFormat = sinf?.let { boxes(src, it.payloadStart, it.end).firstOrNull { x -> x.type == "frma" } }
                ?.takeIf { it.payloadStart + 4 <= it.end }
                ?.let { ascii(src, it.payloadStart) }
                ?: entry.type
            val entryPayload = ByteArrayOutputStream(entry.end - entry.payloadStart)
            entryPayload.write(src, entry.payloadStart, fixed)
            for (c in children) {
                if (c.type != "sinf") entryPayload.write(src, c.start, c.end - c.start)
            }
            out.write(writeBox(frmaFormat, entryPayload.toByteArray()))
        }
        return out.toByteArray()
    }

    private fun sampleEntryFixedBytes(src: ByteArray, entry: Box): Int? = when (entry.type) {
        "encv" -> 78.takeIf { entry.payloadStart + 78 <= entry.end }
        "enca" -> {
            val extraV1 = if (entry.payloadStart + 10 <= entry.end && u16(src, entry.payloadStart + 8) == 1) 16 else 0
            (28 + extraV1).takeIf { entry.payloadStart + 28 + extraV1 <= entry.end }
        }
        "encs" -> 8.takeIf { entry.payloadStart + 8 <= entry.end }
        else -> null
    }

    private fun readTrackId(src: ByteArray, trak: Box): Int? {
        val tkhd = firstChild(src, trak, "tkhd") ?: return null
        if (tkhd.payloadStart + 4 > tkhd.end) return null
        val version = src[tkhd.payloadStart].toInt() and 0xFF
        val idOff = tkhd.payloadStart + if (version == 1) 20 else 12
        return if (idOff + 4 <= tkhd.end) i32(src, idOff) else null
    }

    private fun readTenc(src: ByteArray, tenc: Box, trackId: Int, scheme: String): TrackCrypto? {
        val p = tenc.payloadStart
        if (p + 24 > tenc.end) return null
        val version = src[p].toInt() and 0xFF
        val cryptSkip = src[p + 5].toInt() and 0xFF
        val cryptBlocks = if (version >= 1) (cryptSkip ushr 4) and 0x0F else 0
        val skipBlocks = if (version >= 1) cryptSkip and 0x0F else 0
        val ivSize = src[p + 7].toInt() and 0xFF
        val kidHex = (p + 8 until p + 24).joinToString("") { "%02x".format(src[it].toInt() and 0xFF) }
        val constIv = if (ivSize == 0 && p + 25 <= tenc.end) {
            val sz = src[p + 24].toInt() and 0xFF
            if (p + 25 + sz <= tenc.end) src.copyOfRange(p + 25, p + 25 + sz) else null
        } else null
        return TrackCrypto(trackId, scheme, ivSize, kidHex, cryptBlocks, skipBlocks, constIv)
    }

    private data class TfhdInfo(val trackId: Int, val defaultSampleSize: Int, val baseDataOffset: Long?)
    private fun parseTfhd(src: ByteArray, tfhd: Box?): TfhdInfo {
        if (tfhd == null || tfhd.payloadStart + 8 > tfhd.end) return TfhdInfo(1, 0, null)
        val flags = i32(src, tfhd.payloadStart) and 0x00FFFFFF
        val trackId = i32(src, tfhd.payloadStart + 4)
        var pos = tfhd.payloadStart + 8
        var baseOffset: Long? = null
        if ((flags and 0x000001) != 0 && pos + 8 <= tfhd.end) { baseOffset = i64(src, pos); pos += 8 }
        if ((flags and 0x000002) != 0) pos += 4
        if ((flags and 0x000008) != 0) pos += 4
        var defSize = 0
        if ((flags and 0x000010) != 0 && pos + 4 <= tfhd.end) { defSize = i32(src, pos) }
        return TfhdInfo(trackId, defSize, baseOffset)
    }

    private data class TrunInfo(val dataOffset: Int?, val sampleSizes: List<Int>)
    private fun parseTrun(src: ByteArray, trun: Box, defaultSampleSize: Int): TrunInfo {
        if (trun.payloadStart + 8 > trun.end) return TrunInfo(null, emptyList())
        val flags = i32(src, trun.payloadStart) and 0x00FFFFFF
        val count = i32(src, trun.payloadStart + 4).coerceAtLeast(0)
        var pos = trun.payloadStart + 8
        var dataOffset: Int? = null
        if ((flags and 0x000001) != 0 && pos + 4 <= trun.end) { dataOffset = i32(src, pos); pos += 4 }
        if ((flags and 0x000004) != 0) pos += 4
        val hasDur = (flags and 0x000100) != 0
        val hasSize = (flags and 0x000200) != 0
        val hasFlags = (flags and 0x000400) != 0
        val hasCto = (flags and 0x000800) != 0
        val sizes = ArrayList<Int>(count)
        for (i in 0 until count) {
            if (hasDur) pos += 4
            val sz = if (hasSize && pos + 4 <= trun.end) i32(src, pos) else defaultSampleSize
            if (hasSize) pos += 4
            if (hasFlags) pos += 4
            if (hasCto) pos += 4
            sizes += sz
        }
        return TrunInfo(dataOffset, sizes)
    }

    private fun parseSenc(src: ByteArray, senc: Box, preferredIvSize: Int, expectedSamples: Int): List<SampleEnc> {
        if (senc.payloadStart + 8 > senc.end) return emptyList()
        val flags = i32(src, senc.payloadStart) and 0x00FFFFFF
        val count = i32(src, senc.payloadStart + 4).coerceAtLeast(0)
        if (count == 0) return emptyList()
        val hasSub = (flags and 0x000002) != 0
        val ivCandidates = if (!hasSub) {
            val exact = (senc.end - (senc.payloadStart + 8)) / count
            listOf(if (exact == 16) 16 else if (exact == 8) 8 else preferredIvSize)
        } else {
            listOf(preferredIvSize, 8, 16).distinct()
        }
        for (ivSize in ivCandidates) {
            parseSencWithIvSize(src, senc, ivSize, count, hasSub)?.let { return it }
        }
        return if (expectedSamples > 0) parseSencWithIvSize(src, senc, preferredIvSize, count, hasSub).orEmpty() else emptyList()
    }

    private fun parseSencWithIvSize(src: ByteArray, senc: Box, ivSize: Int, count: Int, hasSub: Boolean): List<SampleEnc>? {
        var pos = senc.payloadStart + 8
        val out = ArrayList<SampleEnc>(count)
        for (i in 0 until count) {
            if (pos + ivSize > senc.end) return null
            val iv = if (ivSize > 0) src.copyOfRange(pos, pos + ivSize) else ByteArray(0)
            pos += ivSize
            val subs = if (hasSub) {
                if (pos + 2 > senc.end) return null
                val subCount = u16(src, pos)
                pos += 2
                if (pos + subCount * 6 > senc.end) return null
                ArrayList<Subsample>(subCount).apply {
                    for (j in 0 until subCount) {
                        add(Subsample(u16(src, pos), i32(src, pos + 2)))
                        pos += 6
                    }
                }
            } else emptyList()
            out += SampleEnc(iv, subs)
        }
        return if (pos == senc.end) out else null
    }

    private fun firstChild(src: ByteArray, parent: Box, type: String): Box? =
        boxes(src, parent.payloadStart, parent.end).firstOrNull { it.type == type }

    private fun boxes(src: ByteArray, start: Int, end: Int): List<Box> {
        val list = ArrayList<Box>()
        var pos = start
        while (pos + 8 <= end) {
            val sz32 = u32(src, pos)
            val type = ascii(src, pos + 4)
            var headerSize = 8
            val boxEnd = when (sz32) {
                0L -> end
                1L -> {
                    if (pos + 16 > end) return list
                    headerSize = 16
                    (pos + i64(src, pos + 8)).toInt().coerceAtMost(end)
                }
                else -> (pos + sz32).toInt().coerceAtMost(end)
            }
            if (boxEnd < pos + headerSize) break
            list += Box(type, pos, headerSize, boxEnd)
            pos = boxEnd
        }
        return list
    }

    private fun writeBox(type: String, payload: ByteArray): ByteArray {
        val total = 8 + payload.size
        val buf = ByteArray(total)
        writeI32(buf, 0, total)
        val tb = type.toByteArray(Charsets.US_ASCII)
        System.arraycopy(tb, 0, buf, 4, minOf(4, tb.size))
        System.arraycopy(payload, 0, buf, 8, payload.size)
        return buf
    }

    private fun ascii(src: ByteArray, off: Int): String = String(src, off, 4, Charsets.US_ASCII)
    private fun u16(src: ByteArray, off: Int): Int = ((src[off].toInt() and 0xFF) shl 8) or (src[off + 1].toInt() and 0xFF)
    private fun i32(src: ByteArray, off: Int): Int =
        ((src[off].toInt() and 0xFF) shl 24) or ((src[off + 1].toInt() and 0xFF) shl 16) or
            ((src[off + 2].toInt() and 0xFF) shl 8) or (src[off + 3].toInt() and 0xFF)
    private fun u32(src: ByteArray, off: Int): Long = i32(src, off).toLong() and 0xFFFFFFFFL
    private fun i64(src: ByteArray, off: Int): Long =
        ((i32(src, off).toLong() and 0xFFFFFFFFL) shl 32) or u32(src, off + 4)

    private fun writeI32(dst: ByteArray, off: Int, v: Int) {
        dst[off] = ((v ushr 24) and 0xFF).toByte()
        dst[off + 1] = ((v ushr 16) and 0xFF).toByte()
        dst[off + 2] = ((v ushr 8) and 0xFF).toByte()
        dst[off + 3] = (v and 0xFF).toByte()
    }
}
