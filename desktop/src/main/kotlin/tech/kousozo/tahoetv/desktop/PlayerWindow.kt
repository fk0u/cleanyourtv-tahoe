package tech.kousozo.tahoetv.desktop

import tech.kousozo.tahoetv.core.SourceHealth
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
import tech.kousozo.tahoetv.core.Web

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
    private lateinit var showLabel: Label
    private lateinit var liveBadge: Label
    private lateinit var timeLabel: Label
    private lateinit var elapsedLabel: Label
    private lateinit var pauseBtn: Button
    private var schedule: List<tech.kousozo.tahoetv.core.Epg.Programme> = emptyList()
    private var weather: String = ""

    /** Programme title + elapsed time of the current show, clock + weather; refreshed every few seconds. */
    private fun refreshInfo() {
        if (!::showLabel.isInitialized) return
        val now = java.time.Instant.now()
        val cur = schedule.firstOrNull { it.start <= now && it.stop > now }
        showLabel.text = cur?.title ?: titleLabel.text
        titleLabel.isVisible = cur != null; titleLabel.isManaged = cur != null
        val sec = cur?.let { now.epochSecond - it.start.epochSecond } ?: 0
        elapsedLabel.text = if (cur != null) "%02d:%02d:%02d  /  %s".format(sec / 3600, sec % 3600 / 60, sec % 60, HM.format(cur.stop)) else ""
        timeLabel.text = HM.format(now) + (if (weather.isNotEmpty()) "    $weather" else "")
        pauseBtn.text = if (player?.status == MediaPlayer.Status.PAUSED) "▶" else "⏸"
    }
    private val HM = java.time.format.DateTimeFormatter.ofPattern("HH.mm").withZone(java.time.ZoneId.systemDefault())
    private val ticker = javafx.animation.Timeline(javafx.animation.KeyFrame(Duration.seconds(1.0), { refreshInfo() })).apply { cycleCount = javafx.animation.Timeline.INDEFINITE }
    private lateinit var statusLabel: Label
    /** Cyan ring shown while a stream is loading/reconnecting, instead of a bare black window. */
    private val spinner by lazy { javafx.scene.control.ProgressIndicator().apply {
        style = "-fx-progress-color: #64D2FF;"; setPrefSize(72.0, 72.0); isVisible = false; isManaged = false } }
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
    fun play(streams: List<M3u.Channel>, title: String, schedule: List<tech.kousozo.tahoetv.core.Epg.Programme>,
             onSwitch: (Int) -> Unit, onStatus: (String?) -> Unit) = fx {
        this.schedule = schedule
        this.onSwitch = onSwitch; this.onStatus = onStatus
        val s = ensureStage()
        titleLabel.text = title; s.title = "$title — Tahoe TV"; refreshInfo()
        start(SourceHealth.order(streams), 0, ++generation) // known-live sources first
        s.show(); s.toFront(); reveal()
    }

    private fun report(s: String?, offerNext: Boolean = false) {
        statusLabel.text = s.orEmpty(); statusLabel.isVisible = s != null
        spinner.isVisible = s != null && !offerNext; spinner.isManaged = spinner.isVisible // loading, not a dead end
        nextBtn.isVisible = offerNext; nextBtn.isManaged = offerNext
        if (s != null) reveal()
        SwingUtilities.invokeLater { onStatus(s) }
    }

    private fun start(streams: List<M3u.Channel>, i: Int, gen: Int, retry: Int = 0) {
        if (gen != generation) return // another channel was chosen meanwhile
        watchdog?.stop(); player?.dispose(); player = null; view.mediaPlayer = null
        val c = streams.getOrNull(i) ?: return report("Saluran ini sedang tidak bisa diputar (semua ${streams.size} sumber offline).", offerNext = true)
        val next = { if (gen == generation) { SourceHealth.report(c.url, false); start(streams, i + 1, gen) } }
        var lastTime = -1.0
        // A source that already played and then errors/freezes reconnects (up to 3×) before moving to the backup.
        val recover = { if (lastTime >= 0 && retry < 3 && gen == generation) { report("Menyambung ulang…"); start(streams, i, gen, if (lastTime > 60) 1 else retry + 1) } else next() } // a minute of good playback earns fresh retries
        if (c.url.contains(".mpd", true) || c.drm) {
            val alt = streams.drop(i + 1).firstOrNull { !it.url.contains(".mpd", true) && !it.drm }
            if (alt != null) return start(streams, streams.indexOf(alt), gen)
            return report("Saluran ini menggunakan DASH/DRM. Silakan tonton melalui Web Player bawaan (Shaka Player) atau pemutar eksternal (Kodi/TiviMate).", offerNext = true)
        }
        if (retry == 0) report(if (i == 0) "Memuat siaran…" else "Sumber ${i} tidak jalan, mencoba cadangan ${i + 1}/${streams.size}…")
        val p = try { MediaPlayer(Media(HlsProxy.wrap(c.url, c.headers))) } catch (e: Exception) { return next() }
        player = p; view.mediaPlayer = p
        p.setOnPlaying { if (p.media.width > 0 || p.media.tracks.isNotEmpty()) { SourceHealth.report(c.url, true); report(null) } }
        p.setOnError { recover() }
        p.play()
        // Watchdog: a stream may never error and just hang, or "play" with no picture / a frozen picture.
        // Give it 20 s to start, then require the clock to keep moving and a video size.
        var stuckSince = System.nanoTime(); val began = System.nanoTime()
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
                // STALLED = buffering, give it 30 s (a restart starts the stream over); playing but stuck 12 s = frozen
                lastTime >= 0 && since > (if (p.status == MediaPlayer.Status.STALLED) 30 else 12) -> recover()
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
        titleLabel = Label().apply { style = "-fx-text-fill: rgba(255,255,255,0.75); -fx-font-size: 14px;" }
        showLabel = Label().apply { style = "-fx-text-fill: white; -fx-font-size: 22px; -fx-font-weight: bold;" }
        liveBadge = Label("LIVE").apply { style = "-fx-background-color: #FF453A; -fx-text-fill: white; -fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 2 8; -fx-background-radius: 5;" }
        timeLabel = Label().apply { style = "-fx-text-fill: white; -fx-font-size: 16px;" }
        elapsedLabel = Label().apply { style = "-fx-text-fill: rgba(255,255,255,0.85); -fx-font-size: 15px; -fx-font-family: monospace;" }
        statusLabel = Label().apply {
            style = "-fx-text-fill: white; -fx-font-size: 16px; -fx-background-color: rgba(0,0,0,0.6); -fx-background-radius: 14; -fx-padding: 12 18;"
            isWrapText = true; maxWidth = 560.0; isVisible = false
        }
        nextBtn = button("Saluran berikutnya ▶") { onSwitch(1) }.apply { isVisible = false; isManaged = false }
        fullBtn = button("⤢  Layar penuh") { toggleFull() }
        pauseBtn = button("⏸") { player?.let { p -> if (p.status == MediaPlayer.Status.PLAYING) p.pause() else p.play() }; refreshInfo() }
        val left = VBox(4.0, HBox(8.0, liveBadge).apply { alignment = Pos.CENTER_LEFT }, showLabel, titleLabel, elapsedLabel).apply { alignment = Pos.BOTTOM_LEFT }
        val mid = HBox(12.0, button("⏮") { onSwitch(-1) }, pauseBtn, button("⏭") { onSwitch(1) }).apply { alignment = Pos.CENTER }
        val right = HBox(10.0, fullBtn, button("✕") { st.close() }).apply { alignment = Pos.BOTTOM_RIGHT }
        val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
        bar = BorderPane().apply {
            top = HBox(16.0, spacer, timeLabel).apply { alignment = Pos.CENTER_RIGHT }
            bottom = HBox(10.0, left, Region().also { HBox.setHgrow(it, Priority.ALWAYS) }, mid, Region().also { HBox.setHgrow(it, Priority.ALWAYS) }, right)
                .apply { alignment = Pos.BOTTOM_CENTER; padding = Insets(12.0, 0.0, 0.0, 0.0) }
            padding = Insets(18.0, 22.0, 18.0, 22.0)
            style = "-fx-background-color: linear-gradient(to bottom, rgba(0,0,0,0.65), transparent 30%, transparent 70%, rgba(0,0,0,0.7));"
            isPickOnBounds = false
        }
        hint = Label("▲▼ ganti saluran  ·  F layar penuh  ·  Esc keluar  ·  Spasi jeda").apply {
            style = "-fx-text-fill: rgba(255,255,255,0.7); -fx-font-size: 12px;"; StackPane.setAlignment(this, Pos.BOTTOM_CENTER)
            StackPane.setMargin(this, Insets(0.0, 0.0, 70.0, 0.0))
        }
        val center = VBox(12.0, spinner, statusLabel, nextBtn).apply { alignment = Pos.CENTER; isPickOnBounds = false }
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
        ticker.play()
        Thread { Web.weather(Settings.lat, Settings.lon)?.let { w -> Platform.runLater { weather = "${w.tempC}° ${w.label}"; refreshInfo() } } }.start()
        stage = st
    }

    fun show() = fx { stage?.let { it.show(); it.toFront(); reveal() } }
    fun stop() = fx { generation++; watchdog?.stop(); player?.dispose(); player = null; stage?.hide() }
    fun close() { if (started) Platform.runLater { watchdog?.stop(); player?.dispose(); stage?.close(); Platform.exit() } }
}
