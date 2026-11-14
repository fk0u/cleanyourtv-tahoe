package tech.kousozo.tahoetv.core

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdatesTest {
    @Test fun comparesVersionsNumerically() {
        assertTrue(Updates.newer("2.9.3", "2.9.2"))
        assertTrue(Updates.newer("2.10.0", "2.9.3"))
        assertTrue(Updates.newer("v3.0.0", "2.9.9"))
        assertFalse(Updates.newer("2.9.2", "2.9.2"))
        assertFalse(Updates.newer("2.9.1", "2.9.2"))
        assertFalse(Updates.newer("2.9.3-beta", "2.9.3"))
    }

    @Test fun parsesChecksumsFile() {
        val sample = """
            # Comments should be ignored
            e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  tahoe-launcher.apk
            ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad *tahoe-tv.apk
        """.trimIndent()
        val parsed = Updates.parseChecksums(sample)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", parsed["tahoe-launcher.apk"])
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", parsed["tahoe-tv.apk"])
    }

    @Test fun rejectsTruncatedDownload() {
        val serverSocket = java.net.ServerSocket(0)
        val serverThread = kotlin.concurrent.thread {
            try {
                val client = serverSocket.accept()
                val out = client.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                out.write(ByteArray(20))
                out.flush()
                client.close()
            } catch (_: Exception) {}
        }

        val tempDir = File(System.getProperty("java.io.tmpdir"), "updates-test-" + System.nanoTime()).apply { mkdirs() }
        val target = File(tempDir, "file.apk")
        val part = File(tempDir, "file.apk.part")

        try {
            val url = "http://localhost:${serverSocket.localPort}/truncated"
            val ex = assertFailsWith<IOException> {
                Updates.download(url, target)
            }
            assertTrue(ex.message?.contains("Unduhan terputus") == true, "Expected 'Unduhan terputus' but got: ${ex.message}")
            assertFalse(target.exists(), "Target file must not exist on truncated download")
            assertFalse(part.exists(), ".part file must be cleaned up on failure")
        } finally {
            serverSocket.close()
            serverThread.join(2000)
            tempDir.deleteRecursively()
        }
    }

    @Test fun verifiesSha256Checksum() {
        val content = "Tahoe TV verified content".toByteArray()
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/download") { ex ->
            ex.sendResponseHeaders(200, content.size.toLong())
            ex.responseBody.use { it.write(content) }
        }
        server.start()

        val tempDir = File(System.getProperty("java.io.tmpdir"), "updates-test-" + System.nanoTime()).apply { mkdirs() }
        val target = File(tempDir, "verified.bin")

        try {
            val url = "http://localhost:${server.address.port}/download"
            // Compute expected SHA-256
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val expectedHash = md.digest(content).joinToString("") { "%02x".format(it) }

            Updates.download(url, target, expectedSha256 = expectedHash)
            assertTrue(target.exists())
            assertEquals(content.size.toLong(), target.length())
            assertEquals(expectedHash, Updates.sha256(target))

            // Now test failure with wrong hash
            target.delete()
            assertFailsWith<IOException> {
                Updates.download(url, target, expectedSha256 = "0000000000000000000000000000000000000000000000000000000000000000")
            }
            assertFalse(target.exists())
        } finally {
            server.stop(0)
            tempDir.deleteRecursively()
        }
    }

    @Test fun resolvesRelativeRedirect() {
        val content = "Final payload".toByteArray()
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/start") { ex ->
            ex.responseHeaders.set("Location", "/final-destination")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/final-destination") { ex ->
            ex.sendResponseHeaders(200, content.size.toLong())
            ex.responseBody.use { it.write(content) }
        }
        server.start()

        val tempDir = File(System.getProperty("java.io.tmpdir"), "updates-test-" + System.nanoTime()).apply { mkdirs() }
        val target = File(tempDir, "redirected.bin")

        try {
            val url = "http://localhost:${server.address.port}/start"
            Updates.download(url, target)
            assertTrue(target.exists())
            assertEquals("Final payload", target.readText())
        } finally {
            server.stop(0)
            tempDir.deleteRecursively()
        }
    }

    @Test fun versionMatchesGradlePropertiesAndStreamUa() {
        val file = listOf(File("../gradle.properties"), File("gradle.properties")).first { it.isFile }
        val props = java.util.Properties().apply { file.inputStream().use(::load) }
        assertEquals(props.getProperty("tahoe.version"), TahoeVersion.VERSION)
        assertEquals(props.getProperty("tahoe.versionCode").toInt(), TahoeVersion.VERSION_CODE)
        assertEquals("TahoeTV/${TahoeVersion.VERSION}", Web.STREAM_UA)
    }
}
