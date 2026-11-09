package tech.kousozo.tahoetv.desktop

import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import tech.kousozo.tahoetv.core.Brand
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Same palette as the TV launcher and the phone app. */
/** Colours come from core [Brand] (the one source shared with the TV launcher and desktop); see docs/brand/BRAND.md. */
object Tahoe {
    val Bg = Color(Brand.CANVAS)
    val Surface = Color(Brand.SURFACE)
    val Text = Color(Brand.SNOW)
    val Dim = Color(Brand.TEXT_2)
    val Faint = Color(Brand.TEXT_3)
    val Glass = Color(Brand.GLASS)
    val Stroke = Color(Brand.STROKE)
    val Accent = Color(Brand.CYAN)
    val OnAccent = Color(Brand.ON_CYAN)
    val Lake = Color(Brand.LAKE)
    val Good = Color(Brand.PINE)
    val Bad = Color(Brand.LIVE)
    val Card = Color(Brand.SURFACE)
    val CardHi = Color(Brand.SURFACE_HI)
    val GlassBorder = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.26f), Color.White.copy(alpha = 0.05f)))
}

/** Outfit (variable, OFL), the brand typeface, from resources/font. */
val Outfit = FontFamily(listOf(300, 400, 500, 600, 700, 800).map { w ->
    androidx.compose.ui.text.platform.Font("font/Outfit.ttf", FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
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
fun TahoeTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = Tahoe.Text, onPrimary = Color(Brand.CANVAS), background = Tahoe.Bg, surface = Tahoe.Surface,
        onBackground = Tahoe.Text, onSurface = Tahoe.Text, surfaceVariant = Tahoe.Glass, onSurfaceVariant = Tahoe.Dim,
        secondary = Tahoe.Accent, onSecondary = Tahoe.OnAccent, tertiary = Tahoe.Lake, outline = Tahoe.Stroke, error = Tahoe.Bad,
    ),
    typography = Typography().withFont(Outfit),
    content = { CompositionLocalProvider(LocalContentColor provides Tahoe.Text, LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = Outfit), content = content) }, // text is light everywhere, not only inside Surfaces
)

/** Charcoal canvas with a soft brand-accent light from the top-left corner (no moving blobs: calmer for a dashboard). */
@Composable
fun Aurora(modifier: Modifier = Modifier) = Canvas(modifier.fillMaxSize().background(Tahoe.Bg)) {
    fun glow(c: Color, x: Float, y: Float, r: Float) {
        val center = Offset(size.width * x, size.height * y); val radius = size.maxDimension * r
        drawCircle(Brush.radialGradient(listOf(c, Color.Transparent), center, radius), radius, center)
    }
    glow(Tahoe.Accent.copy(alpha = 0.16f), 0.08f, -0.1f, 0.45f)
    glow(Tahoe.Lake.copy(alpha = 0.12f), 1.0f, 1.05f, 0.5f)
}

@Composable
fun Glass(modifier: Modifier = Modifier, radius: Int = 20, onClick: (() -> Unit)? = null, padding: Int = 18, content: @Composable ColumnScope.() -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hover by src.collectIsHoveredAsState()
    val shape = RoundedCornerShape(radius.dp)
    Column(
        modifier.clip(shape).background(if (hover && onClick != null) Tahoe.CardHi else Tahoe.Card)
            .border(1.dp, Color.White.copy(alpha = 0.06f), shape)
            .then(if (onClick != null) Modifier.hoverable(src).clickable(onClick = onClick) else Modifier)
            .padding(padding.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content,
    )
}

@Composable
fun Title(text: String, sub: String? = null) = Column(Modifier.padding(bottom = 8.dp)) {
    Text(text, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Tahoe.Text)
    sub?.let { Text(it, fontSize = 15.sp, color = Tahoe.Dim) }
}

@Composable
fun Section(text: String) = Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Tahoe.Text, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))

@Composable
fun PrimaryButton(text: String, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) = Button(
    onClick, enabled = enabled, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    colors = ButtonDefaults.buttonColors(containerColor = Tahoe.Text, contentColor = Color(0xFF111114)),
) {
    icon?.let { Icon(it, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
    Text(text, fontWeight = FontWeight.SemiBold)
}

@Composable
fun GhostButton(text: String, icon: ImageVector? = null, color: Color = Tahoe.Text, onClick: () -> Unit) = OutlinedButton(
    onClick, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Tahoe.Stroke),
    colors = ButtonDefaults.outlinedButtonColors(containerColor = Tahoe.Glass, contentColor = color),
    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
) {
    icon?.let { Icon(it, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
    Text(text)
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier.fillMaxWidth(), secret: Boolean = false,
          trailing: @Composable (() -> Unit)? = null) = OutlinedTextField(
    value, onChange, label = { Text(label) }, singleLine = true, modifier = modifier, shape = RoundedCornerShape(14.dp),
    visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
    trailingIcon = trailing,
    colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Tahoe.Accent, unfocusedBorderColor = Tahoe.Stroke, focusedLabelColor = Tahoe.Accent, unfocusedLabelColor = Tahoe.Faint,
        cursorColor = Tahoe.Accent, focusedContainerColor = Color.Black.copy(alpha = 0.3f), unfocusedContainerColor = Color.Black.copy(alpha = 0.3f),
    ),
)

@Composable
fun Chip(text: String, icon: ImageVector? = null) = Row(
    Modifier.clip(RoundedCornerShape(50)).background(Tahoe.Glass).border(1.dp, Tahoe.Stroke, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
) {
    icon?.let { Icon(it, null, tint = Tahoe.Text, modifier = Modifier.size(16.dp)) }
    Text(text, fontSize = 14.sp, color = Tahoe.Text)
}

/** Remote images (thumbnails, logos) with a small in-memory cache. */
object Images {
    private val cache = ConcurrentHashMap<String, ImageBitmap>()
    suspend fun load(url: String): ImageBitmap? = cache[url] ?: withContext(Dispatchers.IO) {
        runCatching {
            val c = java.net.URI(url).toURL().openConnection().apply { connectTimeout = 6000; readTimeout = 10000; setRequestProperty("User-Agent", "TahoeTV") }
            org.jetbrains.skia.Image.makeFromEncoded(c.getInputStream().use { it.readBytes() }).toComposeImageBitmap()
        }.getOrNull()?.also { cache[url] = it }
    }
}

@Composable
fun rememberImage(url: String?): ImageBitmap? {
    var img by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) { if (url != null) img = Images.load(url) }
    return img
}
