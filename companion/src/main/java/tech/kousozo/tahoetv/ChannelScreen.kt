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
    val schedule = remember(Channels.epg, e, now) { Channels.epg?.schedule(e.name, now, e.channel.tvgId).orEmpty() }
    val cur = schedule.firstOrNull()?.takeIf { it.start <= now }
    val scrapedLogo by produceState(e.channel.logo, e.channel.url, e.name) {
        if (value.isNullOrBlank()) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            tech.kousozo.tahoetv.core.MediaScraper.channelLogo(e.name, e.channel.logo)
        }
    }
    val progThumbUrl by produceState<String?>(null, cur?.title, e.name) {
        value = cur?.title?.let { t -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            tech.kousozo.tahoetv.core.MediaScraper.programmeThumbnail(t, e.name)
        } }
    }
    val scrapedBmp = scrapedLogo?.let { rememberNetImage(it) } ?: logo
    val progThumb = progThumbUrl?.let { rememberNetImage(it) }
    val today = java.time.LocalDate.now()
    val later = schedule.filter { it.start > now && it.start.atZone(java.time.ZoneId.systemDefault()).toLocalDate() == today }.take(8)
    val sources = listOf(e.channel) + e.alternates
    val live = remember(now, e) { sources.count { SourceHealth.status(it.url)?.live == true } }
    val checked = remember(now, e) { sources.any { SourceHealth.status(it.url) != null } }

    Box(Modifier.fillMaxSize().background(Tahoe.Bg)) {
        // header wash: programme thumbnail (or logo) under the channel colour, fading into the page
        Box(Modifier.fillMaxWidth().height(380.dp).background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.85f), tint.copy(alpha = 0.35f), Tahoe.Bg)))) {
            val wash = progThumb ?: scrapedBmp
            if (wash != null) Image(wash, null, Modifier.matchParentSize().blur(if (progThumb != null) 12.dp else 60.dp).alpha(if (progThumb != null) 0.55f else 0.35f), contentScale = ContentScale.Crop)
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Tahoe.Bg)))
        }
        Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = TabInset)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                RoundGlass(Icons.AutoMirrored.Rounded.ArrowBack, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "common.back"), onClick = onBack)
            }
            Spacer(Modifier.height(76.dp))
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(84.dp).clip(RoundedCornerShape(24.dp)).background(Color.White).border(2.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center) {
                    if (scrapedBmp != null) Image(scrapedBmp, null, Modifier.padding(12.dp).fillMaxSize(), contentScale = ContentScale.Fit)
                    else Text(e.name.take(4).uppercase(), color = Color(0xFF0D0D12), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                }
                Text(e.name, fontSize = 32.sp, lineHeight = 37.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.64).sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                val lang = PhoneRegion.language
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (live > 0 || checked.not()) LiveBadge()
                    Text(listOfNotNull(
                        e.category.label(lang),
                        tech.kousozo.tahoetv.core.I18n.get(lang, "live.sources", sources.size),
                        if (e.near) tech.kousozo.tahoetv.core.I18n.get(lang, "ch.near_badge") else null,
                    ).joinToString(" · "), color = Tahoe.Dim, fontSize = 14.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AccentPillButton(tech.kousozo.tahoetv.core.I18n.get(lang, "ch.watch_now"), Icons.Rounded.PlayArrow) { Nav.play(e) }
                    val fav = e.channel.url in Channels.favs
                    RoundGlass(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        tech.kousozo.tahoetv.core.I18n.get(lang, if (fav) "ch.fav_remove" else "ch.fav_add"),
                        tint = if (fav) Tahoe.Bad else Tahoe.Text) { Channels.toggleFav(c, e) }
                    RoundGlass(Icons.Rounded.Share, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "player.share")) { shareChannel(c, e) }
                    val activeDev = remember(Pairing.tick) { Sync.devices(c).active() }
                    if (activeDev != null) {
                        RoundGlass(Icons.Rounded.Cast, tech.kousozo.tahoetv.core.I18n.get(PhoneRegion.language, "connect.play_on_tv")) {
                            tech.kousozo.tahoetv.core.TvLink.shared(activeDev.host, activeDev.token)
                                .sessionLoad(e.channel.channelId, e.channel.url, e.name)
                        }
                    }
                }

                // combined now & next card (Figma Mobile / Detail saluran 11:121)
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Tahoe.Surface)
                        .border(1.dp, Tahoe.Stroke, RoundedCornerShape(22.dp)).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(tech.kousozo.tahoetv.core.I18n.get(lang, if (cur != null) "ch.now_playing" else "player.status_live").uppercase(),
                        color = Tahoe.Accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.32.sp)
                    Text(cur?.title ?: tech.kousozo.tahoetv.core.I18n.get(lang, "ch.no_schedule_now"),
                        fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    cur?.desc?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 13.sp, color = Tahoe.Dim, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                    cur?.let { pr ->
                        ProgressBar(progress(pr, now), Modifier.fillMaxWidth())
                        val left = java.time.Duration.between(now, pr.stop).toMinutes().coerceAtLeast(0)
                        Text("${HM.format(pr.start)} – ${HM.format(pr.stop)} · " + tech.kousozo.tahoetv.core.I18n.get(lang, "tv.cc_min_left", left.toString()),
                            fontSize = 14.sp, color = Tahoe.Dim, fontFamily = Mono)
                    }
                    if (later.isNotEmpty()) {
                        HorizontalDivider(color = Tahoe.Stroke)
                        Text(tech.kousozo.tahoetv.core.I18n.get(lang, "ch.up_next").uppercase(),
                            color = Tahoe.Faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.32.sp)
                        later.forEach { p ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(HM.format(p.start), color = Tahoe.Dim, fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = Mono)
                                Text(p.title, Modifier.weight(1f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }

                // sources, in plain words
                val langCh = PhoneRegion.language
                Text(tech.kousozo.tahoetv.core.I18n.get(langCh, "ch.streams_title"), fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                GlassCard {
                    Text(when {
                        sources.size == 1 -> tech.kousozo.tahoetv.core.I18n.get(langCh, "ch.one_stream")
                        else -> tech.kousozo.tahoetv.core.I18n.get(langCh, "ch.multi_streams", sources.size)
                    }, fontSize = 14.sp, color = Tahoe.Dim)
                    if (checked)
                        Text(tech.kousozo.tahoetv.core.I18n.get(langCh, "ch.live_count", live, sources.size), fontSize = 13.sp, color = Tahoe.Faint)
                }
            }
        }
    }
}
