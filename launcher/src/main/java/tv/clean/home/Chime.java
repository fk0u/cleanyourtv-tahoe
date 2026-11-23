package tv.clean.home;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import tech.kousozo.tahoetv.core.SlateTone;

/** Plays Tahoe's own "no signal" chime ([SlateTone]) once; short, soft, made from code. */
final class Chime {
    private static AudioTrack track;
    private Chime() {}

    /** The channel came back, or the viewer left: stop at once. */
    static synchronized void stop() { try { if (track != null) { track.stop(); track.release(); } } catch (Exception ignored) {} track = null; }

    private static String lastKey = ""; private static long lastAt;

    /** [key] names the channel: the same one keeps quiet for 10 minutes, so its background retries don't beep over and over. */
    static synchronized void noSignal(String key) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (key.equals(lastKey) && now - lastAt < 600_000) return;
        lastKey = key; lastAt = now;
        try {
            if (track != null) { track.release(); track = null; }
            short[] pcm = SlateTone.pcm();
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SlateTone.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(pcm.length * 2).setTransferMode(AudioTrack.MODE_STATIC).build();
            t.write(pcm, 0, pcm.length);
            t.setLoopPoints(0, pcm.length, SlateTone.REPEATS - 1); // the whole cycle, three rounds, then quiet
            t.play();
            track = t;
        } catch (Exception ignored) { /* no sound is better than a crash */ }
    }
}
