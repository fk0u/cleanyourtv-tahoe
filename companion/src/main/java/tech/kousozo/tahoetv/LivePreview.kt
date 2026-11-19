package tech.kousozo.tahoetv

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.util.Locale
import tech.kousozo.tahoetv.core.Brand
import tech.kousozo.tahoetv.core.Demo
import tech.kousozo.tahoetv.core.Drm
import tech.kousozo.tahoetv.core.LocalTv
import tech.kousozo.tahoetv.core.M3u
import tech.kousozo.tahoetv.core.SourceHealth
import tech.kousozo.tahoetv.core.Web

/**
 * Lightweight muted live preview for mobile Home Hero and Channel Detail screens.
 * Uses a single shared 480p-capped ExoPlayer with short buffers and automatic failover to static artwork
 * when DRM/DASH/offline/reduced-motion/full-playback applies.
 */
object MobileLivePreview {
    private const val TIMEOUT_MS = 8_000L

    var enabled by mutableStateOf(true)
    var muted by mutableStateOf(true)
    var ready by mutableStateOf(false); private set
    var activeUrl by mutableStateOf<String?>(null); private set

    private val ui = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var attachedView: TextureView? = null
    private var currentOwner: String? = null
    private var pendingStart: Runnable? = null
    private var pendingTimeout: Runnable? = null
    private var attempt = 0

    fun bestCandidate(entry: LocalTv.Entry): M3u.Channel? {
        val raw = listOf(entry.channel) + entry.alternates
        return SourceHealth.order(raw).firstOrNull { ch ->
            ch.url.isNotBlank() && !ch.drm && !Drm.isDash(ch.url, ch.drmType, ch.manifestType)
        }
    }

    fun canPreview(entry: LocalTv.Entry?): Boolean =
        entry != null &&
            enabled &&
            !Demo.isActive() &&
            !Channels.debugOffline &&
            !ThemePrefs.reduceMotion &&
            Playback.entry == null &&
            !Nav.inPip &&
            bestCandidate(entry) != null

    fun focus(ctx: Context, owner: String, entry: LocalTv.Entry) {
        if (!canPreview(entry)) {
            stop(owner)
            return
        }
        val candidate = bestCandidate(entry) ?: run {
            stop(owner)
            return
        }
        if (currentOwner == owner && activeUrl == entry.channel.url && (pendingStart != null || (ready && player != null))) {
            return
        }
        cancelPending()
        attempt++
        val myAttempt = attempt
        currentOwner = owner
        activeUrl = entry.channel.url
        ready = false
        muted = true
        attachedView?.let { v ->
            v.animate().cancel()
            v.alpha = 0f
        }
        player?.stop()
        val appCtx = ctx.applicationContext
        val startRun = Runnable {
            if (myAttempt != attempt || currentOwner != owner || !canPreview(entry)) return@Runnable
            startStream(appCtx, candidate, myAttempt)
        }
        pendingStart = startRun
        ui.postDelayed(startRun, Brand.MOTION_SLOW.toLong())
    }

    fun toggleMute() {
        muted = !muted
        player?.volume = if (muted) 0f else 0.7f
    }

    fun attachSurface(tv: TextureView) {
        if (attachedView !== tv) {
            attachedView = tv
            player?.setVideoTextureView(tv)
        }
        tv.alpha = if (ready) 1f else 0f
    }

    fun detachSurface(tv: TextureView) {
        if (attachedView === tv) {
            attachedView = null
            player?.clearVideoTextureView(tv)
        }
    }

    /** Stops active/pending preview. When [owner] is non-null, only stops if [owner] still owns the preview. */
    fun stop(owner: String? = null) {
        if (owner != null && currentOwner != null && currentOwner != owner) return
        cancelPending()
        attempt++
        currentOwner = null
        activeUrl = null
        ready = false
        attachedView?.let { v ->
            v.animate().cancel()
            v.alpha = 0f
        }
        player?.stop()
    }

    fun release() {
        stop()
        player?.release()
        player = null
        attachedView = null
    }

    private fun cancelPending() {
        pendingStart?.let { ui.removeCallbacks(it) }
        pendingStart = null
        pendingTimeout?.let { ui.removeCallbacks(it) }
        pendingTimeout = null
    }

    private fun ensurePlayer(c: Context): ExoPlayer = player ?: run {
        val lc = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1500, 4000, 750, 1200)
            .build()
        ExoPlayer.Builder(
            c,
            DefaultRenderersFactory(c).setEnableDecoderFallback(true),
        ).setLoadControl(lc).build().also { p ->
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(Int.MAX_VALUE, 480)
                .build()
            p.volume = 0f
            p.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    pendingTimeout?.let { ui.removeCallbacks(it) }
                    pendingTimeout = null
                    ready = true
                    attachedView?.let { tv ->
                        tv.animate().cancel()
                        tv.animate().alpha(1f).setDuration(Brand.MOTION_MED.toLong()).start()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    stop()
                }
            })
            attachedView?.let { p.setVideoTextureView(it) }
            player = p
        }
    }

    private fun startStream(c: Context, ch: M3u.Channel, myAttempt: Int) {
        val p = ensurePlayer(c)
        attachedView?.let { p.setVideoTextureView(it) }
        p.volume = if (muted) 0f else 0.7f
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(6000)
            .setReadTimeoutMs(10000)
            .setUserAgent(ch.headers["User-Agent"] ?: Web.STREAM_UA)
            .setDefaultRequestProperties(ch.headers - "User-Agent")
        val item = MediaItem.Builder().setUri(Uri.parse(ch.url))
        if (ch.url.lowercase(Locale.ROOT).contains(".m3u8")) {
            item.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        p.setMediaSource(DefaultMediaSourceFactory(http).createMediaSource(item.build()))
        p.prepare()
        p.play()
        val timeoutRun = Runnable {
            if (myAttempt == attempt && !ready) {
                stop()
            }
        }
        pendingTimeout = timeoutRun
        ui.postDelayed(timeoutRun, TIMEOUT_MS)
    }
}

/**
 * Renders muted live preview video for [entry] over background artwork while [active] is true.
 */
@Composable
fun LivePreviewSurface(
    entry: LocalTv.Entry,
    owner: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val eligible = active && MobileLivePreview.canPreview(entry)

    LaunchedEffect(eligible, entry.channel.url) {
        if (eligible) MobileLivePreview.focus(ctx, owner, entry)
        else MobileLivePreview.stop(owner)
    }
    DisposableEffect(owner) {
        onDispose { MobileLivePreview.stop(owner) }
    }

    if (!eligible) return

    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                isOpaque = false
                alpha = 0f
                MobileLivePreview.attachSurface(this)
            }
        },
        modifier = modifier,
        update = { tv -> MobileLivePreview.attachSurface(tv) },
        onRelease = { tv -> MobileLivePreview.detachSurface(tv) },
    )
}
