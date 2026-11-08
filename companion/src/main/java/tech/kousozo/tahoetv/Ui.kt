package tech.kousozo.tahoetv

import androidx.compose.foundation.BorderStroke
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
    val Glass = Color.White.copy(alpha = 0.08f)
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

/** Scrolling page with an eyebrow, large title and subtitle (HIG large-title layout). */
@Composable
fun Page(eyebrow: String?, title: String, sub: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Tahoe.Bg).systemBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        eyebrow?.let { Text(it.uppercase(), color = Tahoe.Faint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp) }
        Text(title, color = Tahoe.Text, fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp)
        sub?.let { Text(it, color = Tahoe.Dim, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        content()
    }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
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
    Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
) {
    Text(title, color = Tahoe.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
    Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Tahoe.Good, checkedThumbColor = Color.White))
}
