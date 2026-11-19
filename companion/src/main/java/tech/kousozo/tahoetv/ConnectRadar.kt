package tech.kousozo.tahoetv

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** A TV on the radar: already connected (green) or just found on this Wi-Fi (cyan, tap to connect). */
data class RadarNode(val id: String, val name: String, val connected: Boolean, val stateLabel: String, val onClick: () -> Unit)

/**
 * "Hubungkan ke TV": a living radar. Ripples leave the phone, a sweep rotates, dust drifts, the phone breathes, and each TV
 * pops in on a spring with a pulse travelling along its beam. Everything stops moving (static rings and nodes) with "Kurangi gerakan".
 */
@Composable
fun ConnectRadar(nodes: List<RadarNode>, modifier: Modifier = Modifier, scanning: Boolean = true) {
    val reduce = ThemePrefs.reduceMotion
    val accent = Tahoe.Accent
    val good = Tahoe.Good
    val inf = rememberInfiniteTransition(label = "radar")
    val sweep by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "sweep")
    val ripple by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "ripple")
    val breathe by inf.animateFloat(0.94f, 1.07f, infiniteRepeatable(tween(1800, easing = EaseInOutTahoe), RepeatMode.Reverse), label = "breathe")
    val drift by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(16000, easing = LinearEasing)), label = "drift")
    val dust = remember { val r = Random(7); List(30) { Triple<Float, Float, Float>(r.nextFloat() * 2f * PI.toFloat(), 0.25f + r.nextFloat() * 0.75f, 1f + r.nextFloat() * 1.6f) } }
    val moving = !reduce && scanning
    val nodeRadius = 92.dp

    fun nodePos(i: Int, n: Int): Pair<Dp, Dp> {
        val a = (-90.0 + i * 360.0 / maxOf(n, 1) + if (n == 1) 35.0 else (i % 2) * 14.0) * PI / 180.0
        val r = nodeRadius + if (i % 2 == 0) 0.dp else 16.dp
        return (r.value * cos(a)).dp to (r.value * sin(a)).dp
    }

    Box(modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(280.dp).clearAndSetSemantics {}) {
            val c = center
            val maxR = minOf(size.minDimension / 2f, 140.dp.toPx())
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.24f), Color.Transparent), c, maxR), maxR, c)
            listOf(0.36f, 0.62f, 0.9f).forEachIndexed { i, f ->
                drawCircle(accent.copy(alpha = 0.12f + 0.07f * i), maxR * f, c, style = Stroke(1.5.dp.toPx()))
            }
            if (moving) {
                repeat(3) { k ->
                    val t = (ripple + k / 3f) % 1f
                    drawCircle(accent.copy(alpha = (1f - t) * 0.5f), maxR * (0.16f + 0.84f * t), c, style = Stroke((2.4f * (1f - t) + 0.6f).dp.toPx()))
                }
                rotate(sweep, c) {
                    drawCircle(
                        Brush.sweepGradient(0f to Color.Transparent, 0.72f to Color.Transparent, 1f to accent.copy(alpha = 0.4f), center = c),
                        maxR * 0.94f, c,
                    )
                }
            }
            dust.forEach { (a0, rf, sz) ->
                val a = a0 + (if (moving) drift * 2f * PI.toFloat() * (if (rf > 0.6f) 0.3f else -0.2f) else 0f)
                val tw = if (moving) 0.15f + 0.2f * (0.5f + 0.5f * sin(a * 3f + drift * 12f)) else 0.22f
                drawCircle(Color.White.copy(alpha = tw), sz.dp.toPx(), c + Offset(cos(a) * maxR * rf, sin(a) * maxR * rf))
            }
            nodes.forEachIndexed { i, n ->
                val (px, py) = nodePos(i, nodes.size)
                val end = c + Offset(px.toPx(), py.toPx())
                val col = if (n.connected) good else accent
                drawLine(col.copy(alpha = 0.4f), c, end, 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 11f), if (moving) -ripple * 40f else 0f))
                if (moving) { // a pulse of light travelling from the phone to the TV
                    val t = (ripple * 1.4f + i * 0.27f) % 1f
                    val p = c + (end - c) * t
                    drawCircle(col.copy(alpha = 0.9f * (1f - t * 0.4f)), 4.dp.toPx(), p)
                    drawCircle(col.copy(alpha = 0.25f), 9.dp.toPx(), p)
                }
            }
        }
        // the phone, breathing
        Box(Modifier.size(64.dp).scale(if (moving) breathe else 1f).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Smartphone, null, tint = Tahoe.OnAccent, modifier = Modifier.size(28.dp))
        }
        nodes.forEachIndexed { i, n -> RadarNodeView(n, i, Modifier.offset(nodePos(i, nodes.size).first, nodePos(i, nodes.size).second)) }
    }
}

@Composable
private fun RadarNodeView(n: RadarNode, index: Int, modifier: Modifier) {
    val reduce = ThemePrefs.reduceMotion
    val appear = remember(n.id) { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(n.id) {
        if (!reduce) { kotlinx.coroutines.delay(120L * index); appear.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow)) }
    }
    val haptic = LocalHapticFeedback.current
    val col = if (n.connected) Tahoe.Good else Tahoe.Accent
    Column(
        modifier.width(92.dp).scale(appear.value.coerceAtLeast(0.001f))
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .clickable(role = Role.Button) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); n.onClick() }
            .semantics(mergeDescendants = true) { contentDescription = n.name; stateDescription = n.stateLabel },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(col.copy(alpha = if (n.connected) 1f else 0.2f)).border(1.5.dp, col, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Tv, null, tint = if (n.connected) Color(0xFF0D0F14) else Tahoe.Text, modifier = Modifier.size(24.dp)) }
        Text(n.name, fontSize = 12.sp, color = Tahoe.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}
