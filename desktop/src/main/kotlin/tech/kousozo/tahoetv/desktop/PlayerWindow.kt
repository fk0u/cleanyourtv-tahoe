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
import tech.kousozo.tahoetv.core.M3u

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

    private var generation = 0

    /** Plays the first stream that works: `streams` = the channel's main stream then its backups (other sources).
     *  `onStatus(null)` = playing; otherwise a message for the UI. Called on the Swing/Compose thread. */
    fun play(streams: List<M3u.Channel>, title: String, onStatus: (String?) -> Unit) = fx { start(streams, 0, title, ++generation, onStatus) }

    private fun start(streams: List<M3u.Channel>, i: Int, title: String, gen: Int, onStatus: (String?) -> Unit) {
        if (gen != generation) return // another channel was chosen meanwhile
        val c = streams.getOrNull(i) ?: return SwingUtilities.invokeLater {
            onStatus("Saluran ini sedang tidak bisa diputar (semua ${streams.size} sumber offline). Coba saluran lain.")
        }
        val url = HlsProxy.wrap(c.url, c.headers)
        val next = { start(streams, i + 1, title, gen, onStatus) }
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
        report(if (i == 0) "Memuat siaran…" else "Mencoba sumber cadangan ${i + 1}/${streams.size}…")
        try {
            val p = MediaPlayer(Media(url))
            player = p; view.mediaPlayer = p
            p.setOnPlaying { report(null) }
            p.setOnStalled { report("Menunggu siaran…") }
            p.setOnError { p.dispose(); next() }
            p.play()
            // Some streams never error, they just hang: give each source 20 s, then try the next one.
            javafx.animation.PauseTransition(javafx.util.Duration.seconds(20.0)).apply {
                setOnFinished { if (gen == generation && player === p && p.status != MediaPlayer.Status.PLAYING && p.status != MediaPlayer.Status.PAUSED) { p.dispose(); next() } }
                play()
            }
        } catch (e: Exception) { next() }
        s.show(); s.toFront()
    }

    fun show() = fx { stage?.let { it.show(); it.toFront() } }
    fun stop() = fx { player?.dispose(); player = null; stage?.hide() }
    fun close() { if (started) Platform.runLater { player?.dispose(); stage?.close(); Platform.exit() } }
}
