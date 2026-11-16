package tech.kousozo.tahoetv

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import tech.kousozo.tahoetv.core.I18n
import tech.kousozo.tahoetv.core.WatchRecap

/**
 * Local privacy-first Monthly Recap ("Rekap Tahoe ala Wrapped", #62) with vertical Story image export.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecapSheet(onDismiss: () -> Unit) {
    val c = LocalContext.current
    val lang = PhoneRegion.language
    val dir = remember { File(c.filesDir, "recap") }
    var rev by remember { mutableIntStateOf(0) }
    var enabled by remember(rev) { mutableStateOf(WatchRecap.isEnabled(dir)) }
    val summary = remember(rev, lang) {
        WatchRecap.summarize(dir, "main", WatchRecap.currentMonth(), lang)
    }

    ModalBottomSheet(onDismiss, containerColor = Tahoe.Surface) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = Tahoe.Accent)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(I18n.get(lang, "recap.title"), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Text(I18n.get(lang, "recap.sub"), fontSize = 12.sp, color = Tahoe.Dim)
                }
                IconButton(onDismiss) { Icon(Icons.Rounded.Close, I18n.get(lang, "tv.close"), tint = Tahoe.Dim) }
            }

            // Story preview card
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF1A2557), Color(0xFF1E1338), Color(0xFF0E1826))))
                    .border(1.dp, Tahoe.Accent.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("TAHOE TV · ${summary.month}", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp, color = Tahoe.Accent)
                Text(summary.tagline, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 27.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.08f)).padding(12.dp)) {
                        Text(I18n.get(lang, "recap.total_time"), fontSize = 11.sp, color = Tahoe.Dim)
                        Text(summary.totalHoursDecimal, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                        Text(I18n.get(lang, "recap.sessions", summary.sessionCount), fontSize = 11.sp, color = Tahoe.Dim)
                    }
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.08f)).padding(12.dp)) {
                        Text(I18n.get(lang, "recap.peak_hour"), fontSize = 11.sp, color = Tahoe.Dim)
                        Text(if (summary.peakHour >= 0) "%02d:00".format(summary.peakHour) else "—", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
                if (summary.topChannels.isNotEmpty()) {
                    Text(I18n.get(lang, "recap.top_channels"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Tahoe.Accent)
                    summary.topChannels.forEachIndexed { idx, item ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("#${idx + 1}", fontWeight = FontWeight.Bold, color = Tahoe.Accent, modifier = Modifier.width(30.dp))
                            Text(item.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("${item.minutes} min", fontSize = 12.sp, color = Tahoe.Dim)
                        }
                    }
                }
                if (summary.topShows.isNotEmpty()) {
                    Text(I18n.get(lang, "recap.top_shows"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Tahoe.Accent)
                    summary.topShows.forEach { item ->
                        Text("• ${item.name} (${item.sessions}×)", fontSize = 13.sp, color = Tahoe.Text.copy(alpha = 0.9f))
                    }
                }
            }

            PrimaryButton(I18n.get(lang, "recap.share"), icon = Icons.Rounded.Share) {
                shareRecapStoryImage(c, summary, lang)
            }

            SwitchRow(I18n.get(lang, "recap.enable"), enabled) { on ->
                WatchRecap.setEnabled(dir, on)
                enabled = on
            }
            SecondaryButton(I18n.get(lang, "recap.clear"), Icons.Rounded.DeleteOutline, Tahoe.Bad) {
                WatchRecap.clear(dir)
                rev++
                Toast.makeText(c, I18n.get(lang, "recap.cleared"), Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private fun shareRecapStoryImage(ctx: Context, s: WatchRecap.Summary, lang: String) {
    val w = 900
    val h = 1600
    val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cv = Canvas(bm)

    val bg = Paint().apply {
        shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0xFF0A1228.toInt(), 0xFF1E1338.toInt(), 0xFF0B1F2E.toInt()),
            null, Shader.TileMode.CLAMP,
        )
    }
    cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)

    val pEyebrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF64D2FF.toInt()
        textSize = 30f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val pTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 54f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val pBody = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xD8FFFFFF.toInt()
        textSize = 34f
    }
    val pBox = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1EFFFFFF.toInt()
    }

    cv.drawText("TAHOE TV · ${s.month}", 72f, 150f, pEyebrow)
    var y = 240f
    // Wrap tagline cleanly in two chunks if needed
    val words = s.tagline.split(' ')
    var line = ""
    for (wd in words) {
        val candidate = if (line.isEmpty()) wd else "$line $wd"
        if (pTitle.measureText(candidate) > w - 144f && line.isNotEmpty()) {
            cv.drawText(line, 72f, y, pTitle)
            y += 68f
            line = wd
        } else {
            line = candidate
        }
    }
    if (line.isNotEmpty()) { cv.drawText(line, 72f, y, pTitle); y += 90f }

    cv.drawRoundRect(RectF(72f, y, w - 72f, y + 180f), 32f, 32f, pBox)
    cv.drawText(I18n.get(lang, "recap.total_time"), 108f, y + 60f, pEyebrow)
    cv.drawText("${s.totalHoursDecimal} · ${I18n.get(lang, "recap.sessions", s.sessionCount)}", 108f, y + 126f, pTitle)
    y += 230f

    if (s.topChannels.isNotEmpty()) {
        cv.drawText(I18n.get(lang, "recap.top_channels"), 72f, y, pEyebrow)
        y += 50f
        s.topChannels.forEachIndexed { idx, item ->
            cv.drawRoundRect(RectF(72f, y, w - 72f, y + 96f), 24f, 24f, pBox)
            cv.drawText("#${idx + 1}  ${item.name}", 104f, y + 60f, pBody)
            val mText = "${item.minutes}m"
            cv.drawText(mText, w - 104f - pEyebrow.measureText(mText), y + 60f, pEyebrow)
            y += 118f
        }
    }

    val pFoot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFFFFF.toInt()
        textSize = 26f
    }
    cv.drawText("Tahoe TV · github.com/fk0u/cleanyourtv-tahoe", 72f, h - 90f, pFoot)

    val outDir = File(ctx.cacheDir, "recap").apply { mkdirs() }
    val outFile = File(outDir, "tahoe-recap.png")
    FileOutputStream(outFile).use { fos -> bm.compress(Bitmap.CompressFormat.PNG, 95, fos) }
    bm.recycle()

    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", outFile)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, "${s.tagline}\nhttps://github.com/fk0u/cleanyourtv-tahoe")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        ctx.startActivity(Intent.createChooser(intent, I18n.get(lang, "recap.share")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
