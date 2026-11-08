package tech.kousozo.tahoetv.desktop

import javafx.animation.KeyFrame
import javafx.animation.PauseTransition
import javafx.animation.Timeline
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCombination
import javafx.scene.input.MouseButton
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import javafx.stage.Stage
import javafx.util.Duration
import javax.swing.SwingUtilities
import tech.kousozo.tahoetv.core.M3u

/** Live TV plays in its own JavaFX window: embedding JavaFX video in the Compose window (JFXPanel in SwingPanel)
 *  renders blank on macOS. A real window also gives native full screen, and keeps playing while you browse the app.
 *
 *  On-screen controls appear when the mouse moves (hide after 3 s): previous/next channel, full screen on/off, close.
 *  Keys: ▲/▼ or PageUp/PageDown = channel, F / double-click = full screen, Esc = leave full screen, then close,
 *  Space = pause. A source that errors, hangs, or plays black/frozen video is replaced by the next backup; when every
 *  source fails, the window says so and offers the next channel. */
object PlayerWindow {
    private var stage: Stage? = null
    private var player: MediaPlayer? = null
    private var watchdog: Timeline? = null
    private var started = false
    private var generation = 0

    // overlay nodes (created once with the stage)
    private lateinit var view: MediaView
    private lateinit var titleLabel: Label
    private lateinit var statusLabel: Label
    private lateinit var fullBtn: Button
    private lateinit var bar: BorderPane
    private lateinit var hint: Label
    private lateinit var nextBtn: Button
    private var onSwitch: (Int) -> Unit = {}
    private var onStatus: (String?) -> Unit = {}

    private fun fx(block: () -> Unit) {
        if (!started) { started = true; Platform.setImplicitExit(false); runCatching { Platform.startup(block) }.onFailure { Platform.runLater(block) }; return }
        Platform.runLater(block)
    }

    /** `streams` = the channel's main stream then its backups. `onSwitch(±1)` asks the app for the previous/next channel.
     *  `onStatus(null)` = playing, else a message; called on the Swing/Compose thread. */
    fun play(streams: List<M3u.Channel>, title: String, onSwitch: (Int) -> Unit, onStatus: (String?) -> Unit) = fx {
        this.onSwitch = onSwitch; this.onStatus = onStatus
        val s = ensureStage()
        titleLabel.text = title; s.title = "$title — Tahoe TV"
        start(streams, 0, ++generation)
        s.show(); s.toFront(); reveal()
    }

    private fun report(s: String?, offerNext: Boolean = false) {
        statusLabel.text = s.orEmpty(); statusLabel.isVisible = s != null
        nextBtn.isVisible = offerNext; nextBtn.isManaged = offerNext
        if (s != null) reveal()
        SwingUtilities.invokeLater { onStatus(s) }
    }

    private fun start(streams: List<M3u.Channel>, i: Int, gen: Int) {
        if (gen != generation) return // another channel was chosen meanwhile
        watchdog?.stop(); player?.dispose(); player = null; view.mediaPlayer = null
        val c = streams.getOrNull(i) ?: return report("Saluran ini sedang tidak bisa diputar (semua ${streams.size} sumber offline).", offerNext = true)
        val next = { if (gen == generation) start(streams, i + 1, gen) }
        report(if (i == 0) "Memuat siaran…" else "Sumber ${i} tidak jalan, mencoba cadangan ${i + 1}/${streams.size}…")
        val p = try { MediaPlayer(Media(HlsProxy.wrap(c.url, c.headers))) } catch (e: Exception) { return next() }
        player = p; view.mediaPlayer = p
        p.setOnPlaying { if (p.media.width > 0 || p.media.tracks.isNotEmpty()) report(null) }
        p.setOnError { next() }
        p.play()
        // Watchdog: a stream may never error and just hang, or "play" with no picture / a frozen picture.
        // Give it 20 s to start, then require the clock to keep moving and a video size.
        var lastTime = -1.0; var stuckSince = System.nanoTime(); val began = System.nanoTime()
        watchdog = Timeline(KeyFrame(Duration.seconds(2.0), {
            if (gen != generation || player !== p) return@KeyFrame
            val t = p.currentTime.toSeconds()
            val moving = p.status == MediaPlayer.Status.PLAYING && t > lastTime + 0.1
            if (moving) { lastTime = t; stuckSince = System.nanoTime() }
            val since = (System.nanoTime() - stuckSince) / 1e9
            val age = (System.nanoTime() - began) / 1e9
            when {
                p.status == MediaPlayer.Status.PAUSED -> stuckSince = System.nanoTime()
                age > 20 && lastTime < 0 -> next()                                   // never started
                age > 20 && p.media.width == 0 && p.media.tracks.none { it is javafx.scene.media.VideoTrack } -> next() // no picture at all
                lastTime >= 0 && since > 12 -> { report("Siaran macet, pindah ke sumber lain…"); next() } // frozen
                lastTime >= 0 && statusLabel.isVisible && moving -> report(null)
            }
        })).apply { cycleCount = Timeline.INDEFINITE; play() }
    }

    // ------------------------------------------------------------------ window + overlay

    private fun button(text: String, action: () -> Unit) = Button(text).apply {
        style = "-fx-background-color: rgba(255,255,255,0.16); -fx-text-fill: white; -fx-font-size: 14px; -fx-background-radius: 18; -fx-padding: 8 16;"
        setOnMouseEntered { style = style.replace("0.16", "0.30") }; setOnMouseExited { style = style.replace("0.30", "0.16") }
        isFocusTraversable = false
        setOnAction { action() }
    }

    private val hideControls = PauseTransition(Duration.seconds(3.0))

    /** Show the controls and the cursor; they fade away again after 3 s without mouse movement. */
    private fun reveal() {
        bar.isVisible = true; hint.isVisible = true; stage?.scene?.cursor = Cursor.DEFAULT
        hideControls.setOnFinished {
            if (statusLabel.isVisible) return@setOnFinished // keep visible while there's something to read
            bar.isVisible = false; hint.isVisible = false
            if (stage?.isFullScreen == true) stage?.scene?.cursor = Cursor.NONE
        }
        hideControls.playFromStart()
    }

    private fun toggleFull() { stage?.let { it.isFullScreen = !it.isFullScreen }; reveal() }

    private fun ensureStage(): Stage = stage ?: Stage().also { st ->
        view = MediaView().apply { isPreserveRatio = true }
        titleLabel = Label().apply { style = "-fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold;" }
        statusLabel = Label().apply {
            style = "-fx-text-fill: white; -fx-font-size: 16px; -fx-background-color: rgba(0,0,0,0.6); -fx-background-radius: 14; -fx-padding: 12 18;"
            isWrapText = true; maxWidth = 560.0; isVisible = false
        }
        nextBtn = button("Saluran berikutnya ▶") { onSwitch(1) }.apply { isVisible = false; isManaged = false }
        fullBtn = button("⤢  Layar penuh") { toggleFull() }
        val left = HBox(10.0, button("◀  Sebelumnya") { onSwitch(-1) }, button("Berikutnya  ▶") { onSwitch(1) }).apply { alignment = Pos.CENTER_LEFT }
        val right = HBox(10.0, fullBtn, button("✕  Tutup") { st.close() }).apply { alignment = Pos.CENTER_RIGHT }
        val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
        bar = BorderPane().apply {
            top = HBox(16.0, titleLabel, spacer).apply { alignment = Pos.CENTER_LEFT }
            bottom = HBox(10.0, left, Region().also { HBox.setHgrow(it, Priority.ALWAYS) }, right).apply { padding = Insets(12.0, 0.0, 0.0, 0.0) }
            padding = Insets(18.0, 22.0, 18.0, 22.0)
            style = "-fx-background-color: linear-gradient(to bottom, rgba(0,0,0,0.65), transparent 30%, transparent 70%, rgba(0,0,0,0.7));"
            isPickOnBounds = false
        }
        hint = Label("▲▼ ganti saluran  ·  F layar penuh  ·  Esc keluar  ·  Spasi jeda").apply {
            style = "-fx-text-fill: rgba(255,255,255,0.7); -fx-font-size: 12px;"; StackPane.setAlignment(this, Pos.BOTTOM_CENTER)
            StackPane.setMargin(this, Insets(0.0, 0.0, 70.0, 0.0))
        }
        val center = VBox(12.0, statusLabel, nextBtn).apply { alignment = Pos.CENTER; isPickOnBounds = false }
        val root = StackPane(view, bar, center, hint).apply { style = "-fx-background-color: black;" }
        view.fitWidthProperty().bind(root.widthProperty()); view.fitHeightProperty().bind(root.heightProperty())

        st.scene = Scene(root, 960.0, 540.0, javafx.scene.paint.Color.BLACK)
        st.fullScreenExitHint = "Tekan Esc atau F untuk keluar dari layar penuh"
        st.fullScreenExitKeyCombination = KeyCombination.NO_MATCH // Esc handled below (exit full screen first, close second)
        st.fullScreenProperty().addListener { _, _, full -> fullBtn.text = if (full) "⤡  Keluar layar penuh" else "⤢  Layar penuh"; reveal() }
        st.scene.setOnMouseMoved { reveal() }
        st.scene.setOnKeyPressed { k ->
            when (k.code) {
                KeyCode.F -> toggleFull()
                KeyCode.ESCAPE -> if (st.isFullScreen) st.isFullScreen = false else st.close()
                KeyCode.UP, KeyCode.PAGE_UP, KeyCode.LEFT -> onSwitch(-1)
                KeyCode.DOWN, KeyCode.PAGE_DOWN, KeyCode.RIGHT -> onSwitch(1)
                KeyCode.SPACE -> player?.let { p -> if (p.status == MediaPlayer.Status.PLAYING) p.pause() else p.play() }
                else -> {}
            }
            reveal()
        }
        root.setOnMouseClicked { m -> if (m.button == MouseButton.PRIMARY && m.clickCount == 2) toggleFull() }
        st.setOnHidden { generation++; watchdog?.stop(); player?.dispose(); player = null; SwingUtilities.invokeLater { onStatus("Pemutar ditutup") } }
        stage = st
    }

    fun show() = fx { stage?.let { it.show(); it.toFront(); reveal() } }
    fun stop() = fx { generation++; watchdog?.stop(); player?.dispose(); player = null; stage?.hide() }
    fun close() { if (started) Platform.runLater { watchdog?.stop(); player?.dispose(); stage?.close(); Platform.exit() } }
}
