package tech.kousozo.tahoetv.core

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Tahoe TV's own "no signal" sound, made from code (no audio file to ship). One cycle is the Tahoe motif (three bell
 *  notes, A5, E5, A4) followed by a long, warm test tone that swells and breathes, like a TV test card but softer; players
 *  play [REPEATS] cycles and stop as soon as the channel comes back. Clearly audible, never a harsh beep. */
object SlateTone {
    const val RATE = 22_050
    /** How many cycles a player loops (about a minute in all), then it stays quiet. */
    const val REPEATS = 5
    private const val PEAK = 0.62

    private class Note(val hz: Double, val start: Double, val length: Double, val gain: Double, val bell: Boolean)
    private val CYCLE = listOf(
        Note(880.0, 0.00, 0.30, 0.9, true), Note(659.25, 0.20, 0.34, 0.9, true), Note(440.0, 0.42, 0.70, 1.0, true),
        Note(1000.0, 1.30, 7.00, 0.8, false),            // the long test tone
    )
    private const val CYCLE_SECONDS = 11.0                 // the rest is silence before the next round

    /** Signed 16-bit mono samples of ONE cycle. Deterministic. */
    @JvmStatic fun pcm(): ShortArray {
        val total = (RATE * CYCLE_SECONDS).toInt()
        val mix = DoubleArray(total)
        for (n in CYCLE) {
            val from = (n.start * RATE).toInt(); val len = (n.length * RATE).toInt()
            for (i in 0 until len) {
                val t = i.toDouble() / RATE
                val v = if (n.bell) {
                    val attack = (t / 0.008).coerceAtMost(1.0)
                    val release = ((n.length - t) / 0.02).coerceIn(0.0, 1.0)
                    (sin(2 * PI * n.hz * t) + 0.22 * sin(2 * PI * 2 * n.hz * t) + 0.07 * sin(2 * PI * 3 * n.hz * t)) * attack * release * exp(-t * 5.5)
                } else {
                    val fade = minOf(t / 0.6, (n.length - t) / 0.8, 1.0).coerceIn(0.0, 1.0)   // swells in, eases out
                    val breath = 0.82 + 0.18 * sin(2 * PI * 0.8 * t)                          // slow breathing, so it never feels flat
                    (sin(2 * PI * n.hz * t) + 0.30 * sin(2 * PI * n.hz / 2 * t) + 0.10 * sin(2 * PI * 2 * n.hz * t)) * fade * breath
                }
                if (from + i < total) mix[from + i] += v * n.gain
            }
        }
        val top = mix.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9)
        return ShortArray(total) { (mix[it] / top * PEAK * Short.MAX_VALUE).toInt().toShort() }
    }

    /** One cycle as a complete WAV file (for players that want one). */
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
