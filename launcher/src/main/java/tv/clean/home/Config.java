package tv.clean.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

/** Setup channel for the phone app. Only callers holding WRITE_SECURE_SETTINGS (the ADB shell, the system) may
 *  send it (manifest), so it needs no other auth:
 *    am broadcast -n tv.clean.home/.Config --es user_name Ghani --es city Samarinda --es latitude -0.49 ...
 *    --ez pair true   → result data carries a fresh companion token, so the phone is paired without a code. */
public class Config extends BroadcastReceiver {
    static final String[] KEYS = {"user_name", "city", "latitude", "longitude", "avatar_url",
            "gemini_key", "gemini_model", "youtube_key", "tmdb_key", "ha_url", "ha_token"};

    @Override public void onReceive(Context c, Intent i) {
        JSONObject out = new JSONObject();
        try {
            SharedPreferences.Editor e = Cfg.p(c).edit();
            int n = 0;
            for (String k : KEYS) if (i.hasExtra(k)) { e.putString(k, i.getStringExtra(k)); n++; }
            e.apply();
            if (i.hasExtra("avatar_url")) Avatar.reset(c);
            if (i.hasExtra("dock")) // "pkg1,pkg2,…" for the owner profile
                c.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putString("dock", i.getStringExtra("dock")).putBoolean("dock_v2", true).apply();
            if (i.hasExtra("latitude") || i.hasExtra("city")) // new place: refetch weather/prayer
                c.getSharedPreferences("home", Context.MODE_PRIVATE).edit().remove("weather2_at").remove("prayer2_date").apply();
            out.put("updated", n);
            if (i.getBooleanExtra("pair", false)) {
                byte[] b = new byte[24]; new SecureRandom().nextBytes(b);
                String token = android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
                SharedPreferences cp = c.getSharedPreferences("companion", Context.MODE_PRIVATE);
                Set<String> t = new HashSet<>(cp.getStringSet("tokens", new HashSet<>())); t.add(token);
                cp.edit().putStringSet("tokens", t).apply();
                out.put("token", token);
            }
            out.put("version", c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName);
            if (Home.live != null) Home.live.runOnUiThread(Home.live::recreate); // apply right away
        } catch (Exception ex) {
            try { out.put("error", String.valueOf(ex.getMessage())); } catch (Exception ignored) {}
        }
        setResultCode(1);
        setResultData(out.toString());
    }
}
