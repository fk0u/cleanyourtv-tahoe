package tech.kousozo.tahoetv

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.time.Instant
import tech.kousozo.tahoetv.core.DiagLog
import tech.kousozo.tahoetv.core.Drm
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
    /** Every source failed: the slate (#76) shows, with this reason, and the channel is retried in the background. */
    var slate by mutableStateOf<tech.kousozo.tahoetv.core.Slate.Reason?>(null); private set
    /** Seconds until the next background retry (0 = not counting: retrying now, or given up). */
    var retryIn by mutableIntStateOf(0); private set
    private var autoRetries = 0
    private var sawRecording = false
    var paused by mutableStateOf(false); private set
    /** "1080p · H.264 · AAC", once known. */
    var format by mutableStateOf<String?>(null); private set
    var subtitleLabel by mutableStateOf<String?>(null); private set
    var audioLabel by mutableStateOf<String?>(null); private set

    /** Viewer choices (Setelan pemutar), kept in the "live" prefs. */
    var quality by mutableStateOf(tech.kousozo.tahoetv.core.PlayPrefs.Quality.Auto); private set
    var latency by mutableStateOf(tech.kousozo.tahoetv.core.PlayPrefs.Latency.Standard); private set
    var subtitleSize by mutableStateOf(tech.kousozo.tahoetv.core.PlayPrefs.SubtitleSize.Normal); private set

    private var statsVideoDecoder = ""
    private var statsAudioDecoder = ""
    private var rebufferCount = 0
    private var rebufferMs = 0L
    private var bufferingSince = -1L
    private var droppedFramesCount = 0L
    private var bandwidthKbps = 0

    fun statsSnapshot(): tech.kousozo.tahoetv.core.PlayerStats.Snapshot? {
        val e = entry ?: return null
        val cur = streams.getOrNull(source) ?: e.channel
        val p = player
        val vf = p?.videoFormat
        val af = p?.audioFormat
        val bufSec = if (p != null) maxOf(0.0, (p.bufferedPosition - p.currentPosition) / 1000.0) else 0.0
        val offMs = p?.currentLiveOffset ?: C.TIME_UNSET
        val offSec = if (offMs == C.TIME_UNSET) Double.NaN else maxOf(0.0, offMs / 1000.0)
        val liveRebuf = if (bufferingSince > 0) rebufferMs + maxOf(0L, System.currentTimeMillis() - bufferingSince) else rebufferMs
        val status = when {
            slate != null -> "OFFLINE"
            paused -> "PAUSED"
            message != null -> "LOADING"
            else -> "LIVE"
        }
        return tech.kousozo.tahoetv.core.PlayerStats.Snapshot(
            channelName = e.name,
            sourceIndex = source + 1,
            sourceTotal = maxOf(1, streams.size),
            streamUrl = cur.url,
            status = status,
            width = vf?.width?.coerceAtLeast(0) ?: 0,
            height = vf?.height?.coerceAtLeast(0) ?: 0,
            fps = vf?.frameRate?.takeIf { it > 0f } ?: 0f,
            videoCodec = vf?.sampleMimeType.orEmpty(),
            audioCodec = af?.sampleMimeType.orEmpty(),
            decoderName = statsVideoDecoder.ifBlank { statsAudioDecoder },
            videoBitrateKbps = vf?.bitrate?.takeIf { it > 0 }?.div(1000) ?: 0,
            bufferSeconds = bufSec,
            liveOffsetSeconds = offSec,
            rebufferCount = rebufferCount,
            rebufferMs = liveRebuf,
            droppedFrames = droppedFramesCount,
            bandwidthKbps = bandwidthKbps,
        )
    }

    fun cycleTrack(trackType: Int): String = cycleTrack(null, trackType)

    fun cycleTrack(ctx: Context?, trackType: Int): String {
        val p = player ?: return "-"
        val groups = p.currentTracks.groups.filter { it.type == trackType && it.isSupported }
        if (groups.isEmpty()) {
            if (trackType == C.TRACK_TYPE_TEXT) subtitleLabel = null
            if (trackType == C.TRACK_TYPE_AUDIO) audioLabel = null
            return if (trackType == C.TRACK_TYPE_TEXT) "CC: -" else "Audio: -"
        }
        val options = mutableListOf<Triple<androidx.media3.common.TrackGroup, Int, String>>()
        groups.forEach { g ->
            for (i in 0 until g.length) {
                if (g.isTrackSupported(i)) {
                    val fmt = g.getTrackFormat(i)
                    val label = listOfNotNull(
                        fmt.label?.takeIf { it.isNotBlank() },
                        fmt.language?.takeIf { it.isNotBlank() && it != "und" }?.uppercase(),
                    ).joinToString(" · ").ifBlank { "Track ${options.size + 1}" }
                    options += Triple(g.mediaTrackGroup, i, label)
                }
            }
        }
        if (options.isEmpty()) return "-"
        val curIdx = groups.firstNotNullOfOrNull { g ->
            (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { idx ->
                options.indexOfFirst { it.first == g.mediaTrackGroup && it.second == idx }
            }
        } ?: -1
        val b = p.trackSelectionParameters.buildUpon()
        b.clearOverridesOfType(trackType)
        val chKey = entry?.let { tech.kousozo.tahoetv.core.PlayPrefs.channelTrackKey(it.channel.channelId, it.name) }
        val lp = ctx?.applicationContext?.getSharedPreferences("live", 0)
        if (trackType == C.TRACK_TYPE_TEXT && curIdx == options.lastIndex) {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            p.trackSelectionParameters = b.build()
            if (lp != null && chKey != null) lp.edit().putString("tr_s_$chKey", "off").apply()
            subtitleLabel = "Off"
            return "CC: Off"
        }
        val next = options[(curIdx + 1) % options.size]
        b.setTrackTypeDisabled(trackType, false)
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(next.first, next.second))
        p.trackSelectionParameters = b.build()
        val chosenLang = next.first.getFormat(next.second).language
        if (lp != null && chKey != null && !chosenLang.isNullOrBlank()) {
            lp.edit().putString((if (trackType == C.TRACK_TYPE_TEXT) "tr_s_" else "tr_a_") + chKey, chosenLang).apply()
        }
        if (trackType == C.TRACK_TYPE_TEXT) subtitleLabel = next.third
        if (trackType == C.TRACK_TYPE_AUDIO) audioLabel = next.third
        return next.third
    }

    fun loadPrefs(c: Context) {
        val p = c.getSharedPreferences("live", 0)
        quality = tech.kousozo.tahoetv.core.PlayPrefs.Quality.of(p.getString("quality", null))
        latency = tech.kousozo.tahoetv.core.PlayPrefs.Latency.of(p.getString("latency", null))
        subtitleSize = tech.kousozo.tahoetv.core.PlayPrefs.SubtitleSize.of(p.getString("sub_size", null))
    }

    fun setSubtitleSize(c: Context, sz: tech.kousozo.tahoetv.core.PlayPrefs.SubtitleSize) {
        subtitleSize = sz
        c.getSharedPreferences("live", 0).edit().putString("sub_size", sz.key).apply()
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
    private var recapDir: java.io.File? = null
    private var recapStartedAt: Long = 0L
    private var recapChannelId: String = ""
    private var recapChannelName: String = ""
    private var recapShow: String = ""

    private fun flushRecap() {
        val dir = recapDir ?: return
        if (recapStartedAt <= 0L || recapChannelName.isBlank()) return
        val sec = ((System.currentTimeMillis() - recapStartedAt) / 1000L).toInt()
        recapStartedAt = 0L
        if (sec >= 15) tech.kousozo.tahoetv.core.WatchRecap.record(dir, recapChannelId, recapChannelName, recapShow, sec, "main")
    }

    /** Built with, and its callbacks keep, the application context only: this object outlives activities (rotation). */
    private var appCtx: Context? = null
    fun player(ctx: Context): ExoPlayer = player ?: ctx.applicationContext.let { c ->
        appCtx = c
        val tuning = tech.kousozo.tahoetv.core.PlayPrefs.tuningFor(latency)
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(tuning.minBufferMs, tuning.maxBufferMs, tuning.bufferForPlaybackMs, tuning.bufferForPlaybackAfterRebufferMs)
            .build()
        ExoPlayer.Builder(
            c,
            // decoder fallback + FFmpeg (AC-3, E-AC-3, DTS, MP2…) after the phone's own decoders
            DefaultRenderersFactory(c).setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON),
        ).setLoadControl(loadControl).build().also { p ->
        p.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onAudioDecoderInitialized(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, name: String, a: Long, b: Long) {
                statsAudioDecoder = name; DiagLog.add("live", "audio decoder $name")
            }
            override fun onVideoDecoderInitialized(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, name: String, a: Long, b: Long) {
                statsVideoDecoder = name; DiagLog.add("live", "video decoder $name")
            }
            override fun onDroppedVideoFrames(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, count: Int, elapsedMs: Long) {
                droppedFramesCount += maxOf(0, count)
            }
            override fun onLoadError(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo, mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData, error: java.io.IOException, wasCanceled: Boolean) {
                DiagLog.add("live", "load error ${error.javaClass.simpleName}: ${error.message?.take(80)} (${loadEventInfo.uri.host})")
            }
            override fun onBandwidthEstimate(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
                if (bitrateEstimate > 0) bandwidthKbps = (bitrateEstimate / 1000L).toInt()
            }
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
                if (state == Player.STATE_BUFFERING && everReady && bufferingSince < 0) {
                    bufferingSince = System.currentTimeMillis()
                    return
                }
                if (state != Player.STATE_READY) return
                if (bufferingSince > 0) {
                    rebufferCount++
                    val stall = maxOf(0L, System.currentTimeMillis() - bufferingSince)
                    rebufferMs += stall
                    bufferingSince = -1L
                    // every freeze is written down (when, how long, how far behind live, bandwidth) so a report says why
                    DiagLog.add("live", "freeze ${stall} ms, behind live ${p.currentLiveOffset} ms, ~${bandwidthKbps} kbps, ${streams.getOrNull(source)?.url?.substringBefore('?')}")
                }
                // a finished playlist, or a "live" window far in the past, is a recording (yesterday's show on a loop)
                if (!everReady && isRecording(p, streams.getOrNull(source))) { DiagLog.add("live", "rekaman, bukan live → sumber berikutnya"); sawRecording = true; next(c); return }
                streams.getOrNull(source)?.let { SourceHealth.report(it.url, true) }
                message = null; everReady = true; retries = 0; slate = null; autoRetries = 0; retryIn = 0
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
        MobileLivePreview.stop()
        if (entry?.channel?.url == e.channel.url && slate == null) { resume(); return }
        flushRecap()
        recapDir = java.io.File(c.applicationContext.filesDir, "recap")
        recapStartedAt = System.currentTimeMillis()
        recapChannelId = e.channel.channelId
        recapChannelName = e.name
        recapShow = nowOn(Channels.epg, e, java.time.Instant.now())?.title.orEmpty()
        entry = e; slate = null; autoRetries = 0; retryIn = 0; sawRecording = false
        MobileLivePreview.syncProbeGate()
        Channels.remember(c, e)
        Account.publishNowPlaying(c, e.channel.channelId, e.channel.url, e.name)
        val fallbackStream = tech.kousozo.tahoetv.core.OttEngine.findFallbackStream(e.channel)
        val raw = (listOf(e.channel) + e.alternates + listOfNotNull(fallbackStream)).distinctBy { it.url }
        val app = c.applicationContext
        if (raw.size <= 1) {
            streams = SourceHealth.order(raw)
            source = 0
            start(app)
        } else {
            val my = ++attempt
            val zapGen = tech.kousozo.tahoetv.core.StreamSelector.newZapGeneration()
            Thread({
                val ranked = tech.kousozo.tahoetv.core.StreamSelector.rankOnOpen(raw, generation = zapGen)
                ui.post {
                    if (entry?.channel?.url == e.channel.url && attempt == my) {
                        streams = ranked
                        source = 0
                        start(app)
                    }
                }
            }, "tahoe-rank").apply { isDaemon = true }.start()
        }
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
    fun stop() { flushRecap(); attempt++; player?.stop(); appCtx?.let { c -> runCatching { c.stopService(android.content.Intent(c, PlaybackService::class.java)) } }; entry = null; message = null; format = null; paused = false; slate = null; retryIn = 0; MobileLivePreview.syncProbeGate() }

    /** "Coba lagi" on the slate, or the background retry: every source again, best first. */
    fun retry(c: Context) {
        val e = entry ?: return
        slate = null; retryIn = 0; sawRecording = false
        val fallbackStream = tech.kousozo.tahoetv.core.OttEngine.findFallbackStream(e.channel)
        val raw = (listOf(e.channel) + e.alternates + listOfNotNull(fallbackStream)).distinctBy { it.url }
        val app = c.applicationContext
        if (raw.size <= 1) {
            streams = SourceHealth.order(raw)
            source = 0
            start(app)
        } else {
            val my = ++attempt
            val zapGen = tech.kousozo.tahoetv.core.StreamSelector.newZapGeneration()
            Thread({
                val ranked = tech.kousozo.tahoetv.core.StreamSelector.rankOnOpen(raw, generation = zapGen)
                ui.post {
                    if (entry?.channel?.url == e.channel.url && attempt == my) {
                        streams = ranked
                        source = 0
                        start(app)
                    }
                }
            }, "tahoe-rank").apply { isDaemon = true }.start()
        }
    }

    /** Counts down to the next background retry; stops after [Slate.MAX_RETRIES] (the slate then waits for the viewer). */
    private fun scheduleRetry(c: Context, my: Int) {
        if (autoRetries >= tech.kousozo.tahoetv.core.Slate.MAX_RETRIES) { retryIn = 0; return }
        retryIn = tech.kousozo.tahoetv.core.Slate.RETRY_SECONDS
        fun tick() {
            if (my != attempt || slate == null) return
            if (--retryIn <= 0) { autoRetries++; retry(c) } else ui.postDelayed(::tick, 1000)
        }
        ui.postDelayed(::tick, 1000)
    }

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
            p.stop(); message = tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "player.unavailable")
            slate = if (sawRecording) tech.kousozo.tahoetv.core.Slate.Reason.Recording else tech.kousozo.tahoetv.core.Slate.Reason.Offline
            scheduleRetry(c, my); return
        }
        DiagLog.add("live", "${e.name} sumber ${source + 1}/${streams.size} ${s.url}")
        everReady = false; retries = 0; message = ""; format = null; paused = false
        rebufferCount = 0; rebufferMs = 0L; bufferingSince = -1L; droppedFramesCount = 0L
        statsVideoDecoder = ""; statsAudioDecoder = ""
        val chKey = tech.kousozo.tahoetv.core.PlayPrefs.channelTrackKey(e.channel.channelId, e.name)
        val lp = c.applicationContext.getSharedPreferences("live", 0)
        val tsp = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        val remAudio = lp.getString("tr_a_$chKey", null)
        if (!remAudio.isNullOrBlank()) tsp.setPreferredAudioLanguage(remAudio) else tsp.setPreferredAudioLanguages()
        val remSub = lp.getString("tr_s_$chKey", null)
        if (remSub == "off") {
            tsp.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            subtitleLabel = "Off"
        } else if (!remSub.isNullOrBlank()) {
            tsp.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(remSub)
            subtitleLabel = remSub.uppercase()
        } else {
            tsp.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguages()
            subtitleLabel = null
        }
        p.trackSelectionParameters = tsp.build()

        val tuning = tech.kousozo.tahoetv.core.PlayPrefs.tuningFor(latency, SourceHealth.isTightWindow(s.url), false)
        val show = nowOn(Channels.epg, e, java.time.Instant.now())?.title
        p.setMediaSource(StreamSource.build(s, tuning, StreamSource.Meta(show ?: e.name, e.name, e.channel.logo)))
        p.prepare(); p.play()
        // the system media controls (notification, lock screen, headset buttons) follow the playing channel
        runCatching { c.startService(android.content.Intent(c, PlaybackService::class.java)) }
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

/** Floating mini-player (Mochy-style): the channel playing, its show, live progress, ⏯ and next; tap to open TV Live, ✕ to stop.
 *  Shown on every tab except TV Live while something plays. */
@Composable
fun MiniPlayer(visible: Boolean, modifier: Modifier = Modifier) {
    val c = LocalContext.current
    val lang = PhoneRegion.language
    val e = Playback.entry
    val enterAnim = if (ThemePrefs.reduceMotion) {
        fadeIn(tween(200))
    } else {
        slideInVertically(tween(tech.kousozo.tahoetv.core.Brand.MOTION_MED, easing = EaseDrawerTahoe)) { it } +
            fadeIn(tween(tech.kousozo.tahoetv.core.Brand.MOTION_FAST, easing = EaseOutTahoe))
    }
    val exitAnim = if (ThemePrefs.reduceMotion) {
        fadeOut(tween(200))
    } else {
        slideOutVertically(tween(240, easing = EaseDrawerTahoe)) { it } +
            fadeOut(tween(180, easing = EaseOutTahoe))
    }
    AnimatedVisibility(visible && e != null, modifier, enter = enterAnim, exit = exitAnim) {
        val ch = e ?: return@AnimatedVisibility
        var now by remember(ch.channel.url) { mutableStateOf(Instant.now()) }
        LaunchedEffect(ch.channel.url) {
            while (true) {
                delay(15_000)
                now = Instant.now()
            }
        }
        val prog = nowOn(Channels.epg, ch, now)
        val frac = prog?.let { progress(it, now) }
        val minLeft = prog?.let { java.time.Duration.between(now, it.stop).toMinutes().coerceAtLeast(0) }
        val statusLine = when (Playback.message) {
            "" -> tech.kousozo.tahoetv.core.I18n.get(lang, "player.loading")
            null -> {
                val stateWord = if (Playback.paused) tech.kousozo.tahoetv.core.I18n.get(lang, "player.paused") else "LIVE"
                val leftPart = minLeft?.let { " · " + tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_min_left", it.toString()) }.orEmpty()
                "${ch.name} · $stateWord$leftPart"
            }
            else -> tech.kousozo.tahoetv.core.I18n.get(lang, "player.status_unplayable")
        }
        Surface(
            Modifier.padding(horizontal = 14.dp).fillMaxWidth().height(72.dp),
            shape = RoundedCornerShape(22.dp),
            color = Tahoe.SurfaceHi,
            border = BorderStroke(if (ThemePrefs.highContrast) 2.dp else 1.dp, Tahoe.Stroke),
            shadowElevation = 14.dp,
        ) {
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize().padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f).fillMaxHeight()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(role = Role.Button) { Nav.tab = 1; Nav.detail = null; Nav.page = null }
                            .semantics(mergeDescendants = true) {
                                liveRegion = LiveRegionMode.Polite
                                contentDescription = tech.kousozo.tahoetv.core.I18n.get(lang, "a11y.mini_player_fmt", prog?.title ?: ch.name, ch.name) + " · $statusLine"
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(88.dp, 50.dp).clip(RoundedCornerShape(14.dp))) {
                            ChannelArt(ch, Modifier.fillMaxSize(), logoScale = 0.85f)
                            if (Playback.message == null) {
                                Box(
                                    Modifier.align(Alignment.BottomEnd).padding(4.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color.Black.copy(alpha = 0.72f))
                                        .padding(horizontal = 5.dp, vertical = 3.dp),
                                ) {
                                    NowPlayingIndicator(playing = !Playback.paused)
                                }
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                prog?.title ?: ch.name,
                                fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Tahoe.Text,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                statusLine,
                                fontSize = 13.sp, color = Tahoe.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    IconButton({ Playback.toggle() }, Modifier.size(48.dp)) {
                        Icon(
                            if (Playback.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                            tech.kousozo.tahoetv.core.I18n.get(lang, if (Playback.paused) "player.play" else "player.pause"),
                            Modifier.size(26.dp),
                        )
                    }
                    IconButton({ Playback.zap(c, Channels.entries, 1) }, Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.SkipNext, tech.kousozo.tahoetv.core.I18n.get(lang, "player.next_channel"))
                    }
                    IconButton({ Playback.stop() }, Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.Close, tech.kousozo.tahoetv.core.I18n.get(lang, "player.stop"), tint = Tahoe.Dim, modifier = Modifier.size(20.dp))
                    }
                }
                if (frac != null) {
                    KeepLtr {
                        ProgressBar(frac, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp).height(3.dp))
                    }
                }
            }
        }
    }
}
