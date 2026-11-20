package tv.clean.home;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import tech.kousozo.tahoetv.core.Brand;
import tech.kousozo.tahoetv.core.DeviceProfile;
import tech.kousozo.tahoetv.core.Drm;
import tech.kousozo.tahoetv.core.M3u;
import tech.kousozo.tahoetv.core.SourceHealth;

/**
 * Live preview (#181): the focused channel plays quietly in a small video area, so moving through the guide feels alive.
 * Kept light for 1 GB TV sticks: one player, created on first use, capped at 480p with a short buffer, started after a short
 * dwell, 360p on LIGHT-tier devices, never for DRM/DASH channels, or when the viewer turned it off ("live_preview").
 *
 * It is given several sources (the channel's own and its backups, then the next channels') and walks through them when one fails or
 * is silent for 7 s, so a preview almost always appears. The picture keeps its real aspect ratio (letterboxed, never cropped or
 * stretched), and background stream probing waits while it runs.
 */
final class LivePreview {
    static final long DWELL_MS = 350;
    private static final long SOURCE_TIMEOUT_MS = 7_000;
    private static final float VOLUME = 0.25f;

    private final Context c;
    private final TextureView view;
    private final View badge;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private List<M3u.Channel> cands = Collections.emptyList();
    private int idx;
    private int gen;
    private String currentKey;
    private boolean shown;
    private Runnable pending, timeout, snapJob;

    /** A picture of [channelUrl] was just saved (UI thread): Home refreshes that card. */
    static volatile java.util.function.Consumer<String> snapListener;

    private static String sha1(String s) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte b : java.security.MessageDigest.getInstance("SHA-1").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }

    /** The saved frame of a channel (one real picture per channel, up to a day old), or null. */
    static java.io.File snapshot(Context c, String channelUrl) {
        java.io.File f = new java.io.File(new java.io.File(c.getCacheDir(), "snaps"), sha1(channelUrl) + ".jpg");
        return f.exists() && System.currentTimeMillis() - f.lastModified() < 24L * 3600_000L ? f : null;
    }

    /** Once the picture has been up for a moment, keep one frame as the channel's thumbnail (skips black frames). */
    private void scheduleSnapshot(final String key) {
        if (snapJob != null) ui.removeCallbacks(snapJob);
        snapJob = () -> {
            try {
                if (!shown || !key.equals(currentKey) || !view.isAvailable()) return;
                android.graphics.Bitmap full = view.getBitmap();
                if (full == null || full.getWidth() < 2) return;
                int w = 480, h = Math.max(2, Math.round(480f * full.getHeight() / full.getWidth()));
                android.graphics.Bitmap bm = android.graphics.Bitmap.createScaledBitmap(full, w, h, true);
                full.recycle();
                long sum = 0; int n = 0;
                for (int y = 0; y < h; y += 12) for (int x = 0; x < w; x += 12) { int p = bm.getPixel(x, y); sum += ((p >> 16) & 255) + ((p >> 8) & 255) + (p & 255); n++; }
                if (n == 0 || sum / (3.0 * n) < 18) { bm.recycle(); return; } // near black: a fade or a slate
                java.io.File dir = new java.io.File(c.getCacheDir(), "snaps"); dir.mkdirs();
                java.io.File out = new java.io.File(dir, sha1(key) + ".jpg");
                try (java.io.FileOutputStream os = new java.io.FileOutputStream(out)) { bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 72, os); }
                bm.recycle();
                java.util.function.Consumer<String> l = snapListener;
                if (l != null) l.accept(key);
            } catch (Throwable ignored) { }
        };
        ui.postDelayed(snapJob, 1500);
    }

    LivePreview(Context c, TextureView view, View badge) {
        this.c = c; this.view = view; this.badge = badge;
        view.setAlpha(0f); if (badge != null) badge.setAlpha(0f);
    }

    /** On by default everywhere (the viewer can turn it off in Control Center). Light 1 GB sticks get a smaller picture instead of none. */
    static boolean enabled(Context c) {
        return Cfg.p(c).getBoolean("live_preview", true);
    }

    private boolean light() { return DeviceProfiler.current(c).getActiveTier() == DeviceProfile.Tier.LIGHT; }

    /** Focus moved to [ch]: stop what plays now and start [ch] after the dwell, if allowed. */
    void focus(M3u.Channel ch) { focus(ch == null ? Collections.<M3u.Channel>emptyList() : Collections.singletonList(ch)); }

    /** Preview the first of [sources] that plays (best first); DRM and DASH sources are skipped. */
    void focus(List<M3u.Channel> sources) {
        if (pending != null) ui.removeCallbacks(pending);
        List<M3u.Channel> ok = new ArrayList<>();
        if (enabled(c) && sources != null) for (M3u.Channel ch : sources) {
            if (ch != null && !ch.getUrl().isEmpty() && !ch.getDrm() && !Drm.isDash(ch.getUrl(), ch.getDrmType(), ch.getManifestType())) ok.add(ch);
        }
        if (ok.isEmpty()) { stop(); return; }
        String key = ok.get(0).getUrl();
        if (key.equals(currentKey) && player != null && shown) return;
        final int my = ++gen;
        cands = ok; idx = 0; currentKey = key; shown = false;
        hide();
        SourceHealth.yieldToPlayback = true; // probes wait: the picture comes first
        pending = () -> start(my);
        ui.postDelayed(pending, DWELL_MS);
    }

    private void start(int my) {
        if (my != gen) return;
        if (idx >= cands.size()) { stop(); return; }
        M3u.Channel ch = cands.get(idx);
        if (player == null) {
            boolean light = light();
            DefaultLoadControl lc = new DefaultLoadControl.Builder().setBufferDurationsMs(light ? 1200 : 1500, light ? 3000 : 4000, 750, 1200).build();
            player = new ExoPlayer.Builder(c).setLoadControl(lc).build();
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setMaxVideoSize(light ? 640 : 854, light ? 360 : 480).setMaxVideoBitrate(light ? 1_200_000 : 2_500_000).build());
            player.setVideoTextureView(view);
            player.setVolume(VOLUME);
            player.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) show();
                }
                @Override public void onPlayerError(PlaybackException error) { next(gen); }
                @Override public void onVideoSizeChanged(VideoSize vs) { fit(vs); }
            });
        }
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(6000).setReadTimeoutMs(10000)
                .setUserAgent(ch.getHeaders().containsKey("User-Agent") ? ch.getHeaders().get("User-Agent") : tech.kousozo.tahoetv.core.Web.STREAM_UA);
        HashMap<String, String> h = new HashMap<>(ch.getHeaders()); h.remove("User-Agent");
        http.setDefaultRequestProperties(h);
        MediaItem.Builder item = new MediaItem.Builder().setUri(Uri.parse(ch.getUrl()));
        if (ch.getUrl().toLowerCase(Locale.ROOT).contains(".m3u8")) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaSource(new DefaultMediaSourceFactory(http).createMediaSource(item.build()));
        player.prepare(); player.play();
        if (timeout != null) ui.removeCallbacks(timeout);
        timeout = () -> { if (my == gen && !shown) next(my); };
        ui.postDelayed(timeout, SOURCE_TIMEOUT_MS);
    }

    /** This source failed or stayed silent: try the next one. */
    private void next(int my) {
        if (my != gen) return;
        idx++;
        start(my);
    }

    /** Keep the picture's own aspect ratio: fit it inside the area (centred), never crop or stretch. */
    private void fit(VideoSize vs) {
        if (vs == null || vs.width <= 0 || vs.height <= 0) return;
        View parent = (View) view.getParent();
        if (parent == null) return;
        float ar = vs.width * vs.pixelWidthHeightRatio / vs.height;
        Runnable apply = () -> {
            int pw = parent.getWidth(), ph = parent.getHeight();
            if (pw <= 0 || ph <= 0) return;
            int w, h;
            if ((float) pw / ph > ar) { h = ph; w = Math.round(ph * ar); } else { w = pw; h = Math.round(pw / ar); }
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp instanceof FrameLayout.LayoutParams) { ((FrameLayout.LayoutParams) lp).gravity = Gravity.CENTER; }
            lp.width = w; lp.height = h;
            view.setLayoutParams(lp);
        };
        if (parent.getWidth() > 0) apply.run(); else parent.post(apply);
    }

    private void show() {
        shown = true;
        if (currentKey != null) scheduleSnapshot(currentKey);
        if (timeout != null) { ui.removeCallbacks(timeout); timeout = null; }
        long d = (long) (Brand.MOTION_MED * Math.min(1f, Ui.motion(c)));
        view.animate().alpha(1f).setDuration(d).start();
        if (badge != null) badge.animate().alpha(1f).setDuration(d).start();
    }

    private void hide() {
        view.animate().cancel(); view.setAlpha(0f);
        if (badge != null) { badge.animate().cancel(); badge.setAlpha(0f); }
    }

    /** Stop playback (screen left, or a full-screen player is about to open). */
    void stop() {
        gen++;
        if (pending != null) ui.removeCallbacks(pending);
        if (timeout != null) ui.removeCallbacks(timeout);
        if (snapJob != null) { ui.removeCallbacks(snapJob); snapJob = null; }
        pending = null; timeout = null; currentKey = null; shown = false; hide();
        if (player != null) player.stop();
        SourceHealth.yieldToPlayback = false;
    }

    void release() {
        stop();
        if (player != null) { player.release(); player = null; }
    }
}
