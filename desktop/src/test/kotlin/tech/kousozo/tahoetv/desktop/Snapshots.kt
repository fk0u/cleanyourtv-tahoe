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
    }
}
