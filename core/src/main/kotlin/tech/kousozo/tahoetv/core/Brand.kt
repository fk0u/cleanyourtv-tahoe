package tech.kousozo.tahoetv.core

/**
 * Tahoe TV design tokens — the single source of truth for the phone app (Compose), the TV launcher (Android Views,
 * Java: `Brand.ACCENT`) and the desktop app (Compose Desktop). Designers get the same values in
 * `docs/brand/tokens.json`; `BrandTokensTest` fails if the two drift apart.
 *
 * Brand idea — "Danau di malam hari" (a lake at night): a calm dark surface (Night), lit by one clear light
 * (Tahoe Cyan), with deep water underneath (Lake). Everything else is quiet so content stays the hero.
 * Colours are ARGB ints so every platform can use them without a UI dependency.
 */
object Brand {
    // ----------------------------------------------------------------- core palette
    /** Primary accent: focus, links, progress, the "light" of the brand. */
    const val CYAN = 0xFF64D2FF.toInt()
    const val CYAN_LIGHT = 0xFF9BE3FF.toInt()
    const val CYAN_DEEP = 0xFF2EA8E0.toInt()
    /** Text/icons placed ON cyan (never white on cyan: contrast). */
    const val ON_CYAN = 0xFF06222F.toInt()

    /** Secondary: deep lake blue, for gradients and depth. */
    const val LAKE = 0xFF1F3BD1.toInt()
    const val LAKE_DEEP = 0xFF0A1A7A.toInt()

    /** Surfaces, darkest → lightest. */
    const val NIGHT = 0xFF07080D.toInt()        // TV / full-bleed video backgrounds
    const val CANVAS = 0xFF0E0F13.toInt()       // app background
    const val SURFACE = 0xFF1A1C22.toInt()      // cards
    const val SURFACE_HI = 0xFF23262E.toInt()   // raised / hovered cards
    const val SNOW = 0xFFF5F5F7.toInt()         // primary text & icons

    // ----------------------------------------------------------------- semantic
    const val LIVE = 0xFFFF453A.toInt()         // LIVE badge, errors, destructive
    const val PINE = 0xFF30D158.toInt()         // connected, success, switches on
    const val SUN = 0xFFFFD60A.toInt()          // favourites, warnings
    const val TEXT_2 = 0xB3F5F5F7.toInt()       // secondary text (70 %)
    const val TEXT_3 = 0x73F5F5F7.toInt()       // tertiary text / captions (45 %)
    const val STROKE = 0x24FFFFFF.toInt()       // hairlines and card borders (14 %)
    const val GLASS = 0x17FFFFFF.toInt()        // translucent fill over images (9 %)

    /** Category tints: all shades around cyan/lake so grids stay one family. */
    val CATEGORY_TINTS = intArrayOf(CYAN, 0xFF5E8BFF.toInt(), 0xFF4FE3C1.toInt(), 0xFF9FB4FF.toInt())

    // ----------------------------------------------------------------- type (Outfit, variable)
    const val FONT = "Outfit"
    /** Phone/desktop sizes in sp. TV uses the same names at 10-foot sizes (TV_*, in dp on a 960×540 canvas). */
    const val TYPE_DISPLAY = 40f
    const val TYPE_TITLE = 32f
    const val TYPE_HEADLINE = 20f
    const val TYPE_BODY = 15f
    const val TYPE_LABEL = 13f
    const val TYPE_CAPTION = 11f
    const val TV_DISPLAY = 96f
    const val TV_TITLE = 38f
    const val TV_HEADLINE = 24f
    const val TV_BODY = 15f
    const val TV_CAPTION = 12f
    /** Eyebrows (small uppercase labels above titles): letter spacing in em. */
    const val EYEBROW_TRACKING = 0.12f

    // ----------------------------------------------------------------- shape, space, motion
    const val RADIUS_S = 12f
    const val RADIUS_M = 18f
    const val RADIUS_L = 26f
    const val RADIUS_XL = 32f
    /** 4-pt grid. */
    const val SPACE_1 = 4f
    const val SPACE_2 = 8f
    const val SPACE_3 = 12f
    const val SPACE_4 = 16f
    const val SPACE_5 = 20f
    const val SPACE_6 = 24f
    const val SPACE_8 = 32f
    const val MOTION_FAST = 180
    const val MOTION_MED = 260
    const val MOTION_SLOW = 420
    /** TV safe area (dp). */
    const val TV_SAFE_TOP = 30f
    const val TV_SAFE_SIDE = 56f

    // ----------------------------------------------------------------- logo
    /** The mark on a 24-unit grid, drawn as a 2-unit round stroke: screen + stand, two peaks, the lake wave.
     *  The live light is a filled circle at ([MARK_LIGHT_X], [MARK_LIGHT_Y]) r [MARK_LIGHT_R] in Cyan.
     *  Same geometry as docs/brand/build-icons.sh (which makes every app icon). */
    const val MARK_PATH = "M6.5,4 h11 a4.5,4.5 0 0 1 4.5,4.5 v7 a4.5,4.5 0 0 1 -4.5,4.5 h-11 a4.5,4.5 0 0 1 -4.5,-4.5 v-7 a4.5,4.5 0 0 1 4.5,-4.5 z " +
        "M9,22.5 h6 M5.8,13.6 L9.4,8.8 L11.9,11.6 L14.4,8.4 L18.2,13.6 M6.2,16.6 q1.45,-1.1 2.9,0 q1.45,1.1 2.9,0 q1.45,-1.1 2.9,0 q1.45,1.1 2.9,0"
    const val MARK_LIGHT_X = 18f
    const val MARK_LIGHT_Y = 7.6f
    const val MARK_LIGHT_R = 1.4f

    // ----------------------------------------------------------------- voice
    /** Product name as written everywhere (never "TahoeTV" or "TAHOE TV" in body copy). */
    const val NAME = "Tahoe TV"
    const val TAGLINE = "Tahoe TV: TV yang tenang dan mudah, untuk seisi rumah."

    @JvmStatic
    fun tagline(lang: String): String = when (lang.lowercase()) {
        "en" -> "Tahoe TV: calm, simple TV for the whole family."
        "zh" -> "Tahoe TV：安静、简单，全家都会用的电视。"
        else -> TAGLINE
    }

    /** Every token as name → value, for docs and the tokens.json sync test. */
    fun tokens(): Map<String, Any> = linkedMapOf(
        "color.cyan" to hex(CYAN), "color.cyanLight" to hex(CYAN_LIGHT), "color.cyanDeep" to hex(CYAN_DEEP), "color.onCyan" to hex(ON_CYAN),
        "color.lake" to hex(LAKE), "color.lakeDeep" to hex(LAKE_DEEP),
        "color.night" to hex(NIGHT), "color.canvas" to hex(CANVAS), "color.surface" to hex(SURFACE), "color.surfaceHi" to hex(SURFACE_HI), "color.snow" to hex(SNOW),
        "color.live" to hex(LIVE), "color.pine" to hex(PINE), "color.sun" to hex(SUN),
        "color.text2" to hex(TEXT_2), "color.text3" to hex(TEXT_3), "color.stroke" to hex(STROKE), "color.glass" to hex(GLASS),
        "type.font" to FONT, "type.display" to TYPE_DISPLAY, "type.title" to TYPE_TITLE, "type.headline" to TYPE_HEADLINE,
        "type.body" to TYPE_BODY, "type.label" to TYPE_LABEL, "type.caption" to TYPE_CAPTION,
        "type.tv.display" to TV_DISPLAY, "type.tv.title" to TV_TITLE, "type.tv.headline" to TV_HEADLINE, "type.tv.body" to TV_BODY, "type.tv.caption" to TV_CAPTION,
        "type.eyebrowTracking" to EYEBROW_TRACKING,
        "radius.s" to RADIUS_S, "radius.m" to RADIUS_M, "radius.l" to RADIUS_L, "radius.xl" to RADIUS_XL,
        "space.1" to SPACE_1, "space.2" to SPACE_2, "space.3" to SPACE_3, "space.4" to SPACE_4, "space.5" to SPACE_5, "space.6" to SPACE_6, "space.8" to SPACE_8,
        "motion.fast" to MOTION_FAST, "motion.med" to MOTION_MED, "motion.slow" to MOTION_SLOW,
        "tv.safeTop" to TV_SAFE_TOP, "tv.safeSide" to TV_SAFE_SIDE,
    )

    /** "#AARRGGBB" (or "#RRGGBB" when opaque). */
    fun hex(argb: Int): String {
        val a = argb ushr 24
        return if (a == 0xFF) "#%06X".format(argb and 0xFFFFFF) else "#%08X".format(argb.toLong() and 0xFFFFFFFFL)
    }
}
