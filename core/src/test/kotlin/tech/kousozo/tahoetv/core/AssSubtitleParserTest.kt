package tech.kousozo.tahoetv.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssSubtitleParserTest {
    private val sampleAss = """
        [Script Info]
        ScriptType: v4.00+
        PlayResX: 1280
        PlayResY: 720

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, OutlineColour, Bold, Italic, Alignment
        Style: Default,Outfit,36,&H00FFFFFF,&H00000000,0,0,2
        Style: Sign,Outfit,42,&H0000FFFF,&H00112233,-1,0,8

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:00:01.50,0:00:04.25,Default,,0,0,0,,Hello, world!\NSecond line
        Dialogue: 0,0:00:05.00,0:00:07.00,Sign,,0,0,0,,{\i1\an7\pos(640,180)\c&HFF0000&}Top sign
    """.trimIndent()

    @Test
    fun parsesStylesOverrideTagsAndActiveWindows() {
        assertTrue(AssSubtitleParser.looksLikeAss(sampleAss))
        val doc = AssSubtitleParser.parseDocument(sampleAss)
        assertEquals(1280, doc.playResX)
        assertEquals(720, doc.playResY)
        assertEquals(2, doc.cues.size)

        val first = doc.cues[0]
        assertEquals(1500L, first.startMs)
        assertEquals(4250L, first.endMs)
        assertEquals("Hello, world!\nSecond line", first.text)
        assertEquals(0xFFFFFFFF.toInt(), first.colorArgb)
        assertEquals(2, first.alignment)
        assertNull(first.posX)

        val second = doc.cues[1]
        assertEquals(5000L, second.startMs)
        assertEquals(7000L, second.endMs)
        assertEquals("Top sign", second.text)
        assertTrue(second.bold) // inherited from Sign style (-1)
        assertTrue(second.italic) // inline \i1
        assertEquals(7, second.alignment) // inline \an7
        assertEquals(0.5f, second.posX!!, 0.01f)
        assertEquals(0.25f, second.posY!!, 0.01f)
        assertEquals(0xFF0000FF.toInt(), second.colorArgb) // ASS &HFF0000& = blue -> ARGB 0xFF0000FF

        assertEquals(1, doc.activeAt(2000L).size)
        assertEquals(0, doc.activeAt(4500L).size)
        assertEquals("Top sign", doc.activeAt(5500L).single().text)
    }

    @Test
    fun exportsWebVttWithPositionAndMarkup() {
        val vtt = AssSubtitleParser.toWebVtt(sampleAss)
        assertTrue(vtt.startsWith("WEBVTT"))
        assertTrue("00:00:01.500 --> 00:00:04.250 line:88% align:center" in vtt)
        assertTrue("Hello, world!\nSecond line" in vtt)
        assertTrue("<b><i>Top sign</i></b>" in vtt)
        assertTrue("line:25% align:start" in vtt)
    }
}
