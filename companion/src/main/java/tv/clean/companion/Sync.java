package tv.clean.companion;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.net.URI;
import java.util.List;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;

/** Runs while notification access is granted: keeps a WebSocket to the TV, forwards notifications and the
 *  playing media session, and applies play/pause/next sent back from the TV's Control Center. */
public class Sync extends NotificationListenerService {
    static Sync inst;
    final Handler ui = new Handler(Looper.getMainLooper());
    WebSocketClient ws;
    MediaController media;
    int retry;
    String status = "Belum tersambung";

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("tv", MODE_PRIVATE); }

    @Override public void onListenerConnected() {
        inst = this;
        connect();
        MediaSessionManager msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        ComponentName me = new ComponentName(this, Sync.class);
        msm.addOnActiveSessionsChangedListener(this::sessions, me, ui);
        sessions(msm.getActiveSessions(me));
    }

    @Override public void onListenerDisconnected() { inst = null; if (ws != null) ws.close(); }

    void connect() {
        SharedPreferences p = prefs(this);
        String host = p.getString("host", ""), token = p.getString("token", "");
        if (host.isEmpty() || token.isEmpty()) { status = "Belum dipasangkan"; MainActivity.refresh(); return; }
        if (ws != null && ws.isOpen()) return;
        try {
            ws = new WebSocketClient(new URI("ws://" + host + ":8765/ws?token=" + token)) {
                @Override public void onOpen(ServerHandshake h) { retry = 0; status = "Tersambung ke " + host; ui.post(MainActivity::refresh); ui.post(() -> sendMedia()); }
                @Override public void onMessage(String m) { ui.post(() -> command(m)); }
                @Override public void onClose(int code, String reason, boolean remote) {
                    status = "pair first".equals(reason) || "unpaired".equals(reason) ? "Dilepas dari TV, pasangkan ulang" : "Terputus, menyambung ulang…";
                    ui.post(MainActivity::refresh);
                    if (!reason.contains("pair")) ui.postDelayed(Sync.this::connect, Math.min(30_000, 1000L << Math.min(retry++, 5)));
                }
                @Override public void onError(Exception e) {}
            };
            ws.connect();
        } catch (Exception e) { status = "Alamat TV tidak valid"; }
    }

    void send(JSONObject o) { if (ws != null && ws.isOpen()) ws.send(o.toString()); }

    void command(String m) {
        try {
            JSONObject o = new JSONObject(m);
            if (!"media_cmd".equals(o.optString("t")) || media == null) return;
            MediaController.TransportControls tc = media.getTransportControls();
            switch (o.optString("c")) {
                case "play": tc.play(); break;
                case "pause": tc.pause(); break;
                case "next": tc.skipToNext(); break;
                case "prev": tc.skipToPrevious(); break;
            }
        } catch (Exception ignored) {}
    }

    // ---- notifications ----

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        if (sbn.isOngoing() || sbn.getPackageName().equals(getPackageName()) || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        if (!prefs(this).getBoolean("notif", true)) return;
        CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE), text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (title == null) return;
        try {
            String app = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(sbn.getPackageName(), 0)).toString();
            send(new JSONObject().put("t", "notif").put("app", app).put("title", title.toString()).put("text", text == null ? "" : text.toString()));
        } catch (Exception ignored) {}
    }

    // ---- now playing ----

    final MediaController.Callback cb = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { sendMedia(); }
        @Override public void onPlaybackStateChanged(PlaybackState s) { sendMedia(); }
        @Override public void onSessionDestroyed() { media = null; sendMedia(); }
    };

    void sessions(List<MediaController> list) {
        MediaController pick = null;
        if (list != null) for (MediaController c : list) {
            PlaybackState s = c.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { pick = c; break; }
            if (pick == null) pick = c;
        }
        if (media != null) media.unregisterCallback(cb);
        media = pick;
        if (media != null) media.registerCallback(cb, ui);
        sendMedia();
    }

    void sendMedia() {
        try {
            MediaMetadata m = media == null ? null : media.getMetadata();
            if (m == null || m.getText(MediaMetadata.METADATA_KEY_TITLE) == null || !prefs(this).getBoolean("media", true)) {
                send(new JSONObject().put("t", "media").put("stopped", true));
                return;
            }
            PlaybackState s = media.getPlaybackState();
            CharSequence artist = m.getText(MediaMetadata.METADATA_KEY_ARTIST);
            String app = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(media.getPackageName(), 0)).toString();
            send(new JSONObject().put("t", "media").put("title", m.getText(MediaMetadata.METADATA_KEY_TITLE).toString())
                    .put("artist", artist == null ? app : artist.toString()).put("app", app)
                    .put("playing", s != null && s.getState() == PlaybackState.STATE_PLAYING));
        } catch (Exception ignored) {}
    }
}
