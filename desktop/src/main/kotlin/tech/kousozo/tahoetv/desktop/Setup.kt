package tech.kousozo.tahoetv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.kousozo.tahoetv.core.Region

/** First run (#71): language, then country. Picks the channel list and guide; can be changed later in Settings.
 *  The rest of the app is still in Indonesian until it's translated (#56), so this screen carries its own text. */
@Composable
fun SetupScreen(onDone: () -> Unit) {
    val (suggestedCountry, suggestedLang) = remember { Region.suggest() }
    var lang by remember { mutableStateOf(Region.language(Settings.language.ifBlank { suggestedLang })) }
    var country by remember { mutableStateOf(Region.country(Settings.country) ?: suggestedCountry) }
    var step by remember { mutableStateOf(0) }
    val startCountry = remember { country } // list order fixed while open: a clicked row doesn't jump under the cursor
    val t = TEXT.getValue(lang)

    Box(Modifier.fillMaxSize()) {
        Aurora()
        Column(Modifier.align(Alignment.Center).widthIn(max = 560.dp).fillMaxHeight().padding(vertical = 48.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            BrandTile(64.dp)
            Spacer(Modifier.height(18.dp))
            Text(t.getValue(if (step == 0) "lang.title" else "country.title"), color = Tahoe.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(t.getValue(if (step == 0) "lang.sub" else "country.sub"), color = Tahoe.Dim, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp, bottom = 22.dp))

            if (step == 0) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Region.LANGUAGES.forEach { l -> Option(LANG_NAMES.getValue(l), null, l == lang) { lang = l } }
                }
                Spacer(Modifier.weight(1f))
                PrimaryButton(t.getValue("next"), Icons.Rounded.ArrowForward) { step = 1 }
            } else {
                var query by remember { mutableStateOf("") }
                val all = remember(lang) { Region.countries(lang) }
                val shown = remember(all, query) {
                    val q = query.trim().lowercase()
                    val match = all.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.code.lowercase() == q }
                    // the chosen / suggested country first, so it's visible without searching
                    match.sortedBy { if (it.code == startCountry) 0 else if (it.code == suggestedCountry) 1 else 2 }
                }
                Field(query, { query = it }, t.getValue("search"))
                Spacer(Modifier.height(10.dp))
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                LaunchedEffect(query) { listState.scrollToItem(0) } // new search: start at the top
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(shown, key = { it.code }) { c -> Option(c.name, c.flag, c.code == country) { country = c.code } }
                }
                Text(t.getValue(if (country == Region.HOME) "note.home" else "note.other").replace("%s", Region.name(country, lang)),
                    color = Tahoe.Faint, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton(t.getValue("back")) { step = 0 }
                    PrimaryButton(t.getValue("done"), Icons.Rounded.Check) {
                        Settings.language = lang
                        Settings.country = country
                        onDone()
                    }
                }
            }
        }
    }
}

@Composable
private fun Option(text: String, flag: String?, selected: Boolean, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(if (selected) Tahoe.Accent.copy(alpha = .18f) else Tahoe.Glass)
        .border(1.dp, if (selected) Tahoe.Accent else Tahoe.Stroke, RoundedCornerShape(14.dp))
        .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    flag?.let { Text(it, fontSize = 20.sp, modifier = Modifier.padding(end = 12.dp)) }
    Text(text, color = Tahoe.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
    if (selected) Icon(Icons.Rounded.Check, null, tint = Tahoe.Accent)
}

/** Each language's own name, so people find theirs whatever is selected. */
private val LANG_NAMES = mapOf("en" to "English", "id" to "Bahasa Indonesia")

private val TEXT = mapOf(
    "en" to mapOf(
        "lang.title" to "Welcome to Tahoe TV", "lang.sub" to "Choose your language",
        "country.title" to "Where are you watching?", "country.sub" to "We'll load free channels and the TV guide for your country",
        "search" to "Search country", "next" to "Continue", "back" to "Back", "done" to "Start watching",
        "note.home" to "%s includes curated extra sources, regional channels and prayer times.",
        "note.other" to "Free channels for %s come from iptv-org. Some countries have few channels; you can add your own playlist in Settings.",
    ),
    "id" to mapOf(
        "lang.title" to "Selamat datang di Tahoe TV", "lang.sub" to "Pilih bahasa",
        "country.title" to "Kamu menonton dari mana?", "country.sub" to "Kami memuat saluran gratis dan jadwal acara untuk negaramu",
        "search" to "Cari negara", "next" to "Lanjut", "back" to "Kembali", "done" to "Mulai menonton",
        "note.home" to "%s punya sumber tambahan pilihan, saluran daerah, dan jadwal shalat.",
        "note.other" to "Saluran gratis untuk %s diambil dari iptv-org. Beberapa negara salurannya sedikit; kamu bisa menambah playlist sendiri di Setelan.",
    ),
)
