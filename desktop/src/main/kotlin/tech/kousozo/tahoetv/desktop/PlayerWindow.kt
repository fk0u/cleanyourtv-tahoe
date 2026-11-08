package tech.kousozo.tahoetv.desktop

import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.input.KeyCode
import javafx.scene.input.MouseButton
import javafx.scene.layout.StackPane
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import javafx.stage.Stage
import javax.swing.SwingUtilities

/** Live TV plays in its own JavaFX window: embedding JavaFX video in the Compose window (JFXPanel in SwingPanel)
 *  renders blank on macOS. A real window also gives native full screen, and keeps playing while you browse the app.
 *  Keys: F or double-click = full screen, Esc = leave full screen / close, Space = pause. */
object PlayerWindow {
    private var stage: Stage? = null
    private var player: MediaPlayer? = null
    private var started = false

    private fun fx(block: () -> Unit) {
        if (!started) { started = true; Platform.setImplicitExit(false); runCatching { Platform.startup(block) }.onFailure { Platform.runLater(block) }; return }
        Platform.runLater(block)
    }

    /** `onStatus(null)` = playing; otherwise a message for the UI. Called on the Swing/Compose thread. */
    fun play(url: String, title: String, onStatus: (String?) -> Unit) = fx {
        fun report(s: String?) = SwingUtilities.invokeLater { onStatus(s) }
        player?.dispose()
        val view = MediaView().apply { isPreserveRatio = true }
        val s = stage ?: Stage().also { st ->
            val root = StackPane().apply { style = "-fx-background-color: black;" }
            st.scene = Scene(root, 960.0, 540.0, javafx.scene.paint.Color.BLACK)
            st.scene.setOnKeyPressed { k ->
                when (k.code) {
                    KeyCode.F -> st.isFullScreen = !st.isFullScreen
                    KeyCode.ESCAPE -> if (!st.isFullScreen) st.close()
                    KeyCode.SPACE -> player?.let { p -> if (p.status == MediaPlayer.Status.PLAYING) p.pause() else p.play() }
                    else -> {}
                }
            }
            root.setOnMouseClicked { m -> if (m.button == MouseButton.PRIMARY && m.clickCount == 2) st.isFullScreen = !st.isFullScreen }
            st.setOnHidden { player?.dispose(); player = null; SwingUtilities.invokeLater { onStatus("Pemutar ditutup") } }
            stage = st
        }
        val root = s.scene.root as StackPane
        root.children.setAll(view)
        view.fitWidthProperty().bind(root.widthProperty()); view.fitHeightProperty().bind(root.heightProperty())
        s.title = "$title — Tahoe TV"
        report("Memuat siaran…")
        try {
            val p = MediaPlayer(Media(url))
            player = p; view.mediaPlayer = p
            p.setOnPlaying { report(null) }
            p.setOnStalled { report("Menunggu siaran…") }
            p.setOnError { report("Saluran ini sedang tidak bisa diputar (offline atau format tidak didukung). Coba saluran lain.") }
            p.play()
        } catch (e: Exception) {
            report("Saluran ini sedang tidak bisa diputar (offline atau format tidak didukung). Coba saluran lain.")
        }
        s.show(); s.toFront()
    }

    fun show() = fx { stage?.let { it.show(); it.toFront() } }
    fun stop() = fx { player?.dispose(); player = null; stage?.hide() }
    fun close() { if (started) Platform.runLater { player?.dispose(); stage?.close(); Platform.exit() } }
}
