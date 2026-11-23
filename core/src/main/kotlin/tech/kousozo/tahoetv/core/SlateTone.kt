package tech.kousozo.tahoetv.core

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Tahoe TV's own "no signal" sound, made from code (no audio file to ship): three soft bell notes, A5, E5 and a low A4
 *  that rings out, instead of the harsh one-kilohertz TV beep. Quiet by design (peak about -7 dB) so it never startles. */
object SlateTone {
    const val RATE = 22_050
    private const val PEAK = 0.45

    private class Note(val hz: Double, val start: Double, val length: Double, val gain: Double)
    private val MOTIF = listOf(Note(880.0, 0.00, 0.30, 0.9), Note(659.25, 0.20, 0.34, 0.9), Note(440.0, 0.42, 0.70, 1.0))

    /** Signed 16-bit mono samples. Deterministic. */
    @JvmStatic fun pcm(): ShortArray {
        val total = (RATE * (MOTIF.maxOf { it.start + it.length } + 0.05)).toInt()
        val mix = DoubleArray(total)
        for (n in MOTIF) {
            val from = (n.start * RATE).toInt(); val len = (n.length * RATE).toInt()
            for (i in 0 until len) {
                val t = i.toDouble() / RATE
                val attack = (t / 0.008).coerceAtMost(1.0)           // 8 ms: no click
                val release = ((n.length - t) / 0.02).coerceIn(0.0, 1.0)
                val decay = exp(-t * 5.5)                            // a bell rings and fades
                val tone = sin(2 * PI * n.hz * t) + 0.22 * sin(2 * PI * 2 * n.hz * t) + 0.07 * sin(2 * PI * 3 * n.hz * t)
                if (from + i < total) mix[from + i] += tone * attack * release * decay * n.gain
            }
        }
        val top = mix.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9)
        return ShortArray(total) { (mix[it] / top * PEAK * Short.MAX_VALUE).toInt().toShort() }
    }

    /** The same sound as a complete WAV file (for players that want one). */
    @JvmStatic fun wav(): ByteArray {
        val s = pcm(); val data = s.size * 2
        val b = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray()).put("fmt ".toByteArray())
        b.putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        for (x in s) b.putShort(x)
        return b.array()
    }
}
