package tech.kousozo.tahoetv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import tech.kousozo.tahoetv.core.Drm
import tech.kousozo.tahoetv.core.M3u
import tech.kousozo.tahoetv.core.PlayPrefs.PlaybackTuning

/** One place that turns a playlist entry into an ExoPlayer source: HLS or DASH, request headers, ClearKey / Widevine / PlayReady.
 *  The full player and the Home / channel live preview both use it, so a channel that plays fully also previews. */
internal object StreamSource {
    /** [tuning] = bounded live offset and gentle speed correction for the full player; null for a preview (no catch-up needed). */
    /** What the system media controls show: [title] (the programme or channel), [artist] (the channel), [artwork] (its logo). */
    class Meta(val title: String, val artist: String, val artwork: String?)

    /** Retries a failed segment or playlist quickly (0.5 s, 1 s, 1.5 s … up to 2.5 s, eight times) instead of ExoPlayer's default
     *  of three tries with growing waits: a slow public server then costs a short hiccup, not a re-prepare of the whole stream. */
    private class QuickRetryPolicy : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(8) {
        override fun getRetryDelayMsFor(info: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val e = info.exception
            if (e is androidx.media3.common.ParserException || e is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException && e.responseCode in 400..499 && e.responseCode != 408 && e.responseCode != 429)
                return androidx.media3.common.C.TIME_UNSET // a real "not found": do not keep asking
            return minOf(500L * info.errorCount, 2_500L)
        }
    }

    fun build(s: M3u.Channel, tuning: PlaybackTuning?, meta: Meta? = null): MediaSource {
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000).setReadTimeoutMs(12_000)
            .setUserAgent(s.headers["User-Agent"] ?: tech.kousozo.tahoetv.core.Web.STREAM_UA).setDefaultRequestProperties(s.headers - "User-Agent")
        val item = MediaItem.Builder().setUri(Uri.parse(s.url))
        if (tuning != null) item.setLiveConfiguration(
            MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(tuning.targetOffsetMs)
                .setMinOffsetMs(tuning.minOffsetMs)
                .setMaxOffsetMs(tuning.maxOffsetMs)
                .setMinPlaybackSpeed(tuning.minPlaybackSpeed)
                .setMaxPlaybackSpeed(tuning.maxPlaybackSpeed)
                .build()
        )
        if (meta != null) item.setMediaMetadata(androidx.media3.common.MediaMetadata.Builder()
            .setTitle(meta.title).setArtist(meta.artist).setIsPlayable(true)
            .apply { meta.artwork?.takeIf { it.startsWith("http") }?.let { setArtworkUri(Uri.parse(it)) } }.build())
        if (s.isDash) item.setMimeType(MimeTypes.APPLICATION_MPD)
        else if (s.url.contains(".m3u8", ignoreCase = true)) item.setMimeType(MimeTypes.APPLICATION_M3U8)

        val factory = DefaultMediaSourceFactory(http).setLoadErrorHandlingPolicy(QuickRetryPolicy())
        val scheme = Drm.scheme(s.drmType, s.drmKey)
        val jwk = Drm.toClearKeyJwk(s.drmKey)
        val licUrl = Drm.licenseUrl(s.drmKey)
        if (jwk != null) {
            val mgr = DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(true)
                .build(LocalMediaDrmCallback(jwk.toByteArray(Charsets.UTF_8)))
            factory.setDrmSessionManagerProvider { mgr }
        } else if (licUrl != null && scheme != Drm.Scheme.NONE) {
            val uuid = when (scheme) {
                Drm.Scheme.WIDEVINE -> C.WIDEVINE_UUID
                Drm.Scheme.PLAYREADY -> C.PLAYREADY_UUID
                else -> C.CLEARKEY_UUID
            }
            val drmCfg = MediaItem.DrmConfiguration.Builder(uuid)
                .setLicenseUri(licUrl)
                .setMultiSession(true)
            if (s.headers.isNotEmpty()) drmCfg.setLicenseRequestHeaders(s.headers)
            item.setDrmConfiguration(drmCfg.build())
            val drmProv = DefaultDrmSessionManagerProvider().apply { setDrmHttpDataSourceFactory(http) }
            factory.setDrmSessionManagerProvider(drmProv)
        }
        return factory.createMediaSource(item.build())
    }
}
