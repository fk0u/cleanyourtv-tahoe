package tv.clean.home;

import android.content.Context;
import android.os.Build;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import tech.kousozo.tahoetv.core.DiagLog;
import tech.kousozo.tahoetv.core.SourceHealth;

/** The TV's side of "Kirim log": installs the shared DiagLog (kept in filesDir, so a crash survives a restart) and
 *  builds the report a paired phone fetches from GET /log. */
final class Diag {
    private Diag() {}

    static void install(Context c) { DiagLog.install(new File(c.getFilesDir(), "diag.log")); }

    static String report(Context c) {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("Perangkat", Build.MANUFACTURER + " " + Build.MODEL);
        d.put("Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        d.put("ABI", String.join(", ", Build.SUPPORTED_ABIS));
        d.put("Memori", Runtime.getRuntime().maxMemory() / (1 << 20) + " MB heap");
        d.put("Profil", Kids.prefs(c).getString("profile", "main"));
        d.put("FFmpeg", ffmpeg());
        return DiagLog.report("Tahoe TV (launcher)", About.version(c), d, "");
    }

    /** "tersedia (versi …)" when the FFmpeg decoder's native library loaded on this device. */
    static String ffmpeg() {
        try {
            return androidx.media3.decoder.ffmpeg.FfmpegLibrary.isAvailable() ? "tersedia (" + androidx.media3.decoder.ffmpeg.FfmpegLibrary.getVersion() + ")" : "tidak tersedia";
        } catch (Throwable t) { return "tidak tersedia"; }
    }
}
