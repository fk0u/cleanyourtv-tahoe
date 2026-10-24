package tv.clean.home;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.*;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;
import org.json.JSONObject;

/** Prayer times (Aladhan, Kemenag RI method), adzan alarms that survive process death and reboots,
 *  and the full-screen prayer sheet with per-prayer adzan toggles. */
final class Prayer {
    /** Display name, Aladhan key, whether it can carry an adzan. */
    static final String[][] TIMES = {
            {"Imsak", "Imsak", "0"}, {"Subuh", "Fajr", "1"}, {"Syuruq", "Sunrise", "0"},
            {"Dzuhur", "Dhuhr", "1"}, {"Ashar", "Asr", "1"}, {"Maghrib", "Maghrib", "1"}, {"Isya", "Isha", "1"}};

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("home", Context.MODE_PRIVATE); }
    static String today() { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()); }

    static void fetchIfStale(Context c, Runnable done, android.os.Handler ui) {
        SharedPreferences p = prefs(c);
        if (today().equals(p.getString("prayer2_date", ""))) { ui.post(done); return; }
        Net.bg.execute(() -> {
            try {
                String js = Net.get("https://api.aladhan.com/v1/timings/" + new SimpleDateFormat("dd-MM-yyyy", Locale.US).format(new Date())
                        + "?latitude=" + c.getString(R.string.latitude) + "&longitude=" + c.getString(R.string.longitude) + "&method=20");
                JSONObject data = new JSONObject(js).getJSONObject("data");
                JSONObject t = data.getJSONObject("timings"), h = data.getJSONObject("date").getJSONObject("hijri");
                StringBuilder sb = new StringBuilder();
                for (String[] x : TIMES) sb.append(x[0]).append('=').append(t.getString(x[1]).substring(0, 5)).append(';');
                String hijri = h.getString("day") + " " + h.getJSONObject("month").getString("en") + " " + h.getString("year") + " H";
                p.edit().putString("prayer2", sb.toString()).putString("prayer2_date", today()).putString("hijri", hijri).apply();
                schedule(c);
                ui.post(done);
            } catch (Exception ignored) {}
        });
    }

    static LinkedHashMap<String, Long> times(Context c) {
        LinkedHashMap<String, Long> out = new LinkedHashMap<>();
        SharedPreferences p = prefs(c);
        if (!today().equals(p.getString("prayer2_date", ""))) return out;
        for (String part : p.getString("prayer2", "").split(";")) {
            String[] kv = part.split("=");
            if (kv.length != 2) continue;
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(kv[1].substring(0, 2)));
            cal.set(Calendar.MINUTE, Integer.parseInt(kv[1].substring(3, 5)));
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
            out.put(kv[0], cal.getTimeInMillis());
        }
        return out;
    }

    static boolean adzanCapable(String name) { for (String[] x : TIMES) if (x[0].equals(name)) return x[2].equals("1"); return false; }
    static boolean adzanOn(Context c, String name) { return adzanCapable(name) && prefs(c).getBoolean("adzan_" + name, true); }

    /** Next of the five daily prayers after now: {name, epochMs} or null. */
    static Object[] next(Context c) {
        for (Map.Entry<String, Long> e : times(c).entrySet())
            if (adzanCapable(e.getKey()) && e.getValue() > System.currentTimeMillis()) return new Object[]{e.getKey(), e.getValue()};
        return null;
    }

    static String countdown(long at) {
        long m = (at - System.currentTimeMillis()) / 60_000 + 1;
        return m >= 60 ? (m / 60) + " j " + (m % 60) + " m" : m + " menit";
    }

    /** One alarm for the next prayer; the receiver re-arms the following one. */
    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        Object[] n = next(c);
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, new Intent(c, Alarm.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        if (n != null) {
            c.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putString("alarm_for", (String) n[0]).apply();
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, (Long) n[1], pi);
        }
    }

    /** Fires at prayer time: banner over any app, adzan if enabled, then arms the next one. */
    public static class Alarm extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            String name = prefs(c).getString("alarm_for", "");
            Notif.banner(c, null, "Waktu shalat", "Waktunya shalat " + name, c.getString(R.string.city) + " dan sekitarnya");
            if (adzanOn(c, name)) play(c, name);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> schedule(c), 90_000);
        }
    }

    /** Re-arm after reboot (alarms do not survive one); fetch today's times if needed. */
    public static class Boot extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            PendingResult r = goAsync();
            fetchIfStale(c, () -> { schedule(c); r.finish(); }, new android.os.Handler(android.os.Looper.getMainLooper()));
        }
    }

    static MediaPlayer player;

    /** User-supplied adzan in files/adzan/ (adzan_subuh.mp3 for Subuh, adzan.mp3 otherwise); a soft chime if absent. */
    static void play(Context c, String name) {
        File dir = c.getExternalFilesDir("adzan");
        File f = dir == null ? null : new File(dir, name.equals("Subuh") ? "adzan_subuh.mp3" : "adzan.mp3");
        if (f != null && !f.exists() && dir != null) f = new File(dir, "adzan.mp3");
        stop();
        if (f != null && f.exists()) {
            try {
                player = new MediaPlayer();
                player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                player.setDataSource(f.getPath());
                player.setOnCompletionListener(mp -> stop());
                player.prepare(); player.start();
                return;
            } catch (Exception ignored) {}
        }
        ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_ALARM, 70);
        tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 600);
    }

    static void stop() { if (player != null) { try { player.release(); } catch (Exception ignored) {} player = null; } }

    // ---- sheet ----

    static final class Panel extends FrameLayout implements tv.clean.home.Sheet {
        final LinearLayout list;
        final List<String> names = new ArrayList<>();
        int sel;

        Panel(Context c) {
            super(c);
            float d = Ui.dp(c, 1);
            setBackgroundColor(0xF2080A10);
            View glow = new View(c);
            glow.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                    new int[]{0x4D1F6F5C, 0x00000000, 0x33244A8A}));
            addView(glow, new LayoutParams(-1, -1));

            LinearLayout left = new LinearLayout(c); left.setOrientation(LinearLayout.VERTICAL);
            TextView eb = Ui.eyebrow(c, Ui.TEXT_2);
            eb.setText(prefs(c).getString("hijri", "") + "  ·  " + c.getString(R.string.city));
            Object[] n = next(c);
            TextView title = Ui.text(c, Ui.TITLE + 8, 300, Ui.TEXT);
            title.setText(n == null ? "Sampai jumpa besok" : (String) n[0]);
            TextView big = Ui.text(c, Ui.DISPLAY, 200, Ui.TEXT);
            big.setText(n == null ? "" : new SimpleDateFormat("HH.mm", Locale.getDefault()).format(new Date((Long) n[1])));
            TextView cd = Ui.text(c, Ui.HEADLINE, 400, Ui.ACCENT);
            cd.setText(n == null ? "" : "dalam " + countdown((Long) n[1]));
            left.addView(eb); left.addView(title); left.addView(big); left.addView(cd);
            LayoutParams ll = new LayoutParams(-2, -2, Gravity.CENTER_VERTICAL); ll.leftMargin = (int) (64 * d);
            addView(left, ll);

            list = new LinearLayout(c); list.setOrientation(LinearLayout.VERTICAL);
            list.setBackground(new GlassDrawable(Ui.dp(c, Ui.R_LARGE), Ui.GLASS, d));
            list.setPadding((int) (12 * d), (int) (12 * d), (int) (12 * d), (int) (12 * d));
            String nextName = n == null ? "" : (String) n[0];
            for (Map.Entry<String, Long> e : times(c).entrySet()) {
                names.add(e.getKey());
                LinearLayout row = new LinearLayout(c); row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding((int) (16 * d), (int) (10 * d), (int) (16 * d), (int) (10 * d));
                boolean hot = e.getKey().equals(nextName);
                TextView nm = Ui.text(c, Ui.BODY + 1, hot ? 600 : 400, hot ? Ui.TEXT : Ui.TEXT_2); nm.setText(e.getKey());
                TextView tm = Ui.text(c, Ui.BODY + 1, hot ? 600 : 400, hot ? Ui.TEXT : Ui.TEXT_2);
                tm.setText(new SimpleDateFormat("HH:mm", Locale.US).format(new Date(e.getValue())));
                TextView bell = Ui.text(c, Ui.BODY, 500, Ui.TEXT_2);
                row.addView(nm, new LinearLayout.LayoutParams(0, -2, 1));
                row.addView(tm);
                LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams((int) (92 * d), -2); bl.leftMargin = (int) (18 * d);
                bell.setGravity(Gravity.END);
                row.addView(bell, bl);
                list.addView(row);
            }
            names.add("Tes adzan");
            TextView test = Ui.text(c, Ui.BODY, 500, Ui.TEXT); test.setText("▶  Tes suara adzan");
            test.setPadding((int) (16 * d), (int) (12 * d), (int) (16 * d), (int) (10 * d));
            list.addView(test);
            LayoutParams rl = new LayoutParams((int) (380 * d), -2, Gravity.END | Gravity.CENTER_VERTICAL); rl.rightMargin = (int) (64 * d);
            addView(list, rl);

            TextView hint = Ui.text(c, Ui.CAPTION, 500, Ui.TEXT_3);
            hint.setText("OK: adzan aktif/mati  ·  Suara sendiri: files/adzan/adzan.mp3");
            LayoutParams hl = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); hl.bottomMargin = (int) (18 * d);
            addView(hint, hl);
            render();
        }

        void render() {
            Context c = getContext();
            for (int i = 0; i < list.getChildCount(); i++) {
                View row = list.getChildAt(i);
                row.setBackground(i == sel ? new GlassDrawable(Ui.dp(c, Ui.R_MED), Ui.GLASS_FOCUS, Ui.dp(c, 1)) : null);
                if (row instanceof LinearLayout && i < names.size() - 1) {
                    String n = names.get(i);
                    ((TextView) ((LinearLayout) row).getChildAt(2)).setText(adzanCapable(n) ? (adzanOn(c, n) ? "Adzan ●" : "Adzan ○") : "");
                }
            }
        }

        @Override public boolean key(KeyEvent e) {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP: sel = Math.max(0, sel - 1); render(); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: sel = Math.min(names.size() - 1, sel + 1); render(); return true;
                case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_BUTTON_A:
                    if (e.getRepeatCount() > 0) return true;
                    String n = names.get(sel);
                    if (n.equals("Tes adzan")) { if (player != null) stop(); else play(getContext(), "Dzuhur"); }
                    else if (adzanCapable(n)) { prefs(getContext()).edit().putBoolean("adzan_" + n, !adzanOn(getContext(), n)).apply(); schedule(getContext()); }
                    render();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT: return true;
            }
            return false;
        }

        @Override public void onClose() {}
    }
}
