package tech.kousozo.tahoetv.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.kousozo.tahoetv.core.FilmLibrary
import tech.kousozo.tahoetv.core.I18n
import tech.kousozo.tahoetv.core.MediaScraper
import tech.kousozo.tahoetv.core.Sources

/** The movies and series found in the playlists (pixeldrain, film/series groups): never part of Live. */
object DeskLibrary { var titles by mutableStateOf<List<FilmLibrary.Title>>(emptyList()) }

private val posterCache = java.util.Collections.synchronizedMap(HashMap<String, ImageBitmap>())

private fun fetchPoster(t: FilmLibrary.Title): ImageBitmap? = runCatching {
    posterCache[t.key]?.let { return@runCatching it }
    val url = MediaScraper.poster(t.title, t.year, t.kind == FilmLibrary.Kind.Series) ?: return@runCatching null
    val c = java.net.URI(url).toURL().openConnection().apply { connectTimeout = 6000; readTimeout = 10000; setRequestProperty("User-Agent", "TahoeTV") }
    val bytes = c.getInputStream().use { it.readNBytes(6_000_000) }
    org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap().also { posterCache[t.key] = it }
}.getOrNull()

@Composable
fun FilmsScreen() {
    val lang = Settings.lang
    var picked by remember { mutableStateOf<FilmLibrary.Title?>(null) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (DeskLibrary.titles.isNotEmpty()) return@LaunchedEffect
        loading = true
        withContext(Dispatchers.IO) {
            runCatching {
                val raw = Sources.loadChannels(Settings.playlist, Settings.viewer.countries, Settings.viewer.channelLanguages) {}
                DeskLibrary.titles = FilmLibrary.group(raw)
            }
        }
        loading = false
    }
    fun play(t: FilmLibrary.Title, start: Int) {
        var at = start
        fun open() {
            val item = t.items[at]
            val label = FilmLibrary.episodeLabel(item)
            PlayerWindow.play(listOf(item.channel), if (label.isBlank()) t.title else "${t.title} · $label", emptyList(),
                onSwitch = { d -> javax.swing.SwingUtilities.invokeLater { at = ((at + d) % t.items.size + t.items.size) % t.items.size; open() } }) { }
        }
        open()
    }
    val titles = DeskLibrary.titles
    Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 28.dp, top = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(I18n.get(lang, "tv.mh_tab_film"), fontSize = 28.sp, color = Tahoe.Text)
        if (titles.isEmpty()) Text(if (loading) "…" else I18n.get(lang, "tv.film_empty"), color = Tahoe.Text)
        LazyVerticalGrid(GridCells.Adaptive(150.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            for ((label, kind) in listOf(I18n.get(lang, "tv.film_movies") to FilmLibrary.Kind.Movie, I18n.get(lang, "tv.film_series") to FilmLibrary.Kind.Series)) {
                val part = titles.filter { it.kind == kind }
                if (part.isEmpty()) continue
                item(span = { GridItemSpan(maxLineSpan) }, key = "h-$label") { Text(label, fontSize = 20.sp, color = Tahoe.Text) }
                items(part, key = { it.key }) { t -> Poster(t) { if (t.items.size > 1) picked = t else play(t, 0) } }
            }
        }
    }
    picked?.let { t ->
        AlertDialog(
            onDismissRequest = { picked = null },
            confirmButton = { TextButton({ picked = null }) { Text("OK") } },
            title = { Text(t.title) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScrollCompat()) {
                    t.items.forEachIndexed { i, item ->
                        Text(FilmLibrary.episodeLabel(item).ifBlank { item.channel.name }, modifier = Modifier.fillMaxWidth().clickable { picked = null; play(t, i) }.padding(vertical = 10.dp))
                    }
                }
            },
        )
    }
}

@Composable
private fun Modifier.verticalScrollCompat(): Modifier = this.then(Modifier.verticalScroll(rememberScrollState()))

@Composable
private fun Poster(t: FilmLibrary.Title, onClick: () -> Unit) {
    var img by remember(t.key) { mutableStateOf(posterCache[t.key]) }
    LaunchedEffect(t.key) { if (img == null) img = withContext(Dispatchers.IO) { fetchPoster(t) } }
    Column(Modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)).background(Tahoe.Surface), contentAlignment = Alignment.Center) {
            val b = img
            if (b != null) Image(b, t.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(t.title, color = Tahoe.Text, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(10.dp), maxLines = 5, overflow = TextOverflow.Ellipsis)
        }
        Text(t.title, color = Tahoe.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
