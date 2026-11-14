package tech.kousozo.tahoetv.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class I18nTest {
    private val langs = listOf("en", "id", "zh")

    @Test fun allI18nKeysPresentAndNonBlankInEnIdZh() {
        assertEquals(langs.toSet(), I18n.TABLES.keys)
        val expectedKeys = I18n.TABLES.getValue("id").keys
        assertTrue(expectedKeys.isNotEmpty())
        langs.forEach { lang ->
            val table = I18n.TABLES.getValue(lang)
            assertEquals(expectedKeys, table.keys, "Key mismatch for lang=$lang")
            table.forEach { (k, v) ->
                assertTrue(v.isNotBlank(), "Blank value for ($lang, $k)")
            }
        }
        // Verify format placeholders align across all 3 languages
        val ph = Regex("%[sd]")
        expectedKeys.forEach { key ->
            val counts = langs.map { ph.findAll(I18n.TABLES.getValue(it).getValue(key)).count() }.distinct()
            assertEquals(1, counts.size, "Placeholder count differs for key=$key")
        }
    }

    @Test fun onboardingTextParityAcrossEnIdZh() {
        val field = OnboardingText::class.java.getDeclaredField("T").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val tables = field.get(OnboardingText) as Map<String, Map<String, String>>
        assertEquals(langs.toSet(), tables.keys)
        val expectedKeys = tables.getValue("en").keys
        langs.forEach { lang ->
            val table = tables.getValue(lang)
            assertEquals(expectedKeys, table.keys, "OnboardingText key mismatch for lang=$lang")
            table.forEach { (k, v) ->
                assertTrue(v.isNotBlank(), "Blank OnboardingText for ($lang, $k)")
            }
        }
    }

    @Test fun categoryLabelsLocalizeCleanly() {
        LocalTv.Category.entries.forEach { cat ->
            langs.forEach { lang ->
                val lbl = cat.label(lang)
                assertTrue(lbl.isNotBlank() && !lbl.startsWith("cat."), "Bad label for $cat in $lang: $lbl")
                assertEquals(lbl, OnboardingText.category(lang, cat))
            }
        }
        assertNotEquals(LocalTv.Category.Sports.label("en"), LocalTv.Category.Sports.label("id"))
        assertNotEquals(LocalTv.Category.Sports.label("en"), LocalTv.Category.Sports.label("zh"))
    }

    @Test fun playPrefsLocalizeAcrossLanguages() {
        PlayPrefs.Quality.entries.forEach { q ->
            langs.forEach { lang -> assertTrue(q.label(lang).isNotBlank()) }
        }
        assertEquals("Auto", PlayPrefs.Quality.Auto.label("en"))
        assertEquals("Otomatis", PlayPrefs.Quality.Auto.label("id"))
        assertEquals("自动", PlayPrefs.Quality.Auto.label("zh"))

        PlayPrefs.Latency.entries.forEach { l ->
            langs.forEach { lang ->
                assertTrue(l.label(lang).isNotBlank())
                assertTrue(l.note(lang).isNotBlank())
            }
        }
        assertEquals("Low", PlayPrefs.Latency.Low.label("en"))
        assertEquals("低延迟", PlayPrefs.Latency.Low.label("zh"))
    }

    @Test fun scanFoundLabelsAndStatusesLocalize() {
        val anon = Scan.Found("192.168.1.9", "", "3.2.0", adb = true, tahoe = true)
        assertTrue(anon.label("en").startsWith("Device · "))
        assertTrue(anon.label("id").startsWith("Perangkat · "))
        assertTrue(anon.label("zh").startsWith("设备 · "))

        val named = Scan.Found("192.168.1.9", "LivingRoom.local", "3.2.0", adb = true, tahoe = true)
        assertEquals("LivingRoom · 192.168.1.9", named.label("en"))

        langs.forEach { lang ->
            assertTrue(anon.status(lang).contains("3.2.0"))
            val noAdb = anon.copy(adb = false)
            assertTrue(noAdb.status(lang).isNotBlank())
            val plain = anon.copy(tahoe = false)
            assertTrue(plain.status(lang).isNotBlank())
        }
        assertFalse(anon.status("en").contains("siap"))
        assertFalse(anon.status("zh").contains("siap"))
    }

    @Test fun greetingsAndUnknownLanguageFallback() {
        assertEquals("Good morning", I18n.greeting("en", 7))
        assertEquals("Selamat siang", I18n.greeting("id", 12))
        assertEquals("晚上好", I18n.greeting("zh", 21))
        assertEquals(I18n.get("id", "nav.home"), I18n.get("fr", "nav.home"))
        assertEquals("Home", I18n.get("EN", "nav.home"))
        assertEquals("12 channels", I18n.get("en", "home.channel_count", 12))
    }
}
