package tv.clean.home;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import tech.kousozo.tahoetv.core.DeviceProfile;
import tech.kousozo.tahoetv.core.SourceHealth;

/** Inspects Android TV RAM, CPU/ABI, display capabilities, and hardware codecs once at boot (#38). */
final class DeviceProfiler {
    private DeviceProfiler() {}

    private static volatile DeviceProfile.Profile cached;

    static DeviceProfile.Profile current(Context c) {
        if (cached != null) return cached;
        return refresh(c);
    }

    static DeviceProfile.Profile refresh(Context c) {
        Context app = c.getApplicationContext();
        ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        int ramMb = 1024;
        boolean lowRam = false;
        if (am != null) {
            am.getMemoryInfo(mi);
            if (mi.totalMem > 0) ramMb = (int) (mi.totalMem / (1024L * 1024L));
            else ramMb = Math.max(512, am.getMemoryClass() * 4);
            lowRam = am.isLowRamDevice();
        }
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        String abi = Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown";

        DisplayMetrics dm = app.getResources().getDisplayMetrics();
        int widthPx = dm.widthPixels, heightPx = dm.heightPixels;
        int refreshHz = 60;
        boolean hdr = false;
        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        if (wm != null) {
            Display disp = wm.getDefaultDisplay();
            if (disp != null) {
                refreshHz = Math.max(24, Math.round(disp.getRefreshRate()));
                if (Build.VERSION.SDK_INT >= 24) {
                    Display.HdrCapabilities hc = disp.getHdrCapabilities();
                    hdr = hc != null && hc.getSupportedHdrTypes().length > 0;
                }
            }
        }

        boolean hwHevc = false, hwAv1 = false, hwAc3 = false;
        try {
            MediaCodecInfo[] codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
            for (MediaCodecInfo info : codecs) {
                if (info.isEncoder()) continue;
                boolean hw = Build.VERSION.SDK_INT >= 29
                        ? info.isHardwareAccelerated()
                        : !info.getName().startsWith("OMX.google.") && !info.getName().startsWith("c2.android.");
                if (!hw) continue;
                for (String type : info.getSupportedTypes()) {
                    if (type.equalsIgnoreCase("video/hevc")) hwHevc = true;
                    else if (type.equalsIgnoreCase("video/av01")) hwAv1 = true;
                    else if (type.equalsIgnoreCase("audio/ac3") || type.equalsIgnoreCase("audio/eac3")) hwAc3 = true;
                }
            }
        } catch (Exception ignored) {}

        DeviceProfile.Specs specs = new DeviceProfile.Specs(
                ramMb, lowRam, cores, Build.VERSION.SDK_INT, abi,
                widthPx, heightPx, refreshHz, hdr, hwHevc, hwAv1, hwAc3
        );
        SharedPreferences prefs = app.getSharedPreferences("home", Context.MODE_PRIVATE);
        DeviceProfile.Tier manual = DeviceProfile.Tier.of(prefs.getString("device_tier_override", null));
        DeviceProfile.Profile profile = DeviceProfile.classify(specs, manual);
        cached = profile;
        SourceHealth.setProbeConcurrency(profile.getProbeConcurrency());
        return profile;
    }

    static DeviceProfile.Tier overrideTier(Context c) {
        return DeviceProfile.Tier.of(c.getApplicationContext().getSharedPreferences("home", Context.MODE_PRIVATE)
                .getString("device_tier_override", null));
    }

    static void setOverrideTier(Context c, DeviceProfile.Tier tier) {
        SharedPreferences.Editor ed = c.getApplicationContext().getSharedPreferences("home", Context.MODE_PRIVATE).edit();
        if (tier == null) ed.remove("device_tier_override"); else ed.putString("device_tier_override", tier.getKey());
        ed.apply();
        refresh(c);
    }
}
