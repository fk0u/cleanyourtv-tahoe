package tv.clean.home;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tech.kousozo.tahoetv.core.LocalTv;

/** The "Anak" profile: Live TV limited to kids' and documentary channels, only apps the owner allowed, no settings.
 *  Leaving it (to "Saya" or "Tamu") takes a 4-digit PIN, created the first time the profile is chosen. The PIN is kept
 *  as a salted SHA-256 hash in the launcher's prefs, never in plain text. */
final class Kids {
    private Kids() {}

    static final String PROFILE = "kids";
    /** Kid-friendly apps allowed out of the box when they're installed (the owner can add or remove any). */
    static final List<String> DEFAULT_APPS = Arrays.asList(LiveTv.PKG, "com.google.android.youtube.tvkids");

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("home", Context.MODE_PRIVATE); }

    /** True while the kids profile is the current one (also read by the Live TV and guide activities). */
    static boolean on(Context c) { return PROFILE.equals(prefs(c).getString("profile", "main")); }

    // ------------------------------------------------------------------ channels

    static boolean channelAllowed(LocalTv.Entry e) {
        return LocalTv.INSTANCE.inCategory(e, LocalTv.Category.Kids) || LocalTv.INSTANCE.inCategory(e, LocalTv.Category.Documentary);
    }

    /** [l] as it should be shown now: everything, or only kids' and documentary channels in the kids profile. */
    static List<LocalTv.Entry> channels(Context c, List<LocalTv.Entry> l) {
        if (!on(c)) return l;
        List<LocalTv.Entry> out = new ArrayList<>();
        for (LocalTv.Entry e : l) if (channelAllowed(e)) out.add(e);
        return out;
    }

    // ------------------------------------------------------------------ apps

    static Set<String> apps(Context c) {
        String s = prefs(c).getString("kids_apps", null);
        if (s == null) return new LinkedHashSet<>(DEFAULT_APPS);
        return s.isEmpty() ? new LinkedHashSet<>() : new LinkedHashSet<>(Arrays.asList(s.split(",")));
    }

    static boolean appAllowed(Context c, String pkg) { return apps(c).contains(pkg); }

    static void setApp(Context c, String pkg, boolean allowed) {
        Set<String> s = apps(c);
        if (allowed) s.add(pkg); else s.remove(pkg);
        prefs(c).edit().putString("kids_apps", android.text.TextUtils.join(",", s)).apply();
    }

    // ------------------------------------------------------------------ PIN

    static boolean hasPin(Context c) { return prefs(c).contains("kids_pin"); }

    static void setPin(Context c, String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String s = hex(salt);
        prefs(c).edit().putString("kids_salt", s).putString("kids_pin", hash(s, pin))
                .remove("kids_pin_fails").remove("kids_pin_until").apply();
    }

    static boolean checkPin(Context c, String pin) {
        SharedPreferences p = prefs(c);
        String want = p.getString("kids_pin", null), salt = p.getString("kids_salt", "");
        return want != null && MessageDigest.isEqual(want.getBytes(), hash(salt, pin).getBytes());
    }

    static long pinLockLeftMs(Context c) {
        return Math.max(0L, prefs(c).getLong("kids_pin_until", 0L) - System.currentTimeMillis());
    }

    static int recordWrongPin(Context c) {
        SharedPreferences p = prefs(c);
        int fails = p.getInt("kids_pin_fails", 0) + 1;
        int sec = fails >= 5 ? 300 : fails >= 3 ? 30 : 2;
        p.edit().putInt("kids_pin_fails", fails)
                .putLong("kids_pin_until", System.currentTimeMillis() + sec * 1000L).apply();
        tech.kousozo.tahoetv.core.DiagLog.add("kids", "PIN Anak salah ke-" + fails + ", jeda " + sec + " dtk");
        return sec;
    }

    static void clearPinBackoff(Context c) {
        prefs(c).edit().remove("kids_pin_fails").remove("kids_pin_until").apply();
    }

    static String hash(String salt, String pin) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest((salt + ":" + pin).getBytes("UTF-8"))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
