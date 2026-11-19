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
    fun build(s: M3u.Channel, tuning: PlaybackTuning?): MediaSource {
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
        if (s.isDash) item.setMimeType(MimeTypes.APPLICATION_MPD)
        else if (s.url.contains(".m3u8", ignoreCase = true)) item.setMimeType(MimeTypes.APPLICATION_M3U8)

        val factory = DefaultMediaSourceFactory(http)
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
