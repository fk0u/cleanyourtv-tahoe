package tv.clean.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;
import tech.kousozo.tahoetv.core.AccountApi;
import tech.kousozo.tahoetv.core.DisplayProfile;

/** The Tahoe account on this TV. The phone (or the ADB shell during setup) hands over the server address and a sign-in
 *  token; the token is sealed by an Android Keystore key. The profile's display choices land in the same prefs the TV's own
 *  settings use ([Cfg]), the profile photo in [Avatar]'s file and the wallpaper in the personal wallpapers folder, so every
 *  screen already reads them. Changes made on the TV are pushed back, so the phone and TV stay in step. */
final class Acct {
    private static final String ALIAS = "tahoe_tv_account_v1";
    private Acct() {}

    private static SharedPreferences p(Context c) { return c.getApplicationContext().getSharedPreferences("account", Context.MODE_PRIVATE); }

    static boolean linked(Context c) { return token(c) != null && !p(c).getString("url", "").isEmpty(); }
    static String email(Context c) { return p(c).getString("email", ""); }

    // ---- display choices, read by the screens (defaults = how the TV looked before accounts)
    static boolean clock24(Context c) { return Cfg.p(c).contains("clock24") ? Cfg.p(c).getBoolean("clock24", true) : android.text.format.DateFormat.is24HourFormat(c); }
    static boolean clockSeconds(Context c) { return Cfg.p(c).getBoolean("clock_seconds", false); }
    static String clockSize(Context c) { return Cfg.p(c).getString("clock_size", "m"); }
    static float clockScale(Context c) { switch (clockSize(c)) { case "s": return 0.8f; case "l": return 1.2f; default: return 1f; } }
    static boolean greeting(Context c) { return Cfg.p(c).getBoolean("greeting_on", true); }
    /** "HH.mm" / "h.mm", with seconds when chosen. */
    static String clockPattern(Context c) { return clockPattern(c, true); }
    /** [withSeconds] false for screens that only redraw once in a while (the player overlay). */
    static String clockPattern(Context c, boolean withSeconds) { return (clock24(c) ? "HH.mm" : "h.mm") + (withSeconds && clockSeconds(c) ? ".ss" : ""); }
    /** How often a clock must redraw. */
    static long clockTickMs(Context c) { return clockSeconds(c) ? 1000 : 20_000; }

    // ---- token sealing
    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (ks.getKey(ALIAS, null) instanceof SecretKey) return (SecretKey) ks.getKey(ALIAS, null);
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return g.generateKey();
    }
    private static String seal(String s) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key());
        byte[] ct = c.doFinal(s.getBytes("UTF-8")), iv = c.getIV(), all = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, all, 0, iv.length); System.arraycopy(ct, 0, all, iv.length, ct.length);
        return Base64.encodeToString(all, Base64.NO_WRAP);
    }
    private static String unseal(String s) {
        try {
            byte[] b = Base64.decode(s, Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, b, 0, 12));
            return new String(c.doFinal(b, 12, b.length - 12), "UTF-8");
        } catch (Exception e) { return null; }
    }
    private static String token(Context c) { String s = p(c).getString("token", null); return s == null ? null : unseal(s); }

    private static AccountApi api(Context c) {
        String t = token(c), u = p(c).getString("url", "");
        if (t == null || u.isEmpty()) return null;
        try { return new AccountApi(u, t, android.os.Build.MODEL); } catch (Exception e) { return null; }
    }

    // ---- linking
    /** Takes the session handed over by the phone; an empty [token] signs the TV out. Pulls the profile in the background. */
    static void link(Context c, String url, String token, String email) {
        Context app = c.getApplicationContext();
        if (token == null || token.isEmpty()) { unlink(app, true); return; }
        try { new AccountApi(url, token, ""); } catch (Exception e) { tech.kousozo.tahoetv.core.DiagLog.add("account", "account: refused address (" + e.getMessage() + ")"); return; }
        try { p(app).edit().putString("url", url.trim()).putString("token", seal(token)).putString("email", email == null ? "" : email).apply(); }
        catch (Exception e) { tech.kousozo.tahoetv.core.DiagLog.add("account", "account: could not store the sign-in"); return; }
        pull(app, null);
    }

    static void unlink(Context c, boolean remote) {
        AccountApi a = remote ? api(c) : null;
        if (a != null) Net.bg.execute(() -> { try { a.logout(); } catch (Exception ignored) {} });
        p(c).edit().clear().apply();
    }

    /** Fetches the profile and applies it. [done] runs on the main thread. */
    static void pull(Context c, Runnable done) {
        Context app = c.getApplicationContext();
        android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        Net.bg.execute(() -> {
            AccountApi a = api(app);
            if (a == null) return;
            try {
                AccountApi.Profile r = a.profile();
                DisplayProfile d = r.getSettings();
                SharedPreferences.Editor e = Cfg.p(app).edit();
                e.putBoolean("clock24", d.getClock24()).putBoolean("clock_seconds", d.getClockSeconds()).putString("clock_size", d.getClockSize())
                        .putBoolean("greeting_on", d.getGreeting()).putBoolean("reduce_motion", d.getReduceMotion());
                if (!d.getDisplayName().isEmpty()) e.putString("user_name", d.getDisplayName());
                e.apply();
                p(app).edit().putInt("rev", r.getRev()).putString("profile", d.toJson().toString()).apply();
                picture(app, a, d.getAvatarId(), "avatar");
                picture(app, a, d.getWallpaperId(), "wallpaper", d.getWallpaperDim());
                tech.kousozo.tahoetv.core.DiagLog.add("account", "account: profile applied (rev " + r.getRev() + ")");
            } catch (Exception ex) { tech.kousozo.tahoetv.core.DiagLog.add("account", "account: pull failed (" + ex.getClass().getSimpleName() + ")"); }
            if (Home.live != null) Home.live.runOnUiThread(Home.live::recreate);
            if (done != null) ui.post(done);
        });
    }

    private static void picture(Context c, AccountApi a, String id, String kind) throws Exception { picture(c, a, id, kind, 0); }

    private static void picture(Context c, AccountApi a, String id, String kind, int dim) throws Exception {
        boolean avatar = kind.equals("avatar");
        File out = avatar ? Avatar.file(c) : new File(wallDir(c), "account_wallpaper.jpg");
        String stamp = id + ":" + dim;
        if (id.isEmpty()) { if (p(c).contains("have_" + kind)) { out.delete(); p(c).edit().remove("have_" + kind).apply(); if (avatar) Avatar.cached = null; } return; }
        if (stamp.equals(p(c).getString("have_" + kind, "")) && out.exists()) return;
        byte[] raw = a.downloadFile(id);
        if (avatar) { try (FileOutputStream f = new FileOutputStream(out)) { f.write(raw); } Avatar.cached = null; }
        else { // the darkness chosen on the phone is baked into the picture once, so showing it costs nothing at runtime
            Bitmap bm = BitmapFactory.decodeByteArray(raw, 0, raw.length);
            if (bm == null) return;
            Bitmap m = bm.copy(Bitmap.Config.ARGB_8888, true);
            new Canvas(m).drawColor(android.graphics.Color.argb(Math.round(Math.max(0, Math.min(80, dim)) * 2.55f), 0, 0, 0));
            try (FileOutputStream f = new FileOutputStream(out)) { m.compress(Bitmap.CompressFormat.JPEG, 90, f); }
        }
        p(c).edit().putString("have_" + kind, stamp).apply();
    }

    private static File wallDir(Context c) { File d = c.getExternalFilesDir("wallpapers"); if (d != null) d.mkdirs(); return d; }

    // ---- handoff: continue on this TV what another device of the account was watching
    /** Set by [checkHandoff] when another device (phone, desktop) was playing something recently; null otherwise. */
    static volatile tech.kousozo.tahoetv.core.Handoff pending;
    private static String lastPublished = "";

    static boolean handoffOn(Context c) { return Cfg.p(c).getBoolean("handoff_on", true); }

    /** Looks at the account for something to carry on; [changed] runs on the main thread when the answer is new. */
    static void checkHandoff(Context c, Runnable changed) {
        if (!linked(c) || !handoffOn(c)) { if (pending != null) { pending = null; if (changed != null) changed.run(); } return; }
        Context app = c.getApplicationContext();
        android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        Net.bg.execute(() -> {
            AccountApi a = api(app);
            if (a == null) return;
            try {
                tech.kousozo.tahoetv.core.Handoff h = a.handoff();
                if (h != null && "tv".equals(h.getKind()) && android.os.Build.MODEL.equals(h.getDevice())) h = null; // this TV's own
                String was = pending == null ? "" : pending.getChannelId() + pending.getUrl(), now = h == null ? "" : h.getChannelId() + h.getUrl();
                pending = h;
                if (!was.equals(now) && changed != null) ui.post(changed);
            } catch (Exception ex) { tech.kousozo.tahoetv.core.DiagLog.add("account", "handoff check failed (" + ex.getClass().getSimpleName() + ")"); }
        });
    }

    /** Tells the account this TV is now playing [name], so the phone and the Mac can offer to continue it. */
    static void publishNowPlaying(Context c, String channelId, String url, String name) {
        if (!linked(c) || !handoffOn(c)) return;
        String key = channelId + "|" + url;
        if (key.equals(lastPublished)) return;
        lastPublished = key;
        Context app = c.getApplicationContext();
        Net.bg.execute(() -> {
            AccountApi a = api(app);
            if (a == null) return;
            try { a.setHandoff(new tech.kousozo.tahoetv.core.Handoff(channelId == null ? "" : channelId, url == null ? "" : url, name == null ? "" : name, android.os.Build.MODEL, "tv", 0, 0)); }
            catch (Exception ex) { tech.kousozo.tahoetv.core.DiagLog.add("account", "handoff publish failed (" + ex.getClass().getSimpleName() + ")"); }
        });
    }

    /** Sends what was changed on the TV up to the account (keeps the picture ids and anything else the server has). */
    static void push(Context c) {
        if (!linked(c)) return;
        Context app = c.getApplicationContext();
        Net.bg.execute(() -> {
            AccountApi a = api(app);
            if (a == null) return;
            try {
                JSONObject base = new JSONObject(p(app).getString("profile", "{}"));
                SharedPreferences cfg = Cfg.p(app);
                base.put("clock24", clock24(app)).put("clockSeconds", clockSeconds(app)).put("clockSize", clockSize(app))
                        .put("greeting", greeting(app)).put("reduceMotion", cfg.getBoolean("reduce_motion", false));
                DisplayProfile d = DisplayProfile.fromJson(base);
                int rev = p(app).getInt("rev", 0);
                Integer saved = a.saveProfile(d, rev);
                if (saved == null) { rev = a.profile().getRev(); saved = a.saveProfile(d, rev); }
                if (saved != null) p(app).edit().putInt("rev", saved).putString("profile", d.toJson().toString()).apply();
            } catch (Exception ex) { tech.kousozo.tahoetv.core.DiagLog.add("account", "account: push failed (" + ex.getClass().getSimpleName() + ")"); }
        });
    }
}
