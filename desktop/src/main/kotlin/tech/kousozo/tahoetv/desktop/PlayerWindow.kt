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
import javafx.animation.FadeTransition
import javafx.scene.Group
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.input.KeyCombination
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.scene.transform.Rotate
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
import tech.kousozo.tahoetv.core.HlsProbe
import tech.kousozo.tahoetv.core.LocalTv
import tech.kousozo.tahoetv.core.M3u
import tech.kousozo.tahoetv.core.Web

/** Live TV plays in its own JavaFX window: embedding JavaFX video in the Compose window (JFXPanel in SwingPanel)
 *  renders blank on macOS. A real window also gives native full screen, and keeps playing while you browse the app.
 *
 *  On-screen controls appear when the mouse moves (hide after 3 s): previous/next channel, full screen on/off, close.
 *  Keys: ▲/▼ or PageUp/PageDown = channel, F / double-click = full screen, Esc = leave full screen, then close,
 *  Space = pause. A source that errors, hangs, plays a recording (finished/old playlist), shows only black or sound, or
 *  freezes is replaced by the next backup; when every source fails, the window says so and offers the next channel and the
 *  Web Player. Loading shows an Apple TV–style spinner over the channel's name and logo (no "loading source" text). */
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
    private lateinit var webBtn: Button
    private lateinit var flagBtn: Button
    private var currentStreams: List<M3u.Channel> = emptyList()
    /** Every stream of the channel (main + backups) incl. ones marked "not live", for the restore button. */
    private var channelStreams: List<M3u.Channel> = emptyList()
    private lateinit var restoreBtn: Button
    /** Sent to the app when the window closes, so it stops treating the channel as playing. */
    const val CLOSED = "Pemutar ditutup"
    private var currentIndex = 0
    private var failReason: String? = null

    // ---- loading overlay: dark scrim, channel logo + name, 12-tick spinner (tvOS / Apple TV style)
    private lateinit var loader: StackPane
    private lateinit var loaderName: Label
    private lateinit var loaderLogo: ImageView
    private lateinit var loaderTile: StackPane
    private var spinPhase = 0
    private var fade: FadeTransition? = null
    private val ticks: List<Rectangle> by lazy { (0 until 12).map { i ->
        Rectangle(-2.0, -30.0, 4.0, 12.0).apply { arcWidth = 4.0; arcHeight = 4.0; fill = Color.WHITE; transforms.add(Rotate(i * 30.0, 0.0, 0.0)) } } }
    private val spinTimer by lazy { Timeline(KeyFrame(Duration.millis(85.0), {
        spinPhase = (spinPhase + 1) % 12
        ticks.forEachIndexed { i, r -> r.opacity = 0.18 + 0.82 * (((spinPhase - i) % 12 + 12) % 12) / 11.0 }
    })).apply { cycleCount = Timeline.INDEFINITE } }

    private fun loading(on: Boolean) {
        if (!::loader.isInitialized) return
        fade?.stop()
        if (on) {
            if (!loader.isVisible) loader.opacity = 0.0
            loader.isVisible = true
            fade = FadeTransition(Duration.millis(260.0), loader).apply { toValue = 1.0; play() }
            spinTimer.play()
        } else if (loader.isVisible) {
            fade = FadeTransition(Duration.millis(260.0), loader).apply { toValue = 0.0; setOnFinished { loader.isVisible = false; spinTimer.stop() }; play() }
        }
    }

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
        loaderName.text = title
        val logo = streams.firstOrNull()?.logo
        loaderLogo.image = logo?.let { runCatching { Image(it, 200.0, 120.0, true, true, true) }.getOrNull() }
        loaderTile.isVisible = loaderLogo.image != null; loaderTile.isManaged = loaderTile.isVisible
        channelStreams = streams
        start(SourceHealth.order(streams), 0, ++generation) // known-live sources first, recordings left out
        s.show(); s.toFront(); reveal()
    }

    /** Playing: hide the loader and any error. Only real failures ever reach the app window (no "loading…" chatter). */
    private fun playingNow() {
        tech.kousozo.tahoetv.core.DiagLog.add("live", "gambar tampil")
        loading(false); statusLabel.isVisible = false; nextBtn.isVisible = false; nextBtn.isManaged = false; webBtn.isVisible = false; webBtn.isManaged = false
        restoreBtn.isVisible = false; restoreBtn.isManaged = false
        SwingUtilities.invokeLater { onStatus(null) }
    }

    /** Dead end: say why, offer the next channel and the browser Web Player. */
    private fun fail(msg: String, offerWeb: Boolean = true) {
        tech.kousozo.tahoetv.core.DiagLog.add("live", "${titleLabel.text} gagal: $msg")
        loading(false)
        statusLabel.text = msg; statusLabel.isVisible = true
        nextBtn.isVisible = true; nextBtn.isManaged = true; webBtn.isVisible = offerWeb; webBtn.isManaged = offerWeb
        val marked = channelStreams.any { SourceHealth.isBanned(it.url) }
        restoreBtn.isVisible = marked; restoreBtn.isManaged = marked
        reveal()
        SwingUtilities.invokeLater { onStatus(msg) }
    }

    /** Drops the old player without letting it hold up the next channel: dispose() can block while a live stream is
     *  buffering, which froze the window so no other channel could be opened. */
    private fun release() {
        val p = player ?: return
        player = null; view.mediaPlayer = null
        Thread({ runCatching { p.dispose() }.onFailure { Platform.runLater { runCatching { p.dispose() } } } }, "tahoe-dispose").apply { isDaemon = true }.start()
    }

    /** True when the picture is (nearly) pure black: a video the decoder can't draw, which JavaFX still reports as "playing". */
    private fun pictureIsBlack(): Boolean = runCatching {
        if (stage?.isIconified == true || view.mediaPlayer == null) return false
        val sp = javafx.scene.SnapshotParameters().apply { transform = javafx.scene.transform.Scale(0.12, 0.12); fill = Color.BLACK }
        val img = view.snapshot(sp, null)
        val r = img.pixelReader
        for (y in 0 until img.height.toInt() step 2) for (x in 0 until img.width.toInt() step 2) {
            val c = r.getColor(x, y)
            if (maxOf(c.red, c.green, c.blue) > 0.08) return false
        }
        true
    }.getOrDefault(false)

    private fun start(streams: List<M3u.Channel>, i: Int, gen: Int, retry: Int = 0) {
        if (gen != generation) return // another channel was chosen meanwhile
        watchdog?.stop(); release()
        currentStreams = streams; currentIndex = i
        val c = streams.getOrNull(i) ?: return fail(failReason ?: "Saluran ini sedang tidak bisa diputar. Coba saluran lain, atau buka di Web Player.")
        val next = { if (gen == generation) { SourceHealth.report(c.url, false); start(streams, i + 1, gen) } }
        if (c.url.contains(".mpd", true) || c.drm) {
            val alt = streams.drop(i + 1).firstOrNull { !it.url.contains(".mpd", true) && !it.drm }
            if (alt != null) return start(streams, streams.indexOf(alt), gen)
            return fail("Saluran ini menggunakan DASH/DRM. Silakan tonton melalui Web Player bawaan (Shaka Player) atau pemutar eksternal (Kodi/TiviMate).")
        }
        if (retry == 0) { failReason = null; statusLabel.isVisible = false; nextBtn.isVisible = false; nextBtn.isManaged = false; webBtn.isVisible = false; webBtn.isManaged = false; loading(true) }
        // A source that "answers" can still be a recording (finished playlist, or yesterday's broadcast replayed on a loop).
        // Unless it was just verified live, look at the playlist first and skip it if it isn't live.
        val hls = c.url.substringBefore('?').endsWith(".m3u8", true)
        if (retry == 0 && hls && !LocalTv.isVod(c) && SourceHealth.status(c.url)?.live != true) {
            Thread({
                val kind = HlsProbe.probe(c.url, c.headers, 4000)
                Platform.runLater {
                    if (gen != generation) return@runLater
                    if (kind == HlsProbe.Kind.REPLAY || kind == HlsProbe.Kind.STALE) { failReason = "Siaran ini hanya tersedia sebagai rekaman, bukan siaran langsung."; next() }
                    else begin(streams, i, gen, retry, c, next)
                }
            }, "tahoe-probe").apply { isDaemon = true }.start()
            return
        }
        begin(streams, i, gen, retry, c, next)
    }

    private fun begin(streams: List<M3u.Channel>, i: Int, gen: Int, retry: Int, c: M3u.Channel, next: () -> Unit) {
        if (gen != generation) return
        tech.kousozo.tahoetv.core.DiagLog.add("live", "${titleLabel.text} sumber ${i + 1}/${streams.size}" + (if (retry > 0) " (ulang $retry)" else "") + " ${c.url}")
        var lastTime = -1.0
        val isVod = LocalTv.isVod(c)
        // A source that already played and then errors/freezes reconnects (up to 3×) before moving to the backup.
        val recover = { if (lastTime >= 0 && retry < 3 && gen == generation) start(streams, i, gen, if (lastTime > 60) 1 else retry + 1) else next() } // a minute of good playback earns fresh retries
        val p = try { MediaPlayer(Media(HlsProxy.wrap(c.url, c.headers))) } catch (e: Exception) { return next() }
        player = p; view.mediaPlayer = p
        // "Playing" means a picture: sound alone, or tracks that never draw, no longer end the loader (that was the black screen).
        // Events from a player that was already replaced (disposal runs in the background) must not touch the new one.
        val current = { gen == generation && player === p }
        p.setOnPlaying { if (current() && (p.media.width > 0 || p.media.tracks.any { it is javafx.scene.media.VideoTrack })) { SourceHealth.report(c.url, true); playingNow() } }
        p.setOnError { if (current()) { tech.kousozo.tahoetv.core.DiagLog.error("live", "JavaFX: ${p.error?.type} ${p.error?.message}"); recover() } }
        // A live channel that reaches its end is a finished recording: restarting it just plays it from the top again.
        p.setOnEndOfMedia { if (!isVod && current()) { failReason = "Siaran ini hanya tersedia sebagai rekaman, bukan siaran langsung."; next() } }
        p.play()
        // Watchdog: a stream may never error and just hang, or "play" with no picture / a frozen or black picture.
        var stuckSince = System.nanoTime(); val began = System.nanoTime()
        var black = 0
        watchdog = Timeline(KeyFrame(Duration.seconds(2.0), {
            if (gen != generation || player !== p) return@KeyFrame
            val t = p.currentTime.toSeconds()
            val moving = p.status == MediaPlayer.Status.PLAYING && t > lastTime + 0.1
            if (moving) { lastTime = t; stuckSince = System.nanoTime() }
            val since = (System.nanoTime() - stuckSince) / 1e9
            val age = (System.nanoTime() - began) / 1e9
            val noVideo = p.media.width == 0 && p.media.tracks.none { it is javafx.scene.media.VideoTrack }
            when {
                p.status == MediaPlayer.Status.PAUSED -> stuckSince = System.nanoTime()
                age > 20 && lastTime < 0 -> next()                                   // never started
                // sound only: judged once it really plays (track info is known by then), not while still connecting
                age > 12 && noVideo && lastTime >= 0 && p.status == MediaPlayer.Status.PLAYING && p.media.tracks.isNotEmpty() ->
                    { failReason = "Siaran ini hanya mengirim suara, tanpa gambar."; next() }
                // STALLED = buffering, give it 30 s (a restart starts the stream over); playing but stuck 12 s = frozen
                lastTime >= 0 && since > (if (p.status == MediaPlayer.Status.STALLED) 30 else 12) -> recover()
            }
            // Playing for a while but nothing is drawn: the decoder can't show this video (JavaFX plays H.264/AAC only).
            if (moving && !noVideo) {
                black = if (pictureIsBlack()) black + 1 else 0
                // ~20 s of pure black (10 checks): longer than a fade or a dark scene
                if (black >= 10) { black = 0; failReason = "Pemutar ini tidak bisa menampilkan gambar siaran tersebut (kodek video tidak didukung)."; next() }
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
        webBtn = button("Buka di Web Player") { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(IptvServer.webUrl())) } }.apply { isVisible = false; isManaged = false }
        restoreBtn = button("Pulihkan sumber yang ditandai") {
            SourceHealth.unbanAll(channelStreams); start(SourceHealth.order(channelStreams), 0, ++generation)
        }.apply { isVisible = false; isManaged = false }
        flagBtn = button("⚑ Bukan live") { reportRecording() }.apply { tooltip = javafx.scene.control.Tooltip("Sumber ini memutar rekaman / siaran lama: lewati dan jangan dipakai lagi") }
        fullBtn = button("⤢  Layar penuh") { toggleFull() }
        pauseBtn = button("⏸") { player?.let { p -> if (p.status == MediaPlayer.Status.PLAYING) p.pause() else p.play() }; refreshInfo() }
        val left = VBox(4.0, HBox(8.0, liveBadge).apply { alignment = Pos.CENTER_LEFT }, showLabel, titleLabel, elapsedLabel).apply { alignment = Pos.BOTTOM_LEFT }
        val mid = HBox(12.0, button("⏮") { onSwitch(-1) }, pauseBtn, button("⏭") { onSwitch(1) }).apply { alignment = Pos.CENTER }
        val right = HBox(10.0, flagBtn, fullBtn, button("✕") { st.close() }).apply { alignment = Pos.BOTTOM_RIGHT }
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
        val center = VBox(12.0, statusLabel, nextBtn, webBtn, restoreBtn).apply { alignment = Pos.CENTER; isPickOnBounds = false }
        loaderLogo = ImageView().apply { isPreserveRatio = true; fitWidth = 150.0; fitHeight = 84.0 }
        loaderTile = StackPane(loaderLogo).apply {
            maxWidth = 176.0; maxHeight = 104.0; padding = Insets(10.0)
            style = "-fx-background-color: rgba(255,255,255,0.94); -fx-background-radius: 18;"
        }
        loaderName = Label().apply { style = "-fx-text-fill: white; -fx-font-size: 22px; -fx-font-weight: bold; -fx-letter-spacing: 1px;" }
        val spinner = StackPane(Group().apply { children.addAll(ticks) }).apply { setPrefSize(72.0, 72.0); minWidth = 72.0; minHeight = 72.0 }
        loader = StackPane(VBox(26.0, loaderTile, loaderName, spinner).apply { alignment = Pos.CENTER; isPickOnBounds = false }).apply {
            style = "-fx-background-color: radial-gradient(center 50% 45%, radius 80%, rgba(20,24,34,0.92), rgba(4,5,8,0.97));"
            isVisible = false; isMouseTransparent = true
        }
        val root = StackPane(view, loader, bar, center, hint).apply { style = "-fx-background-color: black;" }
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
        st.setOnHidden { generation++; watchdog?.stop(); release(); spinTimer.stop(); SwingUtilities.invokeLater { onStatus(CLOSED) } }
        ticker.play()
        Thread { Web.weather(Settings.lat, Settings.lon)?.let { w -> Platform.runLater { weather = "${w.tempC}° ${w.label}"; refreshInfo() } } }.start()
        stage = st
    }

    fun show() = fx { stage?.let { it.show(); it.toFront(); reveal() } }
    fun stop() = fx { generation++; watchdog?.stop(); release(); stage?.hide() }
    fun close() { if (started) Platform.runLater { watchdog?.stop(); release(); stage?.close(); Platform.exit() } }

    /** "This isn't live": the current source plays a recording. Skip it now and never start it first again. */
    private fun reportRecording() {
        val c = currentStreams.getOrNull(currentIndex) ?: return
        SourceHealth.ban(c.url); failReason = "Semua sumber saluran ini memutar rekaman."
        start(currentStreams, currentIndex + 1, ++generation) // new generation: a probe still running for the flagged source is ignored
    }
}
