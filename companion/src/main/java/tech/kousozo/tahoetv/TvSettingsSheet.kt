package tech.kousozo.tahoetv

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tech.kousozo.tahoetv.core.*

private data class PairedDeviceRow(
    val prefix: String,
    val name: String,
    val role: String,
    val ip: String,
)

/**
 * Complete phone-based TV Control & Settings Center (#35 + #54):
 * manages TV display/dock/wallpaper/performance/subtitle prefs, playlists/EPG,
 * Kids daily time limits/schedule/allowed apps/channels + 7-day report, paired phones & write-only API keys.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvSettingsSheet(
    host: String,
    token: String,
    onDismiss: () -> Unit,
) {
    val c = LocalContext.current
    val lang = PhoneRegion.language
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) }

    // General & display
    var userName by remember { mutableStateOf("") }
    var tvName by remember { mutableStateOf("Tahoe TV") }
    var simpleMode by remember { mutableStateOf(false) }
    var subSize by remember { mutableStateOf("normal") }
    var perfTier by remember { mutableStateOf("auto") }
    var wallMode by remember { mutableStateOf("personal") }
    var wallMinutes by remember { mutableIntStateOf(5) }
    var screensaverEnabled by remember { mutableStateOf(true) }
    var playlistUrl by remember { mutableStateOf("") }
    var epgUrl by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf("popularity") }

    // Dock
    var dockSlots by remember { mutableIntStateOf(6) }
    val appItems = remember { mutableStateListOf<TvSettings.AppItem>() }

    // Kids (#54)
    var kidsGateMode by remember { mutableStateOf("pin") }
    var kidsMathDiff by remember { mutableStateOf("easy") }
    var dailyLimit by remember { mutableIntStateOf(0) }
    var scheduleEnabled by remember { mutableStateOf(false) }
    var startMin by remember { mutableIntStateOf(360) }
    var endMin by remember { mutableIntStateOf(1200) }
    var extraChannelsText by remember { mutableStateOf("") }
    val kidsApps = remember { mutableStateMapOf<String, Boolean>() }
    var kidsReport by remember { mutableStateOf<JSONObject?>(null) }

    // Devices & write-only API keys
    val devices = remember { mutableStateListOf<PairedDeviceRow>() }
    var hasGemini by remember { mutableStateOf(false) }
    var hasYoutube by remember { mutableStateOf(false) }
    var hasTmdb by remember { mutableStateOf(false) }
    var hasHa by remember { mutableStateOf(false) }
    var geminiKey by remember { mutableStateOf("") }
    var youtubeKey by remember { mutableStateOf("") }
    var tmdbKey by remember { mutableStateOf("") }
    var haUrl by remember { mutableStateOf("") }
    var haToken by remember { mutableStateOf("") }

    fun hydrate(s: TvSettings.Snapshot) {
        userName = s.userName
        tvName = s.tvName
        simpleMode = s.simpleMode
        subSize = s.subtitleSize
        perfTier = s.performanceTier
        wallMode = s.wallMode
        wallMinutes = s.wallRotateMinutes
        screensaverEnabled = s.screensaverEnabled
        playlistUrl = s.playlistUrl
        epgUrl = s.epgUrl
        sortMode = s.sortMode
        dockSlots = s.dockSlots
        appItems.clear()
        val orderedPkgs = s.dockPackages
        val byPkg = s.allApps.associateBy { it.pkg }
        val seen = HashSet<String>()
        orderedPkgs.forEach { p -> byPkg[p]?.let { appItems += it; seen += p } }
        s.allApps.forEach { if (it.pkg !in seen) appItems += it }

        kidsGateMode = s.kidsGateMode
        kidsMathDiff = s.kidsMathDifficulty
        dailyLimit = s.kidsPolicy.dailyLimitMinutes
        scheduleEnabled = s.kidsPolicy.scheduleEnabled
        startMin = s.kidsPolicy.allowedStartMin
        endMin = s.kidsPolicy.allowedEndMin
        extraChannelsText = s.kidsPolicy.extraChannelIds.joinToString(", ")
        kidsApps.clear()
        val ka = s.kidsApps.toSet()
        s.allApps.forEach { kidsApps[it.pkg] = it.pkg in ka || it.kidsAllowed }
        hasGemini = s.hasGeminiKey
        hasYoutube = s.hasYoutubeKey
        hasTmdb = s.hasTmdbKey
        hasHa = s.hasHaUrl
    }

    LaunchedEffect(host, token) {
        loading = true
        loadError = null
        withContext(Dispatchers.IO) {
            runCatching {
                val snap = TvLink.fetchSettings(host, token, lang)
                val rep = runCatching { TvLink.fetchKidsReport(host, token) }.getOrNull()
                val devJson = runCatching {
                    Web.get("http://${host.trim()}:8765/devices", 6000, mapOf("Authorization" to "Bearer $token"))
                }.getOrNull()
                Triple(snap, rep, devJson)
            }
        }.fold({ (snap, rep, devJson) ->
            hydrate(snap)
            kidsReport = rep
            devices.clear()
            runCatching {
                val arr = JSONObject(devJson ?: "{}").optJSONArray("devices") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    devices += PairedDeviceRow(
                        o.optString("prefix"),
                        o.optString("name", "HP"),
                        o.optString("role", "owner"),
                        o.optString("ip", ""),
                    )
                }
            }
            loading = false
        }, {
            loadError = it.message ?: I18n.get(lang, "pair.unreachable")
            loading = false
        })
    }

    ModalBottomSheet(onDismiss, containerColor = Tahoe.Surface) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.90f).padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Tune, null, tint = Tahoe.Accent)
                Spacer(Modifier.width(10.dp))
                Text(I18n.get(lang, "tvset.title"), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                IconButton(onDismiss) { Icon(Icons.Rounded.Close, I18n.get(lang, "tv.close"), tint = Tahoe.Dim) }
            }

            if (loading) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(color = Tahoe.Accent)
                        Text(I18n.get(lang, "tvset.loading"), color = Tahoe.Dim, fontSize = 13.sp)
                    }
                }
                return@ModalBottomSheet
            }

            loadError?.let { err ->
                Note(err, Icons.Rounded.ErrorOutline)
                Spacer(Modifier.weight(1f))
                return@ModalBottomSheet
            }

            val tabLabels = listOf(
                I18n.get(lang, "tvset.tab_general"),
                I18n.get(lang, "tvset.tab_dock"),
                I18n.get(lang, "tvset.tab_kids"),
                I18n.get(lang, "tvset.tab_keys"),
            )
            ScrollableTabRow(
                selectedTabIndex = tab,
                containerColor = Tahoe.Bg.copy(alpha = 0.4f),
                contentColor = Tahoe.Accent,
                edgePadding = 4.dp,
            ) {
                tabLabels.forEachIndexed { idx, lbl ->
                    Tab(selected = tab == idx, onClick = { tab = idx }, text = { Text(lbl, maxLines = 1) })
                }
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (tab) {
                    0 -> {
                        Field(userName, { userName = it }, I18n.get(lang, "tvset.owner_name"))
                        Field(tvName, { tvName = it }, I18n.get(lang, "tvset.tv_name"))
                        SwitchRow(I18n.get(lang, "tv.cc_simple_mode"), simpleMode) { simpleMode = it }
                        SwitchRow(I18n.get(lang, "tvset.screensaver"), screensaverEnabled) { screensaverEnabled = it }

                        Text(I18n.get(lang, "tvset.wall_mode"), fontSize = 13.sp, color = Tahoe.Dim)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ChoicePill(I18n.get(lang, "tvset.wall_personal"), wallMode == "personal", Modifier.weight(1f)) { wallMode = "personal" }
                            ChoicePill(I18n.get(lang, "tvset.wall_default"), wallMode == "default", Modifier.weight(1f)) { wallMode = "default" }
                        }

                        Text(I18n.get(lang, "sub.size_label"), fontSize = 13.sp, color = Tahoe.Dim)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PlayPrefs.SubtitleSize.entries.forEach { sz ->
                                ChoicePill(sz.label(lang), subSize == sz.key, Modifier.weight(1f)) { subSize = sz.key }
                            }
                        }

                        Text(I18n.get(lang, "device.tier_label"), fontSize = 13.sp, color = Tahoe.Dim)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("auto" to "Auto", "light" to I18n.get(lang, "device.tier_light"), "standard" to I18n.get(lang, "device.tier_standard"), "full" to I18n.get(lang, "device.tier_full"))
                                .forEach { (k, lbl) -> ChoicePill(lbl, perfTier == k, Modifier.weight(1f)) { perfTier = k } }
                        }

                        Text(I18n.get(lang, "sort.label"), fontSize = 13.sp, color = Tahoe.Dim)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            LocalTv.SortMode.entries.forEach { m ->
                                ChoicePill(m.label(lang), sortMode == m.key, Modifier.weight(1f)) { sortMode = m.key }
                            }
                        }

                        Field(playlistUrl, { playlistUrl = it }, I18n.get(lang, "tvset.playlist_url"), KeyboardType.Uri)
                        Field(epgUrl, { epgUrl = it }, I18n.get(lang, "tvset.epg_url"), KeyboardType.Uri)
                    }

                    1 -> {
                        Text(I18n.get(lang, "tvset.dock_hint"), fontSize = 13.sp, color = Tahoe.Dim)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(I18n.get(lang, "tvset.dock_slots", dockSlots), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Slider(dockSlots.toFloat(), { dockSlots = it.toInt() }, valueRange = 3f..12f, steps = 8, modifier = Modifier.width(180.dp))
                        }
                        appItems.forEachIndexed { idx, app ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tahoe.Glass)
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(app.dock, { checked -> appItems[idx] = app.copy(dock = checked) })
                                Text(app.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                IconButton({
                                    if (idx > 0) {
                                        val prev = appItems[idx - 1]
                                        appItems[idx - 1] = appItems[idx]
                                        appItems[idx] = prev
                                    }
                                }, enabled = idx > 0) { Icon(Icons.Rounded.KeyboardArrowUp, null) }
                                IconButton({
                                    if (idx < appItems.lastIndex) {
                                        val next = appItems[idx + 1]
                                        appItems[idx + 1] = appItems[idx]
                                        appItems[idx] = next
                                    }
                                }, enabled = idx < appItems.lastIndex) { Icon(Icons.Rounded.KeyboardArrowDown, null) }
                            }
                        }
                    }

                    2 -> {
                        kidsReport?.let { rep ->
                            val usedMin = rep.optInt("watchedMinutes", 0)
                            val lim = rep.optInt("dailyLimitMinutes", 0)
                            GlassCard {
                                Text(I18n.get(lang, "kids.report_title"), fontWeight = FontWeight.Bold)
                                Text(
                                    if (lim > 0) I18n.get(lang, "kids.report_today", usedMin, lim)
                                    else I18n.get(lang, "kids.report_no_limit", usedMin),
                                    color = Tahoe.Accent, fontSize = 14.sp,
                                )
                                val chs = rep.optJSONArray("channels")
                                if (chs != null && chs.length() > 0) {
                                    val summary = (0 until minOf(4, chs.length())).joinToString(" · ") { i ->
                                        val o = chs.getJSONObject(i)
                                        "${o.optString("name")} (${o.optInt("minutes")}m)"
                                    }
                                    Text(summary, color = Tahoe.Dim, fontSize = 12.sp)
                                }
                                TextButton({
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            runCatching {
                                                TvLink.pushSettings(host, token, JSONObject().put("kidsBonusMinutes", 30), lang)
                                                kidsReport = TvLink.fetchKidsReport(host, token)
                                            }
                                        }
                                        Toast.makeText(c, "+30 min", Toast.LENGTH_SHORT).show()
                                    }
                                }) { Text(I18n.get(lang, "kids.extend_30m"), color = Tahoe.Good) }
                            }
                        }

                        Text(I18n.get(lang, "kids.daily_limit"), fontSize = 13.sp, color = Tahoe.Dim)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0, 30, 60, 90, 120).forEach { m ->
                                ChoicePill(if (m == 0) I18n.get(lang, "kids.limit_off") else "${m}m", dailyLimit == m, Modifier.weight(1f)) { dailyLimit = m }
                            }
                        }

                        Text(I18n.get(lang, "kids.schedule_label"), fontSize = 13.sp, color = Tahoe.Dim)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ChoicePill(I18n.get(lang, "kids.schedule_off"), !scheduleEnabled, Modifier.weight(1f)) { scheduleEnabled = false }
                            ChoicePill("06–19", scheduleEnabled && startMin == 360 && endMin == 1140, Modifier.weight(1f)) {
                                scheduleEnabled = true; startMin = 360; endMin = 1140
                            }
                            ChoicePill("06–20", scheduleEnabled && startMin == 360 && endMin == 1200, Modifier.weight(1f)) {
                                scheduleEnabled = true; startMin = 360; endMin = 1200
                            }
                            ChoicePill("07–21", scheduleEnabled && startMin == 420 && endMin == 1260, Modifier.weight(1f)) {
                                scheduleEnabled = true; startMin = 420; endMin = 1260
                            }
                        }

                        Text(I18n.get(lang, "kids.gate_mode"), fontSize = 13.sp, color = Tahoe.Dim)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MathGate.Mode.entries.forEach { gm ->
                                ChoicePill(gm.label(lang), kidsGateMode == gm.key, Modifier.weight(1f)) { kidsGateMode = gm.key }
                            }
                        }

                        Field(extraChannelsText, { extraChannelsText = it }, I18n.get(lang, "kids.channels_title"))
                        appItems.forEach { app ->
                            val on = kidsApps[app.pkg] == true
                            CheckRow(app.label, null, on) { kidsApps[app.pkg] = !on }
                        }
                    }

                    3 -> {
                        if (devices.isNotEmpty()) {
                            Text(I18n.get(lang, "connect.devices"), fontWeight = FontWeight.SemiBold)
                            devices.forEach { d ->
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Tahoe.Glass)
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(d.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                        Text("${d.ip} · ${d.role}", color = Tahoe.Dim, fontSize = 12.sp)
                                    }
                                    TextButton({
                                        scope.launch {
                                            val ok = withContext(Dispatchers.IO) {
                                                runCatching {
                                                    Web.request("POST", "http://${host.trim()}:8765/devices/revoke",
                                                        JSONObject().put("prefix", d.prefix).toString(), 6000,
                                                        mapOf("Authorization" to "Bearer $token"))
                                                    true
                                                }.getOrDefault(false)
                                            }
                                            if (ok) devices.remove(d)
                                        }
                                    }) { Text(I18n.get(lang, "tvset.revoke"), color = Tahoe.Bad) }
                                }
                            }
                        }

                        Text(I18n.get(lang, "tvset.keys_hint"), fontSize = 12.sp, color = Tahoe.Dim)
                        Field(geminiKey, { geminiKey = it }, "Gemini API Key" + if (hasGemini) " (${I18n.get(lang, "tvset.key_set")})" else "", secret = true)
                        Field(youtubeKey, { youtubeKey = it }, "YouTube API Key" + if (hasYoutube) " (${I18n.get(lang, "tvset.key_set")})" else "", secret = true)
                        Field(tmdbKey, { tmdbKey = it }, "TMDB API Key" + if (hasTmdb) " (${I18n.get(lang, "tvset.key_set")})" else "", secret = true)
                        Field(haUrl, { haUrl = it }, "Home Assistant URL" + if (hasHa) " (${I18n.get(lang, "tvset.key_set")})" else "", KeyboardType.Uri)
                        Field(haToken, { haToken = it }, "Home Assistant Token", secret = true)
                    }
                }
            }

            PrimaryButton(I18n.get(lang, "tvset.save"), enabled = !saving, icon = Icons.Rounded.Save) {
                saving = true
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        runCatching {
                            val extra = extraChannelsText.split(',').map { KidsPolicy.Config.normalizeKey(it) }.filter { it.isNotEmpty() }.toSet()
                            val kp = KidsPolicy.Config(dailyLimit, scheduleEnabled, startMin, endMin, extra, emptySet())
                            val patch = JSONObject().apply {
                                put("userName", userName.trim())
                                put("tvName", tvName.trim().ifBlank { "Tahoe TV" })
                                put("simpleMode", simpleMode)
                                put("subtitleSize", subSize)
                                put("performanceTier", perfTier)
                                put("wallMode", wallMode)
                                put("wallRotateMinutes", wallMinutes)
                                put("screensaverEnabled", screensaverEnabled)
                                put("sortMode", sortMode)
                                put("playlistUrl", playlistUrl.trim())
                                put("epgUrl", epgUrl.trim())
                                put("dockSlots", dockSlots)
                                put("dockPackages", JSONArray(appItems.filter { it.dock }.map { it.pkg }))
                                put("kidsGateMode", kidsGateMode)
                                put("kidsMathDifficulty", kidsMathDiff)
                                put("kidsApps", JSONArray(kidsApps.filterValues { it }.keys.toList()))
                                put("kidsPolicy", kp.toJson())
                                if (geminiKey.isNotBlank()) put("geminiKey", geminiKey.trim())
                                if (youtubeKey.isNotBlank()) put("youtubeKey", youtubeKey.trim())
                                if (tmdbKey.isNotBlank()) put("tmdbKey", tmdbKey.trim())
                                if (haUrl.isNotBlank()) put("haUrl", haUrl.trim())
                                if (haToken.isNotBlank()) put("haToken", haToken.trim())
                            }
                            TvLink.pushSettings(host, token, patch, lang)
                        }
                    }
                    saving = false
                    res.fold({
                        hydrate(it)
                        geminiKey = ""; youtubeKey = ""; tmdbKey = ""; haUrl = ""; haToken = ""
                        Toast.makeText(c, I18n.get(lang, "tvset.saved"), Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }, {
                        Toast.makeText(c, it.message ?: I18n.get(lang, "pair.unreachable"), Toast.LENGTH_LONG).show()
                    })
                }
            }
        }
    }
}

@Composable
private fun ChoicePill(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) Tahoe.Accent.copy(alpha = 0.24f) else Tahoe.Glass)
            .border(1.dp, if (selected) Tahoe.Accent else Tahoe.Stroke, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) Tahoe.Accent else Tahoe.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
