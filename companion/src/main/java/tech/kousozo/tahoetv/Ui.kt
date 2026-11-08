package tech.kousozo.tahoetv

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
    content = content,
)

/** Slow-drifting colour blobs behind everything: the Tahoe wallpaper feel without an image (GPU-cheap). */
@Composable
fun Aurora(modifier: Modifier = Modifier) {
    val t by rememberInfiniteTransition(label = "aurora").animateFloat(0f, 1f,
        infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Reverse), label = "t")
    Canvas(modifier.fillMaxSize().background(Tahoe.Bg)) {
        fun blob(c: Color, x: Float, y: Float, r: Float) =
            drawCircle(Brush.radialGradient(listOf(c, Color.Transparent), Offset(size.width * x, size.height * y), size.minDimension * r), size.minDimension * r, Offset(size.width * x, size.height * y))
        blob(Color(0xFF2A4BFF).copy(alpha = 0.45f), 0.15f + 0.2f * t, 0.12f, 0.9f)
        blob(Color(0xFFB13CFF).copy(alpha = 0.32f), 0.9f - 0.25f * t, 0.35f + 0.1f * t, 0.8f)
        blob(Color(0xFF00C2FF).copy(alpha = 0.22f), 0.4f, 0.95f - 0.15f * t, 0.9f)
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

/** Floating glass tab bar (iOS 26 style). */
@Composable
fun TabBar(tabs: List<Pair<String, ImageVector>>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) = Surface(
    modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 10.dp).fillMaxWidth().height(64.dp),
    shape = RoundedCornerShape(32.dp), color = Color(0xFF1C1C22).copy(alpha = 0.82f),
    border = BorderStroke(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.25f), Color.White.copy(alpha = 0.05f)))),
    shadowElevation = 12.dp,
) {
    Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        tabs.forEachIndexed { i, (label, icon) ->
            val on = i == selected
            val bg by animateColorAsState(if (on) Color.White.copy(alpha = 0.14f) else Color.Transparent, label = "tab")
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(26.dp)).background(bg).clickable { onSelect(i) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Icon(icon, label, tint = if (on) Tahoe.Accent else Tahoe.Dim, modifier = Modifier.size(22.dp))
                Text(label, fontSize = 11.sp, color = if (on) Tahoe.Text else Tahoe.Dim, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}
