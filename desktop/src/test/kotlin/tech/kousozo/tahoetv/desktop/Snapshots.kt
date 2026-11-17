package tech.kousozo.tahoetv.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import java.io.File
import kotlin.test.Test

/** Renders every screen offscreen (no window) so the UI can be reviewed as PNGs. Network data loads for a few seconds first. */
class Snapshots {
    @Test fun render() {
        val out = File(System.getProperty("snapshots.out")).apply { mkdirs() }
        Screen.entries.forEach { s ->
            val scene = ImageComposeScene(1280, 820, Density(1f)) { TahoeTheme { App(s, askRegion = false) } }
            var t = 0L
            repeat(300) { scene.render(t); Thread.sleep(100); t += 100_000_000 } // let images/APIs arrive, animations settle
            File(out, "${s.name}.png").writeBytes(scene.render(t).encodeToData()!!.bytes)
            scene.close()
        }
        // #41 / #71: the home and settings screens in the light theme and mirrored right-to-left
        val variants = listOf("light" to false, "rtl" to true)
        for ((tag, rtl) in variants) for (s in listOf(Screen.Home, Screen.Prefs)) {
            ThemePrefs.mode = if (rtl) tech.kousozo.tahoetv.core.Theme.Mode.DARK else tech.kousozo.tahoetv.core.Theme.Mode.LIGHT
            val scene = ImageComposeScene(1280, 820, Density(1f)) {
                TahoeTheme {
                    if (rtl) androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) { App(s, askRegion = false) }
                    else App(s, askRegion = false)
                }
            }
            var t = 0L
            repeat(100) { scene.render(t); Thread.sleep(100); t += 100_000_000 }
            File(out, "${s.name}-$tag.png").writeBytes(scene.render(t).encodeToData()!!.bytes)
            scene.close()
        }
        ThemePrefs.mode = tech.kousozo.tahoetv.core.Theme.Mode.DARK
    }
}
