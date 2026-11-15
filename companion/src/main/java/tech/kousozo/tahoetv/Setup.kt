package tech.kousozo.tahoetv

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import tech.kousozo.tahoetv.core.TvAdb

// ==================================================================== setup wizard

@Composable
fun SetupScreen(onExit: () -> Unit, m: SetupModel = viewModel()) {
    val c = LocalContext.current
    val exit = { m.scan(); onExit() } // next visit starts fresh
    BackHandler {
        when (m.step) {
            SetupModel.Step.Scan, SetupModel.Step.Done -> exit()
            SetupModel.Step.Apply, SetupModel.Step.Restore -> if (!m.busy) exit()
            else -> m.back()
        }
    }
    when (m.step) {
        SetupModel.Step.Scan -> Page(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.step", 1), tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.scan_title"), tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.scan_sub")) {
            Note(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.scan_note"), Icons.Rounded.Info)
            Section(when {
                m.noWifi -> tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.no_wifi")
                m.scanning && m.found.isEmpty() -> tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.scanning")
                m.found.isEmpty() -> tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.none_found")
                else -> tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.pick_tv")
            })
            if (m.scanning) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Tahoe.Accent, trackColor = Tahoe.Glass)
            m.found.forEach { f ->
                GlassCard(onClick = {
                    if (f.adb) m.connect(f.ip) else Toast.makeText(c, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.adb_toast"), Toast.LENGTH_SHORT).show()
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(Icons.Rounded.Tv, null, tint = if (f.adb) Tahoe.Accent else Tahoe.Faint)
                        Column(Modifier.weight(1f)) {
                            Text(f.label, fontWeight = FontWeight.SemiBold)
                            Text(f.status, color = Tahoe.Dim, fontSize = 13.sp)
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = Tahoe.Faint)
                    }
                }
            }
            if (!m.scanning && m.found.isEmpty() && !m.noWifi) Text(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.type_ip_hint"), color = Tahoe.Dim, fontSize = 14.sp)
            Section(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.or_ip"))
            Field(m.host, { m.host = it }, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.ip_field"), KeyboardType.Uri)
            PrimaryButton(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.connect_btn"), enabled = m.host.isNotBlank()) { m.connect() }
            SecondaryButton(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.rescan"), Icons.Rounded.Refresh) { m.scan() }
            SecondaryButton(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "common.back"), Icons.AutoMirrored.Rounded.ArrowBack, onClick = exit)
        }

        SetupModel.Step.Connect -> Page(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.step", 2), tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.connect_step_title"),
            tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.connect_step_sub")) {
            val err = m.error
            if (err == null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(vertical = 12.dp)) {
                    CircularProgressIndicator(color = Tahoe.Accent, modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    Text(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.connecting_to", m.host), color = Tahoe.Dim)
                }
                Note(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.connect_note"), Icons.Rounded.Info)
            } else {
                Note(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.connect_failed", err), Icons.Rounded.ErrorOutline)
                PrimaryButton(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "common.retry"), icon = Icons.Rounded.Refresh) { m.connect() }
                SecondaryButton(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "common.back")) { m.back() }
            }
        }

        SetupModel.Step.Info -> {
            val tv = m.tv!!
            var confirm by remember { mutableStateOf(false) }
            Page(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.step", 3), tv.model,
                "Android ${tv.release} (SDK ${tv.sdk})" + (if (tv.lowRam) " · ${tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.low_ram")}" else "") + (m.version?.let { "\n${tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.installed_note", it)}" } ?: "")) {
                if (tv.sdk < 26) Note(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.sdk_low"), Icons.Rounded.Info)
                if (m.version != null) SecondaryButton(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.restore_btn"), Icons.Rounded.Restore, Tahoe.Bad) { confirm = true }
                Section(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.debloat_title"))
                Text(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.debloat_sub"), color = Tahoe.Dim, fontSize = 13.sp)
                val items = TvAdb.CATALOG.filter { it.pkg in m.debloat }
                if (items.isEmpty()) Note(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.debloat_none"))
                items.forEach { b -> CheckRow(b.what, b.pkg, m.debloat[b.pkg] == true) { m.debloat[b.pkg] = m.debloat[b.pkg] != true } }
                val off = TvAdb.CATALOG.count { it.pkg in m.alreadyOff }
                if (off > 0) Text(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.debloat_off", off), color = Tahoe.Faint, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                PrimaryButton(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "common.next"), icon = Icons.AutoMirrored.Rounded.ArrowForward) { m.next() }
            }
            if (confirm) AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.restore_title")) },
                text = { Text(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.restore_body")) },
                confirmButton = { TextButton({ confirm = false; m.restore() }) { Text(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.restore_confirm"), color = Tahoe.Bad) } },
                dismissButton = { TextButton({ confirm = false }) { Text(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "common.cancel")) } },
                containerColor = androidx.compose.ui.graphics.Color(0xFF1C1C1E),
            )
        }

        SetupModel.Step.Personal -> Page(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.step", 4), tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.personal_title"), tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.personal_sub")) {
            Field(m.name, { m.name = it }, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.name_hint"))
            Field(m.city, { m.city = it }, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.city_hint"), trailing = {
                IconButton({ m.geocode() }) { Icon(Icons.Rounded.Search, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.city_search"), tint = Tahoe.Accent) }
            })
            if (m.place.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.Place, null, tint = Tahoe.Accent, modifier = Modifier.size(16.dp))
                Text(m.place, color = Tahoe.Dim, fontSize = 13.sp)
            }
            Field(m.avatar, { m.avatar = it }, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.avatar_hint"), KeyboardType.Uri)

            Section(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.dock_title"))
            Text(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.dock_note"), color = Tahoe.Dim, fontSize = 13.sp)
            m.dockChoices.forEach { pkg ->
                val i = m.dock.indexOf(pkg)
                CheckRow(m.tv!!.label(pkg), pkg, i >= 0, badge = if (i >= 0) "${i + 1}" else null) {
                    if (!m.toggleDock(pkg)) Toast.makeText(c, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.dock_max"), Toast.LENGTH_SHORT).show()
                }
            }

            Section(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.api_optional"))
            Text(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.api_note"), color = Tahoe.Dim, fontSize = 13.sp)
            Field(m.gemini, { m.gemini = it.trim() }, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.api_gemini"), secret = true)
            Field(m.youtube, { m.youtube = it.trim() }, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.api_yt"), secret = true)
            Field(m.tmdb, { m.tmdb = it.trim() }, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.api_tmdb"), secret = true)

            Section(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.other_label"))
            SwitchRow(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.adguard"), m.adguard) { m.adguard = it }
            SwitchRow(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.disable_stock"), m.disableStock) { m.disableStock = it }
            Spacer(Modifier.height(8.dp))
            PrimaryButton(tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "setup.install_tv_btn"), icon = Icons.Rounded.Download) { m.apply() }
        }

        SetupModel.Step.Apply, SetupModel.Step.Restore, SetupModel.Step.Done -> {
            val done = m.step == SetupModel.Step.Done
            val restoring = m.step == SetupModel.Step.Restore
            val langS = PhoneRegion.language
            Page(
                if (done) tech.kousozo.tahoetv.core.I18n.get(langS, "setup.done_label") else if (restoring) tech.kousozo.tahoetv.core.I18n.get(langS, "setup.restoring_label") else tech.kousozo.tahoetv.core.I18n.get(langS, "setup.step", 5),
                if (done) tech.kousozo.tahoetv.core.I18n.get(langS, "setup.ready_title") else if (restoring) (if (m.busy) tech.kousozo.tahoetv.core.I18n.get(langS, "setup.restoring_title") else tech.kousozo.tahoetv.core.I18n.get(langS, "setup.restored_title")) else if (m.error != null) tech.kousozo.tahoetv.core.I18n.get(langS, "setup.stopped_title") else tech.kousozo.tahoetv.core.I18n.get(langS, "setup.installing_title"),
                if (done) tech.kousozo.tahoetv.core.I18n.get(langS, "setup.done_sub") else if (m.busy) tech.kousozo.tahoetv.core.I18n.get(langS, "setup.installing_sub") else null,
            ) {
                if (m.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Tahoe.Accent, trackColor = Tahoe.Glass)
                GlassCard {
                    m.log.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = if (it.startsWith("✗")) Tahoe.Bad else Tahoe.Text) }
                }
                if (done) {
                    Note(tech.kousozo.tahoetv.core.I18n.get(langS, "setup.usb_off_note"), Icons.Rounded.Shield)
                    PrimaryButton(tech.kousozo.tahoetv.core.I18n.get(langS, "setup.usb_off_btn"), icon = Icons.Rounded.LockOpen) {
                        m.disableDebugging { Toast.makeText(c, tech.kousozo.tahoetv.core.I18n.get(langS, "setup.usb_off_toast"), Toast.LENGTH_SHORT).show() }
                    }
                }
                if (!m.busy && m.step == SetupModel.Step.Apply) SecondaryButton(tech.kousozo.tahoetv.core.I18n.get(langS, "setup.edit_prefs"), Icons.AutoMirrored.Rounded.ArrowBack) { m.next() }
                if (!m.busy) SecondaryButton(tech.kousozo.tahoetv.core.I18n.get(langS, "setup.go_home"), Icons.Rounded.Home, onClick = exit)
            }
        }
    }
}
