package tv.clean.home;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import java.io.File;

/** Plays one local recording (.ts), or a film/series playlist (extra "urls"), full screen: OK pauses, ◀ ▶ seek 10 s, Back leaves. */
public class RecordingPlayer extends Activity {
    private ExoPlayer player;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (TvGuard.blockIfNotTv(this)) return;
        String path = getIntent().getStringExtra("path");
        String[] urls = getIntent().getStringArrayExtra("urls"); // film/series: http(s) sources, played in order
        if (urls == null && (path == null || !new File(path).exists())) { finish(); return; }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        SurfaceView surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        TextView title = new TextView(this);
        title.setText(getIntent().getStringExtra("title"));
        title.setTextColor(0xCCFFFFFF); title.setTypeface(Ui.font(this, 600));
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30 * getResources().getDisplayMetrics().widthPixels / 1920f);
        title.setShadowLayer(8, 0, 2, 0xFF000000);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tl.leftMargin = (int) (112 * getResources().getDisplayMetrics().widthPixels / 1920f); tl.topMargin = (int) (64 * getResources().getDisplayMetrics().widthPixels / 1920f);
        root.addView(title, tl);
        setContentView(root);
        player = new ExoPlayer.Builder(this).build();
        player.setVideoSurfaceView(surface);
        if (urls != null) {
            java.util.List<MediaItem> items = new java.util.ArrayList<>();
            for (String u : urls) if (u.startsWith("https://") || u.startsWith("http://")) items.add(MediaItem.fromUri(u));
            if (items.isEmpty()) { finish(); return; }
            player.setMediaItems(items);
        } else player.setMediaItem(MediaItem.fromUri(Uri.fromFile(new File(path))));
        player.prepare();
        player.play();
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (player == null) return super.onKeyDown(code, e);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                player.setPlayWhenReady(!player.getPlayWhenReady()); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_MEDIA_NEXT: if (player.hasNextMediaItem()) { player.seekToNextMediaItem(); return true; } break;
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_MEDIA_PREVIOUS: if (player.hasPreviousMediaItem()) { player.seekToPreviousMediaItem(); return true; } break;
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_MEDIA_REWIND: player.seekTo(Math.max(0, player.getCurrentPosition() - 10_000)); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: player.seekTo(player.getCurrentPosition() + 10_000); return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override protected void onStop() { super.onStop(); if (player != null) player.pause(); }
    @Override protected void onDestroy() { if (player != null) { player.release(); player = null; } super.onDestroy(); }
}
