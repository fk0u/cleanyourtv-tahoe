package tech.kousozo.tahoetv.desktop

import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.paint.Color
import javafx.scene.shape.SVGPath
import javafx.scene.shape.StrokeLineCap
import javafx.scene.shape.StrokeLineJoin

/** The icons of the JavaFX player (Lucide, ISC licence), drawn as vector paths instead of emoji or font glyphs, which look different on every system. */
object FxIcon {
    private val PATHS = mapOf(
        "play" to "M6 3l14 9-14 9z",
        "pause" to "M6 4h4v16H6zM14 4h4v16h-4z",
        "prev" to "M19 20 9 12l10-8zM5 19V5",
        "next" to "M5 4l10 8-10 8zM19 5v14",
        "x" to "M18 6 6 18M6 6l12 12",
        "menu" to "M4 6h16M4 12h16M4 18h16",
        "pip" to "M21 9V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v10c0 1.1.9 2 2 2h4M12 13h8a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-8a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z",
        "flag" to "M4 22V4a1 1 0 0 1 .4-.8A6 6 0 0 1 8 2c3 0 5 2 7.333 2q2 0 3.067-.8A1 1 0 0 1 20 4v10a1 1 0 0 1-.4.8A6 6 0 0 1 16 16c-3 0-5-2-8-2a6 6 0 0 0-4 1.528",
        "volume" to "M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298zM16 9a5 5 0 0 1 0 6M19.364 18.364a9 9 0 0 0 0-12.728",
        "mute" to "M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298zM22 9l-6 6M16 9l6 6",
    )

    /** A [size]-pixel icon in [color]; [name] is one of play, pause, prev, next, x, menu, pip, flag, volume, mute. */
    @JvmStatic @JvmOverloads fun node(name: String, size: Double = 20.0, color: Color = Color.WHITE): Node {
        val p = SVGPath().apply {
            content = PATHS[name] ?: ""
            fill = if (name == "play" || name == "pause" || name == "prev" || name == "next") color else null
            stroke = color; strokeWidth = 2.0; strokeLineCap = StrokeLineCap.ROUND; strokeLineJoin = StrokeLineJoin.ROUND
        }
        return Group(p).apply { scaleX = size / 24.0; scaleY = size / 24.0 }
    }
}
