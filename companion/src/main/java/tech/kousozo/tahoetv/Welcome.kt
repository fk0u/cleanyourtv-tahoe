package tech.kousozo.tahoetv

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.kousozo.tahoetv.core.Region

/** The viewer's country + language on this phone (#71). Blank country = first-run setup not done yet. */
object PhoneRegion {
    private fun p(c: Context) = c.getSharedPreferences("region", 0)
    var country by mutableStateOf(""); private set
    var language by mutableStateOf("en"); private set

    fun load(c: Context) {
        country = Region.country(p(c).getString("country", null)).orEmpty()
        language = Region.language(p(c).getString("language", null) ?: java.util.Locale.getDefault().language)
    }
    fun save(c: Context, country: String, language: String) {
        p(c).edit().putString("country", country).putString("language", language).apply()
        val changed = country != this.country
        this.country = country; this.language = language
        if (changed) Channels.reload(c) // a new country = a new channel list and guide
    }
    /** Reopen the setup (Beranda → Wilayah). */
    fun reset() { country = "" }
    val code get() = country.ifBlank { Region.HOME }
}

/** First run: language, then country. The rest of the app is still Indonesian until it's translated (#56). */
@Composable
fun WelcomeScreen() {
    val c = LocalContext.current
    val (suggestedCountry, suggestedLang) = remember { Region.suggest() }
    var lang by remember { mutableStateOf(if (PhoneRegion.country.isBlank()) suggestedLang else PhoneRegion.language) }
    var country by remember { mutableStateOf(Region.country(PhoneRegion.country) ?: suggestedCountry) }
    var step by remember { mutableStateOf(0) }
    val t = WELCOME.getValue(lang)

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        BrandTile(56.dp)
        Spacer(Modifier.height(16.dp))
        Text(t.getValue(if (step == 0) "lang.title" else "country.title"), color = Tahoe.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(t.getValue(if (step == 0) "lang.sub" else "country.sub"), color = Tahoe.Dim, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, bottom = 20.dp))
        if (step == 0) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Region.LANGUAGES.forEach { l -> RegionOption(LANG_NAME.getValue(l), null, l == lang) { lang = l } }
            }
            Spacer(Modifier.weight(1f))
            PrimaryButton(t.getValue("next"), icon = Icons.Rounded.ArrowForward) { step = 1 }
        } else {
            var query by remember { mutableStateOf("") }
            val all = remember(lang) { Region.countries(lang) }
            val shown = remember(all, query, country) {
                val q = query.trim().lowercase()
                all.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.code.lowercase() == q }
                    .sortedBy { if (it.code == country) 0 else if (it.code == suggestedCountry) 1 else 2 }
            }
            Field(query, { query = it }, t.getValue("search"))
            Spacer(Modifier.height(10.dp))
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                LaunchedEffect(query) { listState.scrollToItem(0) } // new search: start at the top
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown, key = { it.code }) { co -> RegionOption(co.name, co.flag, co.code == country) { country = co.code } }
            }
            Text(t.getValue(if (country == Region.HOME) "note.home" else "note.other").replace("%s", Region.name(country, lang)),
                color = Tahoe.Faint, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
            // both buttons are full width on the phone: stacked, the main action first
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(t.getValue("done"), icon = Icons.Rounded.Check) { PhoneRegion.save(c, country, lang) }
                SecondaryButton(t.getValue("back")) { step = 0 }
            }
        }
    }
}

@Composable
private fun RegionOption(text: String, flag: String?, selected: Boolean, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(if (selected) Tahoe.Text.copy(alpha = .14f) else Tahoe.Glass)
        .border(1.dp, if (selected) Tahoe.Text.copy(alpha = .5f) else Tahoe.Stroke, RoundedCornerShape(16.dp))
        .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    flag?.let { Text(it, fontSize = 20.sp, modifier = Modifier.padding(end = 12.dp)) }
    Text(text, color = Tahoe.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
    if (selected) Icon(Icons.Rounded.Check, null, tint = Tahoe.Text)
}

private val LANG_NAME = mapOf("en" to "English", "id" to "Bahasa Indonesia")

private val WELCOME = mapOf(
    "en" to mapOf(
        "lang.title" to "Welcome to Tahoe TV", "lang.sub" to "Choose your language",
        "country.title" to "Where are you watching?", "country.sub" to "Free channels and the TV guide for your country",
        "search" to "Search country", "next" to "Continue", "back" to "Back", "done" to "Start watching",
        "note.home" to "%s includes curated extra sources, regional channels and prayer times.",
        "note.other" to "Free channels for %s come from iptv-org. Some countries have only a few.",
    ),
    "id" to mapOf(
        "lang.title" to "Selamat datang di Tahoe TV", "lang.sub" to "Pilih bahasa",
        "country.title" to "Kamu menonton dari mana?", "country.sub" to "Saluran gratis dan jadwal acara untuk negaramu",
        "search" to "Cari negara", "next" to "Lanjut", "back" to "Kembali", "done" to "Mulai menonton",
        "note.home" to "%s punya sumber tambahan pilihan, saluran daerah, dan jadwal shalat.",
        "note.other" to "Saluran gratis untuk %s diambil dari iptv-org. Beberapa negara salurannya sedikit.",
    ),
)
