package tv.clean.home;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import tech.kousozo.tahoetv.core.Epg;
import tech.kousozo.tahoetv.core.LocalTv;
import tech.kousozo.tahoetv.core.M3u;

/** Tahoe's built-in Live TV: full-screen player for the free channels (national priority first, then near you).
 *
 *  Remote: ▲/▼ (or CH+/CH−) zap · OK or ◀ opens the channel list · ▶ shows the info banner · Back closes the list, then exits.
 *  Each channel tries its streams in order (main, then backups); a source that errors, never starts, or freezes is
 *  skipped. When every source fails the banner says so and ▼ goes to the next channel. */
public class LiveTv extends Activity {
    /** The running player, so the phone remote's CH+/CH− can zap it. */
    static LiveTv live;
    final Handler ui = new Handler(Looper.getMainLooper());
    final DateTimeFormatter hm = DateTimeFormatter.ofPattern("HH.mm").withZone(ZoneId.systemDefault());
    ExoPlayer player;
    SurfaceView surface;
    LinearLayout guide, rows;
    FrameLayout osd;                       // on-screen display: top + bottom scrims with all the info
    TextView bNum, bName, bMeta, bNow, bNowTime, bNext, bNextTime, bStatus, bCat, loading, tTemp, tCond, tDay, tTime, logoText;
    android.widget.ImageView logo;
    View progress;
    FrameLayout progressTrack;
    List<LocalTv.Entry> list = new ArrayList<>();
    Epg epg;
    int index = -1, source = 0;
    long sourceStarted;
    SharedPreferences prefs;

    float dp(float v) { return Ui.dp(this, v); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("live", MODE_PRIVATE);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        loading = Ui.text(this, Ui.HEADLINE, 600, Ui.TEXT); loading.setText("Memuat saluran…");
        root.addView(loading, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        osd = buildOsd();
        root.addView(osd, new FrameLayout.LayoutParams(-1, -1));

        // channel list (left)
        guide = new LinearLayout(this); guide.setOrientation(LinearLayout.VERTICAL);
        guide.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xF20A0B10, 0xE60A0B10, 0x000A0B10}));
        guide.setPadding((int) dp(Ui.SAFE_SIDE), (int) dp(Ui.SAFE_TOP), (int) dp(20), (int) dp(Ui.SAFE_TOP));
        TextView gt = Ui.text(this, Ui.HEADLINE, 700, Ui.TEXT); gt.setText("Live TV");
        guide.addView(gt);
        rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this); sv.setVerticalScrollBarEnabled(false); sv.addView(rows);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, 0, 1); sl.topMargin = (int) dp(12);
        guide.addView(sv, sl);
        guide.setVisibility(View.GONE);
        root.addView(guide, new FrameLayout.LayoutParams((int) dp(480), -1, Gravity.START));
        setContentView(root);

        player = new ExoPlayer.Builder(this).build();
        player.setVideoSurfaceView(surface);
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException e) { recover(); }
            @Override public void onPlaybackStateChanged(int s) {
                if (s == Player.STATE_READY) { everReady = true; retries = 0; loading.setVisibility(View.GONE); bStatus.setVisibility(View.GONE); }
            }
        });

        Live.fetch(this, l -> {
            list = l;
            String want = getIntent().getStringExtra("url"), last = prefs.getString("last", null);
            int i = 0;
            for (int k = 0; k < l.size(); k++) if (l.get(k).getChannel().getUrl().equals(want != null ? want : last)) { i = k; break; }
            buildRows(); tune(i);
        }, e -> { epg = e; buildRows(); if (index >= 0) showBanner(); }, () -> loading.setText("Daftar saluran tidak bisa dimuat. Cek internet."));
        ui.post(watchdog);
    }

    // ------------------------------------------------------------------ playback

    void tune(int i) {
        if (list.isEmpty()) return;
        index = ((i % list.size()) + list.size()) % list.size();
        source = 0;
        prefs.edit().putString("last", list.get(index).getChannel().getUrl()).apply();
        play();
        showBanner();
    }

    List<M3u.Channel> streams() {
        LocalTv.Entry e = list.get(index);
        List<M3u.Channel> s = new ArrayList<>(); s.add(e.getChannel()); s.addAll(e.getAlternates());
        return s;
    }

    void play() {
        List<M3u.Channel> s = streams();
        if (source >= s.size()) {
            player.stop();
            bStatus.setText("Saluran ini sedang tidak bisa diputar. Tekan ▼ untuk saluran berikutnya.");
            bStatus.setVisibility(View.VISIBLE); loading.setVisibility(View.GONE); showBanner(); return;
        }
        M3u.Channel c = s.get(source);
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(8000).setReadTimeoutMs(15000)
                .setUserAgent(c.getHeaders().containsKey("User-Agent") ? c.getHeaders().get("User-Agent") : "TahoeTV/2.9.2");
        java.util.HashMap<String, String> h = new java.util.HashMap<>(c.getHeaders()); h.remove("User-Agent");
        http.setDefaultRequestProperties(h);
        player.setMediaSource(new DefaultMediaSourceFactory(http).createMediaSource(MediaItem.fromUri(Uri.parse(c.getUrl()))));
        player.prepare(); player.play();
        sourceStarted = System.currentTimeMillis(); everReady = false; retries = 0; lastPos = -1; stuckSince = System.currentTimeMillis();
        loading.setText(source == 0 ? "Memuat " + list.get(index).getName() + "…" : "Mencoba sumber cadangan " + (source + 1) + "/" + s.size() + "…");
        loading.setVisibility(View.VISIBLE);
    }

    void nextSource() { source++; play(); }

    boolean everReady; int retries;

    /** A source that already played and then hiccups (rebuffer, network blip, fell behind the live window)
     *  is reconnected at the live edge; only a source that never played, or keeps failing, moves to the backup. */
    void recover() {
        if (!everReady || retries >= 3) { nextSource(); return; }
        retries++; stuckSince = System.currentTimeMillis(); lastPos = -1;
        loading.setText("Menyambung ulang…"); loading.setVisibility(View.VISIBLE);
        ui.postDelayed(() -> { player.seekToDefaultPosition(); player.prepare(); player.play(); }, 1000);
    }

    long lastPos = -1, stuckSince;

    /** Never started in 20 s, or picture frozen for 12 s → next source. */
    final Runnable watchdog = new Runnable() { @Override public void run() {
        if (player != null && index >= 0 && source < streams().size()) {
            long now = System.currentTimeMillis(), pos = player.getCurrentPosition();
            boolean moving = player.isPlaying() && pos != lastPos;
            if (moving) { lastPos = pos; stuckSince = now; }
            if (!everReady && now - sourceStarted > 20_000) nextSource();
            else if (lastPos >= 0 && now - stuckSince > 12_000) recover();
        }
        ui.postDelayed(this, 2000);
    }};

    // ------------------------------------------------------------------ banner + list

    final Runnable hideBanner = () -> { if (bStatus.getVisibility() != View.VISIBLE) osd.animate().alpha(0).setDuration(Ui.MED).start(); };

    void showBanner() {
        if (index < 0) return;
        LocalTv.Entry e = list.get(index);
        bNum.setText(String.format(java.util.Locale.ROOT, "%03d", index + 1));
        bName.setText(e.getName().toUpperCase(java.util.Locale.ROOT));
        int sources = 1 + e.getAlternates().size();
        bMeta.setText(e.getCategory().getLabel() + (sources > 1 ? "  ·  " + sources + " sumber" : "") + (e.getNear() ? "  ·  dekat kamu" : ""));
        bCat.setText(e.getCategory().getLabel());
        Logos.into(e.getChannel().getLogo(), logo, logoText, e.getName());
        Instant now = Instant.now();
        List<Epg.Programme> s = epg == null ? new ArrayList<>() : epg.schedule(e.getName(), now);
        Epg.Programme p = !s.isEmpty() && !s.get(0).getStart().isAfter(now) ? s.get(0) : null;
        Epg.Programme n = s.size() > (p != null ? 1 : 0) ? s.get(p != null ? 1 : 0) : null;
        bNowTime.setText(p != null ? hm.format(p.getStart()) + " – " + hm.format(p.getStop()) : "Sekarang");
        bNow.setText(p != null ? p.getTitle() : epg == null ? "Memuat jadwal…" : "Jadwal acara tidak tersedia");
        ((View) bNext.getParent()).setVisibility(n != null ? View.VISIBLE : View.GONE);
        if (n != null) { bNextTime.setText(hm.format(n.getStart()) + " – " + hm.format(n.getStop())); bNext.setText(n.getTitle()); }
        float f = p == null ? 0 : Math.max(0, Math.min(1, (now.getEpochSecond() - p.getStart().getEpochSecond()) / (float) Math.max(1, p.getStop().getEpochSecond() - p.getStart().getEpochSecond())));
        progressTrack.post(() -> { progress.getLayoutParams().width = (int) (progressTrack.getWidth() * f); progress.requestLayout(); });
        tick();
        osd.animate().cancel(); osd.setAlpha(1);
        ui.removeCallbacks(hideBanner); ui.postDelayed(hideBanner, 6000);
    }

    /** Clock + date (top right). */
    void tick() {
        java.util.Date d = new java.util.Date();
        tDay.setText(new java.text.SimpleDateFormat("EEEE", new java.util.Locale("id")).format(d));
        tTime.setText(new java.text.SimpleDateFormat("d MMM yyyy  ·  HH.mm", new java.util.Locale("id")).format(d));
    }

    TextView pill(String text, int bg, int fg) {
        TextView t = Ui.text(this, Ui.CAPTION, 700, fg);
        t.setText(text); t.setShadowLayer(0, 0, 0, 0); t.setLetterSpacing(0.06f);
        t.setPadding((int) dp(10), (int) dp(4), (int) dp(10), (int) dp(4));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(bg); g.setCornerRadius(dp(8)); t.setBackground(g);
        return t;
    }

    /** One schedule line: time in a glass pill, then the title. */
    LinearLayout scheduleRow(TextView time, TextView title) {
        LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL);
        time.setPadding((int) dp(12), (int) dp(6), (int) dp(12), (int) dp(6));
        time.setBackground(new GlassDrawable(dp(10), 0x33FFFFFF, dp(1)));
        r.addView(time, new LinearLayout.LayoutParams((int) dp(150), -2));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1); tl.leftMargin = (int) dp(16);
        r.addView(title, tl);
        return r;
    }

    /** Broadcast-style OSD: weather (top left), day/date/time (top right), and a bottom band with the channel logo
     *  tile, big channel number + name, now/next schedule with progress, and a colour-key legend for the remote. */
    FrameLayout buildOsd() {
        FrameLayout o = new FrameLayout(this);
        View top = new View(this);
        top.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xB3000000, 0x00000000}));
        o.addView(top, new FrameLayout.LayoutParams(-1, (int) dp(150), Gravity.TOP));
        View bottom = new View(this);
        bottom.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xF2000000, 0xB0000000, 0x00000000}));
        o.addView(bottom, new FrameLayout.LayoutParams(-1, (int) dp(300), Gravity.BOTTOM));

        // top left: weather
        LinearLayout w = new LinearLayout(this); w.setGravity(Gravity.CENTER_VERTICAL);
        tTemp = Ui.text(this, 34, 600, Ui.TEXT); tTemp.setText("—°");
        LinearLayout wc = new LinearLayout(this); wc.setOrientation(LinearLayout.VERTICAL);
        TextView we = Ui.eyebrow(this, Ui.TEXT_3); we.setText("Cuaca");
        tCond = Ui.text(this, Ui.BODY, 600, Ui.TEXT); tCond.setText(Cfg.s(this, "city", R.string.city));
        wc.addView(we); wc.addView(tCond);
        View sep = new View(this); sep.setBackgroundColor(0x40FFFFFF);
        w.addView(tTemp); LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams((int) dp(1), (int) dp(30)); sp.setMargins((int) dp(14), 0, (int) dp(14), 0);
        w.addView(sep, sp); w.addView(wc);
        FrameLayout.LayoutParams wl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        wl.setMargins((int) dp(Ui.SAFE_SIDE), (int) dp(Ui.SAFE_TOP), 0, 0);
        o.addView(w, wl);

        // top right: day + date/time
        LinearLayout dt = new LinearLayout(this); dt.setOrientation(LinearLayout.VERTICAL); dt.setGravity(Gravity.END);
        tDay = Ui.text(this, Ui.BODY, 700, Ui.TEXT); tTime = Ui.text(this, Ui.CAPTION, 500, Ui.TEXT_2);
        dt.addView(tDay); dt.addView(tTime);
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        dl.setMargins(0, (int) dp(Ui.SAFE_TOP), (int) dp(Ui.SAFE_SIDE), 0);
        o.addView(dt, dl);

        // bottom band
        LinearLayout band = new LinearLayout(this); band.setOrientation(LinearLayout.VERTICAL);
        LinearLayout main = new LinearLayout(this); main.setGravity(Gravity.BOTTOM);
        FrameLayout tile = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable tg = new android.graphics.drawable.GradientDrawable(); tg.setColor(0xF5FFFFFF); tg.setCornerRadius(dp(18));
        tile.setBackground(tg); Ui.clip(tile, dp(18));
        logo = new android.widget.ImageView(this); logo.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        logo.setPadding((int) dp(14), (int) dp(14), (int) dp(14), (int) dp(14));
        logoText = Ui.text(this, Ui.HEADLINE, 800, 0xFF111114); logoText.setShadowLayer(0, 0, 0, 0); logoText.setGravity(Gravity.CENTER);
        tile.addView(logoText, new FrameLayout.LayoutParams(-1, -1)); tile.addView(logo, new FrameLayout.LayoutParams(-1, -1));
        main.addView(tile, new LinearLayout.LayoutParams((int) dp(150), (int) dp(100)));

        LinearLayout id = new LinearLayout(this); id.setOrientation(LinearLayout.VERTICAL);
        bNum = Ui.text(this, 44, 700, Ui.TEXT); bNum.setIncludeFontPadding(false);
        bName = Ui.text(this, Ui.HEADLINE, 800, Ui.TEXT); bName.setLetterSpacing(0.02f);
        bMeta = Ui.text(this, Ui.CAPTION, 500, Ui.TEXT_2);
        bStatus = Ui.text(this, Ui.BODY, 600, 0xFFFF8A80); bStatus.setVisibility(View.GONE);
        id.addView(bNum); id.addView(bName); id.addView(bMeta); id.addView(bStatus);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(0, -2, 1); il.leftMargin = (int) dp(20);
        main.addView(id, il);

        LinearLayout right = new LinearLayout(this); right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout chips = new LinearLayout(this);
        chips.addView(pill("● LIVE", Ui.LIVE, 0xFFFFFFFF));
        bCat = pill("", 0x33FFFFFF, Ui.TEXT);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2); cl.leftMargin = (int) dp(8);
        chips.addView(bCat, cl);
        right.addView(chips);
        bNowTime = Ui.text(this, Ui.BODY, 600, Ui.TEXT); bNow = Ui.text(this, Ui.BODY, 700, Ui.TEXT);
        bNextTime = Ui.text(this, Ui.BODY, 500, Ui.TEXT_2); bNext = Ui.text(this, Ui.BODY, 500, Ui.TEXT_2);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2); rl.topMargin = (int) dp(10);
        right.addView(scheduleRow(bNowTime, bNow), rl);
        LinearLayout.LayoutParams rl2 = new LinearLayout.LayoutParams(-1, -2); rl2.topMargin = (int) dp(8);
        right.addView(scheduleRow(bNextTime, bNext), rl2);
        progressTrack = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable trk = new android.graphics.drawable.GradientDrawable(); trk.setColor(0x33FFFFFF); trk.setCornerRadius(dp(2));
        progressTrack.setBackground(trk);
        progress = new View(this);
        android.graphics.drawable.GradientDrawable pg = new android.graphics.drawable.GradientDrawable(); pg.setColor(Ui.ACCENT); pg.setCornerRadius(dp(2));
        progress.setBackground(pg);
        progressTrack.addView(progress, new FrameLayout.LayoutParams(0, -1));
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, (int) dp(4)); pl.topMargin = (int) dp(14);
        right.addView(progressTrack, pl);
        main.addView(right, new LinearLayout.LayoutParams((int) dp(500), -2));
        band.addView(main);

        // remote colour-key legend
        LinearLayout legend = new LinearLayout(this); legend.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        String[][] keys = {{"▲▼", "Ganti saluran"}, {"OK", "Daftar saluran"}, {"▶", "Info"}, {"↩", "Keluar"}};
        int[] colors = {Ui.ACCENT, Ui.GOOD, Ui.SUN, Ui.LIVE};
        for (int k = 0; k < keys.length; k++) {
            View dot = new View(this); android.graphics.drawable.GradientDrawable dg = new android.graphics.drawable.GradientDrawable();
            dg.setColor(colors[k]); dg.setCornerRadius(dp(2)); dot.setBackground(dg);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams((int) dp(8), (int) dp(8)); dlp.leftMargin = (int) dp(k == 0 ? 0 : 26); dlp.rightMargin = (int) dp(8);
            legend.addView(dot, dlp);
            TextView lt = Ui.text(this, Ui.CAPTION, 500, Ui.TEXT_2); lt.setText(keys[k][0] + "  " + keys[k][1]);
            legend.addView(lt);
        }
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2); ll.topMargin = (int) dp(18);
        band.addView(legend, ll);

        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bl.setMargins((int) dp(Ui.SAFE_SIDE), 0, (int) dp(Ui.SAFE_SIDE), (int) dp(Ui.SAFE_TOP));
        o.addView(band, bl);

        // weather once, in the background
        Net.bg.execute(() -> {
            try {
                double lat = Double.parseDouble(Cfg.s(this, "latitude", R.string.latitude)), lon = Double.parseDouble(Cfg.s(this, "longitude", R.string.longitude));
                tech.kousozo.tahoetv.core.Web.Weather wx = tech.kousozo.tahoetv.core.Web.INSTANCE.weather(lat, lon);
                if (wx != null) ui.post(() -> { tTemp.setText(wx.getTempC() + "°"); tCond.setText(wx.getLabel() + "  ·  " + Cfg.s(this, "city", R.string.city)); });
            } catch (Exception ignored) {}
        });
        tick();
        return o;
    }

    void buildRows() {
        rows.removeAllViews();
        Instant now = Instant.now();
        for (int k = 0; k < list.size(); k++) {
            LocalTv.Entry e = list.get(k);
            LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding((int) dp(10), (int) dp(8), (int) dp(12), (int) dp(8));
            r.setFocusable(true); r.setFocusableInTouchMode(true);
            TextView num = Ui.text(this, Ui.CAPTION, 600, Ui.TEXT_3); num.setText(String.format(java.util.Locale.ROOT, "%03d", k + 1));
            r.addView(num, new LinearLayout.LayoutParams((int) dp(34), -2));
            FrameLayout tile = new FrameLayout(this);
            android.graphics.drawable.GradientDrawable tg = new android.graphics.drawable.GradientDrawable(); tg.setColor(0xF5FFFFFF); tg.setCornerRadius(dp(8));
            tile.setBackground(tg);
            android.widget.ImageView iv = new android.widget.ImageView(this); iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            iv.setPadding((int) dp(4), (int) dp(4), (int) dp(4), (int) dp(4));
            TextView it = Ui.text(this, Ui.CAPTION, 800, 0xFF111114); it.setShadowLayer(0, 0, 0, 0); it.setGravity(Gravity.CENTER);
            tile.addView(it, new FrameLayout.LayoutParams(-1, -1)); tile.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            Logos.into(e.getChannel().getLogo(), iv, it, e.getName());
            r.addView(tile, new LinearLayout.LayoutParams((int) dp(54), (int) dp(36)));
            LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL);
            TextView name = Ui.text(this, Ui.BODY, 600, Ui.TEXT); name.setText(e.getName()); name.setShadowLayer(0, 0, 0, 0);
            List<Epg.Programme> s = epg == null ? null : epg.schedule(e.getName(), now);
            Epg.Programme p = s != null && !s.isEmpty() && !s.get(0).getStart().isAfter(now) ? s.get(0) : null;
            TextView sub = Ui.text(this, Ui.CAPTION, 500, Ui.TEXT_3); sub.setText(p != null ? p.getTitle() : e.getCategory().getLabel()); sub.setShadowLayer(0, 0, 0, 0);
            col.addView(name); col.addView(sub);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1); cp.leftMargin = (int) dp(12);
            r.addView(col, cp);
            int i = k;
            r.setOnFocusChangeListener((v, f) -> {
                v.setBackground(f ? new GlassDrawable(dp(Ui.R_SMALL), Ui.GLASS_FOCUS, dp(1)) : null);
                v.animate().scaleX(f ? 1.03f : 1f).scaleY(f ? 1.03f : 1f).setDuration(Ui.FAST).start();
            });
            r.setOnClickListener(v -> { guide.setVisibility(View.GONE); tune(i); });
            rows.addView(r);
        }
    }

    void openGuide() {
        guide.setVisibility(View.VISIBLE);
        if (index >= 0 && index < rows.getChildCount()) rows.getChildAt(index).requestFocus();
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        boolean open = guide.getVisibility() == View.VISIBLE;
        if (code == KeyEvent.KEYCODE_BACK) { if (open) guide.setVisibility(View.GONE); else finish(); return true; }
        if (open) return super.onKeyDown(code, e);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_CHANNEL_UP: tune(index - 1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_CHANNEL_DOWN: tune(index + 1); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_MENU: openGuide(); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_INFO: showBanner(); return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override protected void onStop() { super.onStop(); if (live == this) live = null; if (player != null) player.pause(); }
    @Override protected void onStart() { super.onStart(); live = this; if (player != null && index >= 0) player.play(); }
    @Override protected void onDestroy() { ui.removeCallbacksAndMessages(null); if (player != null) player.release(); player = null; super.onDestroy(); }

    // ------------------------------------------------------------------ app tile

    static final String PKG = "tv.clean.home.livetv"; // pseudo package: the dock/grid identify apps by package

    /** 16:9 banner for the built-in app: deep blue → red glass with a TV glyph and "Live TV". */
    static Drawable banner(Context c) {
        Bitmap bm = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bm);
        Paint bg = new Paint();
        bg.setShader(new LinearGradient(0, 0, 320, 180, new int[]{0xFF1A2A80, 0xFF6A1B9A, 0xFFE53935}, null, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, 320, 180, bg);
        Paint s = new Paint(Paint.ANTI_ALIAS_FLAG); s.setStyle(Paint.Style.STROKE); s.setStrokeWidth(7); s.setColor(Color.WHITE);
        s.setStrokeCap(Paint.Cap.ROUND); s.setStrokeJoin(Paint.Join.ROUND);
        cv.drawRoundRect(new RectF(126, 44, 194, 96), 9, 9, s);
        cv.drawLine(144, 30, 160, 44, s); cv.drawLine(176, 30, 160, 44, s);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG); dot.setColor(0xFFFF5252); cv.drawCircle(184, 54, 6, dot);
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG); tp.setColor(Color.WHITE); tp.setTextSize(30); tp.setTypeface(Ui.font(c, 700)); tp.setTextAlign(Paint.Align.CENTER);
        cv.drawText("Live TV", 160, 140, tp);
        return new BitmapDrawable(c.getResources(), bm);
    }
}
