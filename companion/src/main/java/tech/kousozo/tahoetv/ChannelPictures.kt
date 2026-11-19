package tech.kousozo.tahoetv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import tech.kousozo.tahoetv.core.LocalTv
import tech.kousozo.tahoetv.core.MediaScraper

/**
 * Real pictures for channels and programmes, found on the web: the channel logo (playlist logo first, then Wikipedia, then an image
 * search) and a photo for the show on now. Looked up only for what is on screen, two at a time, cached on disk by [MediaScraper] and
 * [NetImages], so scrolling never floods the network.
 */
object ChannelPictures {
    private val gate = Semaphore(2)

    suspend fun logoUrl(channelName: String): String? =
        gate.withPermit { withContext(Dispatchers.IO) { runCatching { MediaScraper.channelLogo(channelName, null) }.getOrNull() } }

    suspend fun photoUrl(programme: String, channelName: String): String? =
        gate.withPermit { withContext(Dispatchers.IO) { runCatching { MediaScraper.programmeThumbnail(programme, channelName) }.getOrNull() } }
}

/** The channel's logo: from its playlist, and when that is missing or dead, from Wikipedia / the web. */
@Composable
fun rememberChannelLogo(e: LocalTv.Entry): ImageBitmap? {
    val own = rememberNetImage(e.channel.logo)
    var found by remember(e.name) { mutableStateOf<String?>(null) }
    LaunchedEffect(e.name, own == null) {
        if (own != null) return@LaunchedEffect
        delay(1_500) // give the playlist's own logo time to load first
        found = ChannelPictures.logoUrl(e.name)
    }
    return own ?: rememberNetImage(found)
}

/** A real photo for [programme] on [e]'s channel (Wikipedia or the web), or null while loading or when there is none. */
@Composable
fun rememberProgrammePhoto(e: LocalTv.Entry, programme: String?): ImageBitmap? {
    var url by remember(e.name, programme) { mutableStateOf<String?>(null) }
    LaunchedEffect(e.name, programme) {
        if (programme.isNullOrBlank()) return@LaunchedEffect
        delay(400)
        url = ChannelPictures.photoUrl(programme, e.name)
    }
    return rememberNetImage(url, maxPx = 640)
}

/** The latest real frame of [e]'s stream (saved by the live preview), the most truthful picture a channel can have. */
@Composable
fun rememberSnapshot(e: LocalTv.Entry): ImageBitmap? {
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val v = MobileLivePreview.snapVersion
    val img by androidx.compose.runtime.produceState<ImageBitmap?>(null, e.channel.url, v) {
        value = withContext(Dispatchers.IO) { MobileLivePreview.loadSnapshot(ctx, e.channel.url)?.asImageBitmap() }
    }
    return img
}
