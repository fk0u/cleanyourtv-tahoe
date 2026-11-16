package tech.kousozo.tahoetv.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WallpaperProviderTest {
    @Test
    fun parsesWikimediaPotdAndSizesWidth() {
        val raw = """
            {
              "query": {
                "pages": {
                  "12345": {
                    "title": "File:Bromo_sunrise.jpg",
                    "imageinfo": [{
                      "mime": "image/jpeg",
                      "thumburl": "https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Bromo_sunrise.jpg/800px-Bromo_sunrise.jpg",
                      "extmetadata": {
                        "Artist": { "value": "<a href=\"#\">Budi Santoso</a>" }
                      }
                    }]
                  }
                }
              }
            }
        """.trimIndent()
        val photo = WallpaperProvider.parseWikimediaPotd(raw, targetWidth = 1920, lang = "id")
        assertNotNull(photo)
        assertTrue(photo.imageUrl.contains("/1920px-Bromo_sunrise.jpg"))
        assertEquals("Budi Santoso", photo.author)
        assertTrue(photo.attribution.contains("Budi Santoso"))
        assertTrue(photo.attribution.contains("Wikimedia Commons"))
    }

    @Test
    fun parsesUnsplashAndPexelsPhotos() {
        val unsplashRaw = """
            {
              "id": "abc-123",
              "urls": { "raw": "https://images.unsplash.com/photo-1" },
              "links": { "download_location": "https://api.unsplash.com/photos/abc-123/download" },
              "user": { "name": "Andi Wijaya" }
            }
        """.trimIndent()
        val u = WallpaperProvider.parseUnsplashPhoto(unsplashRaw, 3840, "en")
        assertNotNull(u)
        assertTrue(u.imageUrl.contains("w=3840"))
        assertTrue(u.attribution.contains("Andi Wijaya"))
        assertTrue(u.attribution.contains("Unsplash"))

        val pexelsRaw = """
            {
              "photos": [
                {
                  "id": 991,
                  "photographer": "Sari Putri",
                  "src": { "original": "https://images.pexels.com/photos/991/orig.jpg", "landscape": "https://images.pexels.com/photos/991/land.jpg" }
                }
              ]
            }
        """.trimIndent()
        val p = WallpaperProvider.parsePexelsPhoto(pexelsRaw, 1920, "id")
        assertNotNull(p)
        assertEquals("https://images.pexels.com/photos/991/land.jpg", p.imageUrl)
        assertTrue(p.attribution.contains("Sari Putri"))
    }

    @Test
    fun enforcesKidsSafeThemeAndCachesWithPruning() {
        assertEquals(WallpaperProvider.Theme.KIDS_SAFE, WallpaperProvider.effectiveTheme(WallpaperProvider.Theme.CITY, kidsProfile = true))
        assertEquals(WallpaperProvider.Source.WIKIMEDIA, WallpaperProvider.effectiveSource(WallpaperProvider.Source.UNSPLASH, "", ""))
        assertEquals(3840, WallpaperProvider.targetWidth(DeviceProfile.Tier.FULL))
        assertEquals(1280, WallpaperProvider.targetWidth(DeviceProfile.Tier.LIGHT))

        val dir = Files.createTempDirectory("tahoe-wall-test").toFile()
        try {
            val dummyBytes = ByteArray(2048) { (it % 251).toByte() }
            var savedFile: java.io.File? = null
            for (i in 1..12) {
                val meta = WallpaperProvider.PhotoMeta(
                    id = "wall_$i",
                    imageUrl = "https://example.org/$i.jpg",
                    author = "Photog $i",
                    providerName = "Unsplash",
                    attribution = "Photo by Photog $i · Unsplash",
                )
                savedFile = WallpaperProvider.cachePhoto(dir, meta) { dummyBytes }
                assertNotNull(savedFile)
            }
            val remainingJpgs = dir.listFiles { f -> f.name.endsWith(".jpg") }?.size ?: 0
            assertTrue(remainingJpgs <= 10)
            assertNotNull(savedFile)
            assertEquals("Photo by Photog 12 · Unsplash", WallpaperProvider.attributionFor(dir, savedFile.absolutePath))
            assertNull(WallpaperProvider.parseWikimediaPotd("{invalid}", 1920, "en"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun tvSettingsSnapshotRoundTripsWallpaperFields() {
        val snap = TvSettings.Snapshot(
            wallMode = "personal",
            wallSource = "pexels",
            wallTheme = "indonesia",
            hasUnsplashKey = true,
            hasPexelsKey = false,
        )
        val decoded = TvSettings.Snapshot.fromJson(snap.toJson())
        assertEquals("pexels", decoded.wallSource)
        assertEquals("indonesia", decoded.wallTheme)
        assertEquals(true, decoded.hasUnsplashKey)
        assertEquals(false, decoded.hasPexelsKey)
    }
}
