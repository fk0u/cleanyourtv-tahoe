package tech.kousozo.tahoetv

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Puts the channel that is playing into the system's media controls (notification shade, lock screen, headset and car
 *  buttons, TalkBack's media actions). The player itself stays in [Playback]; this only holds the [MediaSession] around it
 *  and keeps playback alive in the background while the picture is hidden, until the viewer stops it. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        session = MediaSession.Builder(this, Playback.player(this)).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiped away from recents: nothing should keep playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Playback.stop()
        stopSelf()
    }

    override fun onDestroy() {
        session?.release() // the player is released by Playback, not here
        session = null
        super.onDestroy()
    }
}
