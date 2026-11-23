package tech.kousozo.tahoetv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.VideoSize
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import tech.kousozo.tahoetv.core.Brand
import tech.kousozo.tahoetv.core.Drm
import tech.kousozo.tahoetv.core.LocalTv
import tech.kousozo.tahoetv.core.M3u
import tech.kousozo.tahoetv.core.SourceHealth
import tech.kousozo.tahoetv.core.Web

/**
 * Muted live preview for Home hero and channel detail. One shared ExoPlayer (480p cap, short buffer) that:
 *  - starts quickly (short dwell) and walks through every source of the channel (HLS, DASH, ClearKey/Widevine via [StreamSource])
 *    before giving up, so each highlighted channel gets a preview whenever any of its sources plays;
 *  - keeps the picture's real aspect ratio (letterbox, never cropped or stretched);
 *  - tells the rest of the app when it is ready or has given up ([ready], [failedUrl]) so the hero waits for it;
 *  - holds the background stream health probes while it runs, so previewing never starves the picture of bandwidth.
 */
object MobileLivePreview {
    private const val DWELL_MS = 250L
    private const val SOURCE_TIMEOUT_MS = 7_000L

    var enabled by mutableStateOf(true)
    var muted by mutableStateOf(true)
    var ready by mutableStateOf(false); private set
    var activeUrl by mutableStateOf<String?>(null); private set
    /** The channel whose sources all failed to preview: the hero shows its artwork and moves on. */
    var failedUrl by mutableStateOf<String?>(null); private set
    /** Real aspect ratio of the picture playing (width / height), 16:9 until known. */
    var aspect by mutableStateOf(16f / 9f); private set

    /** Bumped when a new real frame of some channel was saved: cards showing that channel refresh. */
    var snapVersion by mutableIntStateOf(0); private set
    private var appContext: Context? = null
    private var pendingSnap: Runnable? = null

    private val ui = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var attachedView: TextureView? = null
    private var attachedFrame: AspectRatioFrameLayout? = null
    private var currentOwner: String? = null
    private var pendingStart: Runnable? = null
    private var pendingTimeout: Runnable? = null
    private var attempt = 0
    private var sources: List<M3u.Channel> = emptyList()
    private var sourceIdx = 0

    /** Every playable source of [entry], healthiest first. DASH and DRM sources are kept: [StreamSource] plays them. */
    fun candidates(entry: LocalTv.Entry): List<M3u.Channel> =
        SourceHealth.order(listOf(entry.channel) + entry.alternates).filter { it.url.isNotBlank() }

    fun canPreview(entry: LocalTv.Entry?): Boolean =
        entry != null &&
            enabled &&
            !Channels.debugOffline &&
            !ThemePrefs.reduceMotion &&
            Playback.entry == null &&
            !Nav.inPip &&
            failedUrl != entry.channel.url &&
            candidates(entry).isNotEmpty()

    /** Probes wait while anything plays or previews. Called by the player too. */
    fun syncProbeGate() { SourceHealth.yieldToPlayback = Playback.entry != null || activeUrl != null }

    fun focus(ctx: Context, owner: String, entry: LocalTv.Entry) {
        if (!canPreview(entry)) {
            stop(owner)
            return
        }
        if (currentOwner == owner && activeUrl == entry.channel.url && (pendingStart != null || (ready && player != null))) return
        cancelPending()
        attempt++
        val myAttempt = attempt
        currentOwner = owner
        activeUrl = entry.channel.url
        ready = false
        muted = true
        sources = candidates(entry)
        sourceIdx = 0
        attachedView?.let { v -> v.animate().cancel(); v.alpha = 0f }
        player?.stop()
        syncProbeGate()
        val appCtx = ctx.applicationContext
        appContext = appCtx
        val startRun = Runnable {
            if (myAttempt != attempt || currentOwner != owner || !canPreview(entry)) return@Runnable
            pendingStart = null
            startSource(appCtx, myAttempt)
        }
        pendingStart = startRun
        ui.postDelayed(startRun, DWELL_MS)
    }

    /** While the page scrolls the video holds still (decoding and compositing a video layer is what makes a scrolling page stutter). */
    fun setScrolling(scrolling: Boolean) { player?.playWhenReady = !scrolling && activeUrl != null }

    fun toggleMute() {
        muted = !muted
        player?.volume = if (muted) 0f else 0.7f
    }

    fun attachSurface(tv: TextureView, frame: AspectRatioFrameLayout? = null) {
        if (attachedView !== tv) {
            attachedView = tv
            player?.setVideoTextureView(tv)
        }
        if (frame != null) { attachedFrame = frame; frame.setAspectRatio(aspect) }
        tv.alpha = if (ready) 1f else 0f
    }

    fun detachSurface(tv: TextureView) {
        if (attachedView === tv) {
            attachedView = null
            attachedFrame = null
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
        attachedView?.let { v -> v.animate().cancel(); v.alpha = 0f }
        player?.stop()
        syncProbeGate()
    }

    fun release() {
        stop()
        player?.release()
        player = null
        attachedView = null
        attachedFrame = null
    }

    /** Forget a past failure (new load, network back): the channel may preview again. */
    fun clearFailures() { failedUrl = null }

    private fun cancelPending() {
        pendingStart?.let { ui.removeCallbacks(it) }
        pendingStart = null
        pendingTimeout?.let { ui.removeCallbacks(it) }
        pendingTimeout = null
    }

    private fun ensurePlayer(c: Context): ExoPlayer = player ?: run {
        val lc = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2500, 6000, 600, 1200)
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
                    scheduleSnapshot(attempt)
                    attachedView?.let { tv ->
                        tv.animate().cancel()
                        tv.animate().alpha(1f).setDuration(Brand.MOTION_MED.toLong()).start()
                    }
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                        attachedFrame?.setAspectRatio(aspect)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    tryNextSource(c, attempt)
                }
            })
            attachedView?.let { p.setVideoTextureView(it) }
            player = p
        }
    }

    private fun startSource(c: Context, myAttempt: Int) {
        val ch = sources.getOrNull(sourceIdx) ?: return giveUp()
        val p = ensurePlayer(c)
        attachedView?.let { p.setVideoTextureView(it) }
        p.volume = if (muted) 0f else 0.7f
        runCatching {
            p.setMediaSource(StreamSource.build(ch, null))
            p.prepare()
            p.play()
        }.onFailure { return tryNextSource(c, myAttempt) }
        pendingTimeout?.let { ui.removeCallbacks(it) }
        val timeoutRun = Runnable { if (myAttempt == attempt && !ready) tryNextSource(c, myAttempt) }
        pendingTimeout = timeoutRun
        ui.postDelayed(timeoutRun, SOURCE_TIMEOUT_MS)
    }

    private fun tryNextSource(c: Context, myAttempt: Int) {
        if (myAttempt != attempt) return
        sourceIdx++
        if (sourceIdx >= sources.size) giveUp() else startSource(c, myAttempt)
    }

    // ---- real pictures: one frame per channel, kept on disk, shown on cards and as the hero backdrop ----

    private fun snapDir(c: Context) = java.io.File(c.cacheDir, "snaps").apply { mkdirs() }
    private fun snapName(url: String) = java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) } + ".jpg"

    /** A frame of the stream taken ~1.5 s after the picture started (the first frame is often a logo or black). */
    private fun scheduleSnapshot(myAttempt: Int) {
        val c = appContext ?: return
        val url = activeUrl ?: return
        pendingSnap?.let { ui.removeCallbacks(it) }
        val run = Runnable {
            if (myAttempt != attempt || activeUrl != url || !ready) return@Runnable
            val tv = attachedView ?: return@Runnable
            val w = 480; val h = (w / aspect).toInt().coerceIn(200, 480)
            val bmp = runCatching { tv.getBitmap(w, h) }.getOrNull() ?: return@Runnable
            // skip near-black frames (a fade or a slate): sample a grid of pixels
            var lum = 0L; var n = 0
            for (x in 0 until bmp.width step 40) for (y in 0 until bmp.height step 30) {
                val px = bmp.getPixel(x, y); lum += (android.graphics.Color.red(px) + android.graphics.Color.green(px) + android.graphics.Color.blue(px)) / 3; n++
            }
            if (n == 0 || lum / n < 18) { bmp.recycle(); return@Runnable }
            Thread {
                runCatching {
                    java.io.File(snapDir(c), snapName(url)).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 74, it) }
                    ui.post { snapVersion++ }
                }
                bmp.recycle()
            }.start()
        }
        pendingSnap = run
        ui.postDelayed(run, 1_500)
    }

    /** The saved frame of [channelUrl] (decoded off the main thread by the caller), or null if none or older than a day. */
    fun loadSnapshot(c: Context, channelUrl: String): android.graphics.Bitmap? {
        val f = java.io.File(snapDir(c), snapName(channelUrl))
        if (!f.isFile || System.currentTimeMillis() - f.lastModified() > 24L * 3600_000L) return null
        return runCatching { android.graphics.BitmapFactory.decodeFile(f.path) }.getOrNull()
    }

    private fun giveUp() {
        val url = activeUrl
        stop()
        failedUrl = url
    }
}

/**
 * Renders the muted live preview for [entry] while [active] is true. The picture keeps its own aspect ratio
 * (centred, letterboxed, never cropped): put it in a 16:9 box for the best fit.
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
            val tv = TextureView(context).apply { isOpaque = false; alpha = 0f }
            val frame = AspectRatioFrameLayout(context).apply {
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            MobileLivePreview.attachSurface(tv, frame)
            FrameLayout(context).apply {
                addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            }
        },
        modifier = modifier,
        update = { root ->
            val frame = root.getChildAt(0) as? AspectRatioFrameLayout
            val tv = frame?.getChildAt(0) as? TextureView
            if (tv != null) MobileLivePreview.attachSurface(tv, frame)
        },
        onRelease = { root ->
            ((root.getChildAt(0) as? AspectRatioFrameLayout)?.getChildAt(0) as? TextureView)?.let { MobileLivePreview.detachSurface(it) }
        },
    )
}
