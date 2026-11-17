package tv.clean.home;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.TextureView;
import android.view.View;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import java.util.HashMap;
import java.util.Locale;
import tech.kousozo.tahoetv.core.Brand;
import tech.kousozo.tahoetv.core.DeviceProfile;
import tech.kousozo.tahoetv.core.Drm;
import tech.kousozo.tahoetv.core.M3u;

/**
 * Live preview (#181): the channel that has focus plays quietly in a small video area, so moving through the guide
 * feels alive. Kept light for 1 GB TV sticks: one player, created on first use, capped at 480p with a short buffer,
 * started only after focus rests for {@link #DWELL_MS}, and never on LIGHT-tier devices, for DRM/DASH channels, or when
 * the viewer turned it off ("live_preview" in Control Center).
 */
final class LivePreview {
    static final long DWELL_MS = 700;
    private static final float VOLUME = 0.25f;

    private final Context c;
    private final TextureView view;
    private final View badge;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private String current;
    private Runnable pending;

    LivePreview(Context c, TextureView view, View badge) {
        this.c = c; this.view = view; this.badge = badge;
        view.setAlpha(0f); if (badge != null) badge.setAlpha(0f);
    }

    static boolean enabled(Context c) {
        return Cfg.p(c).getBoolean("live_preview", true)
                && DeviceProfiler.current(c).getActiveTier() != DeviceProfile.Tier.LIGHT;
    }

    /** Focus moved to [ch]: stop what plays now and start [ch] after the dwell, if allowed. */
    void focus(M3u.Channel ch) {
        if (pending != null) ui.removeCallbacks(pending);
        if (ch == null || !enabled(c) || ch.getDrm() || Drm.isDash(ch.getUrl(), ch.getDrmType(), ch.getManifestType())) { stop(); return; }
        if (ch.getUrl().equals(current) && player != null && player.isPlaying()) return;
        hide();
        pending = () -> start(ch);
        ui.postDelayed(pending, DWELL_MS);
    }

    private void start(M3u.Channel ch) {
        if (player == null) {
            DefaultLoadControl lc = new DefaultLoadControl.Builder().setBufferDurationsMs(1500, 4000, 800, 1200).build();
            player = new ExoPlayer.Builder(c).setLoadControl(lc).build();
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setMaxVideoSize(854, 480).build());
            player.setVideoTextureView(view);
            player.setVolume(VOLUME);
            player.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) show();
                }
                @Override public void onPlayerError(PlaybackException error) { hide(); }
            });
        }
        current = ch.getUrl();
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(6000).setReadTimeoutMs(10000)
                .setUserAgent(ch.getHeaders().containsKey("User-Agent") ? ch.getHeaders().get("User-Agent") : tech.kousozo.tahoetv.core.Web.STREAM_UA);
        HashMap<String, String> h = new HashMap<>(ch.getHeaders()); h.remove("User-Agent");
        http.setDefaultRequestProperties(h);
        MediaItem.Builder item = new MediaItem.Builder().setUri(Uri.parse(ch.getUrl()));
        if (ch.getUrl().toLowerCase(Locale.ROOT).contains(".m3u8")) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaSource(new DefaultMediaSourceFactory(http).createMediaSource(item.build()));
        player.prepare(); player.play();
    }

    private void show() {
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
        if (pending != null) ui.removeCallbacks(pending);
        pending = null; current = null; hide();
        if (player != null) player.stop();
    }

    void release() {
        stop();
        if (player != null) { player.release(); player = null; }
    }
}
