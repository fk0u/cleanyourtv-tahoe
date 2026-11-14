package tech.kousozo.tahoetv

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.kousozo.tahoetv.core.LocalTv

/** "Pilih kategori": a 2-column grid of glass tiles (icon, name, channel count). Tap selects; the bottom pill opens
 *  TV Live filtered to it. */
@Composable
fun CategoriesScreen() {
    val c = LocalContext.current
    LaunchedEffect(Unit) { Channels.load(c) }
    val all = Channels.entries
    val lang = PhoneRegion.language
    var picked by remember { mutableStateOf(Nav.liveCategory) }
    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Text(tech.kousozo.tahoetv.core.I18n.get(lang, "live.explore_kicker"), Modifier.padding(top = 12.dp), color = Tahoe.Faint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
            Text(tech.kousozo.tahoetv.core.I18n.get(lang, "live.pick_category"), fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp)
            Text(tech.kousozo.tahoetv.core.I18n.get(lang, "live.explore_desc"), color = Tahoe.Dim, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp, bottom = 16.dp))
            if (all == null) { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Tahoe.Accent, trackColor = Tahoe.Glass); return@Column }
            val cats = listOf<LocalTv.Category?>(null) + LocalTv.Category.entries.filter { k -> all.any { LocalTv.inCategory(it, k) } }
            LazyVerticalGrid(GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = TabInset + 72.dp)) {
                items(cats) { k ->
                    val on = k == picked
                    val n = if (k == null) all.size else all.count { LocalTv.inCategory(it, k) }
                    val bg by animateColorAsState(if (on) Color.White.copy(alpha = 0.16f) else Tahoe.Glass, label = "tile")
                    Column(
                        Modifier.height(112.dp).clip(RoundedCornerShape(22.dp)).background(bg)
                            .border(1.dp, if (on) Tahoe.Text.copy(alpha = 0.6f) else Tahoe.Stroke, RoundedCornerShape(22.dp))
                            .clickable { picked = k }.padding(14.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Box(Modifier.size(38.dp).clip(CircleShape).background(if (on) Tahoe.Text else Color.White.copy(alpha = 0.08f)),
                            contentAlignment = Alignment.Center) {
                            Icon(categoryIcon(k), null, tint = if (on) Tahoe.Bg else Tahoe.Text, modifier = Modifier.size(20.dp))
                        }
                        Column {
                            Text(k?.label(lang) ?: tech.kousozo.tahoetv.core.I18n.get(lang, "home.all_channels"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(tech.kousozo.tahoetv.core.I18n.get(lang, "home.channel_count", n), fontSize = 12.sp, color = if (on) Tahoe.Text else Tahoe.Faint)
                        }
                    }
                }
            }
        }
        // bottom pill with a soft accent halo
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = TabInset + 8.dp)
            .background(Brush.radialGradient(listOf(Tahoe.Accent.copy(alpha = 0.35f), Color.Transparent)), RoundedCornerShape(50)).padding(10.dp)) {
            Button({ Nav.category(picked) }, shape = RoundedCornerShape(50), contentPadding = PaddingValues(horizontal = 34.dp, vertical = 14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF111114))) {
                Text(if (picked == null) tech.kousozo.tahoetv.core.I18n.get(lang, "live.watch_all") else tech.kousozo.tahoetv.core.I18n.get(lang, "live.watch_category", picked!!.label(lang)), fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic)
            }
        }
    }
}
