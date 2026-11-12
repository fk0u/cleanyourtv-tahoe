package tech.kousozo.tahoetv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import tech.kousozo.tahoetv.core.LocalTv
import tech.kousozo.tahoetv.core.SourceHealth

/** A channel's own page (like an artist page in a music app): its colour washing the top, round logo, huge name,
 *  Tonton + ♥, what's on now in a glass card, the rest of today's schedule, and plain-language source status. */
@Composable
fun ChannelScreen(e: LocalTv.Entry, onBack: () -> Unit) {
    val c = LocalContext.current
    BackHandler(onBack = onBack)
    val logo = rememberNetImage(e.channel.logo)
    val tint = logo?.let { dominantColor(it) } ?: categoryTint(e.category)
    // the clock ticks (the show on now, its progress and "Berikutnya" roll over), and source checks that finish later show up
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(5_000); now = Instant.now() } }
    val schedule = remember(Channels.epg, e, now) { Channels.epg?.schedule(e.name, now).orEmpty() }
    val cur = schedule.firstOrNull()?.takeIf { it.start <= now }
    val today = java.time.LocalDate.now()
    val later = schedule.filter { it.start > now && it.start.atZone(java.time.ZoneId.systemDefault()).toLocalDate() == today }.take(8)
    val sources = listOf(e.channel) + e.alternates
    val live = remember(now, e) { sources.count { SourceHealth.status(it.url)?.live == true } }
    val checked = remember(now, e) { sources.any { SourceHealth.status(it.url) != null } }

    Box(Modifier.fillMaxSize().background(Tahoe.Bg)) {
        // header wash: the logo blown up and blurred (Android 12+) under the channel colour, fading into the page
        Box(Modifier.fillMaxWidth().height(380.dp).background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.85f), tint.copy(alpha = 0.35f), Tahoe.Bg)))) {
            if (logo != null) Image(logo, null, Modifier.matchParentSize().blur(60.dp).alpha(0.35f), contentScale = ContentScale.Crop)
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Tahoe.Bg)))
        }
        Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = TabInset)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                RoundGlass(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali", onClick = onBack)
            }
            Spacer(Modifier.height(96.dp))
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(92.dp).clip(CircleShape).background(Color.White).border(3.dp, Color.White.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center) {
                    if (logo != null) Image(logo, null, Modifier.padding(14.dp).fillMaxSize(), contentScale = ContentScale.Fit)
                    else Text(e.name.take(3).uppercase(), color = Color(0xFF141216), fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                }
                Text(e.name.uppercase(), fontSize = 40.sp, lineHeight = 40.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(e.category.label, if (e.near) "dekat kamu" else null,
                    if (live > 0) "siaran aktif" else null).joinToString(" · "), color = Tahoe.Dim, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PillButton("Tonton", Icons.Rounded.PlayArrow) { Nav.play(e) }
                    Spacer(Modifier.width(10.dp))
                    val fav = e.channel.url in Channels.favs
                    RoundGlass(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (fav) "Hapus dari favorit" else "Tambah ke favorit",
                        tint = if (fav) Tahoe.Bad else Tahoe.Text) { Channels.toggleFav(c, e) }
                }

                // on now
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (cur != null) "Sedang tayang" else "Siaran langsung", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        cur?.let { Text("${HM.format(it.start)}–${HM.format(it.stop)}", fontSize = 12.sp, color = Tahoe.Dim, fontFamily = Mono) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(cur?.title ?: "Jadwal acara belum tersedia", fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            cur?.desc?.let { Text(it, fontSize = 13.sp, color = Tahoe.Dim, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                        }
                        Spacer(Modifier.width(10.dp))
                        RoundGlass(Icons.Rounded.PlayArrow, "Tonton ${e.name}", size = 44.dp) { Nav.play(e) }
                    }
                    cur?.let { LinearProgressIndicator({ progress(it, now) }, Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                        color = Tahoe.Text, trackColor = Tahoe.Stroke) }
                }

                // later today
                if (later.isNotEmpty()) {
                    Text("Berikutnya hari ini", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    later.forEach { p ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(HM.format(p.start), Modifier.width(56.dp), color = Tahoe.Dim, fontSize = 13.sp, fontFamily = Mono)
                            Text(p.title, Modifier.weight(1f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        HorizontalDivider(color = Tahoe.Stroke)
                    }
                }

                // sources, in plain words
                Text("Siaran", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                GlassCard {
                    Text(when {
                        sources.size == 1 -> "Saluran ini punya 1 siaran."
                        else -> "Saluran ini punya ${sources.size} siaran: kalau satu bermasalah, Tahoe pindah ke cadangan sendiri."
                    }, fontSize = 14.sp, color = Tahoe.Dim)
                    if (checked)
                        Text("$live dari ${sources.size} terdeteksi aktif saat terakhir dicek.", fontSize = 13.sp, color = Tahoe.Faint)
                }
            }
        }
    }
}
