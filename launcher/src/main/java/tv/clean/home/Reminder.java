package tv.clean.home;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Set;

/** "Ingatkan saya": a banner when a programme starts, and Live TV switches to it if it's open.
 *  Keys are "url|startMillis" in prefs; set again to cancel. */
public final class Reminder extends BroadcastReceiver {
    // ponytail: alarms are not re-armed after a reboot (Prayer.Boot pattern if users ask)
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("reminders", 0); }

    static boolean isSet(Context c, String url, long start) { return prefs(c).getStringSet("keys", new HashSet<>()).contains(url + "|" + start); }

    /** Sets or cancels; returns true when the reminder is now on. */
    static boolean toggle(Context c, String url, String channel, String title, long start) {
        String key = url + "|" + start;
        Set<String> keys = new HashSet<>(prefs(c).getStringSet("keys", new HashSet<>()));
        PendingIntent pi = PendingIntent.getBroadcast(c, key.hashCode(),
                new Intent(c, Reminder.class).putExtra("url", url).putExtra("channel", channel).putExtra("title", title).putExtra("key", key),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        boolean on = !keys.remove(key);
        if (on) {
            keys.add(key);
            // exact, like the adzan alarm (an inexact one fired up to ~50 min late on the Mi TV)
            if (android.os.Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, start, pi);
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, start, pi);
        } else am.cancel(pi);
        prefs(c).edit().putStringSet("keys", keys).apply();
        return on;
    }

    @Override public void onReceive(Context c, Intent i) {
        String url = i.getStringExtra("url"), channel = i.getStringExtra("channel"), title = i.getStringExtra("title");
        Set<String> keys = new HashSet<>(prefs(c).getStringSet("keys", new HashSet<>()));
        keys.remove(i.getStringExtra("key")); prefs(c).edit().putStringSet("keys", keys).apply();
        LiveTv tv = LiveTv.live;
        if (tv != null) tv.ui.post(() -> { for (int k = 0; k < tv.list.size(); k++) if (tv.list.get(k).getChannel().getUrl().equals(url)) { tv.tune(k); return; } });
        Notif.banner(c, null, "Pengingat acara", title + " dimulai", tv != null ? "Sekarang di " + channel : "Di " + channel + "  ·  buka Live TV untuk menonton");
    }
}
