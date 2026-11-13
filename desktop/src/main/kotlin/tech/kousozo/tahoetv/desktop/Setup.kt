package tech.kousozo.tahoetv.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tech.kousozo.tahoetv.core.LocalTv
import tech.kousozo.tahoetv.core.OnboardingText
import tech.kousozo.tahoetv.core.Prayer
import tech.kousozo.tahoetv.core.Region
import tech.kousozo.tahoetv.core.Sources
import tech.kousozo.tahoetv.core.Viewer
import tech.kousozo.tahoetv.core.Web

private enum class Step { Language, Country, City, TimeZone, Prayer, Countries, Categories, Favorites, Order, Summary }

/** Onboarding (#77), same steps and texts as the phone: language always first, then country, city, time zone, prayer,
 *  channel countries, categories, favourites, their order, and a summary. Reopened from Settings → Wilayah & bahasa. */
@Composable
fun SetupScreen(onDone: () -> Unit) {
    val (suggestedCountry, suggestedLang) = remember { Region.suggest() }
    var v by remember {
        mutableStateOf(Settings.viewer.takeIf { it.done } ?: Viewer.suggested(suggestedCountry, suggestedLang).copy(favorites = Settings.favorites.toList()))
    }
    var step by remember { mutableStateOf(Step.Language) }
    var catalog by remember { mutableStateOf<List<LocalTv.Entry>?>(null) }
    val t = { k: String -> OnboardingText.get(v.language, k) }
    fun go(d: Int) { step = Step.entries[(step.ordinal + d).coerceIn(0, Step.entries.size - 1)] }

    Box(Modifier.fillMaxSize()) {
        Aurora()
        Column(Modifier.align(Alignment.Center).widthIn(max = 640.dp).fillMaxHeight().padding(vertical = 36.dp, horizontal = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BrandTile(40.dp)
                Text(t("step").replace("%s", "${step.ordinal + 1}/${Step.entries.size}"), color = Tahoe.Dim, fontSize = 13.sp)
            }
            LinearProgressIndicator(progress = { (step.ordinal + 1f) / Step.entries.size }, modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                color = Tahoe.Accent, trackColor = Tahoe.Glass)
            val (title, sub) = when (step) {
                Step.Language -> "lang.title" to "lang.sub"; Step.Country -> "country.title" to "country.sub"
                Step.City -> "city.title" to "city.sub"; Step.TimeZone -> "tz.title" to "tz.sub"; Step.Prayer -> "prayer.title" to "prayer.sub"
                Step.Countries -> "countries.title" to "countries.sub"; Step.Categories -> "cats.title" to "cats.sub"
                Step.Favorites -> "favs.title" to "favs.sub"; Step.Order -> "order.title" to "order.sub"; Step.Summary -> "summary.title" to "summary.sub"
            }
            Text(t(title), color = Tahoe.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(t(sub), color = Tahoe.Dim, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (step) {
                    Step.Language -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Region.LANGUAGES.forEach { l -> Option(OnboardingText.LANGUAGE_NAMES.getValue(l), null, l == v.language) { v = v.copy(language = l) } }
                    }
                    Step.Country -> CountryList(v.language, listOf(v.country), suggestedCountry, multi = false) { cc ->
                        v = if (cc == v.country) v else v.copy(country = cc, prayer = Region.prayerByDefault(cc), prayerMethod = Prayer.methodFor(cc),
                            channelCountries = listOf(cc), city = "", lat = null, lon = null, region = "", timeZone = "")
                    }
                    Step.City -> CityStep(v, t) { v = it }
                    Step.TimeZone -> TimeZoneStep(v, t) { v = v.copy(timeZone = it) }
                    Step.Prayer -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t("prayer.on"), color = Tahoe.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Switch(v.prayer, { v = v.copy(prayer = it) })
                        }
                        if (v.prayer && v.lat == null) Text(t("prayer.needcity"), color = Tahoe.Faint, fontSize = 12.sp)
                        if (v.prayer) {
                            Text(t("prayer.method"), color = Tahoe.Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                            Prayer.METHODS.forEach { m -> Option(m.name, null, m.id == v.prayerMethod) { v = v.copy(prayerMethod = m.id) } }
                        }
                    }
                    Step.Countries -> CountryList(v.language, v.countries, v.country, multi = true) { cc ->
                        val next = if (cc in v.countries) v.countries - cc else v.countries + cc
                        if (next.isNotEmpty()) v = v.copy(channelCountries = next)
                    }
                    Step.Categories -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LocalTv.Category.entries.filter { it != LocalTv.Category.Other && (it != LocalTv.Category.Tvri && it != LocalTv.Category.Near || Region.HOME in v.countries) }
                            .forEach { cat ->
                                val on = cat.name in v.categories
                                Option(OnboardingText.category(v.language, cat), null, on) { v = v.copy(categories = if (on) v.categories - cat.name else v.categories + cat.name) }
                            }
                    }
                    Step.Favorites -> FavoritesStep(v, t, { catalog = it }) { v = v.copy(favorites = it) }
                    Step.Order -> OrderStep(v, t, catalog) { v = v.copy(favorites = it) }
                    Step.Summary -> Summary(v, t)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 14.dp)) {
                if (step != Step.Language) GhostButton(t("back")) { go(-1) }
                else if (Settings.viewer.done) GhostButton(t("back")) { onDone() } // reopened from Settings: leave unchanged
                Spacer(Modifier.weight(1f))
                if (step == Step.Summary) PrimaryButton(t("done"), Icons.Rounded.Check) {
                    val reload = v.countries != Settings.viewer.countries
                    if (reload) PlayerWindow.stop()
                    Settings.viewer = v
                    onDone()
                } else PrimaryButton(if (step == Step.City && v.lat == null) t("skip") else t("next"), Icons.Rounded.ArrowForward) { go(1) }
            }
        }
    }
}

@Composable
private fun CountryList(lang: String, chosen: List<String>, suggested: String, multi: Boolean, onPick: (String) -> Unit) = Column {
    var query by remember { mutableStateOf("") }
    val all = remember(lang) { Region.countries(lang) }
    val start = remember { chosen.toSet() } // order fixed while open: a clicked row doesn't jump under the cursor
    val shown = remember(all, query) {
        val q = query.trim().lowercase()
        all.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.code.lowercase() == q }
            .sortedBy { if (it.code in start) 0 else if (it.code == suggested) 1 else 2 }
    }
    Field(query, { query = it }, OnboardingText.get(lang, "search"))
    Spacer(Modifier.height(10.dp))
    val listState = rememberLazyListState()
    LaunchedEffect(query) { listState.scrollToItem(0) }
    LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(shown, key = { it.code }) { co -> Option(co.name, co.flag, co.code in chosen) { onPick(co.code) } }
    }
    if (!multi && chosen.isNotEmpty()) {
        val cc = chosen.first()
        Text(OnboardingText.get(lang, if (cc == Region.HOME) "note.home" else "note.other").replace("%s", Region.name(cc, lang)),
            color = Tahoe.Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun CityStep(v: Viewer, t: (String) -> String, onChange: (Viewer) -> Unit) = Column {
    var query by remember { mutableStateOf(v.city) }
    var results by remember { mutableStateOf<List<Web.City>?>(null) }
    LaunchedEffect(query) {
        if (query.trim().length < 2 || query == v.city) { results = null; return@LaunchedEffect }
        delay(400)
        results = withContext(Dispatchers.IO) { Web.searchPlaces(query.trim(), v.language, v.country) }
    }
    Field(query, { query = it }, t("city.search"))
    Spacer(Modifier.height(10.dp))
    if (v.lat != null) Option(listOf(v.city, Region.name(v.country, v.language)).joinToString(", "), "📍", true) {}
    else if (results == null) Text(t("city.none"), color = Tahoe.Faint, fontSize = 12.sp)
    results?.let { list ->
        if (list.isEmpty()) Text(t("city.empty"), color = Tahoe.Faint, fontSize = 12.sp)
        LazyColumn(Modifier.weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list) { city ->
                Option(city.label, "📍", false) {
                    onChange(v.copy(city = city.name, lat = city.lat, lon = city.lon, region = city.region, timeZone = city.timeZone.ifBlank { v.timeZone }))
                    query = city.name; results = null
                }
            }
        }
    }
}

@Composable
private fun TimeZoneStep(v: Viewer, t: (String) -> String, onPick: (String) -> Unit) = Column {
    val device = remember { java.util.TimeZone.getDefault().id }
    var query by remember { mutableStateOf("") }
    val zones = remember { java.time.ZoneId.getAvailableZoneIds().filter { '/' in it && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }.sorted() }
    val picked = v.timeZone.ifBlank { device }
    Option(t("tz.device").replace("%s", device), "💻", v.timeZone.isBlank() || v.timeZone == device) { onPick("") }
    Spacer(Modifier.height(6.dp))
    Field(query, { query = it }, t("search"))
    Spacer(Modifier.height(8.dp))
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val q = query.trim().lowercase().replace(' ', '_')
        items(zones.filter { q.isEmpty() || it.lowercase().contains(q) }.sortedBy { if (it == picked) 0 else 1 }) { z ->
            Option(z.replace('_', ' ') + "  " + offset(z), null, z == picked && v.timeZone.isNotBlank()) { onPick(z) }
        }
    }
}

private fun offset(zone: String): String = runCatching {
    val o = java.time.ZoneId.of(zone).rules.getOffset(java.time.Instant.now())
    "UTC" + if (o.totalSeconds == 0) "" else o.id
}.getOrDefault("")

@Composable
private fun FavoritesStep(v: Viewer, t: (String) -> String, onLoaded: (List<LocalTv.Entry>?) -> Unit, onChange: (List<String>) -> Unit) = Column {
    var list by remember(v.countries, v.categories) { mutableStateOf<List<LocalTv.Entry>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(v.countries, v.categories) {
        list = null; failed = false
        val loaded = withContext(Dispatchers.IO) {
            runCatching { LocalTv.arrange(Sources.loadCountries(Settings.playlist, v.countries), v.region.takeIf { Region.HOME in v.countries }) }.getOrNull()
        }
        list = loaded?.let { all -> if (v.categories.isEmpty()) all else all.filter { it.category.name in v.categories } }
        failed = loaded.isNullOrEmpty()
        onLoaded(loaded)
    }
    var query by remember { mutableStateOf("") }
    Field(query, { query = it }, t("search"))
    Spacer(Modifier.height(8.dp))
    when {
        list == null && !failed -> Column { Text(t("favs.loading"), color = Tahoe.Dim, fontSize = 13.sp); LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = Tahoe.Accent, trackColor = Tahoe.Glass) }
        failed -> Text(t("favs.failed"), color = Tahoe.Faint, fontSize = 13.sp)
        else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val q = query.trim().lowercase()
            items(list!!.filter { q.isEmpty() || it.name.lowercase().contains(q) }.take(400), key = { it.channel.url }) { e ->
                val on = e.channel.url in v.favorites
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) Tahoe.Accent.copy(alpha = .14f) else Tahoe.Glass)
                    .clickable { onChange(if (on) v.favorites - e.channel.url else v.favorites + e.channel.url) }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val logo = rememberImage(e.channel.logo)
                    Box(Modifier.size(34.dp).clip(CircleShape).background(Tahoe.Glass), contentAlignment = Alignment.Center) {
                        if (logo != null) Image(logo, null, Modifier.size(26.dp), contentScale = ContentScale.Fit)
                    }
                    Text(e.name, color = Tahoe.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Icon(if (on) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, tint = if (on) Tahoe.Bad else Tahoe.Dim)
                }
            }
        }
    }
}

@Composable
private fun OrderStep(v: Viewer, t: (String) -> String, catalog: List<LocalTv.Entry>?, onChange: (List<String>) -> Unit) {
    if (v.favorites.isEmpty()) { Text(t("order.empty"), color = Tahoe.Faint, fontSize = 13.sp); return }
    val names = remember(catalog) { catalog.orEmpty().associate { it.channel.url to it.name } }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(v.favorites, key = { _, u -> u }) { i, url ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Tahoe.Glass).padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = Tahoe.Dim, fontSize = 13.sp, modifier = Modifier.width(28.dp))
                Text(names[url] ?: url.substringAfterLast('/').substringBefore('.'), color = Tahoe.Text, fontSize = 14.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                IconButton({ if (i > 0) onChange(v.favorites.toMutableList().apply { add(i - 1, removeAt(i)) }) }) { Icon(Icons.Rounded.KeyboardArrowUp, "▲", tint = Tahoe.Text) }
                IconButton({ if (i < v.favorites.size - 1) onChange(v.favorites.toMutableList().apply { add(i + 1, removeAt(i)) }) }) { Icon(Icons.Rounded.KeyboardArrowDown, "▼", tint = Tahoe.Text) }
            }
        }
    }
}

@Composable
private fun Summary(v: Viewer, t: (String) -> String) = Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    listOf(
        "sum.language" to OnboardingText.LANGUAGE_NAMES.getValue(v.language),
        "sum.country" to Region.flag(v.country) + " " + Region.name(v.country, v.language),
        "sum.city" to v.city.ifBlank { t("sum.none") },
        "sum.tz" to v.timeZone.ifBlank { java.util.TimeZone.getDefault().id },
        "sum.prayer" to if (v.prayer) Prayer.name(v.prayerMethod) else t("sum.off"),
        "sum.channels" to v.countries.joinToString("  ") { Region.flag(it) + " " + Region.name(it, v.language) },
        "sum.cats" to if (v.categories.isEmpty()) t("sum.all") else LocalTv.Category.entries.filter { it.name in v.categories }.joinToString(", ") { OnboardingText.category(v.language, it) },
        "sum.favs" to if (v.favorites.isEmpty()) t("sum.none") else v.favorites.size.toString(),
    ).forEach { (k, value) ->
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Tahoe.Glass).border(1.dp, Tahoe.Stroke, RoundedCornerShape(14.dp)).padding(14.dp)) {
            Text(t(k), color = Tahoe.Dim, fontSize = 12.sp)
            Text(value, color = Tahoe.Text, fontSize = 15.sp)
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
