package tech.kousozo.tahoetv.core

import java.io.StringReader
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Seeded demo profile, channels, and EPG for clean screenshots and previews (#99). */
object Demo {
    private val XMLTV_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z").withZone(ZoneOffset.UTC)

    @Volatile
    @JvmField
    var active: Boolean = false

    @Volatile
    @JvmField
    var language: String = "en"

    @JvmStatic
    fun isActive(): Boolean = active || System.getProperty("tahoe.demo") == "true"

    @JvmStatic
    @JvmOverloads
    fun enable(lang: String = System.getProperty("tahoe.demo.lang") ?: "en") {
        language = Region.language(lang)
        active = true
        System.setProperty("tahoe.demo", "true")
    }

    @JvmStatic
    fun reset() {
        active = false
        System.clearProperty("tahoe.demo")
    }

    @JvmStatic
    @JvmOverloads
    fun profileName(lang: String = language): String = when (Region.language(lang)) {
        "id" -> "Rina"
        "zh" -> "小林"
        else -> "Alex"
    }

    @JvmStatic
    @JvmOverloads
    fun viewer(lang: String = language): Viewer = when (Region.language(lang)) {
        "id" -> Viewer(
            language = "id",
            country = "ID",
            city = "Jakarta",
            region = "Jakarta",
            timeZone = "Asia/Jakarta",
            prayer = true,
            channelCountries = listOf("ID"),
            favorites = listOf("demo-news", "demo-nature"),
        )
        "zh" -> Viewer(
            language = "zh",
            country = "SG",
            city = "Singapore",
            timeZone = "Asia/Singapore",
            prayer = false,
            channelCountries = listOf("SG", "US"),
            favorites = listOf("demo-news", "demo-nature"),
        )
        else -> Viewer(
            language = "en",
            country = "US",
            city = "New York",
            timeZone = "America/New_York",
            prayer = false,
            channelCountries = listOf("US", "GB"),
            favorites = listOf("demo-news", "demo-nature"),
        )
    }

    @JvmStatic
    fun channels(): List<M3u.Channel> = listOf(
        M3u.Channel("Tahoe News", "https://demo.tahoetv.local/news.m3u8", null, "News", tvgId = "demo-news"),
        M3u.Channel("Alpine Earth", "https://demo.tahoetv.local/earth.m3u8", null, "Documentary", tvgId = "demo-nature"),
        M3u.Channel("Arena Sport", "https://demo.tahoetv.local/sport.m3u8", null, "Sports", tvgId = "demo-sports"),
        M3u.Channel("Spark Kids", "https://demo.tahoetv.local/kids.m3u8", null, "Kids", tvgId = "demo-kids"),
    )

    @JvmStatic
    @JvmOverloads
    fun epg(now: Instant = Instant.now()): Epg {
        val base = now.truncatedTo(ChronoUnit.HOURS)
        val p1Start = XMLTV_FMT.format(base)
        val p1Stop = XMLTV_FMT.format(base.plus(1, ChronoUnit.HOURS))
        val p2Start = p1Stop
        val p2Stop = XMLTV_FMT.format(base.plus(2, ChronoUnit.HOURS))
        val xml = """
            <tv>
              <channel id="demo-news"><display-name>Tahoe News</display-name></channel>
              <channel id="demo-nature"><display-name>Alpine Earth</display-name></channel>
              <channel id="demo-sports"><display-name>Arena Sport</display-name></channel>
              <channel id="demo-kids"><display-name>Spark Kids</display-name></channel>
              <programme start="$p1Start" stop="$p1Stop" channel="demo-news"><title>Evening Headlines</title></programme>
              <programme start="$p2Start" stop="$p2Stop" channel="demo-news"><title>World Weather Report</title></programme>
              <programme start="$p1Start" stop="$p1Stop" channel="demo-nature"><title>Lakes of the Sierra</title></programme>
              <programme start="$p1Start" stop="$p1Stop" channel="demo-sports"><title>Matchday Live</title></programme>
              <programme start="$p1Start" stop="$p1Stop" channel="demo-kids"><title>Starlight Inventors</title></programme>
            </tv>
        """.trimIndent()
        return Epg.parse(StringReader(xml), windowStart = base.minusSeconds(60), windowEnd = base.plus(3, ChronoUnit.HOURS))
    }
}
