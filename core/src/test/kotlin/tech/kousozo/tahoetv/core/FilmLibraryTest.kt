package tech.kousozo.tahoetv.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilmLibraryTest {
    private fun ch(name: String, url: String = "https://example.com/a.m3u8", group: String? = null) = M3u.Channel(name, url, null, group)

    @Test fun pixeldrainAndFilmGroupsAreLibrary() {
        assertTrue(FilmLibrary.isLibrary(ch("X", "https://pixeldrain.com/api/file/abc")))
        assertTrue(FilmLibrary.isLibrary(ch("X", "https://cdn.pixeldrain.net/u/abc")))
        assertTrue(FilmLibrary.isLibrary(ch("X", group = "Film Indonesia")))
        assertTrue(FilmLibrary.isLibrary(ch("X", group = "Series")))
        assertFalse(FilmLibrary.isLibrary(ch("Trans TV", group = "General")))
        assertFalse(FilmLibrary.isLibrary(ch("X", "https://notpixeldrain.com/a")))
    }

    @Test fun releaseNamesBecomTitles() {
        val m = FilmLibrary.parse("The.Raid.2.2014.1080p.BluRay.x264.mkv")
        assertEquals("The Raid 2", m.title); assertEquals(2014, m.year)
        val s = FilmLibrary.parse("Serial Ku S02E05 720p WEB-DL")
        assertEquals("Serial Ku", s.title); assertEquals(2, s.season); assertEquals(5, s.episode); assertNull(s.year)
        assertEquals("Laskar Pelangi", FilmLibrary.parse("Laskar Pelangi (2008) [Indo]").title)
    }

    @Test fun episodesFoldIntoOneSeriesAndLeaveLive() {
        val list = listOf(
            ch("Drama X S01E02", "https://pixeldrain.com/api/file/2"), ch("Drama X S01E01", "https://pixeldrain.com/api/file/1"),
            ch("Film Y 2020", "https://pixeldrain.com/api/file/3"), ch("RCTI", group = "General"))
        val g = FilmLibrary.group(list)
        assertEquals(2, g.size)
        val series = g.first { it.kind == FilmLibrary.Kind.Series }
        assertEquals(listOf(1, 2), series.items.map { it.episode })
        val live = LocalTv.arrange(list, null, listOf("ID"))
        assertEquals(listOf("RCTI"), live.map { it.name })
    }

    @Test fun posterParsers() {
        val it = """{"results":[{"trackName":"The Raid 2","releaseDate":"2014-03-28T07:00:00Z","artworkUrl100":"https://is1.mzstatic.com/x/100x100bb.jpg"}]}"""
        assertEquals("https://is1.mzstatic.com/x/400x600bb.jpg", MediaScraper.parseItunesPoster(it, "The Raid 2", 2014))
        assertNull(MediaScraper.parseItunesPoster(it, "Unrelated Film", null))
        val tv = """[{"show":{"name":"Drama X","image":{"medium":"http://a/m.jpg","original":"http://a/o.jpg"}}}]"""
        assertEquals("https://a/o.jpg", MediaScraper.parseTvmazePoster(tv, "Drama X"))
    }
}
