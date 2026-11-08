package tv.clean.home;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings that can be changed at runtime (from the phone setup app over ADB, see Config) and fall back to
 *  config.xml / BuildConfig. Lets one public APK serve everyone: nothing personal or secret is compiled in. */
final class Cfg {
    static Context app;

    static SharedPreferences p(Context c) { if (app == null) app = c.getApplicationContext(); return app.getSharedPreferences("cfg", Context.MODE_PRIVATE); }

    /** Text setting: phone-set value, else the resource default. */
    static String s(Context c, String key, int res) {
        String v = p(c).getString(key, null);
        return v == null || v.isEmpty() ? c.getString(res) : v;
    }

    /** API key / endpoint: phone-set value, else whatever was compiled in (secrets.properties, often empty). */
    static String key(String key, String compiled) {
        if (app == null) return compiled;
        String v = app.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString(key, null);
        return v == null || v.isEmpty() ? compiled : v;
    }
}
