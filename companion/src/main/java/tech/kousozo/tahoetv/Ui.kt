package tech.kousozo.tahoetv

import androidx.compose.ui.graphics.drawscope.scale
import tech.kousozo.tahoetv.core.Brand
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Same palette as the TV launcher: near-black, glass surfaces, SF-like system blue. */
/** Colours come from core [Brand] (the one source shared with the TV launcher and desktop); see docs/brand/BRAND.md. */
/** Phone palette. Text, accent and status colours come from core [Brand] (shared with the TV and desktop); the
 *  background, surface, glass and hairline are phone-only overrides for the redesign (#60, inspired by Mochy Music's
 *  look, own code): a warm charcoal instead of blue-black, quieter glass, and colour coming from what's on screen. */
/** Appearance preferences (#41). Default DARK keeps the classic look; SYSTEM follows the OS. Read in composition,
 *  so changing them recomposes everything that uses [Tahoe]. */
object ThemePrefs {
    var mode by androidx.compose.runtime.mutableStateOf(tech.kousozo.tahoetv.core.Theme.Mode.DARK)
    var highContrast by androidx.compose.runtime.mutableStateOf(false)
    var reduceMotion by androidx.compose.runtime.mutableStateOf(false)
    /** Resolved by [TahoeTheme] from [mode] and the system setting. */
    internal var systemDark by androidx.compose.runtime.mutableStateOf(true)
    val dark get() = tech.kousozo.tahoetv.core.Theme.isDark(mode, systemDark)
    val palette get() = tech.kousozo.tahoetv.core.Theme.palette(dark, highContrast) // Figma bg/canvas #0D0F14, bg/surface #191C23
}

object Tahoe {
    private val p get() = ThemePrefs.palette
    val Bg get() = Color(p.bg)
    val Surface get() = Color(p.surface)
    val SurfaceHi get() = Color(p.surfaceHi)
    val Text get() = Color(p.text)
    val Dim get() = Color(p.dim)
    val Faint get() = Color(p.faint)
    val Glass get() = Color(p.glass)
    val Stroke get() = Color(p.stroke)
    val Accent get() = Color(p.accent)
    val OnAccent get() = Color(p.onAccent)
    val Lake = Color(Brand.LAKE)
    val Warm get() = Color(p.warm)
    val Good = Color(Brand.PINE)
    val Bad = Color(Brand.LIVE)
    /** Video stages stay night-dark in every theme. */
    val Stage = Color(Brand.NIGHT)
}

/** Outfit (variable, OFL), the brand typeface: one file, real weights through the wght axis. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Outfit = FontFamily(listOf(300, 400, 500, 600, 700, 800).map { w ->
    Font(R.font.outfit, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
})


/** Every Material text style in the brand face. */
private fun Typography.withFont(f: FontFamily) = Typography(
    displayLarge.copy(fontFamily = f), displayMedium.copy(fontFamily = f), displaySmall.copy(fontFamily = f),
    headlineLarge.copy(fontFamily = f), headlineMedium.copy(fontFamily = f), headlineSmall.copy(fontFamily = f),
    titleLarge.copy(fontFamily = f), titleMedium.copy(fontFamily = f), titleSmall.copy(fontFamily = f),
    bodyLarge.copy(fontFamily = f), bodyMedium.copy(fontFamily = f), bodySmall.copy(fontFamily = f),
    labelLarge.copy(fontFamily = f), labelMedium.copy(fontFamily = f), labelSmall.copy(fontFamily = f),
)

/** The Tahoe TV mark (core [Brand.MARK_PATH]): a screen with two peaks over a lake, and the live light. */
@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier, tint: Color = Tahoe.Text, light: Color = Tahoe.Accent) {
    val path = remember { androidx.compose.ui.graphics.vector.PathParser().parsePathString(Brand.MARK_PATH).toPath() }
    Canvas(modifier.size(size)) {
        scale(this.size.minDimension / 24f, pivot = Offset.Zero) {
            drawPath(path, tint, style = androidx.compose.ui.graphics.drawscope.Stroke(2f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            drawCircle(light, Brand.MARK_LIGHT_R, Offset(Brand.MARK_LIGHT_X, Brand.MARK_LIGHT_Y))
        }
    }
}

/** The app icon in miniature: brand gradient squircle with the white mark. Use where the product signs its name. */
@Composable
fun BrandTile(size: Dp, modifier: Modifier = Modifier) = Box(
    modifier.size(size).clip(RoundedCornerShape(size * 0.28f))
        .background(Brush.linearGradient(listOf(Color(Brand.CYAN), Color(Brand.LAKE), Color(Brand.LAKE_DEEP)))),
    contentAlignment = Alignment.Center,
) { BrandMark(size * 0.62f, tint = Color.White, light = Color(Brand.CYAN_LIGHT)) }

@Composable
fun TahoeTheme(content: @Composable () -> Unit) {
    ThemePrefs.systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    TahoeThemeInner(content)
}

@Composable
private fun TahoeThemeInner(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = (if (ThemePrefs.dark) darkColorScheme() else androidx.compose.material3.lightColorScheme()).copy(
        primary = Tahoe.Text, onPrimary = Color(Brand.CANVAS), background = Tahoe.Bg, surface = Tahoe.Surface,
        onBackground = Tahoe.Text, onSurface = Tahoe.Text, surfaceVariant = Tahoe.Glass, onSurfaceVariant = Tahoe.Dim,
        secondary = Tahoe.Accent, onSecondary = Tahoe.OnAccent, tertiary = Tahoe.Lake, outline = Tahoe.Stroke, error = Tahoe.Bad,
    ),
    typography = Typography().withFont(Outfit),
    content = { CompositionLocalProvider(LocalContentColor provides Tahoe.Text, LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = Outfit),
        androidx.compose.ui.platform.LocalLayoutDirection provides layoutDirection(), content = content) }, // light text outside Surfaces too
)

/** Backdrop behind every screen: warm charcoal, lit from the top by [tint] (the playing channel's colour, like a music
 *  app tinting itself with the album cover). The tint cross-fades when it changes. GPU-cheap: two gradients. */
@Composable
fun Backdrop(tint: Color?, modifier: Modifier = Modifier) {
    // Figma screens sit on a flat bg/canvas; only a playing channel tints the top, faintly
    val t by animateColorAsState(tint ?: Color.Transparent, tween(900), label = "tint")
    Canvas(modifier.fillMaxSize().background(Tahoe.Bg)) {
        if (t.alpha == 0f) return@Canvas
        drawRect(Brush.verticalGradient(0f to t.copy(alpha = 0.42f), 0.45f to t.copy(alpha = 0.10f), 0.75f to Color.Transparent))
        val c = Offset(size.width * 0.15f, size.height * 0.08f); val r = size.maxDimension * 0.55f
        drawCircle(Brush.radialGradient(listOf(t.copy(alpha = 0.25f), Color.Transparent), c, r), r, c)
    }
}

/** The colour a logo "feels" like: the average of its saturated, mid-bright pixels (logos sit on white, so white,
 *  black and greys are skipped). Null for a logo that's all grey. Cached per bitmap. */
fun dominantColor(img: androidx.compose.ui.graphics.ImageBitmap): Color? = DominantCache.get(img) {
    val b = android.graphics.Bitmap.createScaledBitmap(img.asAndroidBitmap(), 24, 24, true)
    var r = 0f; var g = 0f; var bl = 0f; var w = 0f
    val hsv = FloatArray(3)
    for (y in 0 until 24) for (x in 0 until 24) {
        val p = b.getPixel(x, y)
        if (android.graphics.Color.alpha(p) < 128) continue
        android.graphics.Color.colorToHSV(p, hsv)
        if (hsv[1] < 0.25f || hsv[2] < 0.2f) continue
        val weight = hsv[1] * hsv[2]
        r += android.graphics.Color.red(p) * weight; g += android.graphics.Color.green(p) * weight; bl += android.graphics.Color.blue(p) * weight; w += weight
    }
    if (w < 1f) null else Color((r / w).toInt(), (g / w).toInt(), (bl / w).toInt())
}

private object DominantCache {
    private val map = java.util.WeakHashMap<androidx.compose.ui.graphics.ImageBitmap, Any>()
    private val NONE = Any()
    @Synchronized fun get(k: androidx.compose.ui.graphics.ImageBitmap, f: () -> Color?): Color? {
        map[k]?.let { return if (it === NONE) null else it as Color }
        val v = runCatching(f).getOrNull(); map[k] = v ?: NONE; return v
    }
}

/** Figma "Chip": glass pill with a hairline (Default), solid text colour when Selected. Label 14 Medium. */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Tahoe.Text else Tahoe.Glass, label = "chip")
    Text(text, Modifier.clip(RoundedCornerShape(50)).background(bg)
        .border(1.dp, if (selected) Color.Transparent else Tahoe.Stroke, RoundedCornerShape(50))
        .selectable(selected = selected, onClick = onClick, role = androidx.compose.ui.semantics.Role.RadioButton) // TalkBack: "dipilih"
        .padding(horizontal = 16.dp, vertical = 8.dp),
        color = if (selected) Tahoe.Bg else Tahoe.Dim, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.14.sp, fontWeight = FontWeight.Medium)
}

/** Figma "Button / Primary": solid pill (text colour), canvas-coloured label 16 Medium, optional 18 dp icon. */
@Composable
fun PillButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(modifier.pressScale(press).clip(RoundedCornerShape(50)).background(Tahoe.Text).clickable(press, androidx.compose.material3.ripple(), onClick = onClick)
        .padding(start = if (icon != null) 20.dp else 24.dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    icon?.let { Icon(it, null, tint = Tahoe.Bg, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)) }
    Text(text, color = Tahoe.Bg, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium)
    }
}

/** Figma "Button / Glass": translucent pill with a hairline, for secondary actions over imagery. */
@Composable
fun GlassPill(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(modifier.pressScale(press).clip(RoundedCornerShape(50)).background(Tahoe.Glass).border(1.dp, Tahoe.Stroke, RoundedCornerShape(50))
        .clickable(press, androidx.compose.material3.ripple(), onClick = onClick).padding(start = if (icon != null) 20.dp else 24.dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    icon?.let { Icon(it, null, tint = Tahoe.Text, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)) }
    Text(text, color = Tahoe.Text, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** Figma "Button / Accent": Tahoe Cyan pill, dark label, optional 18 dp icon. */
@Composable
fun AccentPillButton(text: String, icon: ImageVector? = null, fullWidth: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = 48.dp)
            .pressScale(press)
            .clip(RoundedCornerShape(50))
            .background(Tahoe.Accent)
            .clickable(press, androidx.compose.material3.ripple(), onClick = onClick)
            .padding(start = if (icon != null && !fullWidth) 20.dp else 24.dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (fullWidth) Arrangement.Center else Arrangement.Start,
    ) {
        icon?.let { Icon(it, null, tint = Tahoe.OnAccent, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)) }
        Text(text, color = Tahoe.OnAccent, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium)
    }
}

/** Figma ".back": 44 dp circular back button inside a 48 dp touch target. */
@Composable
fun BackCircle(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) = Box(
    modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
) {
    Box(
        Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (ThemePrefs.dark) Color.White.copy(alpha = 0.10f) else Tahoe.Glass),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, label, tint = Tahoe.Text, modifier = Modifier.size(20.dp))
    }
}

/** Figma ".switch": 52 × 32 pill track with 26 dp animated knob. */
@Composable
fun FigmaSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val thumbX by androidx.compose.animation.core.animateDpAsState(
        if (checked) 23.dp else 3.dp,
        androidx.compose.animation.core.tween(if (ThemePrefs.reduceMotion) 0 else tech.kousozo.tahoetv.core.Brand.MOTION_FAST, easing = EaseOutTahoe),
        label = "switch",
    )
    Box(
        modifier.heightIn(min = 48.dp).widthIn(min = 52.dp)
            .toggleable(checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onCheckedChange),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(52.dp, 32.dp).clip(RoundedCornerShape(50))
                .background(if (checked) Tahoe.Accent else if (ThemePrefs.dark) Color.White.copy(alpha = 0.16f) else Tahoe.Glass),
        ) {
            Box(
                Modifier.offset(x = thumbX, y = 3.dp).size(26.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (checked) Tahoe.OnAccent else Tahoe.Dim),
            )
        }
    }
}

/** Figma ".search.pill": borderless (or active cyan border) 53–56 dp rounded pill search input. */
@Composable
fun SearchPill(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    active: Boolean = false,
    modifier: Modifier = Modifier,
) = Row(
    modifier.fillMaxWidth().heightIn(min = 53.dp)
        .clip(RoundedCornerShape(50))
        .background(Tahoe.Surface)
        .then(if (active) Modifier.border(1.5.dp, Tahoe.Accent, RoundedCornerShape(50)) else Modifier)
        .padding(horizontal = 18.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Icon(Icons.Rounded.Search, null, tint = Tahoe.Dim, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(10.dp))
    Box(Modifier.weight(1f)) {
        if (value.isEmpty()) Text(placeholder, color = Tahoe.Faint, fontSize = 16.sp, lineHeight = 23.sp)
        androidx.compose.foundation.text.BasicTextField(
            value, onValueChange, singleLine = true,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = placeholder
            },
            textStyle = androidx.compose.ui.text.TextStyle(color = Tahoe.Text, fontSize = 16.sp, fontFamily = Outfit),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Tahoe.Accent),
        )
    }
    if (value.isNotEmpty()) {
        IconButton({ onValueChange("") }, Modifier.size(32.dp)) {
            Icon(Icons.Rounded.Close, tech.kousozo.tahoetv.core.I18n.get(appLanguage(), "live.clear_search"), tint = Tahoe.Dim, modifier = Modifier.size(18.dp))
        }
    }
}

/** Figma "Icon Button": 48 dp glass circle with a hairline and a 22 dp icon. */
@Composable
fun RoundGlass(icon: ImageVector, label: String, size: Dp = 48.dp, tint: Color = Tahoe.Text, onClick: () -> Unit) = Box(
    Modifier.size(size).clip(RoundedCornerShape(50)).background(Tahoe.Glass).border(1.dp, Tahoe.Stroke, RoundedCornerShape(50)).clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
) { Icon(icon, label, tint = tint, modifier = Modifier.size(size * 0.46f)) }

/** Figma "Live Badge": red pill, white dot, LIVE in 11 SemiBold caps. */
@Composable
fun LiveBadge(modifier: Modifier = Modifier) = Row(
    modifier.clip(RoundedCornerShape(50)).background(Tahoe.Bad).padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(Color.White)); Spacer(Modifier.width(6.dp))
    Text("LIVE", color = Color.White, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.32.sp)
}

/** Figma "Progress Bar": 4 dp track (20 % white) with an accent fill. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) = Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.2f))) {
    Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Tahoe.Accent))
}

/** Small monospace caption (times, channel numbers, formats): the "spec sheet" voice. */
val Mono = FontFamily.Monospace

/** Scrolling page with an eyebrow, large title and subtitle (HIG large-title layout). "Langkah N dari 5" adds a step bar. */
@Composable
fun Page(eyebrow: String?, title: String, sub: String? = null, bottomInset: Dp = 88.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 24.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val step = eyebrow?.let { Regex("Langkah (\\d) dari (\\d)").find(it) }?.destructured?.let { (a, b) -> a.toInt() to b.toInt() }
        if (step != null) Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(step.second) { i ->
                val on by animateColorAsState(if (i < step.first) Tahoe.Accent else Tahoe.Stroke, label = "step")
                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(on))
            }
        }
        eyebrow?.let { Text(it.uppercase(), color = Tahoe.Faint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp) }
        Text(title, color = Tahoe.Text, fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp)
        sub?.let { Text(it, color = Tahoe.Dim, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        content()
    }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Surface(
        modifier = modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape, color = Tahoe.Glass, border = BorderStroke(1.dp, Tahoe.Stroke),
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content) }
}

@Composable
fun Note(text: String, icon: ImageVector? = null) = GlassCard {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        icon?.let { Icon(it, null, tint = Tahoe.Accent, modifier = Modifier.size(20.dp)) }
        Text(text, color = Tahoe.Text.copy(alpha = 0.9f), fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
fun Section(text: String) = Text(text, color = Tahoe.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) = Button(
    onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
    colors = ButtonDefaults.buttonColors(containerColor = Tahoe.Text, contentColor = Color(0xFF111114)),
) {
    icon?.let { Icon(it, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
    Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
fun SecondaryButton(text: String, icon: ImageVector? = null, color: Color = Tahoe.Text, onClick: () -> Unit) = OutlinedButton(
    onClick, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp),
    border = BorderStroke(1.dp, Tahoe.Stroke), colors = ButtonDefaults.outlinedButtonColors(containerColor = Tahoe.Glass, contentColor = color),
) {
    icon?.let { Icon(it, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
    Text(text, fontSize = 15.sp)
}

/** Text field. `secret` = API keys: no suggestions/IME learning, masked. */
@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, keyboard: KeyboardType = KeyboardType.Text, secret: Boolean = false,
          trailing: @Composable (() -> Unit)? = null) = OutlinedTextField(
    value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
    keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = !secret),
    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None, trailingIcon = trailing,
    colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Tahoe.Accent, unfocusedBorderColor = Tahoe.Stroke, focusedLabelColor = Tahoe.Accent,
        unfocusedLabelColor = Tahoe.Faint, cursorColor = Tahoe.Accent, focusedContainerColor = Color.Black.copy(alpha = 0.35f),
        unfocusedContainerColor = Color.Black.copy(alpha = 0.35f),
    ),
)

@Composable
fun CheckRow(title: String, sub: String?, checked: Boolean, badge: String? = null, enabled: Boolean = true, onToggle: () -> Unit) = Row(
    Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onToggle).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
) {
    Checkbox(checked, { onToggle() }, enabled = enabled, colors = CheckboxDefaults.colors(checkedColor = Tahoe.Accent, checkmarkColor = Tahoe.Bg, uncheckedColor = Tahoe.Faint))
    Column(Modifier.weight(1f)) {
        Text(title, color = if (enabled) Tahoe.Text else Tahoe.Faint, fontSize = 15.sp)
        sub?.let { Text(it, color = Tahoe.Faint, fontSize = 12.sp) }
    }
    badge?.let {
        Text(it, color = Tahoe.Bg, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.background(Tahoe.Accent, RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 2.dp))
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) = Row(
    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
) {
    Text(title, color = Tahoe.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
    FigmaSwitch(checked, onChange)
}

/** Floating pill tab bar (Mochy-style): icons only, the selected tab grows into a pill with its label. */
@Composable
fun TabBar(tabs: List<Pair<String, ImageVector>>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) = BoxWithConstraints(modifier) {
    // narrow phones (< 360 dp): icons only, so the selected label never pushes the last tab off the bar
    val labels = maxWidth >= 360.dp
    Surface(
        Modifier.navigationBarsPadding().padding(horizontal = if (labels) 22.dp else 12.dp, vertical = 10.dp).fillMaxWidth().height(62.dp),
        shape = RoundedCornerShape(31.dp), color = Color(0xFF1C1A1F).copy(alpha = 0.94f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)), shadowElevation = 18.dp,
    ) {
        Row(Modifier.padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            tabs.forEachIndexed { i, (label, icon) ->
                val on = i == selected
                val bg by animateColorAsState(if (on) Color.White.copy(alpha = 0.14f) else Color.Transparent, label = "tab")
                Row(
                    Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(bg)
                        .selectable(selected = on, onClick = { onSelect(i) }, role = androidx.compose.ui.semantics.Role.Tab) // TalkBack: tab, dipilih
                        .padding(horizontal = if (on && labels) 16.dp else 13.dp).animateContentSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, label, tint = if (on) Tahoe.Text else Tahoe.Faint, modifier = Modifier.size(22.dp))
                    if (on && labels) { Spacer(Modifier.width(8.dp)); Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                }
            }
        }
    }
}

/** Remote images (channel logos) with a byte-bounded in-memory LRU cache and downsampled decoding. */
@Composable
fun rememberNetImage(url: String?): androidx.compose.ui.graphics.ImageBitmap? {
    var img by remember(url) { mutableStateOf(url?.let { NetImages.cache.get(it) }) }
    LaunchedEffect(url) {
        if (url == null || img != null) return@LaunchedEffect
        img = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val c = java.net.URI(url).toURL().openConnection().apply { connectTimeout = 6000; readTimeout = 10000; setRequestProperty("User-Agent", "TahoeTV") }
                val data = try { c.getInputStream().use { it.readBytes() } } finally { (c as? java.net.HttpURLConnection)?.disconnect() }
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, o)
                var sample = 1
                while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 200) sample *= 2
                o.inJustDecodeBounds = false
                o.inSampleSize = sample
                o.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, o)?.let { b ->
                    // logos only need ~200 px: keep memory small
                    val s = minOf(1f, 200f / maxOf(b.width, b.height))
                    val scaled = if (s < 1f) android.graphics.Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true) else b
                    if (scaled !== b) b.recycle()
                    scaled
                }?.asImageBitmap()
            }.getOrNull()?.also { b: androidx.compose.ui.graphics.ImageBitmap -> NetImages.cache.put(url, b) }
        }
    }
    return img
}

object NetImages {
    val cache = object : android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: androidx.compose.ui.graphics.ImageBitmap): Int =
            (value.width * value.height * 4).coerceAtLeast(1)
    }
}

/** RTL for Arabic & co. (#71), from the app language. */
@Composable
fun layoutDirection(): androidx.compose.ui.unit.LayoutDirection =
    if (tech.kousozo.tahoetv.core.Region.isRtl(appLanguage())) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr

/** Video, seek bars and transport controls never mirror: time runs left→right in every language. */
@Composable
fun KeepLtr(content: @Composable () -> Unit) =
    CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr, content = content)

internal fun appLanguage(): String = PhoneRegion.language
