package tech.kousozo.tahoetv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.kousozo.tahoetv.core.AccountApi
import tech.kousozo.tahoetv.core.AccountException
import tech.kousozo.tahoetv.core.AccountText
import tech.kousozo.tahoetv.core.DisplayProfile

/** The signed-in account on this phone: the sign-in token (sealed by an Android Keystore key), the synced
 *  [DisplayProfile] and the cached photo / wallpaper. The server address comes from the build (secrets.properties)
 *  or, in a public build, from what the owner types in; it is never in the repository. */
object Account {
    private const val ALIAS = "tahoe_account_v1"
    var email by mutableStateOf("")
    var profile by mutableStateOf(DisplayProfile())
    var rev = 0
    var avatar by mutableStateOf<ImageBitmap?>(null)
    var wallpaper by mutableStateOf<ImageBitmap?>(null)
    val signedIn get() = email.isNotEmpty()

    private fun prefs(c: Context) = c.getSharedPreferences("account", 0)
    fun serverUrl(c: Context) = BuildConfig.ACCOUNT_URL.ifBlank { prefs(c).getString("url", "").orEmpty() }
    fun setServerUrl(c: Context, u: String) { if (BuildConfig.ACCOUNT_URL.isBlank()) prefs(c).edit().putString("url", u.trim()).apply() }

    private fun key(): javax.crypto.SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun seal(s: String): String = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, key()); Base64.encodeToString(iv + doFinal(s.toByteArray()), Base64.NO_WRAP)
    }
    private fun unseal(s: String): String? = runCatching {
        val b = Base64.decode(s, Base64.NO_WRAP)
        Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12)); String(doFinal(b, 12, b.size - 12)) }
    }.getOrNull()

    fun token(c: Context): String? = prefs(c).getString("token", null)?.let { unseal(it) }
    fun api(c: Context): AccountApi? = serverUrl(c).takeIf { it.isNotBlank() }?.let { runCatching { AccountApi(it, token(c), android.os.Build.MODEL) }.getOrNull() }
    fun available(c: Context) = serverUrl(c).isNotBlank()

    private fun dir(c: Context) = File(c.filesDir, "account").apply { mkdirs() }

    fun load(c: Context) {
        val p = prefs(c)
        if (token(c) == null) { email = ""; return }
        email = p.getString("email", "").orEmpty()
        rev = p.getInt("rev", 0)
        profile = DisplayProfile.fromJson(runCatching { org.json.JSONObject(p.getString("profile", "{}")!!) }.getOrNull())
        avatar = decode(File(dir(c), "avatar.jpg")); wallpaper = decode(File(dir(c), "wallpaper.jpg"))
        applyLocal(c)
    }

    private fun decode(f: File): ImageBitmap? = if (f.exists()) runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull() else null

    fun signedInAs(c: Context, s: AccountApi.Session) {
        prefs(c).edit().putString("token", seal(s.token)).putString("email", s.email).apply()
        email = s.email
    }

    fun signOut(c: Context, remote: Boolean = true) {
        if (remote) api(c)?.let { a -> kotlin.concurrent.thread { runCatching { a.logout() } } }
        prefs(c).edit().clear().apply(); dir(c).deleteRecursively()
        email = ""; profile = DisplayProfile(); rev = 0; avatar = null; wallpaper = null
    }

    /** What this phone itself shows differently: theme, reduced motion. (Text size is applied by [TahoeTheme].) */
    fun applyLocal(c: Context) {
        val p = profile
        ThemePrefs.mode = tech.kousozo.tahoetv.core.Theme.Mode.of(p.theme.uppercase())
        ThemePrefs.reduceMotion = p.reduceMotion
        AppearancePrefs.save(c)
    }

    /** Pulls the profile and the pictures it names. Blocking. */
    fun pull(c: Context) {
        val a = api(c) ?: return
        val r = a.profile()
        rev = r.rev; profile = r.settings
        prefs(c).edit().putInt("rev", rev).putString("profile", r.settings.toJson().toString()).apply()
        fetch(c, a, r.settings.avatarId, "avatar.jpg") { avatar = it }
        fetch(c, a, r.settings.wallpaperId, "wallpaper.jpg") { wallpaper = it }
        applyLocal(c)
    }

    private fun fetch(c: Context, a: AccountApi, id: String, name: String, set: (ImageBitmap?) -> Unit) {
        val f = File(dir(c), name)
        if (id.isBlank()) { f.delete(); set(null); return }
        if (prefs(c).getString("have_$name", "") != id) {
            runCatching { f.writeBytes(a.downloadFile(id)); prefs(c).edit().putString("have_$name", id).apply() }
        }
        set(decode(f))
    }

    /** Saves [next]; if another device saved first, takes its newer revision and saves ours on top once. Blocking. */
    fun push(c: Context, next: DisplayProfile) {
        val a = api(c) ?: return
        profile = next
        var r = a.saveProfile(next, rev)
        if (r == null) { rev = a.profile().rev; r = a.saveProfile(next, rev) }
        if (r != null) { rev = r; prefs(c).edit().putInt("rev", r).putString("profile", next.toJson().toString()).apply() }
        applyLocal(c)
    }

    /** Scales a picked image down (so a 12 MP photo is not uploaded), uploads it, replaces the old one. Blocking. */
    fun uploadPicture(c: Context, uri: Uri, kind: String) {
        val a = api(c) ?: return
        val max = if (kind == "avatar") 512 else 1920
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
        val raw = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw AccountException(0, "Can't read that picture")
        val bmp = if (kind == "avatar") { val s = minOf(raw.width, raw.height); Bitmap.createBitmap(raw, (raw.width - s) / 2, (raw.height - s) / 2, s, s) } else raw
        val scaled = if (maxOf(bmp.width, bmp.height) > max) { val f = max.toFloat() / maxOf(bmp.width, bmp.height); Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt(), (bmp.height * f).toInt(), true) } else bmp
        val out = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 86, it) }.toByteArray()
        val f = a.uploadFile(kind, out, kind)
        val old = if (kind == "avatar") profile.avatarId else profile.wallpaperId
        push(c, if (kind == "avatar") profile.copy(avatarId = f.id) else profile.copy(wallpaperId = f.id))
        if (old.isNotBlank()) runCatching { a.deleteFile(old) }
        File(dir(c), if (kind == "avatar") "avatar.jpg" else "wallpaper.jpg").writeBytes(out)
        prefs(c).edit().putString("have_${if (kind == "avatar") "avatar" else "wallpaper"}.jpg", f.id).apply()
        if (kind == "avatar") avatar = scaled.asImageBitmap() else wallpaper = scaled.asImageBitmap()
    }

    fun removePicture(c: Context, kind: String) {
        val a = api(c) ?: return
        val old = if (kind == "avatar") profile.avatarId else profile.wallpaperId
        push(c, if (kind == "avatar") profile.copy(avatarId = "") else profile.copy(wallpaperId = ""))
        if (old.isNotBlank()) runCatching { a.deleteFile(old) }
        File(dir(c), if (kind == "avatar") "avatar.jpg" else "wallpaper.jpg").delete()
        if (kind == "avatar") avatar = null else wallpaper = null
    }
}

@Composable
fun AccountPage() {
    val c = LocalContext.current
    val lang = PhoneRegion.language
    val scope = rememberCoroutineScope()
    fun t(k: String, a: String? = null) = AccountText.get(lang, k, a)
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var registerMode by remember { mutableStateOf(false) }
    var mail by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var server by remember { mutableStateOf(Account.serverUrl(c)) }
    var pwDialog by remember { mutableStateOf(false) }
    var delDialog by remember { mutableStateOf(false) }

    fun run(block: () -> Unit) {
        busy = true; msg = ""
        scope.launch {
            withContext(Dispatchers.IO) {
                try { block() } catch (e: AccountException) { msg = e.message ?: t("err_generic") } catch (e: java.io.IOException) { msg = t("offline") } catch (e: Exception) { msg = t("err_generic") }
            }
            busy = false
        }
    }
    fun save(next: DisplayProfile) = run { Account.push(c, next); msg = t("saved") }
    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) run { Account.uploadPicture(c, u, "avatar"); msg = t("saved") } }
    val pickWall = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) run { Account.uploadPicture(c, u, "wallpaper"); msg = t("saved") } }

    LaunchedEffect(Account.signedIn) { if (Account.signedIn) run { Account.pull(c) } }

    if (pwDialog) {
        var old by remember { mutableStateOf("") }; var new by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { pwDialog = false }, containerColor = Tahoe.Surface, title = { Text(t("change_pw")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Field(old, { old = it }, t("old_pw"), secret = true); Field(new, { new = it }, t("new_pw"), secret = true) } },
            confirmButton = { TextButton({ pwDialog = false; run { Account.api(c)?.changePassword(old, new); msg = t("saved") } }) { Text(tech.kousozo.tahoetv.core.I18n.get(lang, "common.save"), color = Tahoe.Accent) } },
            dismissButton = { TextButton({ pwDialog = false }) { Text(tech.kousozo.tahoetv.core.I18n.get(lang, "common.cancel"), color = Tahoe.Dim) } })
    }
    if (delDialog) {
        var pw by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { delDialog = false }, containerColor = Tahoe.Surface, title = { Text(t("delete")) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(t("delete_note"), color = Tahoe.Dim, fontSize = 14.sp); Field(pw, { pw = it }, t("password"), secret = true) } },
            confirmButton = { TextButton({ delDialog = false; run { Account.api(c)?.deleteAccount(pw); Account.signOut(c, remote = false) } }) { Text(t("confirm_delete"), color = Tahoe.Bad) } },
            dismissButton = { TextButton({ delDialog = false }) { Text(tech.kousozo.tahoetv.core.I18n.get(lang, "common.cancel"), color = Tahoe.Dim) } })
    }

    PageScaffold(t("title"), if (Account.signedIn) t("signed_as", Account.email) else t("sub")) {
        if (!Account.signedIn) {
            if (BuildConfig.ACCOUNT_URL.isBlank()) Field(server, { server = it; Account.setServerUrl(c, it) }, t("server"), keyboard = androidx.compose.ui.text.input.KeyboardType.Uri)
            Field(mail, { mail = it }, t("email"), keyboard = androidx.compose.ui.text.input.KeyboardType.Email)
            Field(pass, { pass = it }, t("password"), secret = true)
            if (registerMode) Field(invite, { invite = it }, t("invite"), secret = true)
            PrimaryButton(if (busy) t("busy") else if (registerMode) t("register") else t("signin"), enabled = !busy && mail.isNotBlank() && pass.isNotEmpty() && Account.available(c)) {
                run {
                    val api = Account.api(c) ?: return@run
                    val s = if (registerMode) api.register(mail, pass, invite) else api.login(mail, pass)
                    Account.signedInAs(c, s); pass = ""; Account.pull(c)
                }
            }
            Text(if (registerMode) t("to_signin") else t("to_register"), color = Tahoe.Accent, fontSize = 15.sp, modifier = Modifier.clickable { registerMode = !registerMode; msg = "" }.padding(vertical = 8.dp))
        } else {
            val p = Account.profile
            // photo + wallpaper
            MenuGroup(t("photo")) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(72.dp).clip(CircleShape).background(Tahoe.Glass), contentAlignment = Alignment.Center) {
                        val img = Account.avatar
                        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Icon(Icons.Rounded.Person, null, tint = Tahoe.Faint, modifier = Modifier.size(34.dp))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(t("pick_photo"), Icons.Rounded.PhotoCamera) { pickAvatar.launch("image/*") }
                        if (p.avatarId.isNotBlank()) Text(t("remove"), color = Tahoe.Bad, fontSize = 14.sp, modifier = Modifier.clickable { run { Account.removePicture(c, "avatar") } }.padding(4.dp))
                    }
                }
            }
            MenuGroup(t("wallpaper")) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(18.dp)).background(Tahoe.Glass), contentAlignment = Alignment.Center) {
                        val img = Account.wallpaper
                        if (img != null) { Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop); Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = p.wallpaperDim / 100f))) }
                        else Icon(Icons.Rounded.Image, null, tint = Tahoe.Faint, modifier = Modifier.size(34.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        PillButton(t("pick_wall"), Icons.Rounded.Wallpaper) { pickWall.launch("image/*") }
                        if (p.wallpaperId.isNotBlank()) Text(t("remove"), color = Tahoe.Bad, fontSize = 14.sp, modifier = Modifier.clickable { run { Account.removePicture(c, "wallpaper") } }.padding(4.dp))
                    }
                    Text("${t("dim")}: ${p.wallpaperDim}%", color = Tahoe.Dim, fontSize = 13.sp)
                    var dim by remember(p.wallpaperDim) { mutableFloatStateOf(p.wallpaperDim.toFloat()) }
                    Slider(dim, { dim = it }, valueRange = 0f..80f, onValueChangeFinished = { save(p.copy(wallpaperDim = dim.toInt())) },
                        colors = SliderDefaults.colors(thumbColor = Tahoe.Accent, activeTrackColor = Tahoe.Accent))
                }
            }
            // display
            MenuGroup(t("display")) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    var name by remember(p.displayName) { mutableStateOf(p.displayName) }
                    Field(name, { name = it.take(40) }, t("name"), trailing = { if (name != p.displayName) TextButton({ save(p.copy(displayName = name.trim())) }) { Text(tech.kousozo.tahoetv.core.I18n.get(lang, "common.save"), color = Tahoe.Accent) } })
                    Text(t("theme") + " — " + t("on_phone"), color = Tahoe.Dim, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("system" to "t_system", "light" to "t_light", "dark" to "t_dark").forEach { (v, k) -> Chip(t(k), p.theme == v) { save(p.copy(theme = v)) } }
                    }
                    Text("${t("text_size")} — ${t("on_phone")}: ${(p.textScale * 100).toInt()}%", color = Tahoe.Dim, fontSize = 13.sp)
                    var scale by remember(p.textScale) { mutableFloatStateOf(p.textScale) }
                    Slider(scale, { scale = it }, valueRange = 0.85f..1.3f, onValueChangeFinished = { save(p.copy(textScale = scale)) },
                        colors = SliderDefaults.colors(thumbColor = Tahoe.Accent, activeTrackColor = Tahoe.Accent))
                }
                SwitchMenuRow(Icons.Rounded.Animation, t("motion"), p.reduceMotion) { save(p.copy(reduceMotion = it)) }
            }
            MenuGroup(t("on_tv")) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(t("accent"), color = Tahoe.Dim, fontSize = 13.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf("" to 0xFF7AA2FF, "#34C759" to 0xFF34C759, "#FF9F0A" to 0xFFFF9F0A, "#FF453A" to 0xFFFF453A, "#BF5AF2" to 0xFFBF5AF2, "#64D2FF" to 0xFF64D2FF).forEach { (hex, argb) ->
                            Box(Modifier.size(44.dp).clip(CircleShape).background(Color(argb)).clickable(role = androidx.compose.ui.semantics.Role.RadioButton) { save(p.copy(accent = hex)) }, contentAlignment = Alignment.Center) {
                                if (p.accent == hex) Icon(Icons.Rounded.Check, null, tint = Color.Black, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                    Text(t("clock_size"), color = Tahoe.Dim, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("s", "m", "l").forEach { Chip(t(it), p.clockSize == it) { save(p.copy(clockSize = it)) } } }
                    Text(t("density"), color = Tahoe.Dim, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("comfortable", "compact").forEach { Chip(t(it), p.density == it) { save(p.copy(density = it)) } } }
                }
                SwitchMenuRow(Icons.Rounded.Schedule, t("clock24"), p.clock24) { save(p.copy(clock24 = it)) }
                SwitchMenuRow(Icons.Rounded.Timer, t("seconds"), p.clockSeconds) { save(p.copy(clockSeconds = it)) }
                SwitchMenuRow(Icons.Rounded.WavingHand, t("greeting"), p.greeting) { save(p.copy(greeting = it)) }
            }
            MenuGroup(t("security")) {
                MenuRow(Icons.Rounded.Tv, t("to_tv")) {
                    val link = Sync.devices(c).active()?.let { tech.kousozo.tahoetv.core.TvLink.shared(it.host, it.token) }
                    if (link == null) msg = t("tv_fail") else { link.account(Account.serverUrl(c), Account.token(c).orEmpty(), Account.email); msg = t("tv_sent") }
                }
                MenuRow(Icons.Rounded.Key, t("change_pw")) { pwDialog = true }
                MenuRow(Icons.Rounded.Logout, t("signout")) { Account.signOut(c) }
                MenuRow(Icons.Rounded.DeleteOutline, t("delete"), tint = Tahoe.Bad) { delDialog = true }
            }
            Text(t("storage"), color = Tahoe.Faint, fontSize = 12.sp)
        }
        if (msg.isNotEmpty()) Text(msg, color = if (msg == t("saved") || msg == t("tv_sent")) Tahoe.Dim else Tahoe.Bad, fontSize = 14.sp,
            modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
    }
}
