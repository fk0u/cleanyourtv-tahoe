package tech.kousozo.tahoetv.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import org.json.JSONObject

/** docs/brand/tokens.json (for designers / Figma Tokens) must equal Brand.kt (for the apps).
 *  Regenerate after changing Brand.kt: ./gradlew :core:test -Dbrand.write=true */
class BrandTokensTest {
    private val file = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "docs/brand/tokens.json") }
        .firstOrNull { it.parentFile.exists() } ?: error("docs/brand not found")

    private fun json() = JSONObject().apply { Brand.tokens().forEach { (k, v) -> put(k, v) } }.toString(2) + "\n"

    @Test fun tokensJsonMatchesBrand() {
        if (System.getProperty("brand.write") == "true") file.writeText(json())
        assertEquals(json(), file.readText(), "docs/brand/tokens.json is out of date: run ./gradlew :core:test -Dbrand.write=true")
    }

    @Test fun textOnCyanIsReadable() {
        fun lum(c: Int): Double { fun ch(v: Int) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch(c shr 16 and 255) + 0.7152 * ch(c shr 8 and 255) + 0.0722 * ch(c and 255) }
        fun contrast(a: Int, b: Int) = (maxOf(lum(a), lum(b)) + 0.05) / (minOf(lum(a), lum(b)) + 0.05)
        assert(contrast(Brand.ON_CYAN, Brand.CYAN) >= 7.0) { "ON_CYAN on CYAN must pass WCAG AAA" }
        assert(contrast(Brand.SNOW, Brand.CANVAS) >= 7.0) { "SNOW on CANVAS must pass WCAG AAA" }
        assert(contrast(Brand.CYAN, Brand.CANVAS) >= 4.5) { "CYAN text on CANVAS must pass WCAG AA" }
    }

    @Test fun taglineLocalizesAcrossLanguages() {
        assertEquals("Tahoe TV: calm, simple TV for the whole family.", Brand.tagline("en"))
        assertEquals("Tahoe TV: TV yang tenang dan mudah, untuk seisi rumah.", Brand.tagline("id"))
        assertEquals("Tahoe TV：安静、简单，全家都会用的电视。", Brand.tagline("zh"))
    }

    @Test fun webStylesheetUsesBrandTokens() {
        val cssFile = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "website/assets/style.css") }
            .firstOrNull { it.exists() } ?: error("website/assets/style.css not found")
        val css = cssFile.readText()
        assert("--motion-fast: ${Brand.MOTION_FAST}ms" in css)
        assert("--motion-med: ${Brand.MOTION_MED}ms" in css)
        assert("--motion-slow: ${Brand.MOTION_SLOW}ms" in css)
        assert("--violet" !in css && "--pink" !in css)
        assert("--cyan-deep" in css && "--lake-deep" in css)
    }
}
