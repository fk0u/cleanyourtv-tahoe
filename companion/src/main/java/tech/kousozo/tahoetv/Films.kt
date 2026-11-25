package tech.kousozo.tahoetv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.kousozo.tahoetv.core.FilmLibrary
import tech.kousozo.tahoetv.core.I18n
import tech.kousozo.tahoetv.core.LocalTv
import tech.kousozo.tahoetv.core.MediaScraper

/** Film & Series: what comes from pixeldrain or a film/series group (never in Live TV). Posters are looked up from the title. */
@Composable
fun FilmsPage() {
    val c = LocalContext.current
    val lang = PhoneRegion.language
    BackHandler { Nav.page = null }
    LaunchedEffect(Unit) { Channels.load(c) }
    val lib = Channels.library
    var picked by remember { mutableStateOf<FilmLibrary.Title?>(null) }
    fun play(item: FilmLibrary.Item, title: String) {
        val ch = item.channel
        Playback.play(c, LocalTv.Entry(ch.copy(name = title), title, LocalTv.Category.Vod, false, false, false, 1000))
        Nav.playUrl = null; Nav.page = null; Nav.tab = 1
    }
    LazyColumn(
        Modifier.fillMaxSize().background(Tahoe.Bg).statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { BackCircle(I18n.get(lang, "common.back")) { Nav.page = null } }
        item { Text(I18n.get(lang, "tv.mh_tab_film"), fontSize = 28.sp, color = Tahoe.Text, modifier = Modifier.padding(start = 4.dp)) }
        if (lib.isEmpty()) item { Note(I18n.get(lang, "tv.film_empty"), Icons.Rounded.Movie) }
        for ((label, kind) in listOf(I18n.get(lang, "tv.film_movies") to FilmLibrary.Kind.Movie, I18n.get(lang, "tv.film_series") to FilmLibrary.Kind.Series)) {
            val rows = lib.filter { it.kind == kind }.chunked(3)
            if (rows.isEmpty()) continue
            item(key = "h-$label") { Text(label, fontSize = 20.sp, color = Tahoe.Text, modifier = Modifier.padding(start = 4.dp, top = 6.dp)) }
            items(rows, key = { "$label-${it.first().key}" }) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (t in row) PosterCard(t, Modifier.weight(1f)) {
                        if (t.items.size > 1) picked = t else play(t.items.first(), t.title)
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    picked?.let { t ->
        AlertDialog(
            onDismissRequest = { picked = null },
            confirmButton = { TextButton({ picked = null }) { Text(I18n.get(lang, "common.back")) } },
            title = { Text(t.title) },
            text = {
                LazyColumn { items(t.items) { i ->
                    Text(FilmLibrary.episodeLabel(i).ifBlank { i.channel.name }, color = Tahoe.Text,
                        modifier = Modifier.fillMaxWidth().clickable { picked = null; play(i, t.title) }.padding(vertical = 12.dp))
                } }
            },
        )
    }
}

@Composable
private fun PosterCard(t: FilmLibrary.Title, modifier: Modifier, onClick: () -> Unit) {
    var url by remember(t.key) { mutableStateOf<String?>(null) }
    LaunchedEffect(t.key) {
        url = withContext(Dispatchers.IO) { runCatching { MediaScraper.poster(t.title, t.year, t.kind == FilmLibrary.Kind.Series) }.getOrNull() }
    }
    val img = rememberNetImage(url, 500)
    Column(modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)).background(Tahoe.Surface), contentAlignment = Alignment.Center) {
            if (img != null) Image(img, t.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(t.title, color = Tahoe.Text, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(10.dp), maxLines = 5, overflow = TextOverflow.Ellipsis)
        }
        Text(t.title, color = Tahoe.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
