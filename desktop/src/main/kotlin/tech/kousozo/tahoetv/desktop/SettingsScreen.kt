package tech.kousozo.tahoetv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.kousozo.tahoetv.core.M3u
import tech.kousozo.tahoetv.core.Web

@Composable
fun SettingsScreen() {
    val scope = rememberCoroutineScope()
    val lang = Settings.lang
    var city by remember { mutableStateOf(Settings.city) }
    var place by remember { mutableStateOf("${Settings.city} (${"%.3f".format(Settings.lat)}, ${"%.3f".format(Settings.lon)})") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 28.dp, top = 24.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(tech.kousozo.tahoetv.core.I18n.get(lang, "nav.prefs"), fontSize = 40.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, letterSpacing = (-0.8).sp)
        FigmaSettingsCards()
        Text(tech.kousozo.tahoetv.core.I18n.get(lang, "desk.st_advanced"), fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section(tech.kousozo.tahoetv.core.I18n.get(lang, "settings.profile"))
        Field(Settings.name, { Settings.name = it }, tech.kousozo.tahoetv.core.I18n.get(lang, "settings.name_hint"))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(city, { city = it }, tech.kousozo.tahoetv.core.I18n.get(lang, "settings.city_hint"), Modifier.weight(1f))
            GhostButton(tech.kousozo.tahoetv.core.I18n.get(lang, "common.search"), Icons.Rounded.Search) {
                scope.launch {
                    place = tech.kousozo.tahoetv.core.I18n.get(lang, "settings.city_searching")
                    val r = withContext(Dispatchers.IO) { Web.geocode(city) }
                    if (r == null) place = tech.kousozo.tahoetv.core.I18n.get(lang, "settings.city_not_found")
                    else {
                        Settings.city = r.name; Settings.province = r.region; Settings.lat = r.lat; Settings.lon = r.lon; city = r.name; place = r.label
                        Settings.viewer.takeIf { it.done }?.let { Settings.viewer = it.copy(city = r.name, lat = r.lat, lon = r.lon, region = r.region) } // one source of truth
                    }
                }
            }
        }
        Text(place, color = Tahoe.Dim, fontSize = 13.sp)

        Section(tech.kousozo.tahoetv.core.I18n.get(lang, "settings.api_keys"))
        Field(Settings.gemini, { Settings.gemini = it.trim() }, "Gemini", secret = true)
        Field(Settings.geminiModel, { Settings.geminiModel = it.trim() }, "Model Gemini")
        Field(Settings.youtube, { Settings.youtube = it.trim() }, "YouTube Data API", secret = true)
        Field(Settings.tmdb, { Settings.tmdb = it.trim() }, "TMDB", secret = true)

        Section(tech.kousozo.tahoetv.core.I18n.get(lang, "nav.live"))
        OutlinedTextField(Settings.playlist, { Settings.playlist = it }, label = { Text(tech.kousozo.tahoetv.core.I18n.get(lang, "settings.extra_playlists")) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Tahoe.Accent, unfocusedBorderColor = Tahoe.Stroke,
                focusedLabelColor = Tahoe.Accent, unfocusedLabelColor = Tahoe.Faint, cursorColor = Tahoe.Accent))
        Field(Settings.epg, { Settings.epg = it.trim() }, tech.kousozo.tahoetv.core.I18n.get(lang, "settings.epg_url"))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton(tech.kousozo.tahoetv.core.I18n.get(lang, "settings.add_playlist_file"), Icons.Rounded.FolderOpen) { pickPlaylist()?.let { Settings.playlist = (Settings.playlist.trim() + "\n" + it).trim() } }
            GhostButton(tech.kousozo.tahoetv.core.I18n.get(lang, "settings.default_sources"), Icons.Rounded.Restore) { Settings.playlist = ""; Settings.epg = "" }
        }
        Text(tech.kousozo.tahoetv.core.I18n.get(lang, "settings.sources_note"), color = Tahoe.Faint, fontSize = 12.sp)

        Text(tech.kousozo.tahoetv.core.I18n.get(lang, "player.quality"), color = Tahoe.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tech.kousozo.tahoetv.core.PlayPrefs.Quality.entries.forEach { q -> Choice(q.label(lang), Settings.quality == q) { Settings.quality = q } }
        }
        Text(tech.kousozo.tahoetv.core.I18n.get(lang, "player.latency"), color = Tahoe.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tech.kousozo.tahoetv.core.PlayPrefs.Latency.entries.forEach { l -> Choice(l.label(lang), Settings.latency == l) { Settings.latency = l } }
        }
        val qualNote = if (Settings.quality.maxHeight > 0) tech.kousozo.tahoetv.core.I18n.get(lang, "player.quality_cap", Settings.quality.label(lang))
            else tech.kousozo.tahoetv.core.I18n.get(lang, "player.quality_auto_note")
        val latNote = if (Settings.latency == tech.kousozo.tahoetv.core.PlayPrefs.Latency.Low) tech.kousozo.tahoetv.core.I18n.get(lang, "player.latency_low_warn", Settings.latency.note(lang))
            else tech.kousozo.tahoetv.core.I18n.get(lang, "player.latency_note_dot", Settings.latency.note(lang))
        Text("$qualNote $latNote", color = Tahoe.Faint, fontSize = 12.sp)

        Section(tech.kousozo.tahoetv.core.I18n.get(lang, "home.region_language"))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${tech.kousozo.tahoetv.core.Region.flag(Settings.countryCode)}  ${tech.kousozo.tahoetv.core.Region.name(Settings.countryCode, lang)} · " +
                tech.kousozo.tahoetv.core.OnboardingText.LANGUAGE_NAMES.getValue(lang), color = Tahoe.Text, fontSize = 14.sp)
            GhostButton(tech.kousozo.tahoetv.core.I18n.get(lang, "home.change"), Icons.Rounded.Public) { PlayerWindow.stop(); DeskNav.regionSetup = true } // the old country's channel stops
        }

        Text(tech.kousozo.tahoetv.core.I18n.get(lang, "ui.channel_languages"), color = Tahoe.Text, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        Text(tech.kousozo.tahoetv.core.I18n.get(lang, "ui.channel_languages_sub"), color = Tahoe.Faint, fontSize = 12.sp)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tech.kousozo.tahoetv.core.Region.channelLanguages().forEach { l ->
                val on = l in Settings.viewer.channelLanguages
                Choice(java.util.Locale.forLanguageTag(l).getDisplayLanguage(java.util.Locale.forLanguageTag(l)).ifBlank { l }, on) {
                    val v = Settings.viewer
                    Settings.viewer = v.copy(channelLanguages = if (on) v.channelLanguages - l else v.channelLanguages + l)
                }
            }
        }

        Section(tech.kousozo.tahoetv.core.I18n.get(lang, "settings.updates_section"))
        AboutUpdates()
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            GhostButton(tech.kousozo.tahoetv.core.I18n.get(lang, "settings.check_updates"), Icons.Rounded.SystemUpdate) {
                DesktopPush.checkUpdateModal(manual = true)
            }
        }
        Text(tech.kousozo.tahoetv.core.I18n.get(lang, "settings.updates_url_note"), color = Tahoe.Faint, fontSize = 12.sp)

        Section(tech.kousozo.tahoetv.core.I18n.get(lang, "settings.home_share"))
        Text(tech.kousozo.tahoetv.core.I18n.get(lang, "settings.home_share_desc"), color = Tahoe.Dim, fontSize = 13.sp)

        val isRunning by remember { IptvServer.isRunning }
        val chCount by remember { IptvServer.channelCount }
        var serverStarting by remember { mutableStateOf(false) }
        var serverError by remember { mutableStateOf<String?>(null) }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!isRunning) {
                GhostButton(if (serverStarting) tech.kousozo.tahoetv.core.I18n.get(lang, "settings.starting_server") else tech.kousozo.tahoetv.core.I18n.get(lang, "settings.start_server"), Icons.Rounded.PlayArrow, color = Tahoe.Good) {
                    if (serverStarting) return@GhostButton // one start at a time
                    serverStarting = true; serverError = null
                    Thread { // network load: off the UI thread; daemon so it never keeps a closing app alive
                        val err = runCatching { IptvServer.start(Settings.iptvPort) }.exceptionOrNull()
                        javax.swing.SwingUtilities.invokeLater {
                            serverStarting = false
                            serverError = err?.let { "${tech.kousozo.tahoetv.core.I18n.get(lang, "pair.failed")}: ${it.message ?: it.javaClass.simpleName} (port ${Settings.iptvPort})" }
                        }
                    }.apply { isDaemon = true }.start()
                }
            } else {
                GhostButton(tech.kousozo.tahoetv.core.I18n.get(lang, "settings.stop_server"), Icons.Rounded.Stop, color = Tahoe.Bad) {
                    IptvServer.stop()
                }
                GhostButton(tech.kousozo.tahoetv.core.I18n.get(lang, "live.open_web_player"), Icons.Rounded.OpenInBrowser) {
                    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(IptvServer.webUrl())) }
                }
            }
        }
        serverError?.let { Text(it, color = Tahoe.Bad, fontSize = 12.sp) }

        if (isRunning) {
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(Tahoe.Glass)
                    .border(1.dp, Tahoe.Stroke, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(tech.kousozo.tahoetv.core.I18n.get(lang, "settings.active"), Icons.Rounded.Wifi)
                    Text(tech.kousozo.tahoetv.core.I18n.get(lang, "live.channels_loaded", chCount), color = Tahoe.Text, fontSize = 14.sp)
                }
                Text("Playlist Master: ${IptvServer.masterPlaylistUrl()}", color = Tahoe.Accent, fontSize = 13.sp)
                Text("EPG Guide: ${IptvServer.epgUrl()}", color = Tahoe.Dim, fontSize = 12.sp)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GhostButton(tech.kousozo.tahoetv.core.I18n.get(lang, "settings.copy_playlist"), Icons.Rounded.ContentCopy) {
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(IptvServer.masterPlaylistUrl()), null)
                    }
                    GhostButton(tech.kousozo.tahoetv.core.I18n.get(lang, "settings.copy_epg"), Icons.Rounded.ContentCopy) {
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(IptvServer.epgUrl()), null)
                    }
                }
            }
        }
        Section(tech.kousozo.tahoetv.core.I18n.get(lang, "profile.help_group"))
        SendLog()
    }
        }
}

/** "Kirim log": saves the diagnostic report (player events and errors, URLs without their tokens) to Downloads,
 *  copies it to the clipboard and opens GitHub Issues, so a problem can be reported with one paste. */
@Composable
private fun SendLog() {
    val lang = Settings.lang
    var saved by remember { mutableStateOf<String?>(null) }
    Text(tech.kousozo.tahoetv.core.I18n.get(lang, "desktop.log_desc"),
        color = Tahoe.Dim, fontSize = 13.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GhostButton(tech.kousozo.tahoetv.core.I18n.get(lang, "desktop.log_save"), Icons.Rounded.BugReport) {
            saved = runCatching {
                val text = desktopReport()
                val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
                val out = java.io.File(System.getProperty("user.home"), "Downloads/TahoeTV-log-$stamp.txt").apply { parentFile.mkdirs(); writeText(text) }
                java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
                runCatching { java.awt.Desktop.getDesktop().browseFileDirectory(out) }.recoverCatching { java.awt.Desktop.getDesktop().open(out.parentFile) }
                tech.kousozo.tahoetv.core.I18n.get(lang, "desktop.log_saved", out.path)
            }.getOrElse { tech.kousozo.tahoetv.core.I18n.get(lang, "desktop.log_fail", it.message ?: "") }
        }
        GhostButton(tech.kousozo.tahoetv.core.I18n.get(lang, "desktop.log_report"), Icons.Rounded.OpenInBrowser) {
            val url = "https://github.com/${tech.kousozo.tahoetv.core.Updates.REPO}/issues/new"
            runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
                .onFailure { saved = tech.kousozo.tahoetv.core.I18n.get(lang, "desktop.browser_fail", url) }
        }
        GhostButton(tech.kousozo.tahoetv.core.Slate.follow(lang), Icons.Rounded.Chat) {
            val url = tech.kousozo.tahoetv.core.Brand.WHATSAPP_CHANNEL
            runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
                .onFailure { saved = tech.kousozo.tahoetv.core.I18n.get(lang, "desktop.browser_fail", url) }
        }
    }
    saved?.let { Text(it, color = Tahoe.Dim, fontSize = 13.sp) }
}

private fun desktopReport(): String = tech.kousozo.tahoetv.core.DiagLog.report(
    "Tahoe TV (desktop)", System.getProperty("jpackage.app-version") ?: "dev", linkedMapOf(
        "OS" to "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})",
        "Java" to System.getProperty("java.version"),
        "Server IPTV" to if (IptvServer.isRunning.value) "active, ${IptvServer.channelCount.value} channels" else "off",
        "Recordings" to "${Recordings.jobs.size} (${Recordings.jobs.count { it.state == "recording" }} active)",
    ))

/** Version, latest release notes (changelog) and OTA: downloads the installer for this Mac/PC and opens it. */
@Composable
private fun AboutUpdates() {
    val lang = Settings.lang
    val scope = rememberCoroutineScope()
    val current = System.getProperty("jpackage.app-version") ?: "dev"
    val os = System.getProperty("os.name").lowercase(); val arm = System.getProperty("os.arch").contains("aarch64")
    val asset = when {
        "win" in os -> "TahoeTV-windows.msi"
        "linux" in os -> "TahoeTV-linux-amd64.deb"
        arm -> "TahoeTV-macos-arm64.dmg"
        else -> "TahoeTV-macos-intel.dmg"
    }
    var latest by remember { mutableStateOf<tech.kousozo.tahoetv.core.Updates.Release?>(null) }
    var state by remember(lang) { mutableStateOf(tech.kousozo.tahoetv.core.I18n.get(lang, "desktop.updates_checking")) }
    LaunchedEffect(lang) {
        withContext(Dispatchers.IO) { runCatching { tech.kousozo.tahoetv.core.Updates.latest() } }
            .onSuccess { latest = it; state = if (tech.kousozo.tahoetv.core.Updates.newer(it.version, current)) tech.kousozo.tahoetv.core.I18n.get(lang, "desktop.updates_avail", it.version) else tech.kousozo.tahoetv.core.I18n.get(lang, "desktop.updates_latest") }
            .onFailure { state = tech.kousozo.tahoetv.core.I18n.get(lang, "desktop.updates_fail") }
    }
    Text("Tahoe TV $current  ·  $state", color = Tahoe.Dim, fontSize = 14.sp)
    val r = latest
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (r != null && tech.kousozo.tahoetv.core.Updates.newer(r.version, current) && asset in r.assets)
            GhostButton(tech.kousozo.tahoetv.core.I18n.get(lang, "desktop.updates_download_install", r.version), Icons.Rounded.SystemUpdate, color = Tahoe.Accent) {
                scope.launch {
                    val out = java.io.File(System.getProperty("user.home"), "Downloads/$asset")
                    runCatching {
                        val hash = withContext(Dispatchers.IO) { r.checksumFor(asset) }
                        withContext(Dispatchers.IO) { tech.kousozo.tahoetv.core.Updates.download(r.assets.getValue(asset), out, hash) { p -> state = tech.kousozo.tahoetv.core.I18n.get(lang, "desktop.updates_downloading", p) } }
                        state = if ("linux" in os) tech.kousozo.tahoetv.core.I18n.get(lang, "desktop.updates_done_linux", out.name, out.name) else tech.kousozo.tahoetv.core.I18n.get(lang, "desktop.updates_done_other")
                        runCatching { java.awt.Desktop.getDesktop().open(out) }.onFailure {
                            if ("linux" in os) ProcessBuilder("xdg-open", out.absolutePath).start() else throw it
                        }
                    }.onFailure { state = tech.kousozo.tahoetv.core.I18n.get(lang, "desktop.updates_download_fail", it.message ?: "") }
                }
            }
        GhostButton(tech.kousozo.tahoetv.core.I18n.get(lang, "desktop.updates_release_page"), Icons.Rounded.OpenInBrowser) {
            val uri = java.net.URI(r?.page ?: tech.kousozo.tahoetv.core.Updates.PAGE)
            runCatching { java.awt.Desktop.getDesktop().browse(uri) }.onFailure {
                if ("linux" in os) runCatching { ProcessBuilder("xdg-open", uri.toString()).start() }
            }
        }
    }
    r?.let { Text(tech.kousozo.tahoetv.core.I18n.get(lang, "desktop.updates_notes", it.version, it.date, it.notes.replace(Regex("(?m)^#+\\s*"), "").replace("**", "")), color = Tahoe.Dim, fontSize = 13.sp) }
}

/** Copies the chosen .m3u/.txt into ~/.tahoetv/playlists (so cleaning Downloads doesn't break it) and returns that path. */
private fun pickPlaylist(): String? {
    val d = java.awt.FileDialog(null as java.awt.Frame?, tech.kousozo.tahoetv.core.I18n.get(Settings.lang, "desktop.pick_playlist"), java.awt.FileDialog.LOAD)
    d.isVisible = true
    val f = d.files.firstOrNull() ?: return null
    val dir = java.io.File(System.getProperty("user.home"), ".tahoetv/playlists").apply { mkdirs() }
    return java.io.File(dir, f.name).also { f.copyTo(it, overwrite = true) }.path
}


/** A selectable pill for a short list of options. */
@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) = Text(text,
    Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
        .background(if (selected) Tahoe.Accent else Tahoe.Glass)
        .selectable(selected = selected, onClick = onClick, role = androidx.compose.ui.semantics.Role.RadioButton)
        .padding(horizontal = 14.dp, vertical = 7.dp),
    color = if (selected) androidx.compose.ui.graphics.Color(0xFF0B0B0F) else Tahoe.Text, fontSize = 13.sp)


// ---------------------------------------------------------------------------------------------------------------
// Figma "Desktop / Pengaturan": three summary cards (Tampilan, Pemutaran, Saluran & server) with live controls.

@Composable
private fun FigmaSettingsCards() {
    val lang = Settings.lang
    val i = { k: String -> tech.kousozo.tahoetv.core.I18n.get(lang, k) }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        SettingsCard(i("desk.st_display"), Modifier.weight(1f).fillMaxHeight()) {
            val modes = tech.kousozo.tahoetv.core.Theme.Mode.entries
            val names = mapOf(tech.kousozo.tahoetv.core.Theme.Mode.SYSTEM to i("ui.theme_system"), tech.kousozo.tahoetv.core.Theme.Mode.DARK to i("ui.theme_dark"), tech.kousozo.tahoetv.core.Theme.Mode.LIGHT to i("ui.theme_light"))
            ValueRow(i("desk.st_theme"), names[Settings.themeMode] ?: "") { Settings.themeMode = modes[(modes.indexOf(Settings.themeMode) + 1) % modes.size] }
            SwitchRow(i("ui.high_contrast"), Settings.highContrast) { Settings.highContrast = !Settings.highContrast }
            SwitchRow(i("ui.reduce_motion"), Settings.reduceMotion) { Settings.reduceMotion = !Settings.reduceMotion }
        }
        SettingsCard(i("desk.st_playback"), Modifier.weight(1f).fillMaxHeight()) {
            val qs = tech.kousozo.tahoetv.core.PlayPrefs.Quality.entries
            val ls = tech.kousozo.tahoetv.core.PlayPrefs.Latency.entries
            ValueRow(i("player.quality"), Settings.quality.label(lang)) { Settings.quality = qs[(qs.indexOf(Settings.quality) + 1) % qs.size] }
            ValueRow(i("player.latency"), Settings.latency.label(lang)) { Settings.latency = ls[(ls.indexOf(Settings.latency) + 1) % ls.size] }
        }
        SettingsCard(i("desk.st_channels"), Modifier.weight(1f).fillMaxHeight()) {
            ValueRow(i("home.region_language"), "${tech.kousozo.tahoetv.core.Region.flag(Settings.countryCode)} ${tech.kousozo.tahoetv.core.Region.name(Settings.countryCode, lang)}") {
                PlayerWindow.stop(); DeskNav.regionSetup = true
            }
            val cl = Settings.viewer.channelLanguages
            InfoRow(i("ui.channel_languages"), if (cl.isEmpty()) "—" else cl.joinToString(", ") { java.util.Locale.forLanguageTag(it).getDisplayLanguage(java.util.Locale.forLanguageTag(it)).ifBlank { it } })
            val running by remember { IptvServer.isRunning }
            SwitchRow(i("desk.st_iptv"), running) {
                if (running) IptvServer.stop()
                else Thread { runCatching { IptvServer.start(Settings.iptvPort) } }.apply { isDaemon = true }.start()
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) = Column(
    modifier.clip(RoundedCornerShape(24.dp)).background(Tahoe.Card).border(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.06f), RoundedCornerShape(24.dp)).padding(20.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
) {
    Text(title.uppercase(), fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = Tahoe.Faint, letterSpacing = 1.3.sp)
    content()
}

/** A value that is only shown (it is changed in the section below), so it does not look clickable. */
@Composable
private fun InfoRow(label: String, value: String) = Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(label, Modifier.weight(1f), fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
    Text(value, fontSize = 14.sp, color = Tahoe.Dim, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
}

@Composable
private fun ValueRow(label: String, value: String, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
) {
    Text(label, Modifier.weight(1f), fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
    Text(value, fontSize = 14.sp, color = Tahoe.Dim, maxLines = 1)
}

@Composable
private fun SwitchRow(label: String, on: Boolean, onToggle: () -> Unit) = Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).selectable(on, role = androidx.compose.ui.semantics.Role.Switch) { onToggle() }.padding(vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(label, Modifier.weight(1f), fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
    Box(Modifier.size(48.dp, 28.dp).clip(RoundedCornerShape(14.dp)).background(if (on) Tahoe.Accent else androidx.compose.ui.graphics.Color(0xFF4A4D57))) {
        Box(Modifier.align(if (on) Alignment.CenterEnd else Alignment.CenterStart).padding(3.dp).size(22.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (on) androidx.compose.ui.graphics.Color(0xFF0D0F14) else androidx.compose.ui.graphics.Color(0xFFE0E0E4)))
    }
}
