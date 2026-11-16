package tech.kousozo.tahoetv.core

/** Appearance preferences shared by the phone and desktop apps (#41): light/dark/system, high contrast, reduced
 *  motion. Colours come from [Brand]; video stages always stay [Brand.NIGHT] whatever the theme. */
object Theme {
    enum class Mode { SYSTEM, DARK, LIGHT;
        companion object { @JvmStatic fun of(s: String?) = entries.firstOrNull { it.name.equals(s, true) } ?: DARK }
    }

    /** One resolved palette, ARGB ints (no UI dependency). */
    data class Palette(
        val dark: Boolean, val bg: Int, val surface: Int, val surfaceHi: Int, val text: Int, val dim: Int, val faint: Int,
        val glass: Int, val stroke: Int, val accent: Int, val onAccent: Int,
    )

    @JvmStatic fun isDark(mode: Mode, systemDark: Boolean) = when (mode) { Mode.SYSTEM -> systemDark; Mode.DARK -> true; Mode.LIGHT -> false }

    @JvmStatic fun palette(dark: Boolean, highContrast: Boolean, darkBg: Int = Brand.CANVAS, darkSurface: Int = Brand.SURFACE): Palette = when {
        highContrast && dark -> Palette(true, Brand.HC_CANVAS, Brand.HC_SURFACE, 0xFF1A1A1A.toInt(), Brand.HC_TEXT, Brand.HC_TEXT,
            0xE6FFFFFF.toInt(), 0x33FFFFFF, Brand.HC_STROKE, Brand.HC_FOCUS, 0xFF000000.toInt())
        highContrast -> Palette(false, 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFEDEDED.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(),
            0xD9000000.toInt(), 0x1F000000, 0xFF000000.toInt(), 0xFF004E7A.toInt(), 0xFFFFFFFF.toInt())
        dark -> Palette(true, darkBg, darkSurface, Brand.SURFACE_HI, Brand.SNOW, Brand.TEXT_2, Brand.TEXT_3, Brand.GLASS, Brand.STROKE,
            Brand.CYAN, Brand.ON_CYAN)
        else -> Palette(false, Brand.LIGHT_CANVAS, Brand.LIGHT_SURFACE, Brand.LIGHT_SURFACE_HI, Brand.LIGHT_TEXT, Brand.LIGHT_TEXT_2,
            Brand.LIGHT_TEXT_3, 0x0F0E0F13, Brand.LIGHT_STROKE, Brand.LIGHT_ACCENT, 0xFFFFFFFF.toInt())
    }

    /** Relative-luminance contrast ratio (WCAG) of two opaque colours. */
    @JvmStatic fun contrast(a: Int, b: Int): Double {
        fun lum(c: Int): Double {
            fun ch(v: Int) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch(c shr 16 and 0xFF) + 0.7152 * ch(c shr 8 and 0xFF) + 0.0722 * ch(c and 0xFF)
        }
        val (x, y) = lum(a) to lum(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }
}
