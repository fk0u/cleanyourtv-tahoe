package tv.clean.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import tech.kousozo.tahoetv.core.DiagLog;
import tech.kousozo.tahoetv.core.LocalTv;
import tech.kousozo.tahoetv.core.Protocol;

/** Handles Companion remote commands, media/session state, sleep timer, and Assistant tool calls for [Home]. */
final class RemoteController implements Companion.Listener, Tools {
    static final int[] SLEEP = {0, 15, 30, 60, 90, 120};

    private final Home home;
    private long stateRev = 0L;
    private long sleepAt; // 0 = off

    // ponytail: in-process timer; a launcher restart cancels it (AlarmManager if that ever matters)
    private final Runnable sleepNow = () -> {
        sleepAt = 0;
        pushState();
        if (Keys.inst != null && Build.VERSION.SDK_INT >= 28) {
            Keys.inst.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
        }
    };
    private final Runnable sleepWarn;

    RemoteController(Home home) {
        this.home = home;
        this.sleepWarn = () -> Notif.banner(home, null, "Timer tidur", "TV standby dalam 1 menit", "Ubah atau matikan di Pusat Kontrol");
    }

    void cancelSleep() {
        if (home.root != null) {
            home.root.removeCallbacks(sleepNow);
            home.root.removeCallbacks(sleepWarn);
        }
    }

    @Override public void onKey(String key) {
        if (globalKey(key)) return;
        int code;
        switch (key) {
            case "up": code = KeyEvent.KEYCODE_DPAD_UP; break;
            case "down": code = KeyEvent.KEYCODE_DPAD_DOWN; break;
            case "left": code = KeyEvent.KEYCODE_DPAD_LEFT; break;
            case "right": code = KeyEvent.KEYCODE_DPAD_RIGHT; break;
            case "ok": code = KeyEvent.KEYCODE_DPAD_CENTER; break;
            case "menu": code = KeyEvent.KEYCODE_MENU; break;
            case "apps": code = KeyEvent.KEYCODE_ALL_APPS; break;
            // phone-as-gamepad buttons
            case "a": code = KeyEvent.KEYCODE_BUTTON_A; break;
            case "b": code = KeyEvent.KEYCODE_BUTTON_B; break;
            case "x": code = KeyEvent.KEYCODE_BUTTON_X; break;
            case "y": code = KeyEvent.KEYCODE_BUTTON_Y; break;
            case "start": code = KeyEvent.KEYCODE_BUTTON_START; break;
            case "select": code = KeyEvent.KEYCODE_BUTTON_SELECT; break;
            case "l1": code = KeyEvent.KEYCODE_BUTTON_L1; break;
            case "r1": code = KeyEvent.KEYCODE_BUTTON_R1; break;
            case "back":
                if (!home.hasWindowFocus() && Keys.inst != null) {
                    Keys.inst.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                    return;
                }
                code = KeyEvent.KEYCODE_BACK;
                break;
            case "home":
                home.start(new Intent(home, Home.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                return;
            default:
                return;
        }
        // Launcher in front → press it here; our Live TV in front → press it there; any other app → via the accessibility helper.
        LiveTv tv = LiveTv.live;
        if (home.hasWindowFocus()) home.tap(code);
        else if (tv != null && tv.hasWindowFocus()) home.tap(tv, code);
        else if (Keys.inst != null) Keys.inst.press(code);
    }

    @Override public void onLaunch(String pkg) {
        for (Home.App a : home.all) if (a.pkg.equals(pkg)) { home.start(a.launch); return; }
    }

    @Override public void onUrl(String url) {
        Uri u = Uri.parse(url);
        Intent i = new Intent(Intent.ACTION_VIEW, u);
        String host = u.getHost() == null ? "" : u.getHost();
        if (host.contains("youtube.com") || host.contains("youtu.be")) i.setPackage(Trending.YT);
        home.start(i);
    }

    @Override public void onAsk(String text) {
        if (!(home.sheet instanceof Assistant)) home.openSheet(new Assistant(home, this, false));
        if (home.sheet instanceof Assistant) ((Assistant) home.sheet).ask(text);
    }

    /** Keys from the phone remote that work anywhere, not only while the launcher has focus. */
    private boolean globalKey(String key) {
        AudioManager am = (AudioManager) home.getSystemService(Context.AUDIO_SERVICE);
        int media = key.equals("play_pause") ? KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE : key.equals("rewind") ? KeyEvent.KEYCODE_MEDIA_REWIND
                : key.equals("ffwd") ? KeyEvent.KEYCODE_MEDIA_FAST_FORWARD : 0;
        if (media != 0) {
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, media));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, media));
            return true;
        }
        switch (key) {
            case "mute":
                am.adjustSuggestedStreamVolume(AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.STREAM_MUSIC, AudioManager.FLAG_SHOW_UI);
                home.root.postDelayed(this::pushState, 300);
                return true;
            case "prev": {
                LiveTv t = LiveTv.live;
                if (t != null) t.ui.post(t::back);
                return true;
            }
            case "0": case "1": case "2": case "3": case "4": case "5": case "6": case "7": case "8": case "9":
                LiveTv d = LiveTv.live; // channel number entry in Live TV
                if (d != null) d.ui.post(() -> d.digit(key.charAt(0) - '0'));
                return true;
            case "settings":
                if (home.kids()) return true;
                home.start(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            case "livetv":
                home.start(new Intent(home, LiveTv.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            case "ch_up": case "ch_down":
                LiveTv tv = LiveTv.live;
                if (tv != null) tv.ui.post(() -> tv.tune(tv.index + (key.equals("ch_up") ? 1 : -1)));
                else home.start(new Intent(home, LiveTv.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
        }
        return false;
    }

    @Override public void onVolume(int dir) {
        ((AudioManager) home.getSystemService(Context.AUDIO_SERVICE)).adjustSuggestedStreamVolume(
                dir > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                AudioManager.STREAM_MUSIC, AudioManager.FLAG_SHOW_UI);
        home.root.postDelayed(this::pushState, 300); // the adjustment lands a moment later
    }

    @Override public void onVolumeSet(int percent) {
        AudioManager am = (AudioManager) home.getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(Math.max(0, Math.min(100, percent)) * max / 100f), AudioManager.FLAG_SHOW_UI);
        pushState();
    }

    @Override public void onText(String text) {
        View f = home.getCurrentFocus();
        if (home.hasWindowFocus() && f instanceof EditText) {
            ((EditText) f).setText(text);
            ((EditText) f).setSelection(text.length());
        } else if (Keys.inst != null) Keys.inst.type(text);
    }

    /** Screen on → standby (Android 9+ accessibility action); asleep → wake it. */
    @Override public void onPower() {
        PowerManager pm = (PowerManager) home.getSystemService(Context.POWER_SERVICE);
        if (pm.isInteractive()) {
            if (Keys.inst != null && Build.VERSION.SDK_INT >= 28) {
                Keys.inst.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
            }
        } else {
            @SuppressWarnings("deprecation") PowerManager.WakeLock w = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "tahoe:remote");
            w.acquire(3000);
        }
    }

    @Override public void onSessionLoad(String channelId, String url) {
        LiveTv tv = LiveTv.live;
        if (tv != null) {
            tv.ui.post(() -> tv.tuneByChannel(channelId, url));
            return;
        }
        Intent i = new Intent(home, LiveTv.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (channelId != null && !channelId.isEmpty()) i.putExtra("channelId", channelId);
        if (url != null && !url.isEmpty()) i.putExtra("url", url);
        home.start(i);
    }

    @Override public void onSessionControl(String action) {
        LiveTv tv = LiveTv.live;
        if (tv == null || action == null) return;
        tv.ui.post(() -> {
            if ("stop".equals(action)) { tv.finish(); return; }
            if (tv.player == null) return;
            if ("play".equals(action)) { tv.player.play(); tv.player.seekToDefaultPosition(); tv.showBanner(); }
            else if ("pause".equals(action)) { tv.player.pause(); tv.showBanner(); }
            pushState();
        });
    }

    @Override public JSONObject state() {
        AudioManager am = (AudioManager) home.getSystemService(Context.AUDIO_SERVICE);
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int vol = Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max);
        boolean muted = am.isStreamMute(AudioManager.STREAM_MUSIC);
        LiveTv tv = LiveTv.live;
        Protocol.SessionState session;
        if (tv != null && tv.index >= 0 && tv.index < tv.list.size()) {
            LocalTv.Entry e = tv.list.get(tv.index);
            boolean playing = tv.player != null && tv.player.getPlayWhenReady();
            session = new Protocol.SessionState(
                    ++stateRev, e.getChannel().getChannelId(), e.getName(), tv.index + 1, playing, !playing);
        } else {
            session = new Protocol.SessionState(++stateRev, "", "", 0, false, false);
        }
        Protocol.DeviceState ds = new Protocol.DeviceState(vol, muted, sleepLeft(), session);
        return Protocol.toLegacyStateJson(ds, session);
    }

    /** Minutes left, 0 = off. */
    int sleepLeft() {
        return sleepAt == 0 ? 0 : (int) Math.max(1, Math.ceil((sleepAt - System.currentTimeMillis()) / 60000.0));
    }

    /** "Kirim log": the TV can't share files itself, so a paired phone does it. Marks the moment in the log, asks
     *  phones with the Remote open to send it, and says how otherwise. */
    void sendLog() {
        DiagLog.add("user", "--- pengguna menekan Kirim log ---");
        DiagLog.save();
        if (home.companion == null || home.companion.pairedCount() == 0) {
            Toast.makeText(home, "Sambungkan HP dulu (Pusat Kontrol → HP), lalu di HP: Tahoe → Remote → Kirim log.", Toast.LENGTH_LONG).show();
            return;
        }
        // a phone with the Remote open offers to share right away; otherwise the user starts it there
        try { home.companion.broadcast(new JSONObject().put("t", "send_log")); } catch (Exception ignored) {}
        Toast.makeText(home, "Di HP: buka Tahoe → Remote → Kirim log (jika Remote sudah terbuka, HP langsung menawarkannya). Log TV ikut terkirim.", Toast.LENGTH_LONG).show();
    }

    @Override public void onKidsPin() { home.toast("PIN profil Anak diubah dari HP"); }

    @Override public void onSleep(int minutes) {
        cancelSleep();
        sleepAt = minutes <= 0 ? 0 : System.currentTimeMillis() + minutes * 60_000L;
        if (minutes > 0) {
            home.root.postDelayed(sleepNow, minutes * 60_000L);
            if (minutes > 1) home.root.postDelayed(sleepWarn, (minutes - 1) * 60_000L);
        }
        home.toast(minutes <= 0 ? "Timer tidur mati" : "TV standby dalam " + minutes + " menit");
        pushState();
    }

    /** Next / previous step of SLEEP from the current timer. */
    void stepSleep(int dir) {
        int left = sleepLeft(), next = 0;
        if (dir > 0) { for (int m : SLEEP) if (m > left) { next = m; break; } }
        else for (int m : SLEEP) if (m < left) next = m;
        onSleep(next);
    }

    /** Tell paired phones the current volume / channel. */
    void pushState() { if (home.companion != null) home.companion.broadcast(state()); }

    @Override public void onPhoneNotification(String app, String title, String text) {
        Notif.banner(home, null, app.isEmpty() ? "HP" : app, title, text);
    }

    @Override public void onPhoneMedia(JSONObject media) {
        boolean had = home.phoneMedia != null;
        home.phoneMedia = media;
        if (had != (media != null)) home.buildChips(); else home.updateChips();
        home.renderHero();
    }

    @Override public void onUploaded(String kind) {
        if (kind.equals("photo") || kind.equals("wallpaper")) { home.hero.loadWalls(); home.toast("Foto diterima dari HP"); }
        else home.toast("Suara adzan disimpan");
    }

    @Override public JSONArray apps() {
        JSONArray out = new JSONArray();
        try { for (Home.App a : home.all) out.put(new JSONObject().put("pkg", a.pkg).put("label", a.label).put("dock", home.dock.contains(a))); } catch (Exception ignored) {}
        return out;
    }

    void phoneMediaCommand(String c) {
        try { home.companion.broadcast(new JSONObject().put("t", "media_cmd").put("c", c)); } catch (Exception ignored) {}
    }

    // ---------------------------------------------------------------- assistant tools

    @Override public String call(String name, JSONObject args) {
        try {
            switch (name) {
                case "open_app": {
                    String q = args.optString("name").toLowerCase(Locale.ROOT);
                    for (Home.App a : home.all) if (a.label.toLowerCase(Locale.ROOT).contains(q)) {
                        home.ui.postDelayed(() -> { home.closeSheet(); home.start(a.launch); }, 1800);
                        return "Membuka " + a.label;
                    }
                    return "Aplikasi tidak ditemukan";
                }
                case "youtube_search": {
                    Intent i = new Intent(Intent.ACTION_SEARCH).setPackage(Trending.YT).putExtra("query", args.optString("query"));
                    home.ui.postDelayed(() -> { home.closeSheet(); home.start(i); }, 1800);
                    return "Mencari " + args.optString("query") + " di YouTube";
                }
                case "weather": {
                    JSONObject c = home.weather == null ? null : home.weather.optJSONObject("current");
                    JSONObject d = home.weather == null ? null : home.weather.optJSONObject("daily");
                    if (c == null) return "Data cuaca belum ada";
                    return Math.round(c.optDouble("temperature_2m")) + "°C, " + Weather.text(c.optInt("weather_code"))
                            + (d == null ? "" : ", maks " + Math.round(d.optJSONArray("temperature_2m_max").optDouble(0)) + "°, peluang hujan "
                            + d.optJSONArray("precipitation_probability_max").optInt(0) + "%");
                }
                case "prayer_times": {
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<String, Long> en : Prayer.times(home).entrySet())
                        sb.append(en.getKey()).append(' ').append(new SimpleDateFormat("HH:mm", Locale.US).format(new Date(en.getValue()))).append(", ");
                    Object[] n = Prayer.next(home);
                    return sb + (n == null ? "" : "berikutnya " + n[0] + " dalam " + Prayer.countdown((Long) n[1]));
                }
                case "set_volume": {
                    AudioManager am = (AudioManager) home.getSystemService(Context.AUDIO_SERVICE);
                    String lv = args.optString("level");
                    if (lv.contains("naik")) onVolume(1); else if (lv.contains("turun")) onVolume(-1);
                    else am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(Integer.parseInt(lv.replaceAll("\\D", "")) / 100f * am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), AudioManager.FLAG_SHOW_UI);
                    return "Volume diatur";
                }
                case "next_wallpaper": home.hero.nextWall(); return "Wallpaper diganti";
            }
        } catch (Exception e) { return "Gagal: " + e.getMessage(); }
        return "Tool tidak dikenal";
    }
}
