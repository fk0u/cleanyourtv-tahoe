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
    LinearLayout banner, guide, rows;
    TextView bNum, bName, bNow, bNext, bStatus, loading;
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

        // info banner (bottom)
        banner = new LinearLayout(this); banner.setOrientation(LinearLayout.VERTICAL);
        banner.setBackground(new GlassDrawable(dp(Ui.R_LARGE), Ui.GLASS_STRONG, dp(1)));
        banner.setPadding((int) dp(24), (int) dp(18), (int) dp(24), (int) dp(18));
        bNum = Ui.eyebrow(this, Ui.TEXT_2); bName = Ui.text(this, Ui.TITLE, 700, Ui.TEXT);
        bNow = Ui.text(this, Ui.BODY, 600, Ui.ACCENT); bNext = Ui.text(this, Ui.BODY, 400, Ui.TEXT_2);
        bStatus = Ui.text(this, Ui.BODY, 500, 0xFFFF8A80);
        for (View v : new View[]{bNum, bName, bNow, bNext, bStatus}) banner.addView(v);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams((int) dp(560), -2, Gravity.BOTTOM | Gravity.START);
        bl.setMargins((int) dp(Ui.SAFE_SIDE), 0, 0, (int) dp(Ui.SAFE_TOP));
        root.addView(banner, bl);

        // channel list (left)
        guide = new LinearLayout(this); guide.setOrientation(LinearLayout.VERTICAL);
        guide.setBackgroundColor(0xE60B0B10);
        guide.setPadding((int) dp(Ui.SAFE_SIDE), (int) dp(Ui.SAFE_TOP), (int) dp(20), (int) dp(Ui.SAFE_TOP));
        TextView gt = Ui.text(this, Ui.HEADLINE, 700, Ui.TEXT); gt.setText("Live TV");
        guide.addView(gt);
        rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this); sv.setVerticalScrollBarEnabled(false); sv.addView(rows);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, 0, 1); sl.topMargin = (int) dp(12);
        guide.addView(sv, sl);
        guide.setVisibility(View.GONE);
        root.addView(guide, new FrameLayout.LayoutParams((int) dp(420), -1, Gravity.START));
        setContentView(root);

        player = new ExoPlayer.Builder(this).build();
        player.setVideoSurfaceView(surface);
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException e) { nextSource(); }
            @Override public void onPlaybackStateChanged(int s) {
                if (s == Player.STATE_READY) { loading.setVisibility(View.GONE); bStatus.setVisibility(View.GONE); }
            }
        });

        Live.fetch(this, l -> {
            list = l;
            String want = getIntent().getStringExtra("url"), last = prefs.getString("last", null);
            int i = 0;
            for (int k = 0; k < l.size(); k++) if (l.get(k).getChannel().getUrl().equals(want != null ? want : last)) { i = k; break; }
            buildRows(); tune(i);
        }, e -> { epg = e; if (index >= 0) showBanner(); }, () -> loading.setText("Daftar saluran tidak bisa dimuat. Cek internet."));
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
                .setUserAgent(c.getHeaders().containsKey("User-Agent") ? c.getHeaders().get("User-Agent") : "TahoeTV");
        java.util.HashMap<String, String> h = new java.util.HashMap<>(c.getHeaders()); h.remove("User-Agent");
        http.setDefaultRequestProperties(h);
        player.setMediaSource(new DefaultMediaSourceFactory(http).createMediaSource(MediaItem.fromUri(Uri.parse(c.getUrl()))));
        player.prepare(); player.play();
        sourceStarted = System.currentTimeMillis(); lastPos = -1; stuckSince = System.currentTimeMillis();
        loading.setText(source == 0 ? "Memuat " + list.get(index).getName() + "…" : "Mencoba sumber cadangan " + (source + 1) + "/" + s.size() + "…");
        loading.setVisibility(View.VISIBLE);
    }

    void nextSource() { source++; play(); }

    long lastPos = -1, stuckSince;

    /** Never started in 20 s, or picture frozen for 12 s → next source. */
    final Runnable watchdog = new Runnable() { @Override public void run() {
        if (player != null && index >= 0 && source < streams().size()) {
            long now = System.currentTimeMillis(), pos = player.getCurrentPosition();
            boolean moving = player.isPlaying() && pos != lastPos;
            if (moving) { lastPos = pos; stuckSince = now; }
            if (player.getPlaybackState() != Player.STATE_READY && now - sourceStarted > 20_000) nextSource();
            else if (lastPos >= 0 && now - stuckSince > 12_000) nextSource();
        }
        ui.postDelayed(this, 2000);
    }};

    // ------------------------------------------------------------------ banner + list

    final Runnable hideBanner = () -> { if (bStatus.getVisibility() != View.VISIBLE) banner.animate().alpha(0).setDuration(Ui.MED).start(); };

    void showBanner() {
        if (index < 0) return;
        LocalTv.Entry e = list.get(index);
        bNum.setText((index + 1) + "  ·  " + e.getCategory().getLabel());
        bName.setText(e.getName());
        Instant now = Instant.now();
        List<Epg.Programme> s = epg == null ? new ArrayList<>() : epg.schedule(e.getName(), now);
        Epg.Programme p = !s.isEmpty() && !s.get(0).getStart().isAfter(now) ? s.get(0) : null;
        bNow.setText(p != null ? "Sekarang  " + p.getTitle() + "   " + hm.format(p.getStart()) + "–" + hm.format(p.getStop()) : "");
        bNow.setVisibility(p != null ? View.VISIBLE : View.GONE);
        Epg.Programme n = s.size() > (p != null ? 1 : 0) ? s.get(p != null ? 1 : 0) : null;
        bNext.setText(n != null ? "Berikutnya  " + hm.format(n.getStart()) + "  " + n.getTitle() : "");
        bNext.setVisibility(n != null ? View.VISIBLE : View.GONE);
        banner.animate().cancel(); banner.setAlpha(1);
        ui.removeCallbacks(hideBanner); ui.postDelayed(hideBanner, 5000);
    }

    void buildRows() {
        rows.removeAllViews();
        for (int k = 0; k < list.size(); k++) {
            LocalTv.Entry e = list.get(k);
            TextView t = Ui.text(this, Ui.BODY, 500, Ui.TEXT);
            t.setText((k + 1) + "   " + e.getName());
            t.setPadding((int) dp(14), (int) dp(10), (int) dp(14), (int) dp(10));
            t.setShadowLayer(0, 0, 0, 0); t.setFocusable(true); t.setFocusableInTouchMode(true);
            int i = k;
            t.setOnFocusChangeListener((v, f) -> v.setBackground(f ? new GlassDrawable(dp(Ui.R_SMALL), Ui.GLASS_FOCUS, dp(1)) : null));
            t.setOnClickListener(v -> { guide.setVisibility(View.GONE); tune(i); });
            rows.addView(t);
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
