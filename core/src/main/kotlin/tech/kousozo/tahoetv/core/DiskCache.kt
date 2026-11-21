package tech.kousozo.tahoetv.core

import java.io.File
import java.security.MessageDigest

/** A small size-capped byte cache on disk (channel logos, thumbnails): keys are hashed to file names, a read
 *  refreshes the file's time, and writing past [maxBytes] drops the least recently used files first.
 *  Writes are atomic (temp file + rename) so a crash never leaves a half-written entry. */
class DiskCache(private val dir: File, private val maxBytes: Long) {
    init { runCatching { dir.mkdirs() } }

    private fun file(key: String) = File(dir, MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) })

    @Synchronized fun get(key: String): ByteArray? {
        val f = file(key)
        if (!f.isFile) return null
        return runCatching { f.readBytes().also { f.setLastModified(System.currentTimeMillis()) } }.getOrNull()
    }

    @Synchronized fun put(key: String, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > maxBytes) return
        val f = file(key)
        val tmp = File(dir, f.name + ".tmp")
        runCatching { tmp.writeBytes(bytes); if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) } }.onFailure { tmp.delete(); return }
        trim()
    }

    private fun trim() {
        val files = dir.listFiles { x -> x.isFile && !x.name.endsWith(".tmp") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            total -= f.length(); f.delete()
        }
    }

    fun sizeBytes(): Long = dir.listFiles { x -> x.isFile }?.sumOf { it.length() } ?: 0L
}
