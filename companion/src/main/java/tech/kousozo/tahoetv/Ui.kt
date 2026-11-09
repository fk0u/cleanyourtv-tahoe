package tech.kousozo.tahoetv

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
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
object Tahoe {
    val Bg = Color(0xFF07080D)
    val Text = Color(0xFFF5F5F7)
    val Dim = Text.copy(alpha = 0.7f)
    val Faint = Text.copy(alpha = 0.45f)
    val Glass = Color.White.copy(alpha = 0.09f)
    val Stroke = Color.White.copy(alpha = 0.14f)
    val Accent = Color(0xFF64D2FF)
    val Good = Color(0xFF30D158)
    val Bad = Color(0xFFFF453A)
}

@Composable
fun TahoeTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = Tahoe.Text, onPrimary = Color(0xFF111114), background = Tahoe.Bg, surface = Tahoe.Bg,
        onBackground = Tahoe.Text, onSurface = Tahoe.Text, surfaceVariant = Tahoe.Glass, onSurfaceVariant = Tahoe.Dim,
        secondary = Tahoe.Accent, outline = Tahoe.Stroke, error = Tahoe.Bad,
    ),
    content = { CompositionLocalProvider(LocalContentColor provides Tahoe.Text, content = content) }, // light text outside Surfaces too
)

/** Ambient accent glow behind everything: a soft cyan light from the top that breathes slowly, fading into near-black.
 *  The brand accent does the lighting, so screens feel lit rather than flat. GPU-cheap (two radial gradients). */
@Composable
fun Aurora(modifier: Modifier = Modifier) {
    val t by rememberInfiniteTransition(label = "glow").animateFloat(0f, 1f,
        infiniteRepeatable(tween(9_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "t")
    Canvas(modifier.fillMaxSize().background(Tahoe.Bg)) {
        fun glow(c: Color, x: Float, y: Float, r: Float) {
            val center = Offset(size.width * x, size.height * y); val radius = size.maxDimension * r
            drawCircle(Brush.radialGradient(listOf(c, Color.Transparent), center, radius), radius, center)
        }
        glow(Tahoe.Accent.copy(alpha = 0.30f + 0.08f * t), 0.5f, -0.05f, 0.62f + 0.04f * t)   // top light
        glow(Color(0xFF1F3BD1).copy(alpha = 0.22f), 0.05f + 0.1f * t, 0.55f, 0.45f)          // deep blue side fill
        glow(Tahoe.Accent.copy(alpha = 0.10f), 0.95f, 1.0f, 0.5f)                             // faint bottom rim
    }
}

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
    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape, color = Tahoe.Glass,
        border = BorderStroke(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.06f)))),
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
    Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
) {
    Text(title, color = Tahoe.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
    Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Tahoe.Good, checkedThumbColor = Color.White))
}

/** Floating glass tab bar with a raised accent button in the middle (the primary action). `tabs` has an odd count;
 *  the middle entry is the raised one. */
@Composable
fun TabBar(tabs: List<Pair<String, ImageVector>>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val mid = tabs.size / 2
    Box(modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().height(78.dp)) {
        Surface(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(64.dp),
            shape = RoundedCornerShape(32.dp), color = Color(0xFF12141A).copy(alpha = 0.88f),
            border = BorderStroke(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.04f)))),
            shadowElevation = 16.dp,
        ) {
            Row(Modifier.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEachIndexed { i, (label, icon) ->
                    if (i == mid) { Spacer(Modifier.weight(1.2f)); return@forEachIndexed }
                    val on = i == selected
                    val tint by animateColorAsState(if (on) Tahoe.Accent else Tahoe.Faint, label = "tab")
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(26.dp)).clickable { onSelect(i) },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp))
                        Text(label, fontSize = 10.sp, color = if (on) Tahoe.Text else Tahoe.Faint, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                        Box(Modifier.padding(top = 3.dp).size(if (on) 4.dp else 0.dp).clip(RoundedCornerShape(50)).background(Tahoe.Accent))
                    }
                }
            }
        }
        // raised centre button with an accent glow
        val (label, icon) = tabs[mid]
        val on = selected == mid
        val scale by animateFloatAsState(if (on) 1.06f else 1f, label = "fab")
        Box(Modifier.align(Alignment.TopCenter).size(64.dp).scale(scale)
            .shadow(18.dp, RoundedCornerShape(50), ambientColor = Tahoe.Accent, spotColor = Tahoe.Accent)
            .clip(RoundedCornerShape(50))
            .background(Brush.verticalGradient(listOf(Color(0xFF8BE0FF), Tahoe.Accent, Color(0xFF2EA8E0))))
            .clickable { onSelect(mid) }, contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Color(0xFF06222F), modifier = Modifier.size(28.dp))
        }
    }
}

/** Remote images (channel logos) with an in-memory cache; null while loading or on failure. */
@Composable
fun rememberNetImage(url: String?): androidx.compose.ui.graphics.ImageBitmap? {
    var img by remember(url) { mutableStateOf(url?.let { NetImages.cache.get(it) }) }
    LaunchedEffect(url) {
        if (url == null || img != null) return@LaunchedEffect
        img = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val c = java.net.URL(url).openConnection().apply { connectTimeout = 6000; readTimeout = 10000; setRequestProperty("User-Agent", "TahoeTV") }
                val o = android.graphics.BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
                android.graphics.BitmapFactory.decodeStream(c.getInputStream(), null, o)?.let { b ->
                    // logos only need ~200 px: keep memory small
                    val s = minOf(1f, 200f / maxOf(b.width, b.height))
                    (if (s < 1f) android.graphics.Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true) else b)
                }?.asImageBitmap()
            }.getOrNull()?.also { b: androidx.compose.ui.graphics.ImageBitmap -> NetImages.cache.put(url, b) }
        }
    }
    return img
}

object NetImages { val cache = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(200) }
