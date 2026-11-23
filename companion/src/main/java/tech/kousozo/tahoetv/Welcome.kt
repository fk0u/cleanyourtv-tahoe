package tech.kousozo.tahoetv

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.LocationCity
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tech.kousozo.tahoetv.core.I18n
import tech.kousozo.tahoetv.core.LocalTv
import tech.kousozo.tahoetv.core.OnboardingText
import tech.kousozo.tahoetv.core.Prayer
import tech.kousozo.tahoetv.core.Region
import tech.kousozo.tahoetv.core.Sources
import tech.kousozo.tahoetv.core.Viewer
import tech.kousozo.tahoetv.core.Web

/** The viewer's onboarding choices on this phone (#71, #77): language, country, city, time zone, prayer, channel
 *  countries, categories, favourites and their order. Blank country = onboarding not done yet. */
object PhoneRegion {
    private fun p(c: Context) = c.getSharedPreferences("region", 0)
    var viewer by mutableStateOf(Viewer()); private set
    val country get() = viewer.country
    val language get() = viewer.language

    fun load(c: Context) {
        val saved = p(c).getString("viewer", null)
        viewer = if (saved != null) Viewer.fromJson(saved) else {
            // from the earlier two-step setup (country + language only)
            val old = Region.country(p(c).getString("country", null))
            if (old == null) Viewer(language = Region.language(java.util.Locale.getDefault().language))
            else Viewer.suggested(old, Region.language(p(c).getString("language", null))).copy(favorites = Channels.favs.toList())
        }
    }

    fun save(c: Context, v: Viewer) {
        val before = viewer
        val next = v.copy(revision = maxOf(before.revision, v.revision) + 1L)
        val reload = next.countries != before.countries || next.region != before.region ||
            next.city != before.city || next.country != before.country || !before.done ||
            next.channelLanguages != before.channelLanguages
        p(c).edit().putString("viewer", next.toJson()).apply()
        viewer = next; editing = false
        Channels.setFavs(c, next.favorites)
        if (reload) Channels.reload(c) else Channels.rearrange()
        sendPatchToTv(c, Viewer.diff(before, next))
    }

    fun setSortMode(c: Context, mode: tech.kousozo.tahoetv.core.LocalTv.SortMode) {
        val before = viewer
        if (before.sort == mode) return
        val next = before.copy(sortMode = mode.key, revision = before.revision + 1L)
        p(c).edit().putString("viewer", next.toJson()).apply()
        viewer = next
        Channels.rearrange()
        if (next.done) sendPatchToTv(c, Viewer.diff(before, next))
    }

    /** A paired TV receives only the incremental delta patch over the shared ordered link (#111). */
    private fun sendPatchToTv(c: Context, patch: org.json.JSONObject) {
        val sp = Sync.prefs(c)
        if (!sp.getBoolean("sync_tv_confirmed", true)) return
        val host = sp.getString("host", "").orEmpty(); val token = sp.getString("token", "").orEmpty()
        if (host.isEmpty() || token.isEmpty()) return
        tech.kousozo.tahoetv.core.TvLink.shared(host, token).viewerPatch(patch)
    }

    /** A heart tapped elsewhere in the app: keep the onboarding's order, new ones last. */
    fun favoritesChanged(c: Context, favs: Set<String>) {
        val before = viewer
        val kept = before.favorites.filter { it in favs }
        val next = before.copy(favorites = kept + (favs - kept.toSet()), revision = before.revision + 1L)
        p(c).edit().putString("viewer", next.toJson()).apply(); viewer = next
        if (next.done) sendPatchToTv(c, Viewer.diff(before, next)) // the paired TV merges the incremental favourites patch
    }

    /** The onboarding is open again from Beranda (current choices preselected). */
    var editing by mutableStateOf(false)
    fun cancel() { editing = false; WelcomeDebug.step = 0 }
    fun reset() { editing = true }
    val needsSetup get() = !viewer.done || editing || WelcomeDebug.step > 0
    val code get() = Region.country(viewer.country) ?: Region.HOME
}

/** Debug-only override so adb debug extras can jump directly to onboarding step 1..3 on a real device. */
object WelcomeDebug {
    var step by mutableIntStateOf(0)
}

private enum class Step { Language, Country, City, TimeZone, Prayer, Countries, Categories, Favorites, Order, Summary }

/** Redesign 4.0 (#179): three visible steps (Bahasa → Wilayah [negara + kota] → Siap). The rest keeps its defaults
 *  and stays one tap away behind "Atur lebih lanjut" on the last step. */
private val QUICK = listOf(Step.Language, Step.Country, Step.Summary)
private val ADVANCED = listOf(Step.Language, Step.Country, Step.City, Step.TimeZone, Step.Prayer, Step.Countries, Step.Categories, Step.Favorites, Step.Order, Step.Summary)
private fun Step.visible() = when (this) { Step.Language -> 1; Step.Summary -> 3; else -> 2 }

/** Onboarding: language always first, then the rest; every step has a sensible default and can be changed later. */
@Composable
fun WelcomeScreen() {
    val c = LocalContext.current
    val (suggestedCountry, suggestedLang) = remember { Region.suggest() }
    var v by remember {
        mutableStateOf(PhoneRegion.viewer.takeIf { it.done } ?: Viewer.suggested(suggestedCountry, suggestedLang).copy(favorites = Channels.favs.toList()))
    }
    var step by remember { mutableStateOf(Step.Language) }
    LaunchedEffect(WelcomeDebug.step) {
        when (WelcomeDebug.step) {
            1 -> step = Step.Language
            2 -> step = Step.Country
            3 -> step = Step.Summary
        }
    }
    var advanced by remember { mutableStateOf(false) }
    val flow = if (advanced) ADVANCED else QUICK
    /** Channels of the chosen countries, loaded on the favourites step (names for the order step too). */
    var catalog by remember { mutableStateOf<List<LocalTv.Entry>?>(null) }
    val t = { k: String -> OnboardingText.get(v.language, k) }
    fun go(d: Int) { step = flow[(flow.indexOf(step) + d).coerceIn(0, flow.size - 1)] }
    androidx.activity.compose.BackHandler(enabled = step != Step.Language || PhoneRegion.viewer.done || WelcomeDebug.step > 0) {
        if (WelcomeDebug.step > 0) WelcomeDebug.step = 0
        else if (step == Step.Language) PhoneRegion.cancel() else go(-1)
    }

    Box(Modifier.fillMaxSize().background(Tahoe.Bg)) {
        if (step == Step.Language && !advanced) {
            // Figma 23:218 top art wash fading into #07080D
            Box(
                Modifier.fillMaxWidth().height(236.dp)
                    .background(Brush.verticalGradient(listOf(Tahoe.Lake.copy(alpha = 0.65f), Tahoe.Accent.copy(alpha = 0.18f), Tahoe.Bg))),
            ) {
                Box(
                    Modifier.align(Alignment.TopCenter).padding(top = 56.dp).size(110.dp).clip(CircleShape)
                        .background(Brush.radialGradient(listOf(Tahoe.Accent.copy(alpha = 0.40f), Color.Transparent))),
                )
                Box(Modifier.align(Alignment.Center).padding(top = 18.dp)) { BrandTile(56.dp) }
                Box(
                    Modifier.fillMaxSize()
                        .background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Tahoe.Bg)),
                )
            }
        } else if (step == Step.Summary && !advanced) {
            ReadyMosaicHero(v)
        }

        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            if (step == Step.Language && !advanced) {
                Spacer(Modifier.height(144.dp))
            } else if (step == Step.Summary && !advanced) {
                Spacer(Modifier.height(216.dp))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (step != Step.Language || PhoneRegion.viewer.done || WelcomeDebug.step > 0) {
                        BackCircle(t("back")) {
                            if (WelcomeDebug.step > 0) WelcomeDebug.step = 0
                            else if (step == Step.Language) PhoneRegion.cancel()
                            else go(-1)
                        }
                    }
                    if (advanced) {
                        BrandTile(32.dp)
                        Text(t("step").replace("%s", "${flow.indexOf(step) + 1}/${flow.size}"), color = Tahoe.Dim, fontSize = 13.sp)
                    }
                }
            }

            // Segmented step bar (.steps): 3 pills (quick) or linear bar (advanced)
            if (advanced) {
                LinearProgressIndicator(
                    progress = { (flow.indexOf(step) + 1f) / flow.size },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    color = Tahoe.Accent,
                    trackColor = Tahoe.Glass,
                )
            } else {
                Row(Modifier.padding(top = 10.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..3).forEach { i ->
                        val active = i == step.visible()
                        val done = i <= step.visible()
                        val w by androidx.compose.animation.core.animateDpAsState(
                            if (active) 28.dp else 8.dp,
                            tween(if (ThemePrefs.reduceMotion) 0 else tech.kousozo.tahoetv.core.Brand.MOTION_FAST, easing = EaseOutTahoe),
                            label = "step",
                        )
                        Box(Modifier.height(8.dp).width(w).clip(RoundedCornerShape(4.dp)).background(if (done) Tahoe.Accent else Tahoe.Glass))
                    }
                }
            }

            val channelTotal = (Channels.entries?.size ?: 0).coerceAtLeast(128)
            val (titleText, subText) = when (step) {
                Step.Language -> t("lang.title") to t("lang.sub")
                Step.Country -> t("country.title") to t("country.sub")
                Step.City -> t("city.title") to t("city.sub")
                Step.TimeZone -> t("tz.title") to t("tz.sub")
                Step.Prayer -> t("prayer.title") to t("prayer.sub")
                Step.Countries -> t("countries.title") to t("countries.sub")
                Step.Categories -> t("cats.title") to t("cats.sub")
                Step.Favorites -> t("favs.title") to t("favs.sub")
                Step.Order -> t("order.title") to t("order.sub")
                Step.Summary -> if (!advanced) t("ready_channels_fmt").replace("%s", "$channelTotal") to t("summary.sub")
                    else t("summary.title") to t("summary.sub")
            }
            Text(titleText, color = Tahoe.Text, fontSize = if (step == Step.Language || step == Step.Summary) 36.sp else 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp, lineHeight = 40.sp, modifier = Modifier.a11yHeading())
            if (!(step == Step.Summary && !advanced)) {
                Text(subText, color = Tahoe.Dim, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
            } else {
                Spacer(Modifier.height(14.dp))
            }

            val rtlDir = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
            AnimatedContent(
                step,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    val forward = flow.indexOf(targetState) >= flow.indexOf(initialState)
                    val dir = (if (forward) 1 else -1) * rtlDir
                    if (ThemePrefs.reduceMotion) {
                        fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                    } else {
                        (slideInHorizontally(tween(tech.kousozo.tahoetv.core.Brand.MOTION_SHEET, easing = EaseDrawerTahoe)) { dir * it / 4 } +
                            fadeIn(tween(tech.kousozo.tahoetv.core.Brand.MOTION_FAST, easing = EaseOutTahoe))) togetherWith
                            (slideOutHorizontally(tween(220, easing = EaseOutTahoe)) { -dir * it / 5 } +
                                fadeOut(tween(160, easing = EaseOutTahoe)))
                    }
                },
                label = "welcome-step",
            ) { currentStep ->
                when (currentStep) {
                    Step.Language -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Region.LANGUAGES.forEach { l -> Option(OnboardingText.LANGUAGE_NAMES.getValue(l), null, l == v.language) { v = v.copy(language = l) } }
                    }
                    Step.Country -> if (!advanced) {
                        CountryAndCityStep(v, suggestedCountry, t) { v = it }
                    } else {
                        CountryList(v.language, listOf(v.country), suggestedCountry, multi = false) { cc ->
                            v = if (cc == v.country) v else v.copy(
                                country = cc,
                                prayer = Region.prayerByDefault(cc),
                                prayerMethod = Prayer.methodFor(cc),
                                channelCountries = listOf(cc),
                                city = "", lat = null, lon = null, region = "",
                                timeZone = tech.kousozo.tahoetv.core.CountryCatalog.defaultZone(cc),
                            )
                        }
                    }
                    Step.City -> CityStep(v, t) { v = it }
                    Step.TimeZone -> TimeZoneStep(v, t) { v = v.copy(timeZone = it) }
                    Step.Prayer -> Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                        SwitchRow(t("prayer.on"), v.prayer) { v = v.copy(prayer = it) }
                        if (v.prayer && v.lat == null) Text(t("prayer.needcity"), color = Tahoe.Faint, fontSize = 12.sp)
                        if (v.prayer) {
                            Text(t("prayer.method"), color = Tahoe.Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                            Prayer.METHODS.forEach { m -> Option(m.name, null, m.id == v.prayerMethod) { v = v.copy(prayerMethod = m.id) } }
                        }
                    }
                    Step.Countries -> CountryList(v.language, v.countries, v.country, multi = true) { cc ->
                        val now = v.countries
                        val next = if (cc in now) now - cc else now + cc
                        if (next.isNotEmpty()) v = v.copy(channelCountries = next)
                    }
                    Step.Categories -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LocalTv.Category.entries.filter { it != LocalTv.Category.Other && (it != LocalTv.Category.Tvri && it != LocalTv.Category.Near || v.countries.contains(Region.HOME)) }
                            .forEach { cat ->
                                val on = cat.name in v.categories
                                Option(OnboardingText.category(v.language, cat), null, on) { v = v.copy(categories = if (on) v.categories - cat.name else v.categories + cat.name) }
                            }
                    }
                    Step.Favorites -> FavoritesStep(v, t, { catalog = it }) { v = v.copy(favorites = it) }
                    Step.Order -> OrderStep(v, t, catalog) { v = v.copy(favorites = it) }
                    Step.Summary -> Summary(v, t, showMore = !advanced, compactQuick = !advanced, onPrayer = { v = v.copy(prayer = it) }) { advanced = true; step = Step.TimeZone }
                }
            }

            var askTvSync by remember { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 12.dp)) {
                if (step == Step.Summary) {
                    AccentPillButton(t("done"), fullWidth = true) {
                        WelcomeDebug.step = 0
                        val sp = Sync.prefs(c)
                        val paired = sp.getString("host", "").orEmpty().isNotBlank() && sp.getString("token", "").orEmpty().isNotEmpty()
                        if (paired && !sp.getBoolean("sync_tv_asked", false)) askTvSync = true
                        else PhoneRegion.save(c, v)
                    }
                    if (!advanced) {
                        Text(t("change_later_note"), color = Tahoe.Dim, fontSize = 14.sp)
                    }
                } else {
                    AccentPillButton(if (step == Step.City && v.lat == null) t("skip") else t("next"), fullWidth = true) { go(1) }
                }
            }
            if (askTvSync) AlertDialog(
                onDismissRequest = { askTvSync = false },
                title = { Text(I18n.get(v.language, "sync.ask_title")) },
                text = { Text(I18n.get(v.language, "sync.ask_body")) },
                confirmButton = {
                    TextButton({
                        Sync.prefs(c).edit().putBoolean("sync_tv_asked", true).putBoolean("sync_tv_confirmed", true).apply()
                        askTvSync = false
                        PhoneRegion.save(c, v)
                    }) { Text(I18n.get(v.language, "sync.ask_yes")) }
                },
                dismissButton = {
                    TextButton({
                        Sync.prefs(c).edit().putBoolean("sync_tv_asked", true).putBoolean("sync_tv_confirmed", false).apply()
                        askTvSync = false
                        PhoneRegion.save(c, v)
                    }) { Text(I18n.get(v.language, "sync.ask_no")) }
                },
            )
        }
    }
}

/** Figma 23:251: combined country & city step in the 3-step QUICK flow. */
@Composable
private fun CountryAndCityStep(v: Viewer, suggestedCountry: String, t: (String) -> String, onChange: (Viewer) -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    var query by remember { mutableStateOf("") }
    var cityResults by remember(v.country) { mutableStateOf<List<Web.City>?>(null) }
    val allCountries = remember(v.language) { Region.countries(v.language) }
    val suggestedCities = remember(v.country, v.language) { tech.kousozo.tahoetv.core.CountryCatalog.suggestedCities(v.country, v.language) }
    LaunchedEffect(v.country, query) {
        val q = query.trim()
        if (q.length < 2) { cityResults = null; return@LaunchedEffect }
        delay(350)
        cityResults = withContext(Dispatchers.IO) { Web.searchPlaces(q, v.language, v.country) }
    }
    SearchPill(query, { query = it }, t("search"), active = query.isNotBlank())
    val filteredCountries = remember(allCountries, query, v.country) {
        val q = query.trim().lowercase()
        allCountries.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.code.lowercase() == q }
            .sortedBy { if (it.code == v.country) 0 else if (it.code == suggestedCountry) 1 else 2 }
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val cities = cityResults
        if (cities != null && cities.isNotEmpty()) {
            items(cities, key = { "city:${it.label}" }) { city ->
                Option(city.label, null, v.city.equals(city.name, ignoreCase = true), icon = Icons.Rounded.Place) {
                    onChange(v.copy(city = city.name, lat = city.lat, lon = city.lon, region = city.region, timeZone = city.timeZone.ifBlank { v.timeZone }))
                    query = ""; cityResults = null
                }
            }
        }
        items(filteredCountries, key = { "cc:${it.code}" }) { co ->
            val subCity = when {
                co.code == v.country && v.city.isNotBlank() -> listOfNotNull(v.city.takeIf { it.isNotBlank() }, v.region.takeIf { it.isNotBlank() && it != v.city }).joinToString(", ")
                else -> tech.kousozo.tahoetv.core.CountryCatalog.suggestedCities(co.code, v.language).firstOrNull()?.city
            }
            Option(co.name, co.flag, co.code == v.country, sub = subCity) {
                val defaultCity = tech.kousozo.tahoetv.core.CountryCatalog.suggestedCities(co.code, v.language).firstOrNull()
                onChange(v.copy(
                    country = co.code,
                    prayer = Region.prayerByDefault(co.code),
                    prayerMethod = Prayer.methodFor(co.code),
                    channelCountries = listOf(co.code),
                    city = defaultCity?.city ?: "",
                    lat = defaultCity?.lat,
                    lon = defaultCity?.lon,
                    region = defaultCity?.region ?: "",
                    timeZone = defaultCity?.zoneId ?: tech.kousozo.tahoetv.core.CountryCatalog.defaultZone(co.code),
                ))
            }
        }
        if (query.isBlank() && suggestedCities.size > 1) {
            items(suggestedCities.drop(1).take(3), key = { "sc:${it.city}" }) { opt ->
                Option(opt.label(), null, v.city.equals(opt.city, ignoreCase = true), icon = Icons.Rounded.Place) {
                    onChange(v.copy(city = opt.city, lat = opt.lat, lon = opt.lon, region = opt.region, timeZone = opt.zoneId))
                }
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).clickable {
            val city = tech.kousozo.tahoetv.core.CountryCatalog.suggestedCities(suggestedCountry, v.language).firstOrNull()
            query = ""
            onChange(v.copy(
                country = suggestedCountry,
                prayer = Region.prayerByDefault(suggestedCountry),
                prayerMethod = Prayer.methodFor(suggestedCountry),
                channelCountries = listOf(suggestedCountry),
                city = city?.city ?: "",
                lat = city?.lat,
                lon = city?.lon,
                region = city?.region ?: "",
                timeZone = city?.zoneId ?: tech.kousozo.tahoetv.core.CountryCatalog.defaultZone(suggestedCountry),
            ))
        }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.LocationOn, null, tint = Tahoe.Accent, modifier = Modifier.size(18.dp))
        Text(t("use_location"), color = Tahoe.Accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/** Figma 23:301: tilted 3x3 mosaic + fade scrim at top of Onboarding 3 Ready screen. */
@Composable
private fun ReadyMosaicHero(v: Viewer) {
    val entries = remember(v.country) {
        Channels.entries?.takeIf { it.isNotEmpty() }.orEmpty() // the real channels once loaded; empty tiles until then
    }
    Box(Modifier.fillMaxWidth().height(256.dp)) {
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 8.dp).rotate(6f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for (row in 0 until 3) {
                Row(
                    Modifier.offset(x = if (row == 1) 18.dp else (-10).dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for (col in 0 until 3) {
                        // fresh install: the channels are not loaded yet, so draw empty tiles (never index an empty list)
                        val tile = Modifier.width(126.dp).height(82.dp).clip(RoundedCornerShape(18.dp))
                        if (entries.isEmpty()) Box(tile.background(Tahoe.Glass))
                        else ChannelArt(entries[(row * 3 + col) % entries.size], tile, logoScale = 0.78f)
                    }
                }
            }
        }
        Box(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(0.32f to Color.Transparent, 1f to Tahoe.Bg)),
        )
    }
}

@Composable
private fun CountryList(lang: String, chosen: List<String>, suggested: String, multi: Boolean, onPick: (String) -> Unit) = Column {
    var query by remember { mutableStateOf("") }
    val all = remember(lang) { Region.countries(lang) }
    val start = remember { chosen.toSet() } // order fixed while open: picking a row doesn't make it jump
    val shown = remember(all, query) {
        val q = query.trim().lowercase()
        all.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.code.lowercase() == q }
            .sortedBy { if (it.code in start) 0 else if (it.code == suggested) 1 else 2 }
    }
    SearchPill(query, { query = it }, OnboardingText.get(lang, "search"), active = query.isNotBlank())
    Spacer(Modifier.height(10.dp))
    val listState = rememberLazyListState()
    LaunchedEffect(query) { listState.scrollToItem(0) }
    LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(shown, key = { it.code }) { co -> Option(co.name, co.flag, co.code in chosen) { onPick(co.code) } }
    }
    if (!multi && chosen.isNotEmpty()) {
        val cc = chosen.first()
        Text(OnboardingText.get(lang, if (cc == Region.HOME) "note.home" else "note.other").replace("%s", Region.name(cc, lang)),
            color = Tahoe.Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun CityStep(v: Viewer, t: (String) -> String, onChange: (Viewer) -> Unit) = Column {
    var query by remember(v.country) { mutableStateOf(v.city) }
    var results by remember(v.country) { mutableStateOf<List<Web.City>?>(null) }
    val suggested = remember(v.country, v.language) { tech.kousozo.tahoetv.core.CountryCatalog.suggestedCities(v.country, v.language) }
    LaunchedEffect(v.country, query) {
        if (query.trim().length < 2 || query == v.city) { results = null; return@LaunchedEffect }
        delay(400) // typing: wait for a pause
        results = withContext(Dispatchers.IO) { Web.searchPlaces(query.trim(), v.language, v.country) }
    }
    SearchPill(query, { query = it }, tech.kousozo.tahoetv.core.I18n.get(v.language, "onboarding.city_subdistrict_hint"), active = query.isNotBlank())
    Spacer(Modifier.height(10.dp))
    if (v.lat != null) {
        Option(listOf(v.city, Region.name(v.country, v.language)).joinToString(", "), null, true, icon = Icons.Rounded.Place) {
            onChange(v.copy(city = "", lat = null, lon = null, region = "", timeZone = tech.kousozo.tahoetv.core.CountryCatalog.defaultZone(v.country)))
            query = ""
        }
    } else if (results == null) {
        Text(t("city.none"), color = Tahoe.Faint, fontSize = 12.sp)
        if (suggested.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                tech.kousozo.tahoetv.core.I18n.get(v.language, "city.suggested", Region.name(v.country, v.language)),
                color = Tahoe.Dim,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            suggested.forEach { opt ->
                Option(opt.label(), null, v.city.equals(opt.city, ignoreCase = true), icon = Icons.Rounded.LocationCity) {
                    query = opt.city
                    onChange(v.copy(city = opt.city, lat = opt.lat, lon = opt.lon, region = opt.region, timeZone = opt.zoneId))
                }
            }
        }
    }
    results?.let { list ->
        if (list.isEmpty()) Text(t("city.empty"), color = Tahoe.Faint, fontSize = 12.sp)
        LazyColumn(Modifier.weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list) { city ->
                Option(city.label, null, false, icon = Icons.Rounded.Place) {
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
    val zones = remember { (listOf("UTC") + java.time.ZoneId.getAvailableZoneIds().filter { '/' in it && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }.sorted()) }
    val picked = v.timeZone.ifBlank { device }
    Option(t("tz.device").replace("%s", device), null, v.timeZone.isBlank() || v.timeZone == device, icon = Icons.Rounded.PhoneAndroid) { onPick("") }
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
    var list by remember(v.countries) { mutableStateOf<List<LocalTv.Entry>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(v.countries, v.city, v.country) {
        list = null; failed = false
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val raw = Sources.loadCountriesPriority(null, v.countries)
                LocalTv.arrange(raw, v.nearRegion, v.countries, v.sort, v.city, v.country)
            }.getOrNull()
        }
        list = loaded // every channel: favourites stay visible in Live TV whatever the categories
        failed = loaded.isNullOrEmpty()
        onLoaded(loaded)
    }
    var query by remember { mutableStateOf("") }
    Field(query, { query = it }, t("search"))
    Spacer(Modifier.height(8.dp))
    when {
        list == null && !failed -> Column { Text(t("favs.loading"), color = Tahoe.Dim, fontSize = 13.sp); LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = Tahoe.Text, trackColor = Tahoe.Glass) }
        failed -> Text(t("favs.failed"), color = Tahoe.Faint, fontSize = 13.sp)
        else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val q = query.trim().lowercase()
            items(list!!.filter { q.isEmpty() || it.name.lowercase().contains(q) }.take(300), key = { it.channel.url }) { e ->
                val on = e.channel.url in v.favorites
                ChannelRow(e, on) { onChange(if (on) v.favorites - e.channel.url else v.favorites + e.channel.url) }
            }
        }
    }
}

@Composable
private fun ChannelRow(e: LocalTv.Entry, fav: Boolean, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (fav) Tahoe.Text.copy(alpha = .12f) else Tahoe.Glass)
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
) {
    val logo = rememberNetImage(e.channel.logo)
    Box(Modifier.size(36.dp).clip(CircleShape).background(Tahoe.Glass), contentAlignment = Alignment.Center) {
        if (logo != null) Image(logo, null, Modifier.size(28.dp), contentScale = ContentScale.Fit)
    }
    Text(e.name, color = Tahoe.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    Icon(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, tint = if (fav) Tahoe.Bad else Tahoe.Dim)
}

@Composable
private fun OrderStep(v: Viewer, t: (String) -> String, catalog: List<LocalTv.Entry>?, onChange: (List<String>) -> Unit) {
    if (v.favorites.isEmpty()) { Text(t("order.empty"), color = Tahoe.Faint, fontSize = 13.sp); return }
    val names = remember(catalog) { (catalog ?: Channels.entries).orEmpty().associate { it.channel.url to it.name } }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(v.favorites, key = { _, u -> u }) { i, url ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Tahoe.Glass).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = Tahoe.Dim, fontSize = 13.sp, modifier = Modifier.width(28.dp))
                val chName = names[url] ?: url.substringAfterLast('/').substringBefore('.')
                Text(chName, color = Tahoe.Text, fontSize = 15.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                RoundGlass(Icons.Rounded.KeyboardArrowUp, I18n.get(v.language, "a11y.move_up_fmt", chName), 48.dp) { if (i > 0) onChange(v.favorites.toMutableList().apply { add(i - 1, removeAt(i)) }) }
                Spacer(Modifier.width(6.dp))
                RoundGlass(Icons.Rounded.KeyboardArrowDown, I18n.get(v.language, "a11y.move_down_fmt", chName), 48.dp) { if (i < v.favorites.size - 1) onChange(v.favorites.toMutableList().apply { add(i + 1, removeAt(i)) }) }
            }
        }
    }
}

@Composable
private fun Summary(v: Viewer, t: (String) -> String, showMore: Boolean = false, compactQuick: Boolean = false, onPrayer: (Boolean) -> Unit = {}, onMore: () -> Unit = {}) = Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    @Composable
    fun checkLine(text: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(Tahoe.Good.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Check, null, tint = Tahoe.Good, modifier = Modifier.size(13.dp))
        }
        Text(text, color = Tahoe.Dim, fontSize = 15.sp)
    }
    if (compactQuick) {
        checkLine(t("ready_check_region"))
        checkLine(t("ready_check_prayer"))
        checkLine(t("ready_check_remote"))
        Spacer(Modifier.height(4.dp))
        SwitchRow(t("prayer.on"), v.prayer) { onPrayer(it) }
        if (showMore) IconRow { RoundGlass(Icons.Rounded.Tune, t("more")) { onMore() } }
        return@Column
    }
    // what is ready, in plain words, plus the one choice most people care about here (prayer times)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Tahoe.Good.copy(alpha = 0.12f)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Rounded.Check, null, tint = Tahoe.Good)
        Text(t("ready.channels").replace("%s", v.countries.joinToString(", ") { Region.name(it, v.language) }), color = Tahoe.Text, fontSize = 15.sp)
    }
    SwitchRow(t("prayer.on"), v.prayer) { onPrayer(it) }
    if (v.prayer && v.lat == null) Text(t("prayer.needcity"), color = Tahoe.Faint, fontSize = 12.sp)
    if (showMore) IconRow { RoundGlass(Icons.Rounded.Tune, t("more")) { onMore() } }
    fun row(k: String, value: String) = k to value
    listOf(
        row("sum.language", OnboardingText.LANGUAGE_NAMES.getValue(v.language)),
        row("sum.country", Region.flag(v.country) + " " + Region.name(v.country, v.language)),
        row("sum.city", v.city.ifBlank { t("sum.none") }),
        row("sum.tz", v.timeZone.ifBlank { tech.kousozo.tahoetv.core.CountryCatalog.defaultZone(v.country) }),
        row("sum.prayer", if (v.prayer) Prayer.name(v.prayerMethod) else t("sum.off")),
        row("sum.channels", v.countries.joinToString(" ") { Region.flag(it) + " " + Region.name(it, v.language) }),
        row("sum.cats", if (v.categories.isEmpty()) t("sum.all") else LocalTv.Category.entries.filter { it.name in v.categories }.joinToString(", ") { OnboardingText.category(v.language, it) }),
        row("sum.favs", if (v.favorites.isEmpty()) t("sum.none") else v.favorites.size.toString()),
    ).forEach { (k, value) ->
        GlassCard {
            Text(t(k), color = Tahoe.Dim, fontSize = 12.sp)
            Text(value, color = Tahoe.Text, fontSize = 15.sp)
        }
    }
}

@Composable
private fun Option(text: String, flag: String?, selected: Boolean, sub: String? = null, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(22.dp))
        .background(if (selected) (if (ThemePrefs.dark) Color.White.copy(alpha = 0.10f) else Tahoe.SurfaceHi) else Tahoe.Surface)
        .border(if (selected || ThemePrefs.highContrast) 2.dp else 1.dp, if (selected) Tahoe.Accent else Tahoe.Stroke.copy(alpha = 0.3f), RoundedCornerShape(22.dp))
        .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        .semantics(mergeDescendants = true) {}
        .padding(horizontal = 18.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    icon?.let { Icon(it, null, tint = Tahoe.Dim, modifier = Modifier.padding(end = 12.dp).size(22.dp)) }
    flag?.let { Text(it, fontSize = 20.sp, modifier = Modifier.padding(end = 12.dp)) }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text, color = Tahoe.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        sub?.let { Text(it, color = Tahoe.Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
    Box(
        Modifier.size(26.dp).clip(CircleShape)
            .background(if (selected) Tahoe.Accent else if (ThemePrefs.dark) Color.White.copy(alpha = 0.08f) else Tahoe.Glass),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Rounded.Check, null, tint = Tahoe.OnAccent, modifier = Modifier.size(16.dp))
    }
}
