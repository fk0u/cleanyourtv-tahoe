package tech.kousozo.tahoetv

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import tech.kousozo.tahoetv.core.SlateTone

/** Tahoe's own "no signal" chime ([SlateTone]), played once per channel every ten minutes at most. */
object Chime {
    private var track: AudioTrack? = null
    private var lastKey = ""
    private var lastAt = 0L

    /** The channel came back, or the slate left the screen. */
    @Synchronized fun stop() { runCatching { track?.stop(); track?.release() }; track = null }

    @Synchronized fun noSignal(key: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (key == lastKey && now - lastAt < 600_000) return
        lastKey = key; lastAt = now
        runCatching {
            track?.release()
            val pcm = SlateTone.pcm()
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SlateTone.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
            t.write(pcm, 0, pcm.size); t.setLoopPoints(0, pcm.size, SlateTone.REPEATS - 1); t.play(); track = t
        }
    }
}
