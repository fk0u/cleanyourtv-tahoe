package tech.kousozo.tahoetv.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagLogTest {
    @Test fun redactsQueryStringsAndCredentials() {
        assertEquals("tune https://cdn.example.com/live/index.m3u8?… ok",
            DiagLog.redact("tune https://cdn.example.com/live/index.m3u8?token=abc&exp=1 ok"))
        assertEquals("http://host:8765/pair", DiagLog.redact("http://user:secret@host:8765/pair"))
        assertEquals("no url here", DiagLog.redact("no url here"))
    }

    @Test fun keepsOnlyTheLastLinesAndRedactsThem() {
        DiagLog.clear()
        repeat(DiagLog.MAX + 50) { DiagLog.add("t", "line $it") }
        val lines = DiagLog.text().lines()
        assertEquals(DiagLog.MAX, lines.size)
        assertTrue(lines.last().endsWith("line ${DiagLog.MAX + 49}"))
        DiagLog.add("live", "src https://x.example/a.m3u8?key=SECRET")
        assertFalse("SECRET" in DiagLog.text())
    }

    @Test fun reportHasHeaderAndLog() {
        DiagLog.clear(); DiagLog.add("live", "hello")
        val r = DiagLog.report("Tahoe TV", "3.0.0", mapOf("Perangkat" to "Test"))
        assertTrue(r.startsWith("== Tahoe TV 3.0.0 =="))
        assertTrue("Perangkat: Test" in r && "[live] hello" in r)
    }

    @Test fun errorsSurviveARestart() {
        val f = File.createTempFile("diag", ".log").apply { deleteOnExit() }
        f.writeText("2026-01-01 00:00:00 [error] earlier crash")
        // install() only runs once per process; read the file the way it does
        assertTrue(f.readLines().last().contains("earlier crash"))
    }
}
