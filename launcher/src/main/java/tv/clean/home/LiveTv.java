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
import android.view.ViewGroup;
import android.widget.Toast;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.CueGroup;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager;
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider;
import androidx.media3.exoplayer.drm.FrameworkMediaDrm;
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback;
import androidx.media3.exoplayer.hls.HlsManifest;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import tech.kousozo.tahoetv.core.Drm;
import tech.kousozo.tahoetv.core.SourceHealth;
import tech.kousozo.tahoetv.core.Epg;
import tech.kousozo.tahoetv.core.I18n;
import tech.kousozo.tahoetv.core.LocalTv;
import tech.kousozo.tahoetv.core.M3u;
import tech.kousozo.tahoetv.core.OnboardingText;

/** Tahoe's built-in Live TV: full-screen player for the free channels (national priority first, then near you).
 *
 *  Remote: ▲/▼ (or CH+/CH−) zap · OK or ◀ opens the channel list · ▶ shows the info banner · Back closes the list, then exits.
 *  Each channel tries its streams in order (main, then backups); a source that errors, never starts, or freezes is
 *  skipped. When every source fails the banner says so and ▼ goes to the next channel. */
public class LiveTv extends Activity {
    /** The running player, so the phone remote's CH+/CH− can zap it. */
    static LiveTv live;
    final Handler ui = new Handler(Looper.getMainLooper());
    ExoPlayer player;
    FrameLayout surfaceRoot;
    SurfaceView surface;
    int videoW = 0, videoH = 0;
    float videoPar = 1f;
    LinearLayout guide, rows;
    FrameLayout osd;                       // on-screen display: top + bottom scrims with all the info
    Loader loading;
    TextView subtitleOverlay;
    TextView bLive, bNum, bName, bMeta, bNow, bNowTime, bNext, bNextTime, bStatus, bCat, tTemp, tCond, tDay, tTime, logoText, numOverlay;
    android.widget.ImageView logo;
    View progress;
    FrameLayout progressTrack;
    List<LocalTv.Entry> list = new ArrayList<>();
    Epg epg;
    int index = -1, source = 0;
    boolean sawRecording = false, pendingCenterKey = false, techVisible = false;
    long sourceStarted;
    SharedPreferences prefs;
    FrameLayout techPanel;
    LinearLayout techRows;
    TextView techCopyBtn, techLogBtn;
    int techFocusBtn = 0;
    String statsVideoDecoder = "", statsAudioDecoder = "";
    int rebufferCount = 0, bandwidthKbps = 0;
    long rebufferMs = 0L, bufferingSince = -1L, droppedFramesCount = 0L;

    float dp(float v) { return Ui.dp(this, v); }

    DateTimeFormatter hm() {
        return DateTimeFormatter.ofPattern("HH.mm").withZone(TvViewer.get(this).getZone());
    }

    private void fitVideoSurface() {
        if (surface == null || surfaceRoot == null) return;
        int rw = surfaceRoot.getWidth(), rh = surfaceRoot.getHeight();
        if (rw <= 0 || rh <= 0 || videoW <= 0 || videoH <= 0) return;
        float dispW = videoW * (videoPar > 0f ? videoPar : 1f);
        float scale = Math.min(rw / dispW, rh / (float) videoH);
        int w = Math.max(1, Math.round(dispW * scale));
        int h = Math.max(1, Math.round(videoH * scale));
        ViewGroup.LayoutParams lp = surface.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            surface.setLayoutParams(lp);
        }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Diag.install(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("live", MODE_PRIVATE);
        FrameLayout root = new FrameLayout(this);
        surfaceRoot = root;
        root.setBackgroundColor(Color.BLACK);
        root.addOnLayoutChangeListener((v, l, t, r, bot, ol, ot, or, ob) -> {
            if ((r - l) != (or - ol) || (bot - t) != (ob - ot)) fitVideoSurface();
        });
        surface = new SurfaceView(this);
        surface.setClickable(true);
        surface.setFocusable(false); surface.setFocusableInTouchMode(false);
        surface.setOnClickListener(v -> {
            if (guide.getVisibility() == View.VISIBLE) closeGuide();
            else if (osd.getAlpha() > 0.5f) dismissBanner();
            else showBanner();
        });
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        subtitleOverlay = Ui.text(this, tech.kousozo.tahoetv.core.PlayPrefs.SubtitleSize.of(prefs.getString("sub_size", null)).getSpTv(), 600, Color.WHITE);
        subtitleOverlay.setSingleLine(false);
        subtitleOverlay.setGravity(Gravity.CENTER);
        subtitleOverlay.setBackgroundColor(0xA6000000);
        subtitleOverlay.setPadding((int) dp(14), (int) dp(6), (int) dp(14), (int) dp(6));
        subtitleOverlay.setVisibility(View.GONE);
        FrameLayout.LayoutParams subLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        subLp.bottomMargin = (int) dp(72);
        root.addView(subtitleOverlay, subLp);

        techPanel = buildTechPanel();
        techPanel.setVisibility(View.GONE);
        root.addView(techPanel);

        loading = new Loader(this); // Apple TV / Netflix style: logo, name, spinner. No "loading source" text.
        root.addView(loading, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        slate = buildSlate(); // #76: shown instead of a channel that can't play
        slate.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        root.addView(slate, new FrameLayout.LayoutParams(-1, -1));

        osd = buildOsd();
        root.addView(osd, new FrameLayout.LayoutParams(-1, -1));

        numOverlay = Ui.text(this, 52, 700, Ui.TEXT);
        numOverlay.setPadding((int) dp(30), (int) dp(16), (int) dp(30), (int) dp(16));
        numOverlay.setBackground(new GlassDrawable(dp(20), Ui.GLASS, dp(1)));
        numOverlay.setGravity(Gravity.CENTER);
        numOverlay.setVisibility(View.GONE);
        root.addView(numOverlay, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // channel list (left)
        String initLang = TvViewer.get(this).getLanguage();
        guide = new LinearLayout(this); guide.setOrientation(LinearLayout.VERTICAL);
        guide.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xF20A0B10, 0xE60A0B10, 0x000A0B10}));
        guide.setPadding((int) dp(Ui.SAFE_SIDE), (int) dp(Ui.SAFE_TOP), (int) dp(20), (int) dp(Ui.SAFE_TOP));
        TextView gt = Ui.text(this, Ui.HEADLINE, 700, Ui.TEXT); gt.setText("Live TV");
        guide.addView(gt);
        TextView gh = Ui.text(this, Ui.CAPTION, 500, Ui.TEXT_3); gh.setText(I18n.get(initLang, "tv.live_guide_hint"));
        guide.addView(gh);
        rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this); sv.setVerticalScrollBarEnabled(false); sv.addView(rows);
        sv.setOnScrollChangeListener((v, sx, sy, osx, osy) -> {
            if (rows.getHeight() - (sy + sv.getHeight()) <= (int) dp(400) && renderedEnd < list.size()) {
                ensureRowsThrough(renderedEnd + ROW_STEP);
            }
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, 0, 1); sl.topMargin = (int) dp(12);
        guide.addView(sv, sl);
        guide.setVisibility(View.GONE);
        int guideW = (int) Math.min(dp(480), getResources().getDisplayMetrics().widthPixels * 0.85f);
        root.addView(guide, new FrameLayout.LayoutParams(guideW, -1, Gravity.START));
        setContentView(root);

        tech.kousozo.tahoetv.core.PlayPrefs.PlaybackTuning initTuning = tech.kousozo.tahoetv.core.PlayPrefs.tuningFor(
                tech.kousozo.tahoetv.core.PlayPrefs.Latency.of(prefs.getString("latency", null)),
                false,
                DeviceProfiler.current(this).getActiveTier() == tech.kousozo.tahoetv.core.DeviceProfile.Tier.LIGHT);
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(initTuning.getMinBufferMs(), initTuning.getMaxBufferMs(),
                        initTuning.getBufferForPlaybackMs(), initTuning.getBufferForPlaybackAfterRebufferMs())
                .build();
        player = new ExoPlayer.Builder(this, new DefaultRenderersFactory(this).setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON))
                .setLoadControl(loadControl)
                .build();
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override public void onAudioDecoderInitialized(EventTime t, String name, long a, long b) {
                statsAudioDecoder = name != null ? name : "";
                tech.kousozo.tahoetv.core.DiagLog.add("live", "audio decoder " + name);
            }
            @Override public void onVideoDecoderInitialized(EventTime t, String name, long a, long b) {
                statsVideoDecoder = name != null ? name : "";
                tech.kousozo.tahoetv.core.DiagLog.add("live", "video decoder " + name);
            }
            @Override public void onDroppedVideoFrames(EventTime t, int count, long elapsedMs) {
                droppedFramesCount += Math.max(0, count);
            }
            @Override public void onBandwidthEstimate(EventTime t, int totalLoadTimeMs, long totalBytesLoaded, long bitrateEstimate) {
                if (bitrateEstimate > 0) bandwidthKbps = (int) (bitrateEstimate / 1000L);
            }
        });
        // Handle audio focus: pause for the adzan (and other transient sounds), resume after.
        player.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA).setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.setVideoSurfaceView(surface);
        player.addListener(new Player.Listener() {
            @Override public void onVideoSizeChanged(VideoSize vs) {
                if (vs.width > 0 && vs.height > 0) {
                    videoW = vs.width;
                    videoH = vs.height;
                    videoPar = vs.pixelWidthHeightRatio > 0f ? vs.pixelWidthHeightRatio : 1f;
                    fitVideoSurface();
                }
            }
            @Override public void onPlayerError(PlaybackException e) {
                tech.kousozo.tahoetv.core.DiagLog.error("live", e.getErrorCodeName() + (source < order.size() ? " @ " + order.get(source).getUrl() : ""), e.getCause());
                // Fell out of the stream's live window: rejoin at the live point (not a failure of the source).
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) { player.seekToDefaultPosition(); player.prepare(); return; }
                recover();
            }
            @Override public void onPlaybackStateChanged(int s) {
                if (s == Player.STATE_BUFFERING && everReady && bufferingSince < 0) {
                    bufferingSince = System.currentTimeMillis();
                } else if (s == Player.STATE_READY) {
                    if (bufferingSince > 0) {
                        rebufferCount++;
                        rebufferMs += Math.max(0L, System.currentTimeMillis() - bufferingSince);
                        bufferingSince = -1L;
                    }
                    if (!everReady && isRecording()) { tech.kousozo.tahoetv.core.DiagLog.add("live", "rekaman, bukan live → sumber berikutnya"); sawRecording = true; nextSource(); return; } // a finished/old playlist: not live TV, try the backup
                    if (!everReady) {
                        long readyMs = System.currentTimeMillis() - sourceStarted;
                        tech.kousozo.tahoetv.core.DiagLog.add("live", "siap dalam " + readyMs + " ms");
                        if (index >= 0 && index < list.size()) tech.kousozo.tahoetv.core.DeviceProfile.recordFirstFrameMs(list.get(index).getName(), readyMs);
                    }
                    if (source < order.size()) SourceHealth.report(order.get(source).getUrl(), true);
                    everReady = true; retries = 0; loading.setVisibility(View.GONE); bStatus.setVisibility(View.GONE);
                    hideSlate(); slateRetries = 0;
                    if (techVisible) refreshTechPanel();
                }
            }
            @Override public void onIsPlayingChanged(boolean isPlaying) {
                if (Home.live != null) Home.live.pushState();
            }
            @Override public void onCues(CueGroup cueGroup) {
                if (subtitleOverlay == null) return;
                StringBuilder sb = new StringBuilder();
                for (androidx.media3.common.text.Cue cue : cueGroup.cues) {
                    if (cue.text != null && cue.text.length() > 0) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(cue.text);
                    }
                }
                if (sb.length() == 0) subtitleOverlay.setVisibility(View.GONE);
                else {
                    subtitleOverlay.setText(sb.toString());
                    subtitleOverlay.setVisibility(View.VISIBLE);
                }
            }
        });

        Live.fetch(this, all -> {
            List<LocalTv.Entry> l = Kids.channels(this, all); // kids profile: kids' and documentary channels only
            list = l;
            if (l.isEmpty()) { loading.setText(I18n.get(TvViewer.get(this).getLanguage(), Kids.on(this) ? "tv.live_empty_kids" : "tv.live_empty")); return; }
            String wantId = getIntent().getStringExtra("channelId");
            String wantUrl = getIntent().getStringExtra("url"), last = prefs.getString("last", null);
            int i = findChannelIndex(wantId, wantUrl != null ? wantUrl : last);
            if (i < 0) i = 0;
            guideDirty = true;
            if (guide.getVisibility() == View.VISIBLE) buildRows();
            tune(i);
        }, e -> { epg = e; if (guide.getVisibility() == View.VISIBLE) buildRows(); else guideDirty = true; if (index >= 0) showBanner(); }, () -> loading.setText(I18n.get(TvViewer.get(this).getLanguage(), "tv.live_load_failed")));
        ui.post(watchdog);
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String cid = intent.getStringExtra("channelId"), u = intent.getStringExtra("url");
        if ((cid != null && !cid.isEmpty()) || (u != null && !u.isEmpty())) tuneByChannel(cid, u);
    }

    int findChannelIndex(String channelId, String url) {
        if (channelId != null && !channelId.isEmpty()) {
            for (int k = 0; k < list.size(); k++) {
                LocalTv.Entry e = list.get(k);
                if (channelId.equals(e.getChannel().getChannelId())) return k;
                for (M3u.Channel a : e.getAlternates()) if (channelId.equals(a.getChannelId())) return k;
            }
        }
        if (url != null && !url.isEmpty()) {
            for (int k = 0; k < list.size(); k++) {
                LocalTv.Entry e = list.get(k);
                if (url.equals(e.getChannel().getUrl())) return k;
                for (M3u.Channel a : e.getAlternates()) if (url.equals(a.getUrl())) return k;
            }
        }
        return -1;
    }

    void tuneByChannel(String channelId, String url) {
        int i = findChannelIndex(channelId, url);
        if (i >= 0) {
            if (guide.getVisibility() == View.VISIBLE) guide.setVisibility(View.GONE);
            tune(i);
        }
    }

    // ------------------------------------------------------------------ playback

    void tune(int i) {
        if (list.isEmpty()) return;
        if (index >= 0) previous = index;
        index = ((i % list.size()) + list.size()) % list.size();
        source = 0;
        sawRecording = false;
        videoW = 0; videoH = 0; videoPar = 1f;
        if (surface != null) {
            ViewGroup.LayoutParams lp = surface.getLayoutParams();
            if (lp != null && (lp.width != -1 || lp.height != -1)) {
                lp.width = -1; lp.height = -1;
                surface.setLayoutParams(lp);
            }
        }
        hideSlate(); slateRetries = 0; // another channel: a fresh start
        LocalTv.Entry e = list.get(index);
        List<M3u.Channel> raw = new ArrayList<>(); raw.add(e.getChannel()); raw.addAll(e.getAlternates());
        String url = list.get(index).getChannel().getUrl();
        List<String> rc = recent(); rc.remove(url); rc.add(0, url); while (rc.size() > 10) rc.remove(rc.size() - 1);
        prefs.edit().putString("last", url).putString("recent", android.text.TextUtils.join("\n", rc)).apply();
        if (Home.live != null) Home.live.pushState(); // phone remote shows the channel
        final int seq = ++tuneSeq;
        final long zapGen = tech.kousozo.tahoetv.core.StreamSelector.newZapGeneration();
        if (subtitleOverlay != null) subtitleOverlay.setVisibility(View.GONE);
        if (raw.size() <= 1) {
            order = SourceHealth.order(raw); // fixed for this tune, so `source` keeps pointing at the same stream
            play();
        } else {
            new Thread(() -> {
                List<M3u.Channel> ranked = tech.kousozo.tahoetv.core.StreamSelector.rankOnOpen(raw, 2500, zapGen);
                runOnUiThread(() -> {
                    if (seq == tuneSeq && !isFinishing()) {
                        order = ranked;
                        source = 0;
                        play();
                    }
                });
            }, "tahoe-rank").start();
        }
        showBanner();
        if (osd != null) osd.announceForAccessibility(I18n.get(TvViewer.get(this).getLanguage(), "tv.live_channel_num_named", String.valueOf(index + 1), e.getName()));
    }

    /** This channel's streams, known-live first (SourceHealth), as ordered when it was tuned. */
    private int tuneSeq = 0;
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
            bStatus.setText(I18n.get(TvViewer.get(this).getLanguage(), marked ? "tv.live_source_err_marked" : "tv.live_source_err"));
            bStatus.setVisibility(View.GONE); loading.setVisibility(View.GONE); dismissBanner();
            showSlate(sawRecording ? tech.kousozo.tahoetv.core.Slate.Reason.Recording : tech.kousozo.tahoetv.core.Slate.Reason.Offline);
            return;
        }
        M3u.Channel c = s.get(source);
        LocalTv.Entry curEntry = list.get(index);
        String chKey = tech.kousozo.tahoetv.core.PlayPrefs.channelTrackKey(curEntry.getChannel().getChannelId(), curEntry.getName());
        if (subtitleOverlay != null) {
            subtitleOverlay.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                    tech.kousozo.tahoetv.core.PlayPrefs.SubtitleSize.of(prefs.getString("sub_size", null)).getSpTv());
        }
        // viewer choices from Control Center (read on every start, so a change applies from the next channel/source)
        tech.kousozo.tahoetv.core.PlayPrefs.Quality q = tech.kousozo.tahoetv.core.PlayPrefs.Quality.of(prefs.getString("quality", null));
        tech.kousozo.tahoetv.core.PlayPrefs.Latency lat = tech.kousozo.tahoetv.core.PlayPrefs.Latency.of(prefs.getString("latency", null));
        boolean lightDevice = DeviceProfiler.current(this).getActiveTier() == tech.kousozo.tahoetv.core.DeviceProfile.Tier.LIGHT;
        boolean tightWindow = SourceHealth.isTightWindow(c.getUrl());
        tech.kousozo.tahoetv.core.PlayPrefs.PlaybackTuning tuning = tech.kousozo.tahoetv.core.PlayPrefs.tuningFor(lat, tightWindow, lightDevice);

        androidx.media3.common.TrackSelectionParameters.Builder tsp = player.getTrackSelectionParameters().buildUpon();
        tsp.clearOverridesOfType(C.TRACK_TYPE_AUDIO).clearOverridesOfType(C.TRACK_TYPE_TEXT);
        if (q.getMaxHeight() > 0) tsp.setMaxVideoSize(Integer.MAX_VALUE, q.getMaxHeight()); else tsp.clearVideoSizeConstraints();
        String remAudio = prefs.getString("tr_a_" + chKey, null);
        if (remAudio != null && !remAudio.isEmpty()) tsp.setPreferredAudioLanguage(remAudio);
        else tsp.setPreferredAudioLanguages();
        String remSub = prefs.getString("tr_s_" + chKey, null);
        if ("off".equals(remSub)) {
            tsp.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
        } else if (remSub != null && !remSub.isEmpty()) {
            tsp.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(remSub);
        } else {
            tsp.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguages();
        }
        player.setTrackSelectionParameters(tsp.build());
        tech.kousozo.tahoetv.core.DiagLog.add("live", curEntry.getName() + " sumber " + (source + 1) + "/" + s.size() + " " + c.getUrl());
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(8000).setReadTimeoutMs(15000)
                .setUserAgent(c.getHeaders().containsKey("User-Agent") ? c.getHeaders().get("User-Agent") : tech.kousozo.tahoetv.core.Web.STREAM_UA);
        java.util.HashMap<String, String> h = new java.util.HashMap<>(c.getHeaders()); h.remove("User-Agent");
        http.setDefaultRequestProperties(h);
        MediaItem.Builder item = new MediaItem.Builder().setUri(Uri.parse(c.getUrl()))
                // Play behind live with explicit min/max/speed bounds so short-segment HLS feeds (e.g. 2s Wowza) stay steady (#32).
                .setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(tuning.getTargetOffsetMs())
                        .setMinOffsetMs(tuning.getMinOffsetMs())
                        .setMaxOffsetMs(tuning.getMaxOffsetMs())
                        .setMinPlaybackSpeed(tuning.getMinPlaybackSpeed())
                        .setMaxPlaybackSpeed(tuning.getMaxPlaybackSpeed())
                        .build());
        if (Drm.isDash(c.getUrl(), c.getDrmType(), c.getManifestType())) {
            item.setMimeType(MimeTypes.APPLICATION_MPD);
        } else if (c.getUrl().toLowerCase(Locale.ROOT).contains(".m3u8")) {
            item.setMimeType(MimeTypes.APPLICATION_M3U8);
        }
        DefaultMediaSourceFactory factory = new DefaultMediaSourceFactory(http);
        Drm.Scheme scheme = Drm.scheme(c.getDrmType(), c.getDrmKey());
        String jwk = Drm.toClearKeyJwk(c.getDrmKey());
        String licUrl = Drm.licenseUrl(c.getDrmKey());
        if (jwk != null) {
            DefaultDrmSessionManager mgr = new DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .setMultiSession(true)
                    .build(new LocalMediaDrmCallback(jwk.getBytes(StandardCharsets.UTF_8)));
            factory.setDrmSessionManagerProvider(mediaItem -> mgr);
        } else if (licUrl != null && scheme != Drm.Scheme.NONE) {
            UUID uuid = scheme == Drm.Scheme.WIDEVINE ? C.WIDEVINE_UUID
                    : scheme == Drm.Scheme.PLAYREADY ? C.PLAYREADY_UUID : C.CLEARKEY_UUID;
            MediaItem.DrmConfiguration.Builder drmCfg = new MediaItem.DrmConfiguration.Builder(uuid)
                    .setLicenseUri(licUrl)
                    .setMultiSession(true);
            if (!c.getHeaders().isEmpty()) drmCfg.setLicenseRequestHeaders(c.getHeaders());
            item.setDrmConfiguration(drmCfg.build());
            DefaultDrmSessionManagerProvider drmProv = new DefaultDrmSessionManagerProvider();
            drmProv.setDrmHttpDataSourceFactory(http);
            factory.setDrmSessionManagerProvider(drmProv);
        }
        player.setMediaSource(factory.createMediaSource(item.build()));
        targetOffsetMs = tuning.getTargetOffsetMs();
        rebufferCount = 0; rebufferMs = 0L; bufferingSince = -1L; droppedFramesCount = 0L;
        statsVideoDecoder = ""; statsAudioDecoder = "";
        player.prepare(); player.play();
        sourceStarted = System.currentTimeMillis(); everReady = false; retries = 0; lastPos = -1; stuckSince = System.currentTimeMillis();
        loading.channel(list.get(index).getChannel().getLogo(), list.get(index).getName());
        loading.setText(""); loading.setVisibility(View.VISIBLE);
    }

    /** Number keys (TV remote or phone): type 1–3 digits, it tunes 1.5 s after the last one. */
    int typed = 0;
    final Runnable tuneTyped = () -> {
        if (numOverlay != null) numOverlay.setVisibility(View.GONE);
        if (typed > 0) tune(typed - 1);
        typed = 0;
    };
    void digit(int n) {
        typed = typed >= 100 ? n : typed * 10 + n;
        String txt = String.format(java.util.Locale.ROOT, "%03d", typed);
        bNum.setText(txt); osd.animate().alpha(1).setDuration(Ui.FAST).start();
        if (numOverlay != null) {
            numOverlay.setText(txt);
            numOverlay.setContentDescription(I18n.get(TvViewer.get(this).getLanguage(), "tv.live_channel_num", String.valueOf(typed)));
            numOverlay.setVisibility(View.VISIBLE);
        }
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
        // forward: decided by the live offset (live streams often report no duration); back within the cushion = live again
        if (ms > 0 && live && (off == C.TIME_UNSET || off - ms <= targetOffsetMs + 8_000)) player.seekToDefaultPosition();
        else player.seekTo(Math.max(0, pos + ms));
        player.play(); showBanner();
    }

    /** How far behind live the current source aims to play (the chosen latency). */
    private long targetOffsetMs = 20_000;

    /** "● LIVE", or how far behind the live point we are (after ⏪ or a pause). */
    String liveLabel() {
        String lang = TvViewer.get(this).getLanguage();
        if (player == null || !player.getPlayWhenReady()) return "⏸ " + I18n.get(lang, "tv.live_paused");
        long off = player.getCurrentLiveOffset();
        if (off == C.TIME_UNSET || off < targetOffsetMs + 20_000) return "● LIVE";
        long sec = Math.max(0, off - targetOffsetMs) / 1000; // the chosen latency is the normal cushion
        return sec >= 60 ? I18n.get(lang, "tv.live_min_sec_ago", String.valueOf(sec / 60), String.valueOf(sec % 60))
                : I18n.get(lang, "tv.live_sec_ago", String.valueOf(sec));
    }

    /** Long-press OK: this source plays a recording / old show. Skip it now and never start it first again.
     *  On a channel with nothing left to play, long-press OK is the undo: every source marked this way comes back. */
    void flagRecording() {
        if (index < 0) return;
        String lang = TvViewer.get(this).getLanguage();
        if (source >= order.size()) {
            LocalTv.Entry e = list.get(index);
            List<M3u.Channel> all = new ArrayList<>(); all.add(e.getChannel()); all.addAll(e.getAlternates());
            int n = SourceHealth.unbanAll(all);
            Toast.makeText(this, n > 0 ? I18n.get(lang, "tv.live_restored_fmt", String.valueOf(n)) : I18n.get(lang, "tv.live_no_marked"), Toast.LENGTH_SHORT).show();
            if (n > 0) tune(index);
            return;
        }
        sawRecording = true;
        SourceHealth.ban(order.get(source).getUrl());
        tech.kousozo.tahoetv.core.DiagLog.add("live", "ditandai bukan live: " + order.get(source).getUrl());
        Toast.makeText(this, I18n.get(lang, "tv.live_skipped_not_live"), Toast.LENGTH_SHORT).show();
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

    void dismissBanner() {
        ui.removeCallbacks(hideBanner);
        if (osd != null) osd.animate().alpha(0).setDuration(Ui.MED).start();
    }

    void showBanner() {
        if (index < 0) return;
        String lang = TvViewer.get(this).getLanguage();
        LocalTv.Entry e = list.get(index);
        bNum.setText(String.format(java.util.Locale.ROOT, "%03d", index + 1));
        bName.setText(e.getName().toUpperCase(java.util.Locale.ROOT));
        int sources = 1 + e.getAlternates().size();
        String catLbl = OnboardingText.INSTANCE.category(lang, e.getCategory());
        bMeta.setText(catLbl
                + (sources > 1 ? "  ·  " + I18n.get(lang, "tv.live_sources_fmt", String.valueOf(sources)) : "")
                + (e.getNear() ? "  ·  " + I18n.get(lang, "ch.near_badge") : ""));
        bCat.setText(catLbl);
        bLive.setText(liveLabel());
        Logos.into(e.getChannel().getLogo(), logo, logoText, e.getName());
        Instant now = Instant.now();
        List<Epg.Programme> s = epg == null ? new ArrayList<>() : epg.schedule(e.getName(), now, e.getChannel().getTvgId());
        Epg.Programme p = !s.isEmpty() && !s.get(0).getStart().isAfter(now) ? s.get(0) : null;
        Epg.Programme n = s.size() > (p != null ? 1 : 0) ? s.get(p != null ? 1 : 0) : null;
        DateTimeFormatter fmt = hm();
        bNowTime.setText(p != null ? fmt.format(p.getStart()) + " – " + fmt.format(p.getStop()) : I18n.get(lang, "tv.live_now_lbl"));
        bNow.setText(p != null ? p.getTitle() : epg == null ? I18n.get(lang, "tv.guide_loading_sched") : I18n.get(lang, "tv.guide_no_sched"));
        ((View) bNext.getParent()).setVisibility(n != null ? View.VISIBLE : View.GONE);
        if (n != null) { bNextTime.setText(fmt.format(n.getStart()) + " – " + fmt.format(n.getStop())); bNext.setText(n.getTitle()); }
        float f = p == null ? 0 : Math.max(0, Math.min(1, (now.getEpochSecond() - p.getStart().getEpochSecond()) / (float) Math.max(1, p.getStop().getEpochSecond() - p.getStart().getEpochSecond())));
        progressTrack.post(() -> { progress.getLayoutParams().width = (int) (progressTrack.getWidth() * f); progress.requestLayout(); });
        tick();
        osd.animate().cancel(); osd.setAlpha(1);
        ui.removeCallbacks(hideBanner); ui.postDelayed(hideBanner, 6000);
    }

    /** Clock + date (top right). */
    void tick() {
        tech.kousozo.tahoetv.core.Viewer tv = TvViewer.get(this);
        Locale loc = Locale.forLanguageTag(tv.getLanguage());
        java.util.TimeZone tz = java.util.TimeZone.getTimeZone(tv.getZone().getId());
        java.util.Date d = new java.util.Date();
        java.text.SimpleDateFormat dfDay = new java.text.SimpleDateFormat("EEEE", loc);
        dfDay.setTimeZone(tz);
        java.text.SimpleDateFormat dfTime = new java.text.SimpleDateFormat("d MMM yyyy  ·  HH.mm", loc);
        dfTime.setTimeZone(tz);
        tDay.setText(dfDay.format(d));
        tTime.setText(dfTime.format(d));
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
        boolean compact = getResources().getConfiguration().screenWidthDp < 600;
        int side = (int) dp(compact ? 20 : Ui.SAFE_SIDE);
        int topM = (int) dp(compact ? 24 : Ui.SAFE_TOP);
        FrameLayout o = new FrameLayout(this);
        o.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        View top = new View(this);
        top.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xB3000000, 0x00000000}));
        o.addView(top, new FrameLayout.LayoutParams(-1, (int) dp(150), Gravity.TOP));
        View bottom = new View(this);
        bottom.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xF2000000, 0xB0000000, 0x00000000}));
        o.addView(bottom, new FrameLayout.LayoutParams(-1, (int) dp(compact ? 360 : 300), Gravity.BOTTOM));

        // top left: weather
        String lang = TvViewer.get(this).getLanguage();
        LinearLayout w = new LinearLayout(this); w.setGravity(Gravity.CENTER_VERTICAL);
        tTemp = Ui.text(this, compact ? 26 : 34, 600, Ui.TEXT); tTemp.setText("—°");
        LinearLayout wc = new LinearLayout(this); wc.setOrientation(LinearLayout.VERTICAL);
        TextView we = Ui.eyebrow(this, Ui.TEXT_3); we.setText(I18n.get(lang, "home.weather"));
        tCond = Ui.text(this, Ui.BODY, 600, Ui.TEXT); tCond.setText(Cfg.s(this, "city", R.string.city));
        wc.addView(we); wc.addView(tCond);
        View sep = new View(this); sep.setBackgroundColor(0x40FFFFFF);
        w.addView(tTemp); LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams((int) dp(1), (int) dp(compact ? 24 : 30)); sp.setMargins((int) dp(compact ? 10 : 14), 0, (int) dp(compact ? 10 : 14), 0);
        w.addView(sep, sp); w.addView(wc);
        FrameLayout.LayoutParams wl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        wl.setMargins(side, topM, 0, 0);
        o.addView(w, wl);

        // top right: day + date/time
        LinearLayout dt = new LinearLayout(this); dt.setOrientation(LinearLayout.VERTICAL); dt.setGravity(Gravity.END);
        tDay = Ui.text(this, Ui.BODY, 700, Ui.TEXT); tTime = Ui.text(this, Ui.CAPTION, 500, Ui.TEXT_2);
        dt.addView(tDay); dt.addView(tTime);
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        dl.setMargins(0, topM, side, 0);
        o.addView(dt, dl);

        // bottom band
        LinearLayout band = new LinearLayout(this); band.setOrientation(LinearLayout.VERTICAL);
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        main.setGravity(compact ? Gravity.START : Gravity.BOTTOM);
        LinearLayout headRow = new LinearLayout(this); headRow.setGravity(Gravity.BOTTOM);
        FrameLayout tile = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable tg = new android.graphics.drawable.GradientDrawable(); tg.setColor(0xF5FFFFFF); tg.setCornerRadius(dp(compact ? 14 : 18));
        tile.setBackground(tg); Ui.clip(tile, dp(compact ? 14 : 18));
        logo = new android.widget.ImageView(this); logo.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        logo.setPadding((int) dp(compact ? 10 : 14), (int) dp(compact ? 10 : 14), (int) dp(compact ? 10 : 14), (int) dp(compact ? 10 : 14));
        logoText = Ui.text(this, compact ? Ui.BODY : Ui.HEADLINE, 800, 0xFF111114); logoText.setShadowLayer(0, 0, 0, 0); logoText.setGravity(Gravity.CENTER);
        tile.addView(logoText, new FrameLayout.LayoutParams(-1, -1)); tile.addView(logo, new FrameLayout.LayoutParams(-1, -1));
        headRow.addView(tile, new LinearLayout.LayoutParams((int) dp(compact ? 96 : 150), (int) dp(compact ? 64 : 100)));

        LinearLayout id = new LinearLayout(this); id.setOrientation(LinearLayout.VERTICAL);
        boolean simple = TvViewer.simpleMode(this);
        bNum = Ui.text(this, compact ? 28 : (simple ? 52 : 44), 700, Ui.TEXT); bNum.setIncludeFontPadding(false);
        bName = Ui.text(this, compact ? Ui.HEADLINE : (simple ? 32 : Ui.HEADLINE), 800, Ui.TEXT); bName.setLetterSpacing(0.02f);
        bMeta = Ui.text(this, simple ? 16 : Ui.CAPTION, 500, Ui.TEXT_2);
        bStatus = Ui.text(this, Ui.BODY, 600, 0xFFFF8A80); bStatus.setVisibility(View.GONE);
        id.addView(bNum); id.addView(bName); id.addView(bMeta); id.addView(bStatus);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(0, -2, 1); il.leftMargin = (int) dp(compact ? 14 : 20);
        headRow.addView(id, il);
        main.addView(headRow, compact
                ? new LinearLayout.LayoutParams(-1, -2)
                : new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout right = new LinearLayout(this); right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout chips = new LinearLayout(this);
        bLive = pill("● LIVE", Ui.LIVE, 0xFFFFFFFF);
        chips.addView(bLive);
        bCat = pill("", 0x33FFFFFF, Ui.TEXT);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2); cl.leftMargin = (int) dp(8);
        chips.addView(bCat, cl);
        right.addView(chips);
        bNowTime = Ui.text(this, simple ? 19 : Ui.BODY, 600, Ui.TEXT); bNow = Ui.text(this, simple ? 19 : Ui.BODY, 700, Ui.TEXT);
        bNextTime = Ui.text(this, simple ? 18 : Ui.BODY, 500, Ui.TEXT_2); bNext = Ui.text(this, simple ? 18 : Ui.BODY, 500, Ui.TEXT_2);
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
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(compact ? -1 : (int) dp(500), -2);
        if (compact) rightLp.topMargin = (int) dp(12);
        main.addView(right, rightLp);
        band.addView(main);

        // remote colour-key legend + touch action pills
        LinearLayout legend = new LinearLayout(this); legend.setGravity(Gravity.CENTER_VERTICAL | (compact ? Gravity.START : Gravity.END));
        String[][] keys = {
                {"▲▼", I18n.get(lang, "tv.live_key_switch")},
                {"OK", I18n.get(lang, "tv.live_key_guide")},
                {"C", I18n.get(lang, "tv.live_sub_lbl")},
                {"A", I18n.get(lang, "tv.live_audio_lbl")},
                {"ⓘ", I18n.get(lang, "stats.btn_info")},
                {"⏪⏩", I18n.get(lang, "tv.live_key_seek")},
                {"▶", I18n.get(lang, "tv.live_key_info")},
                {"↩", I18n.get(lang, "tv.live_key_exit")}
        };
        int[] colors = {Ui.ACCENT, Ui.GOOD, Ui.ACCENT, Ui.TEXT_2, Ui.GOOD, Ui.SUN, Ui.TEXT_3, Ui.TEXT_2};
        for (int k = 0; k < keys.length; k++) {
            final int keyIdx = k;
            LinearLayout pillBtn = new LinearLayout(this); pillBtn.setGravity(Gravity.CENTER_VERTICAL);
            pillBtn.setPadding((int) dp(k == 0 ? 4 : 12), (int) dp(6), (int) dp(8), (int) dp(6));
            pillBtn.setClickable(true);
            pillBtn.setFocusable(false); pillBtn.setFocusableInTouchMode(false);
            pillBtn.setOnClickListener(v -> {
                switch (keyIdx) {
                    case 0: tune(index + 1); break;
                    case 1: openGuide(); break;
                    case 2: cycleTrack(C.TRACK_TYPE_TEXT); break;
                    case 3: cycleTrack(C.TRACK_TYPE_AUDIO); break;
                    case 4: toggleTechPanel(); break;
                    case 5: seekRel(-30_000); break;
                    case 6: showBanner(); break;
                    case 7: finish(); break;
                }
            });
            View dot = new View(this); android.graphics.drawable.GradientDrawable dg = new android.graphics.drawable.GradientDrawable();
            dg.setColor(colors[k]); dg.setCornerRadius(dp(2)); dot.setBackground(dg);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams((int) dp(8), (int) dp(8)); dlp.rightMargin = (int) dp(6);
            pillBtn.addView(dot, dlp);
            TextView lt = Ui.text(this, Ui.CAPTION, 500, Ui.TEXT_2); lt.setText(keys[k][0] + "  " + keys[k][1]);
            pillBtn.addView(lt);
            legend.addView(pillBtn);
        }
        android.widget.HorizontalScrollView legendScroll = new android.widget.HorizontalScrollView(this);
        legendScroll.setHorizontalScrollBarEnabled(false);
        legendScroll.setFillViewport(true);
        legendScroll.setFocusable(false);
        legendScroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        legendScroll.addView(legend, new FrameLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2); ll.topMargin = (int) dp(14);
        band.addView(legendScroll, ll);

        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bl.setMargins(side, 0, side, topM);
        o.addView(band, bl);

        // weather once, in the background (not when the onboarding left the city empty)
        if (TvViewer.hasCity(this)) Net.bg.execute(() -> {
            try {
                double lat = Double.parseDouble(Cfg.s(this, "latitude", R.string.latitude)), lon = Double.parseDouble(Cfg.s(this, "longitude", R.string.longitude));
                tech.kousozo.tahoetv.core.Web.Weather wx = tech.kousozo.tahoetv.core.Web.INSTANCE.weather(lat, lon, TvViewer.get(this).getLanguage());
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
    static final int ROW_WINDOW = 60, ROW_STEP = 40;
    boolean guideDirty = true;
    int renderedEnd = 0;

    /** Guide: ★ Favorit, Terakhir ditonton, then a windowed slice of every channel (expanded on scroll/focus). */
    void buildRows() {
        rows.removeAllViews();
        guideDirty = false;
        Instant now = Instant.now();
        java.util.Set<String> fav = favs();
        java.util.Map<String, Integer> at = new java.util.HashMap<>();
        for (int k = 0; k < list.size(); k++) at.put(list.get(k).getChannel().getUrl(), k);
        List<Integer> favRows = new ArrayList<>(), recentRows = new ArrayList<>();
        for (int k = 0; k < list.size(); k++) if (fav.contains(list.get(k).getChannel().getUrl())) favRows.add(k);
        for (String u : recent()) { Integer k = at.get(u); if (k != null && !fav.contains(u) && recentRows.size() < 5) recentRows.add(k); }
        String lang = TvViewer.get(this).getLanguage();
        if (!favRows.isEmpty()) { section(I18n.get(lang, "tv.live_fav_section")); for (int k : favRows) rows.addView(row(k, now, fav)); }
        if (!recentRows.isEmpty()) { section(I18n.get(lang, "tv.live_recent_section")); for (int k : recentRows) rows.addView(row(k, now, fav)); }
        if (!favRows.isEmpty() || !recentRows.isEmpty()) section(I18n.get(lang, "home.all_channels"));
        allRows = new View[list.size()];
        renderedEnd = 0;
        ensureRowsThrough(Math.max(ROW_WINDOW, index + 25), now, fav);
    }

    void ensureRowsThrough(int target) { ensureRowsThrough(target, Instant.now(), favs()); }

    void ensureRowsThrough(int target, Instant now, java.util.Set<String> fav) {
        int end = Math.min(list.size(), target);
        while (renderedEnd < end) {
            int k = renderedEnd++;
            allRows[k] = row(k, now, fav);
            rows.addView(allRows[k]);
        }
    }

    void refreshRow(int k) {
        guideDirty = true;
        if (guide.getVisibility() != View.VISIBLE || k < 0 || k >= allRows.length) return;
        ensureRowsThrough(k + 1);
        View old = allRows[k];
        int pos = old == null ? -1 : rows.indexOfChild(old);
        if (pos >= 0) {
            View fresh = row(k, Instant.now(), favs());
            allRows[k] = fresh;
            rows.removeViewAt(pos);
            rows.addView(fresh, pos);
            fresh.requestFocus();
            return;
        }
        buildRows();
        if (k < allRows.length && allRows[k] != null) allRows[k].requestFocus();
    }

    void section(String title) {
        TextView t = Ui.eyebrow(this, Ui.TEXT_3); t.setText(title);
        t.setPadding((int) dp(10), (int) dp(14), 0, (int) dp(4));
        rows.addView(t);
    }

    View row(int k, Instant now, java.util.Set<String> fav) {
        String lang = TvViewer.get(this).getLanguage();
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
        TextView num = Ui.text(this, TvViewer.simpleMode(this) ? 16 : Ui.CAPTION, 600, Ui.TEXT_3); num.setText(String.format(java.util.Locale.ROOT, "%03d", k + 1));
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
        TextView name = Ui.text(this, TvViewer.simpleMode(this) ? 20 : Ui.BODY, 600, Ui.TEXT); name.setText(e.getName()); name.setShadowLayer(0, 0, 0, 0);
        List<Epg.Programme> s = epg == null ? null : epg.schedule(e.getName(), now, e.getChannel().getTvgId());
        Epg.Programme p = s != null && !s.isEmpty() && !s.get(0).getStart().isAfter(now) ? s.get(0) : null;
        Epg.Programme nx = null;
        if (s != null) for (Epg.Programme q : s) if (q.getStart().isAfter(now)) { nx = q; break; }
        boolean reminded = nx != null && Reminder.isSet(this, e.getChannel().getUrl(), nx.getStart().toEpochMilli());
        TextView sub = Ui.text(this, TvViewer.simpleMode(this) ? 16 : Ui.CAPTION, 500, reminded ? Ui.ACCENT : Ui.TEXT_3); sub.setShadowLayer(0, 0, 0, 0);
        sub.setText(reminded ? "🔔 " + hm().format(nx.getStart()) + "  " + nx.getTitle()
                : p != null ? p.getTitle() : OnboardingText.INSTANCE.category(lang, e.getCategory()));
        col.addView(name); col.addView(sub);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1); cp.leftMargin = (int) dp(12);
        r.addView(col, cp);
        if (fav.contains(e.getChannel().getUrl())) { TextView star = Ui.text(this, Ui.BODY, 700, Ui.SUN); star.setText("★"); r.addView(star); }
        r.setContentDescription((k + 1) + ". " + e.getName() + ", " + sub.getText());
        r.setOnFocusChangeListener((v, f) -> {
            if (f && k + 20 >= renderedEnd && renderedEnd < list.size()) ensureRowsThrough(renderedEnd + ROW_STEP);
            v.setBackground(f ? new GlassDrawable(dp(Ui.R_SMALL), Ui.GLASS_FOCUS, dp(1)) : null);
            v.animate().scaleX(f ? 1.03f : 1f).scaleY(f ? 1.03f : 1f).setDuration(Ui.FAST).start();
        });
        r.setOnClickListener(v -> { closeGuide(); tune(k); });
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
        String lang = TvViewer.get(this).getLanguage();
        LocalTv.Entry e = list.get(k);
        if (next == null) { Toast.makeText(this, I18n.get(lang, "tv.live_no_next", e.getName()), Toast.LENGTH_SHORT).show(); return; }
        boolean on = Reminder.toggle(this, e.getChannel().getUrl(), e.getName(), next.getTitle(), next.getStart().toEpochMilli());
        Toast.makeText(this, I18n.get(lang, on ? "tv.live_reminded" : "tv.live_remind_removed", next.getTitle()), Toast.LENGTH_SHORT).show();
        refreshRow(k);
    }

    void toggleFav(int k) {
        String lang = TvViewer.get(this).getLanguage();
        String u = list.get(k).getChannel().getUrl();
        java.util.Set<String> f = favs();
        boolean add = f.add(u); if (!add) f.remove(u);
        prefs.edit().putStringSet("fav", f).apply();
        TvViewer.favoritesChanged(this, f);
        Toast.makeText(this, I18n.get(lang, add ? "tv.live_fav_added" : "tv.live_fav_removed", list.get(k).getName()), Toast.LENGTH_SHORT).show();
        refreshRow(k);
    }

    void openGuide() {
        pendingCenterKey = false;
        guide.setVisibility(View.VISIBLE);
        if (guideDirty || allRows.length != list.size()) buildRows();
        ensureRowsThrough(Math.max(ROW_WINDOW, index + 25));
        if (index >= 0 && index < allRows.length && allRows[index] != null) allRows[index].requestFocus();
    }

    void closeGuide() {
        pendingCenterKey = false;
        guide.setVisibility(View.GONE);
        if (surfaceRoot != null) surfaceRoot.clearFocus();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int k = Home.gamepadToRemote(e.getKeyCode());
        if (k != e.getKeyCode()) e = new KeyEvent(e.getDownTime(), e.getEventTime(), e.getAction(), k, e.getRepeatCount(), e.getMetaState(), e.getDeviceId(), e.getScanCode(), e.getFlags(), e.getSource());
        return super.dispatchKeyEvent(e);
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        boolean open = guide.getVisibility() == View.VISIBLE;
        if (techVisible) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_INFO || code == KeyEvent.KEYCODE_I) { toggleTechPanel(); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                techFocusBtn = techFocusBtn == 0 ? 1 : 0;
                updateTechButtons();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                if (e.getRepeatCount() == 0) { if (techFocusBtn == 0) copyTechStats(); else logTechStats(); }
                return true;
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK) {
            if (typed > 0) {
                ui.removeCallbacks(tuneTyped);
                typed = 0;
                if (numOverlay != null) numOverlay.setVisibility(View.GONE);
                return true;
            }
            if (open) closeGuide(); else finish(); return true;
        }
        if (open) return super.onKeyDown(code, e);
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) { digit(code - KeyEvent.KEYCODE_0); return true; }
        if (code == KeyEvent.KEYCODE_DEL) {
            if (typed > 0) {
                ui.removeCallbacks(tuneTyped);
                typed /= 10;
                if (typed == 0) {
                    if (numOverlay != null) numOverlay.setVisibility(View.GONE);
                } else {
                    String txt = String.format(java.util.Locale.ROOT, "%03d", typed);
                    bNum.setText(txt);
                    if (numOverlay != null) { numOverlay.setText(txt); numOverlay.setContentDescription(I18n.get(TvViewer.get(this).getLanguage(), "tv.live_channel_num", String.valueOf(typed))); }
                    ui.postDelayed(tuneTyped, 1500);
                }
            } else {
                back();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_LAST_CHANNEL) { back(); return true; }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: tune(index - 1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: tune(index + 1); return true;
            case KeyEvent.KEYCODE_CHANNEL_UP: tune(index + 1); return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN: tune(index - 1); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                if (typed > 0) { ui.removeCallbacks(tuneTyped); tuneTyped.run(); return true; }
                if (e.getRepeatCount() == 0) { pendingCenterKey = true; e.startTracking(); }
                return true; // tap = list, hold = "not live"
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_MENU: openGuide(); return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (player != null) { boolean pause = code == KeyEvent.KEYCODE_MEDIA_PAUSE || (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && player.getPlayWhenReady());
                    if (pause) player.pause(); else { player.play(); if (code == KeyEvent.KEYCODE_MEDIA_PLAY) player.seekToDefaultPosition(); } showBanner(); }
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND: seekRel(-30_000); return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: seekRel(30_000); return true;
            case KeyEvent.KEYCODE_PROG_RED: flagRecording(); return true;
            case KeyEvent.KEYCODE_CAPTIONS: case KeyEvent.KEYCODE_C: cycleTrack(C.TRACK_TYPE_TEXT); return true;
            case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK: case KeyEvent.KEYCODE_A: cycleTrack(C.TRACK_TYPE_AUDIO); return true;
            case KeyEvent.KEYCODE_INFO: case KeyEvent.KEYCODE_I: toggleTechPanel(); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: showBanner(); return true;
        }
        return super.onKeyDown(code, e);
    }

    /** Cycles through available subtitle (CC) or audio tracks on the active ExoPlayer stream. */
    void cycleTrack(int trackType) {
        if (player == null) return;
        Tracks tracks = player.getCurrentTracks();
        List<TrackGroup> groups = new ArrayList<>();
        List<Integer> trackIndices = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        int selectedIdx = -1;
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != trackType) continue;
            TrackGroup tg = g.getMediaTrackGroup();
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                Format fmt = g.getTrackFormat(i);
                String lang = fmt.language != null ? fmt.language : "track";
                String name = fmt.label != null ? fmt.label : lang.toUpperCase(Locale.ROOT) + (groups.size() + 1);
                if (g.isTrackSelected(i)) selectedIdx = groups.size();
                groups.add(tg);
                trackIndices.add(i);
                labels.add(name);
            }
        }
        String langTag = TvViewer.get(this).getLanguage();
        String kindName = I18n.get(langTag, trackType == C.TRACK_TYPE_TEXT ? "tv.live_sub_lbl" : "tv.live_audio_lbl");
        if (groups.isEmpty()) {
            Toast.makeText(this, I18n.get(langTag, "tv.live_track_unavail", kindName), Toast.LENGTH_SHORT).show();
            return;
        }
        androidx.media3.common.TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon();
        b.clearOverridesOfType(trackType);
        String chKey = (index >= 0 && index < list.size())
                ? tech.kousozo.tahoetv.core.PlayPrefs.channelTrackKey(list.get(index).getChannel().getChannelId(), list.get(index).getName())
                : null;
        if (trackType == C.TRACK_TYPE_TEXT && selectedIdx == groups.size() - 1) {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
            player.setTrackSelectionParameters(b.build());
            if (chKey != null) prefs.edit().putString("tr_s_" + chKey, "off").apply();
            Toast.makeText(this, I18n.get(langTag, "tv.live_sub_off"), Toast.LENGTH_SHORT).show();
            return;
        }
        int nextIdx = (selectedIdx + 1) % groups.size();
        b.setTrackTypeDisabled(trackType, false);
        b.addOverride(new TrackSelectionOverride(groups.get(nextIdx), trackIndices.get(nextIdx)));
        player.setTrackSelectionParameters(b.build());
        Format chosenFmt = groups.get(nextIdx).getFormat(trackIndices.get(nextIdx));
        if (chKey != null && chosenFmt.language != null && !chosenFmt.language.isEmpty()) {
            prefs.edit().putString((trackType == C.TRACK_TYPE_TEXT ? "tr_s_" : "tr_a_") + chKey, chosenFmt.language).apply();
        }
        String msg = kindName + ": " + labels.get(nextIdx);
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        if (osd != null) osd.announceForAccessibility(msg);
    }

    // ------------------------------------------------------------------ technical info overlay (#61)

    final Runnable techRefreshTick = new Runnable() {
        @Override public void run() {
            if (!techVisible) return;
            refreshTechPanel();
            ui.postDelayed(this, 1000);
        }
    };

    FrameLayout buildTechPanel() {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setBackgroundColor(0x66000000);
        wrap.setClickable(true);
        wrap.setOnClickListener(v -> toggleTechPanel());

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int) dp(20), (int) dp(18), (int) dp(20), (int) dp(18));
        card.setBackground(new GlassDrawable(dp(22), Ui.GLASS_STRONG, dp(1)));
        card.setClickable(true);

        String lang = TvViewer.get(this).getLanguage();
        TextView title = Ui.text(this, Ui.HEADLINE, 700, Ui.TEXT);
        title.setText(I18n.get(lang, "stats.title"));
        card.addView(title);

        techRows = new LinearLayout(this);
        techRows.setOrientation(LinearLayout.VERTICAL);
        techRows.setPadding(0, (int) dp(10), 0, (int) dp(12));
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(techRows);
        card.addView(sv, new LinearLayout.LayoutParams(-1, (int) dp(260)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        techCopyBtn = Ui.text(this, Ui.CAPTION, 700, 0xFF111114);
        techCopyBtn.setText(I18n.get(lang, "stats.copy"));
        techCopyBtn.setGravity(Gravity.CENTER);
        techCopyBtn.setPadding((int) dp(14), (int) dp(9), (int) dp(14), (int) dp(9));
        techCopyBtn.setClickable(true);
        techCopyBtn.setOnClickListener(v -> copyTechStats());

        techLogBtn = Ui.text(this, Ui.CAPTION, 700, Ui.TEXT);
        techLogBtn.setText(I18n.get(lang, "stats.send_log"));
        techLogBtn.setGravity(Gravity.CENTER);
        techLogBtn.setPadding((int) dp(14), (int) dp(9), (int) dp(14), (int) dp(9));
        techLogBtn.setClickable(true);
        techLogBtn.setOnClickListener(v -> logTechStats());

        LinearLayout.LayoutParams bp1 = new LinearLayout.LayoutParams(0, -2, 1);
        bp1.rightMargin = (int) dp(8);
        LinearLayout.LayoutParams bp2 = new LinearLayout.LayoutParams(0, -2, 1);
        actions.addView(techCopyBtn, bp1);
        actions.addView(techLogBtn, bp2);
        card.addView(actions);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) Math.min(dp(440), getResources().getDisplayMetrics().widthPixels * 0.9f), -2, Gravity.TOP | Gravity.END);
        lp.setMargins((int) dp(20), (int) dp(24), (int) dp(24), (int) dp(24));
        wrap.addView(card, lp);
        updateTechButtons();
        return wrap;
    }

    void updateTechButtons() {
        if (techCopyBtn == null || techLogBtn == null) return;
        techCopyBtn.setBackground(new GlassDrawable(dp(12), techFocusBtn == 0 ? 0xF2F5F5F7 : 0x26FFFFFF, dp(1)));
        techCopyBtn.setTextColor(techFocusBtn == 0 ? 0xFF111114 : Ui.TEXT);
        techLogBtn.setBackground(new GlassDrawable(dp(12), techFocusBtn == 1 ? 0xF2F5F5F7 : 0x26FFFFFF, dp(1)));
        techLogBtn.setTextColor(techFocusBtn == 1 ? 0xFF111114 : Ui.TEXT);
    }

    void toggleTechPanel() {
        techVisible = !techVisible;
        techPanel.setVisibility(techVisible ? View.VISIBLE : View.GONE);
        ui.removeCallbacks(techRefreshTick);
        if (techVisible) {
            techFocusBtn = 0;
            updateTechButtons();
            refreshTechPanel();
            ui.postDelayed(techRefreshTick, 1000);
        }
    }

    tech.kousozo.tahoetv.core.PlayerStats.Snapshot buildStatsSnapshot() {
        LocalTv.Entry e = (index >= 0 && index < list.size()) ? list.get(index) : null;
        M3u.Channel cur = (source >= 0 && source < order.size()) ? order.get(source) : (e != null ? e.getChannel() : null);
        Format vf = player != null ? player.getVideoFormat() : null;
        Format af = player != null ? player.getAudioFormat() : null;
        double bufSec = player != null ? Math.max(0.0, player.getBufferedPosition() - player.getCurrentPosition()) / 1000.0 : 0.0;
        long offMs = player != null ? player.getCurrentLiveOffset() : C.TIME_UNSET;
        double offSec = offMs == C.TIME_UNSET ? Double.NaN : Math.max(0.0, offMs) / 1000.0;
        long liveRebuf = bufferingSince > 0 ? rebufferMs + (System.currentTimeMillis() - bufferingSince) : rebufferMs;
        int br = vf != null && vf.bitrate > 0 ? vf.bitrate / 1000 : 0;
        float fps = vf != null && vf.frameRate > 0 ? vf.frameRate : 0f;
        String dec = !statsVideoDecoder.isEmpty() ? statsVideoDecoder : statsAudioDecoder;
        return new tech.kousozo.tahoetv.core.PlayerStats.Snapshot(
                e != null ? e.getName() : "Live TV",
                source + 1,
                Math.max(1, order.size()),
                cur != null ? cur.getUrl() : "",
                liveLabel(),
                videoW > 0 ? videoW : (vf != null ? Math.max(0, vf.width) : 0),
                videoH > 0 ? videoH : (vf != null ? Math.max(0, vf.height) : 0),
                fps,
                vf != null && vf.sampleMimeType != null ? vf.sampleMimeType : "",
                af != null && af.sampleMimeType != null ? af.sampleMimeType : "",
                dec,
                br,
                bufSec,
                offSec,
                rebufferCount,
                liveRebuf,
                droppedFramesCount,
                bandwidthKbps
        );
    }

    void refreshTechPanel() {
        if (techRows == null) return;
        String lang = TvViewer.get(this).getLanguage();
        techRows.removeAllViews();
        for (kotlin.Pair<String, String> row : tech.kousozo.tahoetv.core.PlayerStats.rows(buildStatsSnapshot(), lang)) {
            LinearLayout r = new LinearLayout(this);
            r.setPadding(0, (int) dp(3), 0, (int) dp(3));
            TextView k = Ui.text(this, Ui.CAPTION, 500, Ui.TEXT_3);
            k.setText(row.getFirst());
            TextView v = Ui.text(this, Ui.CAPTION, 600, Ui.TEXT);
            v.setText(row.getSecond());
            v.setGravity(Gravity.END);
            v.setSingleLine(false);
            r.addView(k, new LinearLayout.LayoutParams(0, -2, 0.42f));
            r.addView(v, new LinearLayout.LayoutParams(0, -2, 0.58f));
            techRows.addView(r);
        }
    }

    void copyTechStats() {
        String lang = TvViewer.get(this).getLanguage();
        String text = tech.kousozo.tahoetv.core.PlayerStats.formatText(buildStatsSnapshot(), lang);
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("Tahoe TV Stats", text));
        Toast.makeText(this, I18n.get(lang, "stats.copied"), Toast.LENGTH_SHORT).show();
    }

    void logTechStats() {
        String lang = TvViewer.get(this).getLanguage();
        String text = tech.kousozo.tahoetv.core.PlayerStats.formatText(buildStatsSnapshot(), lang);
        tech.kousozo.tahoetv.core.DiagLog.add("stats", text.replace("\n", " | "));
        Toast.makeText(this, I18n.get(lang, "stats.log_saved"), Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ slate (#76)

    FrameLayout slate; TextView slName, slStatus; LinearLayout slMessages, slPills;
    int slateRetries, slateGen, slateLeft;
    tech.kousozo.tahoetv.core.Slate.Reason lastSlateReason = tech.kousozo.tahoetv.core.Slate.Reason.Offline;

    /** The Tahoe slate: the apology in Indonesian, English and Chinese (the viewer's language first), the reason, and
     *  what to press. Big and calm, readable from the sofa. */
    FrameLayout buildSlate() {
        boolean compact = getResources().getConfiguration().screenWidthDp < 600;
        FrameLayout f = new FrameLayout(this);
        f.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{tech.kousozo.tahoetv.core.Brand.LAKE_DEEP, tech.kousozo.tahoetv.core.Brand.NIGHT, tech.kousozo.tahoetv.core.Brand.CANVAS}));
        LinearLayout col = new LinearLayout(this); col.setOrientation(LinearLayout.VERTICAL); col.setGravity(Gravity.CENTER_HORIZONTAL);
        int slateSide = (int) dp(compact ? 24 : Ui.SAFE_SIDE * 3);
        col.setPadding(slateSide, (int) dp(24), slateSide, (int) dp(24));
        TextView brand = Ui.eyebrow(this, Ui.TEXT_3); brand.setText("Tahoe TV"); col.addView(brand);
        slName = Ui.text(this, compact ? Ui.HEADLINE : Ui.TITLE, 700, Ui.TEXT); slName.setSingleLine(false); slName.setGravity(Gravity.CENTER); slName.setPadding(0, (int) dp(8), 0, (int) dp(14)); col.addView(slName);
        slMessages = new LinearLayout(this); slMessages.setOrientation(LinearLayout.VERTICAL); slMessages.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(slMessages);
        slStatus = Ui.text(this, Ui.BODY, 400, Ui.TEXT_3); slStatus.setGravity(Gravity.CENTER); slStatus.setSingleLine(false);
        slStatus.setPadding(0, (int) dp(16), 0, (int) dp(20)); col.addView(slStatus);

        slPills = new LinearLayout(this);
        slPills.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        slPills.setGravity(Gravity.CENTER);
        col.addView(slPills);

        f.addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        f.setVisibility(View.GONE);
        return f;
    }

    View pill(String glyph, String label, Runnable onTap) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(Ui.R_MED));
        bg.setColor(0x22FFFFFF);
        p.setBackground(bg);
        p.setPadding((int) dp(14), (int) dp(8), (int) dp(16), (int) dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins((int) dp(6), (int) dp(4), (int) dp(6), (int) dp(4));
        p.setLayoutParams(lp);
        if (onTap != null) {
            p.setClickable(true);
            p.setFocusable(false); p.setFocusableInTouchMode(false);
            p.setOnClickListener(v -> onTap.run());
        }

        TextView g = Ui.text(this, Ui.BODY, 700, Ui.ACCENT);
        g.setText(glyph);
        g.setPadding(0, 0, (int) dp(8), 0);
        p.addView(g);

        TextView t = Ui.text(this, Ui.BODY, 600, Ui.TEXT);
        t.setText(label);
        p.addView(t);
        return p;
    }

    void showSlate() {
        showSlate(lastSlateReason);
    }

    void showSlate(tech.kousozo.tahoetv.core.Slate.Reason reason) {
        lastSlateReason = reason;
        dismissBanner();
        bStatus.setVisibility(View.GONE);
        loading.setVisibility(View.GONE);

        String lang = TvViewer.get(this).getLanguage();
        if (index >= 0 && index < list.size()) slName.setText(list.get(index).getName());
        slMessages.removeAllViews();
        java.util.List<String> order = tech.kousozo.tahoetv.core.Slate.order(lang);

        TextView primary = Ui.text(this, Ui.HEADLINE, 600, Ui.TEXT);
        primary.setSingleLine(false); primary.setGravity(Gravity.CENTER);
        primary.setText(tech.kousozo.tahoetv.core.Slate.message(order.get(0)));
        slMessages.addView(primary);

        java.util.List<String> others = new ArrayList<>();
        for (int k = 1; k < order.size(); k++) others.add(tech.kousozo.tahoetv.core.Slate.message(order.get(k)));
        TextView sub = Ui.text(this, Ui.BODY, 400, Ui.TEXT_3);
        sub.setSingleLine(false); sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, (int) dp(8), 0, 0);
        sub.setText(android.text.TextUtils.join("\n", others));
        slMessages.addView(sub);

        if (slPills != null) {
            slPills.removeAllViews();
            slPills.addView(pill("OK", tech.kousozo.tahoetv.core.Slate.label(lang, "retry"), this::retryChannel));
            slPills.addView(pill("▼", tech.kousozo.tahoetv.core.Slate.label(lang, "next"), () -> tune(index + 1)));
        }

        slate.setVisibility(View.VISIBLE);
        int my = ++slateGen;
        if (slateRetries >= tech.kousozo.tahoetv.core.Slate.MAX_RETRIES) { slateLeft = 0; slateStatus(lang, reason); return; }
        slateLeft = tech.kousozo.tahoetv.core.Slate.RETRY_SECONDS;
        slateStatus(lang, reason);
        ui.postDelayed(new Runnable() { @Override public void run() {
            if (my != slateGen || slate.getVisibility() != View.VISIBLE) return;
            if (--slateLeft <= 0) { slateRetries++; retryChannel(); } else { ui.postDelayed(this, 1000); }
        }}, 1000);
    }

    void slateStatus(String lang, tech.kousozo.tahoetv.core.Slate.Reason reason) {
        String when = slateRetries < tech.kousozo.tahoetv.core.Slate.MAX_RETRIES
                ? tech.kousozo.tahoetv.core.Slate.label(lang, "trying")
                : tech.kousozo.tahoetv.core.Slate.label(lang, "waiting");
        slStatus.setText(tech.kousozo.tahoetv.core.Slate.reason(tech.kousozo.tahoetv.core.Slate.order(lang).get(0), reason)
                + "  ·  " + when);
    }

    void hideSlate() { slateGen++; if (slate != null) slate.setVisibility(View.GONE); bStatus.setVisibility(View.GONE); }

    /** Every source of this channel again, best first (OK on the slate, or the background retry). */
    void retryChannel() {
        if (index < 0 || list.isEmpty()) return;
        sawRecording = false;
        hideSlate();
        LocalTv.Entry e = list.get(index);
        List<M3u.Channel> raw = new ArrayList<>(); raw.add(e.getChannel()); raw.addAll(e.getAlternates());
        bStatus.setVisibility(View.GONE);
        final int seq = ++tuneSeq;
        final long zapGen = tech.kousozo.tahoetv.core.StreamSelector.newZapGeneration();
        if (raw.size() <= 1) {
            order = SourceHealth.order(raw);
            source = 0;
            play();
        } else {
            new Thread(() -> {
                List<M3u.Channel> ranked = tech.kousozo.tahoetv.core.StreamSelector.rankOnOpen(raw, 2500, zapGen);
                runOnUiThread(() -> {
                    if (seq == tuneSeq && !isFinishing()) {
                        order = ranked;
                        source = 0;
                        play();
                    }
                });
            }, "tahoe-rank").start();
        }
    }

    @Override public boolean onKeyLongPress(int code, KeyEvent e) {
        if (guide.getVisibility() != View.VISIBLE && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) { pendingCenterKey = false; flagRecording(); return true; }
        return super.onKeyLongPress(code, e);
    }

    @Override public boolean onKeyUp(int code, KeyEvent e) {
        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) && guide.getVisibility() != View.VISIBLE) {
            boolean fire = !e.isCanceled() && (pendingCenterKey || e.isTracking() || e.getDownTime() == e.getEventTime());
            pendingCenterKey = false;
            if (fire) {
                if (slate.getVisibility() == View.VISIBLE) retryChannel(); else openGuide(); // on the slate, OK = try again
            }
            return true;
        }
        return super.onKeyUp(code, e);
    }

    @Override protected void onStop() { super.onStop(); if (live == this) live = null; if (player != null) player.pause(); if (Home.live != null) Home.live.pushState(); }
    @Override protected void onStart() { super.onStart(); live = this; if (player != null && index >= 0) player.play(); if (Home.live != null) Home.live.pushState(); }
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
