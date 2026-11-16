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
        elapsedLabel.text = if (cur != null) "%02d:%02d:%02d  /  %s".format(sec / 3600, sec % 3600 / 60, sec % 60, hmFmt().format(cur.stop)) else ""
        timeLabel.text = hmFmt().format(now) + (if (weather.isNotEmpty()) "    $weather" else "")
        pauseBtn.text = if (player?.status == MediaPlayer.Status.PAUSED) "▶" else "⏸"
        if (::statsCard.isInitialized && statsCard.isVisible) refreshStats()
    }
    private fun hmFmt() = java.time.format.DateTimeFormatter.ofPattern("HH.mm").withZone(Settings.viewer.zone)
    private val ticker = javafx.animation.Timeline(javafx.animation.KeyFrame(Duration.seconds(1.0), { refreshInfo() })).apply { cycleCount = javafx.animation.Timeline.INDEFINITE }
    private lateinit var statusLabel: Label
    private lateinit var webBtn: Button
    private lateinit var flagBtn: Button
    private var currentStreamUrl: String = ""
    private var currentSourceIndex: Int = 1
    private var currentSourceTotal: Int = 1
    private var stalledSinceMs: Long = 0L
    private var rebufferCount: Int = 0
    private var rebufferMs: Long = 0L
    private lateinit var statsCard: VBox
    private lateinit var statsText: Label
    private lateinit var infoBtn: Button
    private var lastStatsSnapshot: tech.kousozo.tahoetv.core.PlayerStats.Snapshot? = null

    private fun refreshStats() {
        if (!::statsText.isInitialized) return
        val p = player
        val media = p?.media
        val videoTrack = media?.tracks?.firstOrNull { it is javafx.scene.media.VideoTrack } as? javafx.scene.media.VideoTrack
        val audioTrack = media?.tracks?.firstOrNull { it is javafx.scene.media.AudioTrack } as? javafx.scene.media.AudioTrack
        val width = (videoTrack?.width ?: media?.width ?: 0).coerceAtLeast(0)
        val height = (videoTrack?.height ?: media?.height ?: 0).coerceAtLeast(0)
        val fps = ((videoTrack?.metadata?.get("framerate") as? Number)?.toFloat()
            ?: (videoTrack?.metadata?.get("fps") as? Number)?.toFloat()
            ?: 0f)
        val vCodec = (videoTrack?.metadata?.get("encoding") as? String)
            ?: (media?.metadata?.get("video codec") as? String)
            ?: ""
        val aCodec = (audioTrack?.metadata?.get("encoding") as? String)
            ?: (media?.metadata?.get("audio codec") as? String)
            ?: ""
        val curSec = p?.currentTime?.toSeconds()?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val bufSec = p?.bufferProgressTime?.toSeconds()?.takeIf { it.isFinite() && it >= 0.0 }?.let { (it - curSec).coerceAtLeast(0.0) } ?: 0.0
        val liveRebufMs = rebufferMs + (if (stalledSinceMs > 0L) (System.currentTimeMillis() - stalledSinceMs).coerceAtLeast(0L) else 0L)
        val snap = tech.kousozo.tahoetv.core.PlayerStats.Snapshot(
            channelName = titleLabel.text.orEmpty(),
            sourceIndex = currentSourceIndex.coerceAtLeast(1),
            sourceTotal = currentSourceTotal.coerceAtLeast(1),
            streamUrl = currentStreamUrl,
            status = p?.status?.name ?: liveBadge.text.ifBlank { "LIVE" },
            width = width,
            height = height,
            fps = fps,
            videoCodec = vCodec,
            audioCodec = aCodec,
            bufferSeconds = bufSec,
            rebufferCount = rebufferCount,
            rebufferMs = liveRebufMs,
        )
        lastStatsSnapshot = snap
        statsText.text = tech.kousozo.tahoetv.core.PlayerStats.formatText(snap, Settings.lang)
    }

    private fun toggleStats(show: Boolean? = null) {
        if (!::statsCard.isInitialized) return
        val next = show ?: !statsCard.isVisible
        statsCard.isVisible = next
        statsCard.isManaged = next
        if (next) refreshStats()
    }
    private var currentStreams: List<M3u.Channel> = emptyList()
    /** Every stream of the channel (main + backups) incl. ones marked "not live", for the restore button. */
    private var channelStreams: List<M3u.Channel> = emptyList()
    private lateinit var restoreBtn: Button
    private lateinit var retryBtn: Button
    /** Slate (#76): background retries of this channel so far, and the countdown ticking towards the next one. */
    private var slateRetries = 0
    private var slateTimer: Timeline? = null
    /** Sent to the app when the window closes, so it stops treating the channel as playing. */
    const val CLOSED = "Pemutar ditutup"
    private var currentIndex = 0
    private var failReason: String? = null
    private var failSlateReason: tech.kousozo.tahoetv.core.Slate.Reason? = null
    private var probedLiveInSession = false
    private var webFallbackTriedGen = -1

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
    private lateinit var miniBtn: Button
    private lateinit var muteBtn: Button
    private lateinit var drawerBtn: Button
    private lateinit var drawerBox: VBox
    private lateinit var drawerList: javafx.scene.control.ListView<String>
    private lateinit var digitBadge: Label
    private lateinit var bar: BorderPane
    private lateinit var hint: Label
    private lateinit var nextBtn: Button
    private var catalogEntries: List<Pair<String, String>> = emptyList() // (channelName, showTitle)
    private var onSwitch: (Int) -> Unit = {}
    private var onJump: (Int) -> Unit = {}
    private var onStatus: (String?) -> Unit = {}

    private var miniMode = false
    private var savedBounds = doubleArrayOf(960.0, 540.0, Double.NaN, Double.NaN)
    private var dragX = 0.0
    private var dragY = 0.0
    private var digitBuf = ""
    private var digitTimer: Timeline? = null
    private val recapDir = java.io.File(System.getProperty("user.home"), ".tahoetv/recap")
    private var recapStartedAt = 0L
    private var recapChannelId = ""
    private var recapChannelName = ""
    private var recapShow = ""

    private fun flushRecap() {
        if (recapStartedAt <= 0L || recapChannelName.isBlank()) return
        val sec = ((System.currentTimeMillis() - recapStartedAt) / 1000L).toInt()
        recapStartedAt = 0L
        if (sec >= 15) tech.kousozo.tahoetv.core.WatchRecap.record(recapDir, recapChannelId, recapChannelName, recapShow, sec, "main")
    }

    fun toggleMiniPlayer() {
        val st = stage ?: return
        miniMode = !miniMode
        if (miniMode) {
            if (st.isFullScreen) st.isFullScreen = false
            savedBounds = doubleArrayOf(st.width, st.height, st.x, st.y)
            st.isAlwaysOnTop = true
            val vb = javafx.stage.Screen.getPrimary().visualBounds
            st.width = 400.0
            st.height = 248.0
            st.x = vb.maxX - 424.0
            st.y = vb.maxY - 272.0
            if (::miniBtn.isInitialized) miniBtn.text = "❐"
            if (::drawerBox.isInitialized) { drawerBox.isVisible = false; drawerBox.isManaged = false }
            if (::drawerBtn.isInitialized) { drawerBtn.isVisible = false; drawerBtn.isManaged = false }
            if (::muteBtn.isInitialized) { muteBtn.isVisible = false; muteBtn.isManaged = false }
            if (::elapsedLabel.isInitialized) { elapsedLabel.isVisible = false; elapsedLabel.isManaged = false }
            if (::timeLabel.isInitialized) { timeLabel.isVisible = false; timeLabel.isManaged = false }
            if (::flagBtn.isInitialized) { flagBtn.isVisible = false; flagBtn.isManaged = false }
            if (::infoBtn.isInitialized) { infoBtn.isVisible = false; infoBtn.isManaged = false }
            if (::hint.isInitialized) { hint.isVisible = false; hint.isManaged = false }
        } else {
            st.isAlwaysOnTop = false
            st.width = savedBounds[0].takeIf { it > 420.0 } ?: 960.0
            st.height = savedBounds[1].takeIf { it > 260.0 } ?: 540.0
            if (!savedBounds[2].isNaN()) st.x = savedBounds[2]
            if (!savedBounds[3].isNaN()) st.y = savedBounds[3]
            if (::miniBtn.isInitialized) miniBtn.text = "❐ Mini"
            if (::drawerBtn.isInitialized) { drawerBtn.isVisible = true; drawerBtn.isManaged = true }
            if (::muteBtn.isInitialized) { muteBtn.isVisible = true; muteBtn.isManaged = true }
            if (::elapsedLabel.isInitialized) { elapsedLabel.isVisible = true; elapsedLabel.isManaged = true }
            if (::timeLabel.isInitialized) { timeLabel.isVisible = true; timeLabel.isManaged = true }
            if (::flagBtn.isInitialized) { flagBtn.isVisible = true; flagBtn.isManaged = true }
            if (::infoBtn.isInitialized) { infoBtn.isVisible = true; infoBtn.isManaged = true }
            if (::hint.isInitialized) { hint.isVisible = true; hint.isManaged = true }
        }
        reveal()
    }

    fun setChannelCatalog(items: List<Pair<String, String>>) = fx {
        catalogEntries = items
        if (::drawerList.isInitialized) {
            drawerList.items.setAll(items.mapIndexed { idx, (chName, show) ->
                val num = "%03d".format(idx + 1)
                if (show.isNotBlank()) "$num · $chName — $show" else "$num · $chName"
            })
        }
    }

    private fun toggleDrawer(force: Boolean? = null) {
        if (!::drawerBox.isInitialized || miniMode) return
        val show = force ?: !drawerBox.isVisible
        drawerBox.isVisible = show
        drawerBox.isManaged = show
        if (show) {
            if (::statsCard.isInitialized && statsCard.isVisible) {
                statsCard.isVisible = false; statsCard.isManaged = false
            }
            if (::drawerList.isInitialized && catalogEntries.isNotEmpty()) {
                drawerList.items.setAll(catalogEntries.mapIndexed { idx, (chName, showTitle) ->
                    val num = "%03d".format(idx + 1)
                    if (showTitle.isNotBlank()) "$num · $chName — $showTitle" else "$num · $chName"
                })
            }
        }
    }

    private fun toggleMute() {
        player?.let { p ->
            p.isMute = !p.isMute
            if (::muteBtn.isInitialized) muteBtn.text = if (p.isMute) "🔇" else "🔊"
        }
    }

    private fun pushDigit(d: Int) {
        if (digitBuf.length >= 3) digitBuf = ""
        digitBuf += d.toString()
        if (::digitBadge.isInitialized) {
            digitBadge.text = "CH ${digitBuf.padStart(3, '0')}"
            digitBadge.isVisible = true; digitBadge.isManaged = true
        }
        digitTimer?.stop()
        digitTimer = Timeline(KeyFrame(Duration.millis(1100.0), {
            val target = digitBuf.toIntOrNull()
            digitBuf = ""
            if (::digitBadge.isInitialized) { digitBadge.isVisible = false; digitBadge.isManaged = false }
            if (target != null && target >= 1) onJump(target - 1)
        })).also { it.play() }
    }

    private fun fx(block: () -> Unit) {
        if (!started) { started = true; Platform.setImplicitExit(false); runCatching { Platform.startup(block) }.onFailure { Platform.runLater(block) }; return }
        Platform.runLater(block)
    }

    /** `streams` = the channel's main stream then its backups. `onSwitch(±1)` asks the app for the previous/next channel.
     *  `onStatus(null)` = playing, else a message; called on the Swing/Compose thread. */
    fun play(streams: List<M3u.Channel>, title: String, schedule: List<tech.kousozo.tahoetv.core.Epg.Programme>,
             onSwitch: (Int) -> Unit, onJump: (Int) -> Unit = {}, onStatus: (String?) -> Unit) {
        // Windows: JavaFX can't play HLS there (every channel failed with "Could not create player!"); use the web player
        // (the page reports back when video really plays, or fails, and its ◀ ▶ call onSwitch)
        if (WebPlayer.preferred) {
            Thread { WebPlayer.open(streams, title, onSwitch, onStatus)?.let { err -> javax.swing.SwingUtilities.invokeLater { onStatus(err) } } }.start()
            return
        }
        playFx(streams, title, schedule, onSwitch, onJump, onStatus)
    }

    private fun playFx(streams: List<M3u.Channel>, title: String, schedule: List<tech.kousozo.tahoetv.core.Epg.Programme>,
             onSwitch: (Int) -> Unit, onJump: (Int) -> Unit, onStatus: (String?) -> Unit) = fx {
        flushRecap()
        this.schedule = schedule
        this.onSwitch = onSwitch; this.onJump = onJump; this.onStatus = onStatus
        val now = java.time.Instant.now()
        recapStartedAt = System.currentTimeMillis()
        recapChannelId = streams.firstOrNull()?.channelId.orEmpty()
        recapChannelName = title
        recapShow = schedule.firstOrNull { it.start <= now && it.stop > now }?.title.orEmpty()
        val s = ensureStage()
        titleLabel.text = title; s.title = "$title — Tahoe TV"; refreshInfo()
        loaderName.text = title
        val logo = streams.firstOrNull()?.logo
        loaderLogo.image = logo?.let { runCatching { Image(it, 200.0, 120.0, true, true, true) }.getOrNull() }
        loaderTile.isVisible = loaderLogo.image != null; loaderTile.isManaged = loaderTile.isVisible
        channelStreams = streams; slateRetries = 0; slateTimer?.stop()
        probedLiveInSession = streams.any { SourceHealth.status(it.url)?.live == true }
        webFallbackTriedGen = -1
        val gen = ++generation
        val zapGen = tech.kousozo.tahoetv.core.StreamSelector.newZapGeneration()
        if (streams.isNotEmpty() && streams.all { needsBrowserDrm(it) }) {
            loading(true)
            Thread({
                val err = WebPlayer.open(streams, title, onSwitch, onStatus)
                Platform.runLater {
                    if (generation != gen) return@runLater
                    if (err == null) {
                        slateTimer?.stop()
                        release()
                        loading(false)
                        stage?.hide()
                    } else {
                        failSlateReason = tech.kousozo.tahoetv.core.Slate.Reason.Unsupported
                        slate(gen)
                    }
                }
            }, "tahoe-drm-web").apply { isDaemon = true }.start()
            return@fx
        }
        s.show(); s.toFront(); reveal()
        if (streams.size <= 1) {
            start(SourceHealth.order(streams), 0, gen)
        } else {
            loading(true)
            Thread({
                val ranked = tech.kousozo.tahoetv.core.StreamSelector.rankOnOpen(streams, generation = zapGen)
                fx { if (generation == gen) start(ranked, 0, gen) }
            }, "tahoe-rank").apply { isDaemon = true }.start()
        }
    }

    /** Playing: hide the loader and any error. Only real failures ever reach the app window (no "loading…" chatter). */
    private fun playingNow() {
        slateRetries = 0; slateTimer?.stop()
        tech.kousozo.tahoetv.core.DiagLog.add("live", "gambar tampil")
        loading(false); statusLabel.isVisible = false; nextBtn.isVisible = false; nextBtn.isManaged = false; webBtn.isVisible = false; webBtn.isManaged = false
        restoreBtn.isVisible = false; restoreBtn.isManaged = false
        SwingUtilities.invokeLater { onStatus(null) }
    }

    /** Dead end: say why, offer the next channel and the browser Web Player. */
    /** Every source failed: the slate (#76) — the apology in Indonesian, English and Chinese (the viewer's language
     *  first), the reason, a countdown to the next background retry, and the buttons. */
    private fun slate(gen: Int) {
        val lang = Settings.lang
        val order = tech.kousozo.tahoetv.core.Slate.order(lang)
        val reason = failSlateReason ?: tech.kousozo.tahoetv.core.Slate.Reason.Offline
        if (gen != webFallbackTriedGen && (probedLiveInSession || reason == tech.kousozo.tahoetv.core.Slate.Reason.Unsupported) &&
            channelStreams.isNotEmpty()) {
            webFallbackTriedGen = gen
            val snapStreams = channelStreams
            val snapTitle = titleLabel.text
            Thread({
                val err = WebPlayer.open(snapStreams, snapTitle, onSwitch, onStatus)
                Platform.runLater {
                    if (gen != generation) return@runLater
                    if (err == null) {
                        slateTimer?.stop()
                        release()
                        loading(false)
                        stage?.hide()
                    } else {
                        slate(gen)
                    }
                }
            }, "tahoe-web-fallback").apply { isDaemon = true }.start()
            return
        }
        val head = order.joinToString("\n\n") { tech.kousozo.tahoetv.core.Slate.message(it) } + "\n\n" + tech.kousozo.tahoetv.core.Slate.reason(order[0], reason)
        fail(head)
        retryBtn.text = tech.kousozo.tahoetv.core.Slate.label(lang, "retry") + " ↻"; retryBtn.isVisible = true; retryBtn.isManaged = true
        slateTimer?.stop()
        if (slateRetries >= tech.kousozo.tahoetv.core.Slate.MAX_RETRIES) { statusLabel.text = head + "\n\n" + tech.kousozo.tahoetv.core.Slate.label(lang, "waiting"); return }
        var left = tech.kousozo.tahoetv.core.Slate.RETRY_SECONDS
        statusLabel.text = head + "\n\n" + tech.kousozo.tahoetv.core.Slate.label(lang, "trying")
        slateTimer = Timeline(KeyFrame(Duration.seconds(1.0), {
            if (gen != generation) { slateTimer?.stop(); return@KeyFrame }
            left--
            if (left <= 0) { slateTimer?.stop(); slateRetries++; start(SourceHealth.order(channelStreams), 0, gen) }
        })).apply { cycleCount = Timeline.INDEFINITE; play() }
    }

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
        val c = streams.getOrNull(i) ?: return slate(gen)
        val next = { if (gen == generation) { SourceHealth.report(c.url, false); start(streams, i + 1, gen) } }
        if (needsBrowserDrm(c)) {
            // Widevine/PlayReady need the browser's own CDM: try another source first, else hand over to the web player
            val alt = streams.drop(i + 1).firstOrNull { !needsBrowserDrm(it) }
            if (alt != null) return start(streams, streams.indexOf(alt), gen)
            failSlateReason = tech.kousozo.tahoetv.core.Slate.Reason.Unsupported
            return slate(gen)
        }
        if (retry == 0) { failReason = null; failSlateReason = null; statusLabel.isVisible = false; nextBtn.isVisible = false; nextBtn.isManaged = false; webBtn.isVisible = false; webBtn.isManaged = false
            retryBtn.isVisible = false; retryBtn.isManaged = false; slateTimer?.stop(); loading(true) }
        // A source that "answers" can still be a recording (finished playlist, or yesterday's broadcast replayed on a loop).
        // Unless it was just verified live, look at the playlist first and skip it if it isn't live.
        val hls = c.url.substringBefore('?').endsWith(".m3u8", true)
        if (retry == 0 && hls && !LocalTv.isVod(c) && SourceHealth.status(c.url)?.live != true) {
            Thread({
                val kind = HlsProbe.probe(c.url, c.headers, 4000)
                // JavaFX takes the first variant and never switches: if that one is dead but another works, go through the relay
                val fix = kind == HlsProbe.Kind.LIVE && runCatching { HlsProbe.fetch(c.url, c.headers, 4000).let { (t, u) -> HlsProbe.firstVariantDead(t, u, c.headers, 4000) } }.getOrDefault(false)
                Platform.runLater {
                    if (gen != generation) return@runLater
                    if (kind == HlsProbe.Kind.REPLAY || kind == HlsProbe.Kind.STALE) { failReason = "Siaran ini hanya tersedia sebagai rekaman, bukan siaran langsung."; failSlateReason = tech.kousozo.tahoetv.core.Slate.Reason.Recording; next() }
                    else if (kind == HlsProbe.Kind.DEAD) { failReason = "Server saluran ini sedang tidak mengirim siaran."; failSlateReason = tech.kousozo.tahoetv.core.Slate.Reason.Offline; next() } // don't wait 20 s for it
                    else { if (kind == HlsProbe.Kind.LIVE) probedLiveInSession = true; if (fix) relayed += c.url; begin(streams, i, gen, retry, c, next) }
                }
            }, "tahoe-probe").apply { isDaemon = true }.start()
            return
        }
        begin(streams, i, gen, retry, c, next)
    }

    /** Sources whose first variant is dead (found by the probe): they go through the relay, retries included. */
    private val relayed = java.util.Collections.synchronizedSet(HashSet<String>())

    /** DRM this window can't decrypt itself (no ClearKey keys): Widevine/PlayReady or a remote licence server. */
    internal fun needsBrowserDrm(c: M3u.Channel): Boolean =
        c.drm && tech.kousozo.tahoetv.core.Drm.clearKeyMap(c.drmKey).isEmpty()

    private fun begin(streams: List<M3u.Channel>, i: Int, gen: Int, retry: Int, c: M3u.Channel, next: () -> Unit) {
        if (gen != generation) return
        currentStreamUrl = c.url
        currentSourceIndex = i + 1
        currentSourceTotal = streams.size
        stalledSinceMs = 0L
        if (retry == 0) { rebufferCount = 0; rebufferMs = 0L }
        tech.kousozo.tahoetv.core.DiagLog.add("live", "${titleLabel.text} sumber ${i + 1}/${streams.size}" + (if (retry > 0) " (ulang $retry)" else "") + " ${c.url}")
        var lastTime = -1.0
        val isVod = LocalTv.isVod(c)
        // A source that already played and then errors/freezes reconnects (up to 3×) before moving to the backup.
        val recover = { if (lastTime >= 0 && retry < 3 && gen == generation) start(streams, i, gen, if (lastTime > 60) 1 else retry + 1) else next() } // a minute of good playback earns fresh retries
        val p = try {
            val src = if (c.isDash) HlsProxy.wrapDash(c.url, c.headers, c.drmKey) else HlsProxy.wrap(c.url, c.headers, force = c.url in relayed)
            MediaPlayer(Media(src))
        } catch (e: Exception) { return next() }
        player = p; view.mediaPlayer = p
        // "Playing" means a picture: sound alone, or tracks that never draw, no longer end the loader (that was the black screen).
        // Events from a player that was already replaced (disposal runs in the background) must not touch the new one.
        val current = { gen == generation && player === p }
        p.setOnStalled {
            if (current() && stalledSinceMs == 0L) {
                stalledSinceMs = System.currentTimeMillis()
                rebufferCount++
                if (::statsCard.isInitialized && statsCard.isVisible) refreshStats()
            }
        }
        p.setOnPlaying {
            if (current()) {
                if (stalledSinceMs > 0L) {
                    rebufferMs += (System.currentTimeMillis() - stalledSinceMs).coerceAtLeast(0L)
                    stalledSinceMs = 0L
                }
                if (p.media.width > 0 || p.media.tracks.any { it is javafx.scene.media.VideoTrack }) { SourceHealth.report(c.url, true); playingNow() }
                if (::statsCard.isInitialized && statsCard.isVisible) refreshStats()
            }
        }
        p.setOnError { if (current()) { tech.kousozo.tahoetv.core.DiagLog.error("live", "JavaFX: ${p.error?.type} ${p.error?.message}"); recover() } }
        // A live channel that reaches its end is a finished recording: restarting it just plays it from the top again.
        p.setOnEndOfMedia { if (!isVod && current()) { failReason = "Siaran ini hanya tersedia sebagai rekaman, bukan siaran langsung."; failSlateReason = tech.kousozo.tahoetv.core.Slate.Reason.Recording; next() } }
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
                    { failReason = "Siaran ini hanya mengirim suara, tanpa gambar."; failSlateReason = tech.kousozo.tahoetv.core.Slate.Reason.Unsupported; next() }
                // STALLED = buffering, give it 30 s (a restart starts the stream over); playing but stuck 12 s = frozen
                lastTime >= 0 && since > (if (p.status == MediaPlayer.Status.STALLED) 30 else 12) -> recover()
            }
            // Playing for a while but nothing is drawn: the decoder can't show this video (JavaFX plays H.264/AAC only).
            if (moving && !noVideo) {
                black = if (pictureIsBlack()) black + 1 else 0
                // ~20 s of pure black (10 checks): longer than a fade or a dark scene
                if (black >= 10) { black = 0; failReason = "Pemutar ini tidak bisa menampilkan gambar siaran tersebut (kodek video tidak didukung)."; failSlateReason = tech.kousozo.tahoetv.core.Slate.Reason.Unsupported; next() }
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
        nextBtn = button("Saluran berikutnya ▶") { onSwitch(1) }.apply { accessibleText = "Saluran berikutnya"; isVisible = false; isManaged = false }
        webBtn = button("Buka di Web Player") { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(IptvServer.webUrl())) } }.apply { accessibleText = "Buka di Web Player"; isVisible = false; isManaged = false }
        retryBtn = button("Coba lagi ↻") { slateTimer?.stop(); start(SourceHealth.order(channelStreams), 0, ++generation) }.apply { accessibleText = "Muat ulang saluran"; isVisible = false; isManaged = false }
        restoreBtn = button("Pulihkan sumber yang ditandai") {
            SourceHealth.unbanAll(channelStreams); start(SourceHealth.order(channelStreams), 0, ++generation)
        }.apply { accessibleText = "Pulihkan sumber yang ditandai"; isVisible = false; isManaged = false }
        val lang = Settings.lang
        flagBtn = button("⚑ Bukan live") { reportRecording() }.apply { accessibleText = "Tandai bukan live"; tooltip = javafx.scene.control.Tooltip("Sumber ini memutar rekaman / siaran lama: lewati dan jangan dipakai lagi") }
        infoBtn = button("ⓘ ${tech.kousozo.tahoetv.core.I18n.get(lang, "stats.btn_info")}") { toggleStats() }.apply {
            accessibleText = tech.kousozo.tahoetv.core.I18n.get(lang, "stats.btn_info")
        }
        miniBtn = button("❐ Mini") { toggleMiniPlayer() }.apply {
            accessibleText = tech.kousozo.tahoetv.core.I18n.get(lang, "desk.mini_player")
        }
        drawerBtn = button("☰") { toggleDrawer() }.apply {
            accessibleText = tech.kousozo.tahoetv.core.I18n.get(lang, "desk.channel_drawer")
        }
        muteBtn = button("🔊") { toggleMute() }.apply {
            accessibleText = tech.kousozo.tahoetv.core.I18n.get(lang, "desk.mute_toggle")
        }
        fullBtn = button("⤢  Layar penuh") { toggleFull() }.apply { accessibleText = "Alihkan layar penuh" }
        pauseBtn = button("⏸") { player?.let { p -> if (p.status == MediaPlayer.Status.PLAYING) p.pause() else p.play() }; refreshInfo() }.apply { accessibleText = "Jeda atau putar" }
        val prevTopBtn = button("⏮") { onSwitch(-1) }.apply { accessibleText = "Saluran sebelumnya" }
        val nextTopBtn = button("⏭") { onSwitch(1) }.apply { accessibleText = "Saluran berikutnya" }
        val closeTopBtn = button("✕") { st.close() }.apply { accessibleText = "Tutup pemutar" }
        val left = VBox(4.0, HBox(8.0, liveBadge).apply { alignment = Pos.CENTER_LEFT }, showLabel, titleLabel, elapsedLabel).apply { alignment = Pos.BOTTOM_LEFT }
        val mid = HBox(12.0, prevTopBtn, pauseBtn, muteBtn, nextTopBtn).apply { alignment = Pos.CENTER }
        val right = HBox(10.0, flagBtn, infoBtn, drawerBtn, miniBtn, fullBtn, closeTopBtn).apply { alignment = Pos.BOTTOM_RIGHT }

        drawerList = javafx.scene.control.ListView<String>().apply {
            style = "-fx-control-inner-background: rgba(14,18,26,0.92); -fx-background-color: transparent; -fx-text-fill: white; -fx-font-size: 13px;"
            prefWidth = 310.0
            prefHeight = 360.0
            setOnMouseClicked { ev ->
                val idx = selectionModel.selectedIndex
                if (idx >= 0) { onJump(idx); if (ev.clickCount >= 1) toggleDrawer(false) }
            }
        }
        val drawerHeader = HBox(8.0,
            Label("☰ ${tech.kousozo.tahoetv.core.I18n.get(lang, "nav.live")}").apply {
                style = "-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px;"
                HBox.setHgrow(this, Priority.ALWAYS); maxWidth = Double.MAX_VALUE
            },
            button("✕") { toggleDrawer(false) }
        ).apply { alignment = Pos.CENTER_LEFT }
        drawerBox = VBox(8.0, drawerHeader, drawerList).apply {
            style = "-fx-background-color: rgba(14, 18, 26, 0.94); -fx-background-radius: 14; -fx-padding: 12 14; -fx-border-color: rgba(255,255,255,0.16); -fx-border-radius: 14;"
            maxWidth = 340.0; maxHeight = 420.0
            isVisible = false; isManaged = false
        }
        StackPane.setAlignment(drawerBox, Pos.CENTER_RIGHT)
        StackPane.setMargin(drawerBox, Insets(24.0))
        statsText = Label().apply {
            style = "-fx-text-fill: #e8eef7; -fx-font-family: 'Monospaced'; -fx-font-size: 12px;"
            isWrapText = true
        }
        val statsFeedback = Label().apply {
            style = "-fx-text-fill: #8de6b6; -fx-font-size: 11px;"
            isVisible = false; isManaged = false
        }
        val copyStatsBtn = button(tech.kousozo.tahoetv.core.I18n.get(lang, "stats.copy")) {
            refreshStats()
            lastStatsSnapshot?.let { snap ->
                val txt = tech.kousozo.tahoetv.core.PlayerStats.formatText(snap, Settings.lang)
                runCatching {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(txt), null)
                }
                statsFeedback.text = tech.kousozo.tahoetv.core.I18n.get(Settings.lang, "stats.copied")
                statsFeedback.isVisible = true; statsFeedback.isManaged = true
            }
        }
        val logStatsBtn = button(tech.kousozo.tahoetv.core.I18n.get(lang, "stats.send_log")) {
            refreshStats()
            lastStatsSnapshot?.let { snap ->
                tech.kousozo.tahoetv.core.DiagLog.add("stats", tech.kousozo.tahoetv.core.PlayerStats.formatText(snap, "en").replace("\n", " | "))
                statsFeedback.text = tech.kousozo.tahoetv.core.I18n.get(Settings.lang, "stats.log_saved")
                statsFeedback.isVisible = true; statsFeedback.isManaged = true
            }
        }
        val statsCloseBtn = button("✕") { toggleStats(false) }
        val statsHeader = HBox(8.0,
            Label("ⓘ ${tech.kousozo.tahoetv.core.I18n.get(lang, "stats.btn_info")}").apply {
                style = "-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 13px;"
                HBox.setHgrow(this, Priority.ALWAYS)
                maxWidth = Double.MAX_VALUE
            },
            statsCloseBtn
        ).apply { alignment = Pos.CENTER_LEFT }
        statsCard = VBox(8.0, statsHeader, statsText, statsFeedback, HBox(8.0, copyStatsBtn, logStatsBtn)).apply {
            style = "-fx-background-color: rgba(14, 18, 26, 0.90); -fx-background-radius: 12; -fx-padding: 12 14; -fx-border-color: rgba(255,255,255,0.16); -fx-border-radius: 12;"
            maxWidth = 380.0
            maxHeight = Region.USE_PREF_SIZE
            isVisible = false
            isManaged = false
        }
        StackPane.setAlignment(statsCard, Pos.TOP_RIGHT)
        StackPane.setMargin(statsCard, Insets(24.0))
        val spacer = Region().also { HBox.setHgrow(it, Priority.ALWAYS) }
        bar = BorderPane().apply {
            top = HBox(16.0, spacer, timeLabel).apply { alignment = Pos.CENTER_RIGHT }
            bottom = HBox(10.0, left, Region().also { HBox.setHgrow(it, Priority.ALWAYS) }, mid, Region().also { HBox.setHgrow(it, Priority.ALWAYS) }, right)
                .apply { alignment = Pos.BOTTOM_CENTER; padding = Insets(12.0, 0.0, 0.0, 0.0) }
            padding = Insets(18.0, 22.0, 18.0, 22.0)
            style = "-fx-background-color: linear-gradient(to bottom, rgba(0,0,0,0.65), transparent 30%, transparent 70%, rgba(0,0,0,0.7));"
            isPickOnBounds = false
        }
        hint = Label("▲▼ ganti saluran  ·  0-9 nomor  ·  L saluran  ·  P mini-player  ·  F layar penuh  ·  M bisu  ·  I stats  ·  Spasi jeda").apply {
            style = "-fx-text-fill: rgba(255,255,255,0.7); -fx-font-size: 12px;"; StackPane.setAlignment(this, Pos.BOTTOM_CENTER)
            StackPane.setMargin(this, Insets(0.0, 0.0, 70.0, 0.0))
        }
        digitBadge = Label("").apply {
            style = "-fx-background-color: rgba(14,18,26,0.92); -fx-text-fill: #64D2FF; -fx-font-size: 24px; -fx-font-weight: bold; -fx-padding: 10 20; -fx-background-radius: 14; -fx-border-color: rgba(100,210,255,0.5); -fx-border-radius: 14;"
            isVisible = false; isManaged = false
            StackPane.setAlignment(this, Pos.TOP_CENTER)
            StackPane.setMargin(this, Insets(24.0, 0.0, 0.0, 0.0))
        }
        val center = VBox(12.0, statusLabel, retryBtn, nextBtn, webBtn, restoreBtn).apply { alignment = Pos.CENTER; isPickOnBounds = false }
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
        val root = StackPane(view, loader, bar, center, hint, statsCard, drawerBox, digitBadge).apply { style = "-fx-background-color: black;" }
        view.fitWidthProperty().bind(root.widthProperty()); view.fitHeightProperty().bind(root.heightProperty())

        st.scene = Scene(root, 960.0, 540.0, javafx.scene.paint.Color.BLACK)
        st.fullScreenExitHint = "Tekan Esc atau F untuk keluar dari layar penuh"
        st.fullScreenExitKeyCombination = KeyCombination.NO_MATCH // Esc handled below (exit full screen first, close second)
        st.fullScreenProperty().addListener { _, _, full -> fullBtn.text = if (full) "⤡  Keluar layar penuh" else "⤢  Layar penuh"; reveal() }
        st.scene.setOnMouseMoved { reveal() }
        st.scene.setOnKeyPressed { k ->
            when (k.code) {
                KeyCode.F -> toggleFull()
                KeyCode.P -> toggleMiniPlayer()
                KeyCode.L -> toggleDrawer()
                KeyCode.M -> toggleMute()
                KeyCode.I -> toggleStats()
                KeyCode.ESCAPE -> if (drawerBox.isVisible) toggleDrawer(false) else if (statsCard.isVisible) toggleStats(false) else if (miniMode) toggleMiniPlayer() else if (st.isFullScreen) st.isFullScreen = false else st.close()
                KeyCode.UP, KeyCode.PAGE_UP, KeyCode.LEFT -> onSwitch(-1)
                KeyCode.DOWN, KeyCode.PAGE_DOWN, KeyCode.RIGHT -> onSwitch(1)
                KeyCode.SPACE -> player?.let { p -> if (p.status == MediaPlayer.Status.PLAYING) p.pause() else p.play() }
                KeyCode.DIGIT0, KeyCode.NUMPAD0 -> pushDigit(0)
                KeyCode.DIGIT1, KeyCode.NUMPAD1 -> pushDigit(1)
                KeyCode.DIGIT2, KeyCode.NUMPAD2 -> pushDigit(2)
                KeyCode.DIGIT3, KeyCode.NUMPAD3 -> pushDigit(3)
                KeyCode.DIGIT4, KeyCode.NUMPAD4 -> pushDigit(4)
                KeyCode.DIGIT5, KeyCode.NUMPAD5 -> pushDigit(5)
                KeyCode.DIGIT6, KeyCode.NUMPAD6 -> pushDigit(6)
                KeyCode.DIGIT7, KeyCode.NUMPAD7 -> pushDigit(7)
                KeyCode.DIGIT8, KeyCode.NUMPAD8 -> pushDigit(8)
                KeyCode.DIGIT9, KeyCode.NUMPAD9 -> pushDigit(9)
                else -> {}
            }
            reveal()
        }
        root.setOnMousePressed { m -> dragX = m.sceneX; dragY = m.sceneY }
        root.setOnMouseDragged { m -> if (miniMode) { st.x = m.screenX - dragX; st.y = m.screenY - dragY } }
        root.setOnMouseClicked { m -> if (m.button == MouseButton.PRIMARY && m.clickCount == 2) { if (miniMode) toggleMiniPlayer() else toggleFull() } }
        st.setOnHidden { flushRecap(); generation++; watchdog?.stop(); release(); spinTimer.stop(); SwingUtilities.invokeLater { onStatus(CLOSED) } }
        ticker.play()
        Thread { Web.weather(Settings.lat, Settings.lon, Settings.lang)?.let { w -> Platform.runLater { weather = "${w.tempC}° ${w.label}"; refreshInfo() } } }.start()
        stage = st
    }

    fun show() { if (WebPlayer.preferred) { Thread { WebPlayer.reopen() }.start(); return }; fx { stage?.let { it.show(); it.toFront(); reveal() } } }
    fun stop() { flushRecap(); if (WebPlayer.preferred) { WebPlayer.stop(); return }; fx { generation++; watchdog?.stop(); release(); stage?.hide() } }
    fun close() { flushRecap(); WebPlayer.stop(); if (started) Platform.runLater { watchdog?.stop(); release(); stage?.close(); Platform.exit() } }

    /** "This isn't live": the current source plays a recording. Skip it now and never start it first again. */
    private fun reportRecording() {
        val c = currentStreams.getOrNull(currentIndex) ?: return
        SourceHealth.ban(c.url); failReason = "Semua sumber saluran ini memutar rekaman."; failSlateReason = tech.kousozo.tahoetv.core.Slate.Reason.Recording
        start(currentStreams, currentIndex + 1, ++generation) // new generation: a probe still running for the flagged source is ignored
    }
}
