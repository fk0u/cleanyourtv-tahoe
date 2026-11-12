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
import android.widget.Toast;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.hls.HlsManifest;
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
import tech.kousozo.tahoetv.core.SourceHealth;
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
    Loader loading;
    TextView bLive, bNum, bName, bMeta, bNow, bNowTime, bNext, bNextTime, bStatus, bCat, tTemp, tCond, tDay, tTime, logoText;
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
        Diag.install(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("live", MODE_PRIVATE);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        loading = new Loader(this); // Apple TV / Netflix style: logo, name, spinner. No "loading source" text.
        root.addView(loading, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        osd = buildOsd();
        root.addView(osd, new FrameLayout.LayoutParams(-1, -1));

        // channel list (left)
        guide = new LinearLayout(this); guide.setOrientation(LinearLayout.VERTICAL);
        guide.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xF20A0B10, 0xE60A0B10, 0x000A0B10}));
        guide.setPadding((int) dp(Ui.SAFE_SIDE), (int) dp(Ui.SAFE_TOP), (int) dp(20), (int) dp(Ui.SAFE_TOP));
        TextView gt = Ui.text(this, Ui.HEADLINE, 700, Ui.TEXT); gt.setText("Live TV");
        guide.addView(gt);
        TextView gh = Ui.text(this, Ui.CAPTION, 500, Ui.TEXT_3); gh.setText("OK tonton  ·  ▶ favorit  ·  Menu ingatkan acara berikutnya");
        guide.addView(gh);
        rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this); sv.setVerticalScrollBarEnabled(false); sv.addView(rows);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, 0, 1); sl.topMargin = (int) dp(12);
        guide.addView(sv, sl);
        guide.setVisibility(View.GONE);
        root.addView(guide, new FrameLayout.LayoutParams((int) dp(480), -1, Gravity.START));
        setContentView(root);

        // Decoder fallback: if the TV's hardware decoder refuses a stream, try the next decoder instead of failing the source.
        // FFmpeg (audio: AC-3, E-AC-3, DTS, MP2…) after the TV's own decoders, for channels whose sound the TV can't decode.
        player = new ExoPlayer.Builder(this, new DefaultRenderersFactory(this).setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)).build();
        player.addAnalyticsListener(new androidx.media3.exoplayer.analytics.AnalyticsListener() {
            @Override public void onAudioDecoderInitialized(EventTime t, String name, long a, long b) { tech.kousozo.tahoetv.core.DiagLog.add("live", "audio decoder " + name); }
            @Override public void onVideoDecoderInitialized(EventTime t, String name, long a, long b) { tech.kousozo.tahoetv.core.DiagLog.add("live", "video decoder " + name); }
        });
        // Handle audio focus: pause for the adzan (and other transient sounds), resume after.
        player.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA).setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setVideoSurfaceView(surface);
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException e) {
                tech.kousozo.tahoetv.core.DiagLog.error("live", e.getErrorCodeName() + (source < order.size() ? " @ " + order.get(source).getUrl() : ""), e.getCause());
                // Fell out of the stream's live window: rejoin at the live point (not a failure of the source).
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) { player.seekToDefaultPosition(); player.prepare(); return; }
                recover();
            }
            @Override public void onPlaybackStateChanged(int s) {
                if (s == Player.STATE_READY) {
                    if (!everReady && isRecording()) { tech.kousozo.tahoetv.core.DiagLog.add("live", "rekaman, bukan live → sumber berikutnya"); nextSource(); return; } // a finished/old playlist: not live TV, try the backup
                    if (!everReady) tech.kousozo.tahoetv.core.DiagLog.add("live", "siap dalam " + (System.currentTimeMillis() - sourceStarted) + " ms");
                    if (source < order.size()) SourceHealth.report(order.get(source).getUrl(), true);
                    everReady = true; retries = 0; loading.setVisibility(View.GONE); bStatus.setVisibility(View.GONE);
                }
            }
        });

        Live.fetch(this, all -> {
            List<LocalTv.Entry> l = Kids.channels(this, all); // kids profile: kids' and documentary channels only
            list = l;
            if (l.isEmpty()) { loading.setText(Kids.on(this) ? "Belum ada saluran anak yang bisa diputar." : "Tidak ada saluran."); return; }
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
        if (index >= 0) previous = index;
        index = ((i % list.size()) + list.size()) % list.size();
        source = 0;
        LocalTv.Entry e = list.get(index);
        List<M3u.Channel> raw = new ArrayList<>(); raw.add(e.getChannel()); raw.addAll(e.getAlternates());
        order = SourceHealth.order(raw); // fixed for this tune, so `source` keeps pointing at the same stream
        String url = list.get(index).getChannel().getUrl();
        List<String> rc = recent(); rc.remove(url); rc.add(0, url); while (rc.size() > 10) rc.remove(rc.size() - 1);
        prefs.edit().putString("last", url).putString("recent", android.text.TextUtils.join("\n", rc)).apply();
        if (Home.live != null) Home.live.pushState(); // phone remote shows the channel
        play();
        showBanner();
    }

    /** This channel's streams, known-live first (SourceHealth), as ordered when it was tuned. */
    List<M3u.Channel> order = new ArrayList<>();
    List<M3u.Channel> streams() { return order; }

    void play() {
        List<M3u.Channel> s = streams();
        if (source >= s.size()) {
            tech.kousozo.tahoetv.core.DiagLog.add("live", list.get(index).getName() + ": semua sumber gagal");
            player.stop();
            LocalTv.Entry e = list.get(index);
            boolean marked = SourceHealth.isBanned(e.getChannel().getUrl());
            for (M3u.Channel a : e.getAlternates()) marked |= SourceHealth.isBanned(a.getUrl());
            bStatus.setText(marked ? "Saluran ini sedang tidak bisa diputar. Tahan OK untuk memulihkan sumber yang ditandai \"bukan live\", atau ▼ untuk saluran berikutnya."
                    : "Saluran ini sedang tidak bisa diputar. Tekan ▼ untuk saluran berikutnya.");
            bStatus.setVisibility(View.VISIBLE); loading.setVisibility(View.GONE); showBanner(); return;
        }
        M3u.Channel c = s.get(source);
        tech.kousozo.tahoetv.core.DiagLog.add("live", list.get(index).getName() + " sumber " + (source + 1) + "/" + s.size() + " " + c.getUrl());
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(8000).setReadTimeoutMs(15000)
                .setUserAgent(c.getHeaders().containsKey("User-Agent") ? c.getHeaders().get("User-Agent") : "TahoeTV/3.0.1");
        java.util.HashMap<String, String> h = new java.util.HashMap<>(c.getHeaders()); h.remove("User-Agent");
        http.setDefaultRequestProperties(h);
        player.setMediaSource(new DefaultMediaSourceFactory(http).createMediaSource(new MediaItem.Builder().setUri(Uri.parse(c.getUrl()))
                // Play ~20 s behind live (default ~12 s) so a slow piece from the server doesn't empty the buffer;
                // ExoPlayer clamps this to what the stream keeps available.
                .setLiveConfiguration(new MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(20_000).build()).build()));
        player.prepare(); player.play();
        sourceStarted = System.currentTimeMillis(); everReady = false; retries = 0; lastPos = -1; stuckSince = System.currentTimeMillis();
        loading.channel(list.get(index).getChannel().getLogo(), list.get(index).getName());
        loading.setText(""); loading.setVisibility(View.VISIBLE);
    }

    /** Number keys (TV remote or phone): type 1–3 digits, it tunes 1.5 s after the last one. */
    int typed = 0;
    final Runnable tuneTyped = () -> { if (typed > 0) tune(typed - 1); typed = 0; };
    void digit(int n) {
        typed = typed >= 100 ? n : typed * 10 + n;
        bNum.setText(String.format(java.util.Locale.ROOT, "%03d", typed)); osd.animate().alpha(1).setDuration(Ui.FAST).start();
        ui.removeCallbacks(tuneTyped); ui.postDelayed(tuneTyped, 1500);
    }

    /** Previous channel (LAST key on the TV remote, ⟲ on the phone). */
    void back() { if (previous >= 0 && previous < list.size()) tune(previous); }

    /** True when the source that just became ready is a recording, not a live channel: a finished HLS playlist
     *  (ENDLIST / VOD) or a "live" window whose position is more than 15 min in the past (yesterday's show replayed).
     *  Movies/VOD files are meant to be like that, and non-HLS streams carry no such information. */
    boolean isRecording() {
        if (index < 0 || source >= order.size() || LocalTv.INSTANCE.isVod(order.get(source))) return false;
        if (!(player.getCurrentManifest() instanceof HlsManifest)) return false;
        Timeline t = player.getCurrentTimeline();
        if (t.isEmpty()) return false;
        Timeline.Window w = t.getWindow(player.getCurrentMediaItemIndex(), new Timeline.Window());
        if (!w.isLive()) return true;
        long off = player.getCurrentLiveOffset();
        return off != C.TIME_UNSET && off > 15 * 60_000L;
    }

    /** ⏪ / ⏩ within what the server keeps (the live window); past the live edge it jumps back to live. */
    void seekRel(long ms) {
        if (player == null || index < 0) return;
        long pos = player.getCurrentPosition(), off = player.getCurrentLiveOffset();
        boolean live = player.isCurrentMediaItemLive();
        // forward: decided by the live offset (live streams often report no duration); past the ~20 s cushion = live again
        if (ms > 0 && live && (off == C.TIME_UNSET || off - ms <= 28_000)) player.seekToDefaultPosition();
        else player.seekTo(Math.max(0, pos + ms));
        player.play(); showBanner();
    }

    /** "● LIVE", or how far behind the live point we are (after ⏪ or a pause). */
    String liveLabel() {
        if (player == null || !player.getPlayWhenReady()) return "⏸ JEDA";
        long off = player.getCurrentLiveOffset();
        if (off == C.TIME_UNSET || off < 40_000) return "● LIVE";
        long sec = Math.max(0, off - 20_000) / 1000; // ~20 s behind is the normal cushion
        return "⏪ -" + (sec >= 60 ? (sec / 60) + " mnt " + (sec % 60) + " dtk" : sec + " dtk");
    }

    /** Long-press OK: this source plays a recording / old show. Skip it now and never start it first again.
     *  On a channel with nothing left to play, long-press OK is the undo: every source marked this way comes back. */
    void flagRecording() {
        if (index < 0) return;
        if (source >= order.size()) {
            LocalTv.Entry e = list.get(index);
            List<M3u.Channel> all = new ArrayList<>(); all.add(e.getChannel()); all.addAll(e.getAlternates());
            int n = SourceHealth.unbanAll(all);
            Toast.makeText(this, n > 0 ? n + " sumber yang ditandai dipulihkan" : "Tidak ada sumber yang ditandai di saluran ini", Toast.LENGTH_SHORT).show();
            if (n > 0) tune(index);
            return;
        }
        SourceHealth.ban(order.get(source).getUrl());
        tech.kousozo.tahoetv.core.DiagLog.add("live", "ditandai bukan live: " + order.get(source).getUrl());
        Toast.makeText(this, "Sumber dilewati: bukan siaran langsung", Toast.LENGTH_SHORT).show();
        nextSource();
    }

    void nextSource() { if (source < order.size()) SourceHealth.report(order.get(source).getUrl(), false); source++; play(); }

    boolean everReady; int retries;

    /** A source that already played and then errors or freezes reconnects and carries on from the same spot
     *  (seeking to the "default position" here used to jump ~15 s back); only a source that never played, or keeps
     *  failing, moves to the backup. */
    void recover() {
        if (!everReady || retries >= 3) { nextSource(); return; }
        retries++; stuckSince = System.currentTimeMillis(); lastPos = -1;
        loading.setText(""); loading.setVisibility(View.VISIBLE);
        ui.postDelayed(() -> { player.prepare(); player.play(); }, 1000);
    }

    long lastPos = -1, stuckSince;

    /** Never started in 20 s, or picture frozen for 12 s → next source. */
    final Runnable watchdog = new Runnable() { @Override public void run() {
        if (player != null && index >= 0 && source < streams().size()) {
            long now = System.currentTimeMillis(), pos = player.getCurrentPosition();
            boolean moving = player.isPlaying() && pos != lastPos;
            int state = player.getPlaybackState();
            // paused on purpose, or paused for the adzan (audio focus), isn't stuck
            if (moving || !player.getPlayWhenReady() || player.getPlaybackSuppressionReason() != Player.PLAYBACK_SUPPRESSION_REASON_NONE) { lastPos = pos; stuckSince = now; }
            if (!everReady && now - sourceStarted > 20_000) { tech.kousozo.tahoetv.core.DiagLog.add("live", "tidak mulai dalam 20 dtk → sumber berikutnya"); nextSource(); }
            // Buffering is ExoPlayer's job: give it 30 s. "Ready" but the picture not moving for 12 s = frozen.
            else if (lastPos >= 0 && now - stuckSince > (state == Player.STATE_BUFFERING ? 30_000 : 12_000)) { tech.kousozo.tahoetv.core.DiagLog.add("live", "gambar macet → sambung ulang"); recover(); }
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
        bLive.setText(liveLabel());
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
        bLive = pill("● LIVE", Ui.LIVE, 0xFFFFFFFF);
        chips.addView(bLive);
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
        String[][] keys = {{"▲▼", "Ganti saluran"}, {"OK", "Daftar saluran"}, {"⏪⏩", "Tunda / maju"}, {"▶", "Info"}, {"↩", "Keluar"}};
        int[] colors = {Ui.ACCENT, Ui.GOOD, Ui.SUN, Ui.TEXT_3, Ui.LIVE};
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

    /** Favourite channels (stream URLs), newest-first recent channels, and the channel before this one. */
    java.util.Set<String> favs() { return new java.util.HashSet<>(prefs.getStringSet("fav", new java.util.HashSet<>())); }
    List<String> recent() { String v = prefs.getString("recent", ""); return v.isEmpty() ? new ArrayList<>() : new ArrayList<>(java.util.Arrays.asList(v.split("\n"))); }
    int previous = -1;
    View[] allRows = new View[0]; // the "Semua saluran" row of each channel, for focusing

    /** Guide: ★ Favorit, Terakhir ditonton, then every channel. */
    void buildRows() {
        rows.removeAllViews();
        Instant now = Instant.now();
        java.util.Set<String> fav = favs();
        java.util.Map<String, Integer> at = new java.util.HashMap<>();
        for (int k = 0; k < list.size(); k++) at.put(list.get(k).getChannel().getUrl(), k);
        List<Integer> favRows = new ArrayList<>(), recentRows = new ArrayList<>();
        for (int k = 0; k < list.size(); k++) if (fav.contains(list.get(k).getChannel().getUrl())) favRows.add(k);
        for (String u : recent()) { Integer k = at.get(u); if (k != null && !fav.contains(u) && recentRows.size() < 5) recentRows.add(k); }
        if (!favRows.isEmpty()) { section("★  Favorit"); for (int k : favRows) rows.addView(row(k, now, fav)); }
        if (!recentRows.isEmpty()) { section("Terakhir ditonton"); for (int k : recentRows) rows.addView(row(k, now, fav)); }
        if (!favRows.isEmpty() || !recentRows.isEmpty()) section("Semua saluran");
        allRows = new View[list.size()];
        for (int k = 0; k < list.size(); k++) { allRows[k] = row(k, now, fav); rows.addView(allRows[k]); }
    }

    void section(String title) {
        TextView t = Ui.eyebrow(this, Ui.TEXT_3); t.setText(title);
        t.setPadding((int) dp(10), (int) dp(14), 0, (int) dp(4));
        rows.addView(t);
    }

    View row(int k, Instant now, java.util.Set<String> fav) {
        LocalTv.Entry e = list.get(k);
        LinearLayout r = new LinearLayout(this); r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding((int) dp(10), (int) dp(8), (int) dp(12), (int) dp(8));
        r.setFocusable(true); r.setFocusableInTouchMode(true);
        // live / dead / not checked yet, from the start-up server check
        List<M3u.Channel> st = new ArrayList<>(); st.add(e.getChannel()); st.addAll(e.getAlternates());
        Boolean live = SourceHealth.live(st);
        View dot = new View(this);
        android.graphics.drawable.GradientDrawable dg = new android.graphics.drawable.GradientDrawable(); dg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dg.setColor(live == null ? 0x55FFFFFF : live ? Ui.GOOD : Ui.LIVE); dot.setBackground(dg);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams((int) dp(7), (int) dp(7)); dl.rightMargin = (int) dp(8);
        r.addView(dot, dl);
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
        Epg.Programme nx = null;
        if (s != null) for (Epg.Programme q : s) if (q.getStart().isAfter(now)) { nx = q; break; }
        boolean reminded = nx != null && Reminder.isSet(this, e.getChannel().getUrl(), nx.getStart().toEpochMilli());
        TextView sub = Ui.text(this, Ui.CAPTION, 500, reminded ? Ui.ACCENT : Ui.TEXT_3); sub.setShadowLayer(0, 0, 0, 0);
        sub.setText(reminded ? "🔔 " + java.time.format.DateTimeFormatter.ofPattern("HH.mm").withZone(java.time.ZoneId.systemDefault()).format(nx.getStart()) + "  " + nx.getTitle()
                : p != null ? p.getTitle() : e.getCategory().getLabel());
        col.addView(name); col.addView(sub);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1); cp.leftMargin = (int) dp(12);
        r.addView(col, cp);
        if (fav.contains(e.getChannel().getUrl())) { TextView star = Ui.text(this, Ui.BODY, 700, Ui.SUN); star.setText("★"); r.addView(star); }
        r.setOnFocusChangeListener((v, f) -> {
            v.setBackground(f ? new GlassDrawable(dp(Ui.R_SMALL), Ui.GLASS_FOCUS, dp(1)) : null);
            v.animate().scaleX(f ? 1.03f : 1f).scaleY(f ? 1.03f : 1f).setDuration(Ui.FAST).start();
        });
        r.setOnClickListener(v -> { guide.setVisibility(View.GONE); tune(k); });
        Epg.Programme next = nx;
        r.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { toggleFav(k); return true; }
            if (code == KeyEvent.KEYCODE_MENU) { remind(k, next); return true; }
            return false;
        });
        return r;
    }

    void remind(int k, Epg.Programme next) {
        LocalTv.Entry e = list.get(k);
        if (next == null) { Toast.makeText(this, "Belum ada jadwal acara berikutnya untuk " + e.getName(), Toast.LENGTH_SHORT).show(); return; }
        boolean on = Reminder.toggle(this, e.getChannel().getUrl(), e.getName(), next.getTitle(), next.getStart().toEpochMilli());
        Toast.makeText(this, (on ? "🔔 Diingatkan: " : "Pengingat dibatalkan: ") + next.getTitle(), Toast.LENGTH_SHORT).show();
        buildRows();
        if (k < allRows.length) allRows[k].requestFocus();
    }

    void toggleFav(int k) {
        String u = list.get(k).getChannel().getUrl();
        java.util.Set<String> f = favs();
        boolean add = f.add(u); if (!add) f.remove(u);
        prefs.edit().putStringSet("fav", f).apply();
        Toast.makeText(this, (add ? "★ Ditambahkan ke favorit: " : "Dihapus dari favorit: ") + list.get(k).getName(), Toast.LENGTH_SHORT).show();
        buildRows();
        if (k < allRows.length) allRows[k].requestFocus();
    }

    void openGuide() {
        guide.setVisibility(View.VISIBLE);
        buildRows(); // fresh live/dead dots
        if (index >= 0 && index < allRows.length) allRows[index].requestFocus();
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        boolean open = guide.getVisibility() == View.VISIBLE;
        if (code == KeyEvent.KEYCODE_BACK) { if (open) guide.setVisibility(View.GONE); else finish(); return true; }
        if (open) return super.onKeyDown(code, e);
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) { digit(code - KeyEvent.KEYCODE_0); return true; }
        if (code == KeyEvent.KEYCODE_LAST_CHANNEL) { back(); return true; }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_CHANNEL_UP: tune(index - 1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_CHANNEL_DOWN: tune(index + 1); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: e.startTracking(); return true; // tap = list, hold = "not live"
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_MENU: openGuide(); return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (player != null) { boolean pause = code == KeyEvent.KEYCODE_MEDIA_PAUSE || (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && player.getPlayWhenReady());
                    if (pause) player.pause(); else { player.play(); if (code == KeyEvent.KEYCODE_MEDIA_PLAY) player.seekToDefaultPosition(); } showBanner(); }
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND: seekRel(-30_000); return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: seekRel(30_000); return true;
            case KeyEvent.KEYCODE_PROG_RED: flagRecording(); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_INFO: showBanner(); return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override public boolean onKeyLongPress(int code, KeyEvent e) {
        if (guide.getVisibility() != View.VISIBLE && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) { flagRecording(); return true; }
        return super.onKeyLongPress(code, e);
    }

    @Override public boolean onKeyUp(int code, KeyEvent e) {
        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && guide.getVisibility() != View.VISIBLE
                && e.isTracking() && !e.isCanceled()) { openGuide(); return true; }
        return super.onKeyUp(code, e);
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
