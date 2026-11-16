package tech.kousozo.tahoetv.desktop

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.layout.BorderPane
import javafx.scene.layout.ColumnConstraints
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.RowConstraints
import javafx.scene.layout.StackPane
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import javafx.scene.paint.Color
import javafx.stage.Stage
import tech.kousozo.tahoetv.core.DiagLog
import tech.kousozo.tahoetv.core.I18n
import tech.kousozo.tahoetv.core.LocalTv
import tech.kousozo.tahoetv.core.M3u
import tech.kousozo.tahoetv.core.SourceHealth

/**
 * Multi-View window (#50): plays 2 (1+1 split) or 4 (2×2 grid) channels simultaneously in independent JavaFX players.
 * Clicking any tile focuses its audio while muting the remaining tiles cleanly.
 */
object MultiViewWindow {
    private var stage: Stage? = null
    private var started = false
    private var gridMode = 4 // 4 = 2x2, 2 = 1+1 split
    private var audioTile = 0
    private var entries: List<LocalTv.Entry> = emptyList()
    private val tiles = ArrayList<Tile>(4)

    private class Tile(
        val index: Int,
        val box: StackPane,
        val view: MediaView,
        val picker: ComboBox<String>,
        val audioBtn: Button,
        val status: Label,
        var player: MediaPlayer? = null,
    )

    private fun fx(block: () -> Unit) {
        if (!started) {
            started = true
            Platform.setImplicitExit(false)
            runCatching { Platform.startup(block) }.onFailure { Platform.runLater(block) }
            return
        }
        Platform.runLater(block)
    }

    fun open(catalog: List<LocalTv.Entry>, initialSelection: LocalTv.Entry? = null) = fx {
        entries = catalog.filter { it.channel.url.isNotBlank() }.take(150)
        val lang = Settings.lang
        val st = stage ?: createStage(lang)
        stage = st
        populatePickers(initialSelection)
        rebuildGrid()
        st.show()
        st.toFront()
    }

    fun close() {
        if (!started) return
        Platform.runLater {
            disposeAll()
            stage?.close()
            stage = null
        }
    }

    private fun createStage(lang: String): Stage {
        tiles.clear()
        val st = Stage().apply { title = I18n.get(lang, "desk.multiview_title") }
        val grid = GridPane().apply { hgap = 6.0; vgap = 6.0; padding = Insets(6.0) }
        val modeBtn = Button(I18n.get(lang, "desk.multiview_2x2"))
        modeBtn.setOnAction {
            gridMode = if (gridMode == 4) 2 else 4
            modeBtn.text = I18n.get(Settings.lang, if (gridMode == 4) "desk.multiview_2x2" else "desk.multiview_1x1")
            rebuildGrid()
        }
        val hint = Label(I18n.get(lang, "desk.multiview_focus_audio")).apply {
            style = "-fx-text-fill: rgba(255,255,255,0.72); -fx-font-size: 12px;"
        }
        val closeBtn = Button("✕").apply {
            setOnAction { disposeAll(); st.close(); stage = null }
        }
        val topBar = HBox(10.0,
            Label(I18n.get(lang, "desk.multiview_title")).apply { style = "-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px;" },
            Region().also { HBox.setHgrow(it, Priority.ALWAYS) },
            hint,
            modeBtn,
            closeBtn,
        ).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(8.0, 12.0, 8.0, 12.0)
            style = "-fx-background-color: #10131b;"
        }

        for (i in 0 until 4) {
            val view = MediaView().apply { isPreserveRatio = true }
            val picker = ComboBox<String>().apply {
                maxWidth = 190.0
                style = "-fx-background-color: rgba(20,24,34,0.85); -fx-text-fill: white; -fx-font-size: 11px;"
            }
            val audioBtn = Button(if (i == 0) "🔊" else "🔇").apply {
                style = "-fx-background-color: rgba(20,24,34,0.85); -fx-text-fill: white; -fx-font-size: 12px; -fx-cursor: hand;"
            }
            val status = Label().apply {
                style = "-fx-text-fill: rgba(255,255,255,0.7); -fx-font-size: 11px; -fx-background-color: rgba(0,0,0,0.55); -fx-padding: 3 8; -fx-background-radius: 6;"
                isVisible = false
            }
            val overlayTop = HBox(6.0, picker, Region().also { HBox.setHgrow(it, Priority.ALWAYS) }, audioBtn).apply {
                padding = Insets(8.0)
                alignment = Pos.TOP_LEFT
                isPickOnBounds = false
            }
            val box = StackPane(view, status, overlayTop).apply {
                style = tileStyle(i == audioTile)
                minWidth = 240.0
                minHeight = 160.0
            }
            view.fitWidthProperty().bind(box.widthProperty())
            view.fitHeightProperty().bind(box.heightProperty())
            val tile = Tile(i, box, view, picker, audioBtn, status)
            picker.setOnAction {
                val chosen = picker.selectionModel.selectedIndex
                if (chosen in entries.indices) startTile(tile, entries[chosen])
            }
            audioBtn.setOnAction { focusAudio(i) }
            box.setOnMouseClicked { focusAudio(i) }
            tiles += tile
        }

        val root = BorderPane().apply {
            top = topBar
            center = grid
            style = "-fx-background-color: #07080d;"
        }
        st.userData = grid
        st.scene = Scene(root, 1120.0, 680.0, Color.BLACK)
        st.setOnHidden { disposeAll(); stage = null }
        return st
    }

    private fun populatePickers(initial: LocalTv.Entry?) {
        val names = entries.map { it.name }
        val startOrder = ArrayList<LocalTv.Entry>()
        initial?.let { if (it in entries) startOrder += it }
        entries.filter { it != initial }.forEach { if (startOrder.size < 4) startOrder += it }
        tiles.forEachIndexed { idx, tile ->
            tile.picker.items.setAll(names)
            val pick = startOrder.getOrNull(idx)
            if (pick != null) {
                tile.picker.selectionModel.select(entries.indexOf(pick))
                startTile(tile, pick)
            }
        }
    }

    private fun rebuildGrid() {
        val st = stage ?: return
        val grid = st.userData as? GridPane ?: return
        grid.children.clear()
        grid.columnConstraints.clear()
        grid.rowConstraints.clear()
        val cols = 2
        val rows = if (gridMode == 4) 2 else 1
        repeat(cols) {
            grid.columnConstraints += ColumnConstraints().apply { percentWidth = 50.0 }
        }
        repeat(rows) {
            grid.rowConstraints += RowConstraints().apply { percentHeight = 100.0 / rows }
        }
        tiles.forEachIndexed { i, t ->
            if (i < gridMode) {
                t.box.isVisible = true
                t.box.isManaged = true
                grid.add(t.box, i % 2, i / 2)
            } else {
                t.box.isVisible = false
                t.box.isManaged = false
                disposeTile(t)
            }
        }
        if (audioTile >= gridMode) focusAudio(0)
    }

    private fun startTile(t: Tile, entry: LocalTv.Entry) {
        disposeTile(t)
        val streams = SourceHealth.order(listOf(entry.channel) + entry.alternates)
            .filter { !PlayerWindow.needsBrowserDrm(it) && !it.isDash }
        val ch = streams.firstOrNull() ?: entry.channel
        t.status.text = entry.name
        t.status.isVisible = true
        runCatching {
            val wrapped = HlsProxy.wrap(ch.url, ch.headers)
            val mp = MediaPlayer(Media(wrapped)).apply {
                isMute = t.index != audioTile
                setOnPlaying { t.status.isVisible = false }
                setOnError { t.status.text = "${entry.name}: ${error?.type ?: "Error"}"; t.status.isVisible = true }
                play()
            }
            t.player = mp
            t.view.mediaPlayer = mp
        }.onFailure {
            DiagLog.error("multiview", "tile ${t.index} failed for ${entry.name}", it)
            t.status.text = "${entry.name}: ${it.message ?: "Error"}"
            t.status.isVisible = true
        }
    }

    private fun focusAudio(idx: Int) {
        audioTile = idx
        tiles.forEach { t ->
            t.player?.isMute = t.index != audioTile
            t.audioBtn.text = if (t.index == audioTile) "🔊" else "🔇"
            t.box.style = tileStyle(t.index == audioTile)
        }
    }

    private fun tileStyle(active: Boolean): String =
        if (active) "-fx-background-color: black; -fx-border-color: #0A84FF; -fx-border-width: 2; -fx-background-radius: 8; -fx-border-radius: 8;"
        else "-fx-background-color: black; -fx-border-color: rgba(255,255,255,0.12); -fx-border-width: 1; -fx-background-radius: 8; -fx-border-radius: 8;"

    private fun disposeTile(t: Tile) {
        val old = t.player ?: return
        t.player = null
        t.view.mediaPlayer = null
        Thread({ runCatching { old.dispose() } }, "tahoe-mv-dispose").apply { isDaemon = true }.start()
    }

    private fun disposeAll() {
        tiles.forEach(::disposeTile)
    }
}
