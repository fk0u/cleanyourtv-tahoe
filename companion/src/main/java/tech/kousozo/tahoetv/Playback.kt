package tech.kousozo.tahoetv

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.time.Instant
import tech.kousozo.tahoetv.core.DiagLog
import tech.kousozo.tahoetv.core.LocalTv
import tech.kousozo.tahoetv.core.M3u
import tech.kousozo.tahoetv.core.SourceHealth

/** The one Live TV player of the app. It outlives the TV Live screen, so a channel keeps playing while you look around
 *  (the floating mini-player shows it), like a music app. Holds the source fallback that used to live in the screen:
 *  known-live sources first; a source that never starts in 20 s, errors before playing, or turns out to be a recording
 *  moves to the next; one that already played reconnects up to 3 times first. */
object Playback {
    /** The channel playing (or loading), null when stopped. */
    var entry by mutableStateOf<LocalTv.Entry?>(null); private set
    /** "" = loading (spinner), null = playing, other text = can't play. */
    var message by mutableStateOf<String?>(null); private set
    var paused by mutableStateOf(false); private set
    /** "1080p · H.264 · AAC", once known. */
    var format by mutableStateOf<String?>(null); private set

    /** Viewer choices (Setelan pemutar), kept in the "live" prefs. */
    var quality by mutableStateOf(tech.kousozo.tahoetv.core.PlayPrefs.Quality.Auto); private set
    var latency by mutableStateOf(tech.kousozo.tahoetv.core.PlayPrefs.Latency.Standard); private set

    fun loadPrefs(c: Context) {
        val p = c.getSharedPreferences("live", 0)
        quality = tech.kousozo.tahoetv.core.PlayPrefs.Quality.of(p.getString("quality", null))
        latency = tech.kousozo.tahoetv.core.PlayPrefs.Latency.of(p.getString("latency", null))
    }

    /** Applies right away: the player switches to a variant at or under the cap. */
    fun setQuality(c: Context, q: tech.kousozo.tahoetv.core.PlayPrefs.Quality) {
        quality = q; c.getSharedPreferences("live", 0).edit().putString("quality", q.key).apply()
        player?.let { applyQuality(it) }
    }

    /** Applies by restarting the current source at the new distance from live. */
    fun setLatency(c: Context, l: tech.kousozo.tahoetv.core.PlayPrefs.Latency) {
        latency = l; c.getSharedPreferences("live", 0).edit().putString("latency", l.key).apply()
        if (entry != null && player != null) start(c.applicationContext)
    }

    private fun applyQuality(p: ExoPlayer) {
        val b = p.trackSelectionParameters.buildUpon()
        if (quality.maxHeight > 0) b.setMaxVideoSize(Int.MAX_VALUE, quality.maxHeight) else b.clearVideoSizeConstraints()
        p.trackSelectionParameters = b.build()
    }

    private var player: ExoPlayer? = null
    private val ui = Handler(Looper.getMainLooper())
    private var streams: List<M3u.Channel> = emptyList()
    private var source = 0
    private var everReady = false
    private var retries = 0
    private var attempt = 0 // bumped per source start: a late 20 s timeout of an old attempt does nothing

    /** Built with, and its callbacks keep, the application context only: this object outlives activities (rotation). */
    fun player(ctx: Context): ExoPlayer = player ?: ctx.applicationContext.let { c -> ExoPlayer.Builder(c,
        // decoder fallback + FFmpeg (AC-3, E-AC-3, DTS, MP2…) after the phone's own decoders
        DefaultRenderersFactory(c).setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)).build().also { p ->
        p.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onAudioDecoderInitialized(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, name: String, a: Long, b: Long) = DiagLog.add("live", "audio decoder $name")
            override fun onVideoDecoderInitialized(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, name: String, a: Long, b: Long) = DiagLog.add("live", "video decoder $name")
        })
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                DiagLog.error("live", "${error.errorCodeName} @ ${streams.getOrNull(source)?.url}", error.cause)
                // fell out of the live window: rejoin at the live point, not a failure of the source
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) { p.seekToDefaultPosition(); p.prepare(); return }
                if (!everReady || retries >= 3) { next(c); return }
                retries++; message = ""
                p.prepare(); p.play()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state != Player.STATE_READY) return
                // a finished playlist, or a "live" window far in the past, is a recording (yesterday's show on a loop)
                if (!everReady && isRecording(p, streams.getOrNull(source))) { DiagLog.add("live", "rekaman, bukan live → sumber berikutnya"); next(c); return }
                streams.getOrNull(source)?.let { SourceHealth.report(it.url, true) }
                message = null; everReady = true; retries = 0
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { paused = !p.playWhenReady }
            override fun onTracksChanged(tracks: Tracks) { format = describe(p) }
            // the video format is often only known once the first frame is decoded
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) { format = describe(p) }
            override fun onRenderedFirstFrame() { format = describe(p) }
        })
        player = p
        applyQuality(p)
    } }

    /** Start [e] (from its best source), or carry on if it's already the channel playing. */
    fun play(c: Context, e: LocalTv.Entry) {
        if (entry?.channel?.url == e.channel.url && message != "Saluran ini sedang tidak bisa diputar. Coba saluran lain.") { resume(); return }
        entry = e
        Channels.remember(c, e)
        streams = SourceHealth.order(listOf(e.channel) + e.alternates) // known-live first
        source = 0
        start(c.applicationContext)
    }

    /** Previous/next channel in [list] (what the TV Live screen shows). */
    fun zap(c: Context, list: List<LocalTv.Entry>?, dir: Int) {
        val l = list?.takeIf { it.isNotEmpty() } ?: return
        val i = l.indexOfFirst { it.channel.url == entry?.channel?.url }
        play(c, l[((if (i < 0) 0 else i) + dir + l.size) % l.size])
    }

    fun toggle() { val p = player ?: return; if (p.playWhenReady) p.pause() else p.play(); paused = !p.playWhenReady }
    fun pause() { player?.pause(); paused = true }
    fun resume() { player?.play(); paused = false }

    /** Stop and forget the channel (the mini-player's ✕). */
    fun stop() { attempt++; player?.stop(); entry = null; message = null; format = null; paused = false }

    fun release() { stop(); player?.release(); player = null }

    private fun next(c: Context) {
        streams.getOrNull(source)?.let { SourceHealth.report(it.url, false) }
        source++
        start(c)
    }

    private fun start(c: Context) {
        val p = player(c)
        val e = entry ?: return
        val s = streams.getOrNull(source)
        val my = ++attempt
        if (s == null) {
            DiagLog.add("live", "${e.name}: semua sumber gagal")
            p.stop(); message = "Saluran ini sedang tidak bisa diputar. Coba saluran lain."; return
        }
        DiagLog.add("live", "${e.name} sumber ${source + 1}/${streams.size} ${s.url}")
        everReady = false; retries = 0; message = ""; format = null; paused = false
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
            .setUserAgent(s.headers["User-Agent"] ?: "TahoeTV/3.0.2").setDefaultRequestProperties(s.headers - "User-Agent")
        p.setMediaSource(DefaultMediaSourceFactory(http).createMediaSource(MediaItem.Builder().setUri(Uri.parse(s.url))
            // ~20 s behind live: a cushion against slow pieces; clamped to what the stream keeps
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(latency.offsetMs).build()).build()))
        p.prepare(); p.play()
        ui.postDelayed({ // never started → next source
            if (my == attempt && !everReady) { DiagLog.add("live", "tidak mulai dalam 20 dtk → sumber berikutnya"); next(c) }
        }, 20_000)
    }

    /** "1080p · H.264 · AAC" from the tracks playing. */
    private fun describe(p: ExoPlayer): String? {
        val v = p.videoFormat; val a = p.audioFormat
        fun codec(mime: String?) = when {
            mime == null -> null
            "avc" in mime -> "H.264"; "hevc" in mime || "hev1" in mime -> "H.265"; "av01" in mime -> "AV1"; "vp9" in mime -> "VP9"
            "mp4a" in mime || "aac" in mime -> "AAC"; "ac3" in mime && "eac3" !in mime -> "AC-3"; "eac3" in mime -> "E-AC-3"; "mpeg" in mime -> "MP2"; "opus" in mime -> "Opus"
            else -> mime.substringAfter('/').uppercase()
        }
        return listOfNotNull(v?.height?.takeIf { it > 0 }?.let { "${it}p" }, codec(v?.sampleMimeType), codec(a?.sampleMimeType))
            .joinToString(" · ").ifEmpty { null }
    }
}

/** Floating mini-player (Mochy-style): the channel playing, its show, ⏯ and next; tap to open TV Live, ✕ to stop.
 *  Shown on every tab except TV Live while something plays. */
@Composable
fun MiniPlayer(visible: Boolean, modifier: Modifier = Modifier) {
    val c = LocalContext.current
    val e = Playback.entry
    AnimatedVisibility(visible && e != null, modifier, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        val ch = e ?: return@AnimatedVisibility
        val logo = rememberNetImage(ch.channel.logo)
        val tint = logo?.let { dominantColor(it) } ?: Tahoe.Accent
        val show = nowOn(Channels.epg, ch, Instant.now())?.title
        Surface(
            Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(64.dp).clickable { Nav.tab = 1; Nav.detail = null },
            shape = RoundedCornerShape(20.dp), color = Color(0xFF1C1A1F).copy(alpha = 0.96f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)), shadowElevation = 14.dp,
        ) {
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(tint.copy(alpha = 0.22f), Color.Transparent)))) {
                Row(Modifier.fillMaxSize().padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                        if (logo != null) Image(logo, null, Modifier.padding(6.dp).fillMaxSize(), contentScale = ContentScale.Fit)
                        else Text(ch.name.take(3).uppercase(), color = Color(0xFF111114), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ch.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(when (Playback.message) { "" -> "Memuat…"; null -> show ?: "Siaran langsung"; else -> "Tidak bisa diputar" },
                            fontSize = 12.sp, color = Tahoe.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton({ Playback.toggle() }) {
                        Icon(if (Playback.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (Playback.paused) "Putar" else "Jeda", Modifier.size(28.dp))
                    }
                    IconButton({ Playback.zap(c, Channels.entries, 1) }) { Icon(Icons.Rounded.SkipNext, "Saluran berikutnya") }
                    IconButton({ Playback.stop() }) { Icon(Icons.Rounded.Close, "Hentikan siaran", tint = Tahoe.Dim, modifier = Modifier.size(20.dp)) }
                }
            }
        }
    }
}
