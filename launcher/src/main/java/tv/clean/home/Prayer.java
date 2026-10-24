package tv.clean.home;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.*;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.graphics.Color;
import android.graphics.Paint;
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

    // ---- sheet: editorial detail on the left, timeline list on the right ----

    static final class Panel extends FrameLayout implements tv.clean.home.Sheet {
        final LinearLayout list;
        final List<String> names = new ArrayList<>();
        final List<Long> at = new ArrayList<>();
        int sel;

        Panel(Context c) {
            super(c);
            float d = Ui.dp(c, 1);
            setBackgroundColor(0xF50A0B10);
            Ui.crumb(this, R.drawable.ic_moon_star, "Jadwal shalat");

            Object[] n = next(c);
            LinearLayout left = new LinearLayout(c); left.setOrientation(LinearLayout.VERTICAL);
            TextView eb = Ui.eyebrow(c, Ui.TEXT_3);
            eb.setText(new SimpleDateFormat("EEE d", Locale.getDefault()).format(new Date()) + "  ·  " + prefs(c).getString("hijri", ""));
            TextView title = Ui.text(c, Ui.TITLE, 600, Ui.TEXT);
            title.setText(n == null ? "Sampai jumpa besok" : (String) n[0]);
            TextView big = Ui.text(c, Ui.DISPLAY, 200, Ui.TEXT); big.setEllipsize(null);
            big.setText(n == null ? "" : new SimpleDateFormat("HH.mm", Locale.getDefault()).format(new Date((Long) n[1])));
            TextView meta = Ui.text(c, Ui.BODY, 400, Ui.TEXT_2);
            meta.setText((n == null ? "" : "dalam " + countdown((Long) n[1]) + "  ·  ") + c.getString(R.string.city) + "  ·  Kemenag RI");
            left.addView(eb);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2); tl.topMargin = (int) (10 * d);
            left.addView(title, tl); left.addView(big, new LinearLayout.LayoutParams(-1, -2)); left.addView(meta);
            LayoutParams ll = new LayoutParams((int) (400 * d), -2, Gravity.CENTER_VERTICAL); ll.leftMargin = (int) (Ui.SAFE_SIDE * d);
            addView(left, ll);

            list = new LinearLayout(c); list.setOrientation(LinearLayout.VERTICAL);
            list.setBackground(new GlassDrawable(Ui.dp(c, Ui.R_LARGE), 0x29FFFFFF, d));
            list.setPadding((int) (14 * d), (int) (14 * d), (int) (14 * d), (int) (12 * d));
            TextView head = Ui.text(c, 13, 500, Ui.TEXT_2);
            head.setText(times(c).size() + " waktu hari ini  ·  OK: adzan aktif/mati");
            head.setPadding((int) (8 * d), 0, 0, (int) (8 * d));
            list.addView(head);
            String nextName = n == null ? "" : (String) n[0];
            for (Map.Entry<String, Long> e : times(c).entrySet()) {
                names.add(e.getKey()); at.add(e.getValue());
                list.addView(row(c, e.getKey(), e.getValue(), e.getKey().equals(nextName)));
            }
            names.add("Tes"); at.add(0L);
            LinearLayout test = new LinearLayout(c); test.setGravity(Gravity.CENTER_VERTICAL);
            test.setPadding((int) (44 * d), (int) (8 * d), (int) (12 * d), (int) (6 * d));
            ImageView pi = Ui.icon(c, R.drawable.ic_play, 16, Ui.TEXT); pi.setTag("i");
            TextView pt = Ui.text(c, 15, 500, Ui.TEXT); pt.setText("Tes suara adzan"); pt.setPadding((int) (10 * d), 0, 0, 0); pt.setTag("n");
            test.addView(pi); test.addView(pt);
            list.addView(test);
            LayoutParams rl = new LayoutParams((int) (440 * d), -2, Gravity.END | Gravity.TOP); rl.rightMargin = (int) (Ui.SAFE_SIDE * d); rl.topMargin = (int) (84 * d); // below the clock pill
            addView(list, rl);
            sel = Math.max(0, names.indexOf(nextName));
            render();
        }

        View row(Context c, String name, long time, boolean next) {
            float d = Ui.dp(c, 1);
            LinearLayout r = new LinearLayout(c); r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding((int) (8 * d), (int) (2 * d), (int) (12 * d), (int) (2 * d));
            boolean past = time < System.currentTimeMillis();
            Dot dot = new Dot(c, next, past);
            r.addView(dot, new LinearLayout.LayoutParams((int) (28 * d), (int) (42 * d)));
            LinearLayout txt = new LinearLayout(c); txt.setOrientation(LinearLayout.VERTICAL); txt.setPadding((int) (8 * d), 0, 0, 0);
            TextView nm = Ui.text(c, 15, next ? 700 : 500, past ? Ui.TEXT_3 : Ui.TEXT); nm.setText(name); nm.setTag("n");
            TextView sub = Ui.text(c, 12.5f, 400, Ui.TEXT_3); sub.setTag("s");
            sub.setText(new SimpleDateFormat("HH:mm", Locale.US).format(new Date(time)) + (next ? "  ·  berikutnya" : past ? "  ·  sudah lewat" : ""));
            txt.addView(nm); txt.addView(sub);
            r.addView(txt, new LinearLayout.LayoutParams(0, -2, 1));
            if (adzanCapable(name)) {
                ImageView bell = Ui.icon(c, R.drawable.ic_bell, 18, Ui.TEXT_2); bell.setTag("i");
                r.addView(bell);
            }
            return r;
        }

        /** Timeline segment: vertical rule with a dot (filled = next, hollow = upcoming, dim = past). */
        static final class Dot extends View {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); final boolean next, past;
            Dot(Context c, boolean next, boolean past) { super(c); this.next = next; this.past = past; }
            @Override protected void onDraw(android.graphics.Canvas cv) {
                float cx = getWidth() / 2f, cy = getHeight() / 2f, d = getResources().getDisplayMetrics().density;
                p.setStyle(Paint.Style.FILL); p.setColor(0x33FFFFFF);
                cv.drawRect(cx - d / 2, -d * 6, cx + d / 2, getHeight() + d * 6, p);
                p.setColor(0xFF0A0B10); cv.drawCircle(cx, cy, 7 * d, p);
                if (next) { p.setColor(Color.WHITE); cv.drawCircle(cx, cy, 6 * d, p); p.setColor(0xFF0A0B10); cv.drawCircle(cx, cy, 2.5f * d, p); }
                else { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f * d); p.setColor(past ? 0x4DFFFFFF : 0xB3FFFFFF); cv.drawCircle(cx, cy, 5 * d, p); }
            }
        }

        void render() {
            Context c = getContext();
            for (int i = 0; i < names.size(); i++) {
                View row = list.getChildAt(i + 1);
                boolean f = i == sel;
                row.setBackground(f ? new GlassDrawable(Ui.dp(c, Ui.R_MED), 0x33FFFFFF, Ui.dp(c, 1)) : null);
                String n = names.get(i);
                ImageView ic = row.findViewWithTag("i");
                if (ic != null && adzanCapable(n)) {
                    boolean on = adzanOn(c, n);
                    ic.setImageResource(on ? R.drawable.ic_bell : R.drawable.ic_bell_off);
                    ic.setImageTintList(android.content.res.ColorStateList.valueOf(on ? Ui.TEXT : Ui.TEXT_3));
                }
                if (ic != null && n.equals("Tes")) ic.setImageResource(player != null ? R.drawable.ic_pause : R.drawable.ic_play);
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
                    if (n.equals("Tes")) { if (player != null) stop(); else play(getContext(), "Dzuhur"); }
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
