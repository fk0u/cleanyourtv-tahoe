package tech.kousozo.tahoetv.desktop

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import tech.kousozo.tahoetv.core.SlateTone

/** Tahoe's own "no signal" sound ([SlateTone]): the motif and a long soft tone, three rounds, once per channel every ten minutes. */
object Chime {
    private var clip: Clip? = null
    private var lastKey = ""
    private var lastAt = 0L

    @Synchronized fun noSignal(key: String) {
        val now = System.currentTimeMillis()
        if (key == lastKey && now - lastAt < 600_000) return
        lastKey = key; lastAt = now
        runCatching {
            stop()
            val pcm = SlateTone.pcm()
            val bytes = ByteArray(pcm.size * 2) { i -> val v = pcm[i / 2].toInt(); (if (i % 2 == 0) v and 0xff else v shr 8).toByte() } // little-endian
            val c = AudioSystem.getClip()
            c.open(AudioFormat(SlateTone.RATE.toFloat(), 16, 1, true, false), bytes, 0, bytes.size)
            c.loop(SlateTone.REPEATS - 1)
            clip = c
        }
    }

    /** The picture came back, or the window closed. */
    @Synchronized fun stop() { runCatching { clip?.stop(); clip?.close() }; clip = null }
}
