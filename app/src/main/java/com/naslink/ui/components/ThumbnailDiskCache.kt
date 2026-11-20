package com.naslink.ui.components

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * File-backed LRU for *generated* thumbnails.
 *
 * The image grid used to go through Coil's disk cache, which stores whatever the fetcher
 * produced — for a remote photo that is the **entire original file**. One grid cell then
 * cost 3–8 MB on disk, so a folder of a few hundred pictures parked hundreds of megabytes
 * for previews nobody had opened at full size. Coil also caches by the *request* key, and
 * its entries can only be invalidated by clearing the whole cache, so there was no way to
 * keep a small preview and drop the bulk.
 *
 * This cache stores what the grid actually draws: a ~30 KB WebP. One entry per file per
 * target size, keyed by a hash of the caller's stable identity string (connection + path +
 * size + mtime + target size), so a re-published file with a fresh random loopback URL
 * still hits.
 *
 * Deliberately plain: no journal, no in-memory index rebuilt from a log. A thumbnail is
 * disposable, so a missing or corrupt entry is a cache miss, never a bug — and a cache
 * whose accounting can drift is exactly the failure mode this replaces.
 */
internal class ThumbnailDiskCache(
    private val directory: File,
    private val maxBytes: Long,
    /** Fraction of [maxBytes] to keep after a trim, so eviction is amortised. */
    private val trimToFraction: Double = 0.8,
) {

    private val lock = Any()

    /** Cached directory total; `-1` means "recompute on next query". */
    private var knownBytes: Long = -1

    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(trimToFraction in 0.1..1.0) { "trimToFraction must be in 0.1..1.0" }
    }

    /** @return the stored bytes, or null on a miss or an unreadable entry. */
    fun get(key: String): ByteArray? {
        val file = fileFor(key)
        synchronized(lock) {
            if (!file.isFile) {
                // The directory can also shrink behind our back (the OS clearing cache
                // space), so a miss invalidates the cached total rather than trusting it.
                knownBytes = -1
                return null
            }
            val bytes = runCatching { file.readBytes() }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                if (file.exists() && file.delete()) knownBytes = -1
                return null
            }
            // Access order, not creation order: a picture scrolled back to should outlive
            // one that was merely generated first.
            file.setLastModified(System.currentTimeMillis())
            return bytes
        }
    }

    /**
     * Stores [bytes] under [key], then trims the directory back under budget.
     *
     * Blobs larger than the whole budget are dropped rather than written and evicted a
     * moment later: an entry that can never survive a trim is not worth the write.
     */
    fun put(key: String, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > maxBytes) return
        val target = fileFor(key)
        synchronized(lock) {
            if (!directory.isDirectory && !directory.mkdirs()) return
            val temporary = File(directory, target.name + ".tmp")
            val written = runCatching {
                FileOutputStream(temporary).use { it.write(bytes) }
                if (!temporary.renameTo(target)) {
                    // renameTo does not overwrite on every filesystem; replace explicitly.
                    target.delete()
                    check(temporary.renameTo(target)) { "Could not publish thumbnail" }
                }
            }.isSuccess
            if (!written) {
                temporary.delete()
                return
            }
            knownBytes = -1
            trim()
        }
    }

    fun clear() {
        synchronized(lock) {
            directory.listFiles()?.forEach { it.delete() }
            // Not `0`: if a delete failed, the next size query must report the truth.
            knownBytes = -1
        }
    }

    /**
     * Total bytes on disk.
     *
     * A full scan rather than a running counter: the directory can also shrink behind our
     * back (the OS clearing the cache directory under storage pressure), and a counter that
     * disagrees with the filesystem is how "I cleared it and it came back" gets reported.
     */
    fun sizeBytes(): Long = synchronized(lock) {
        if (knownBytes >= 0) return knownBytes
        val total = directory.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
        knownBytes = total
        total
    }

    private fun trim() {
        val files = directory.listFiles()?.filter { it.isFile && it.name.endsWith(SUFFIX) } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) {
            knownBytes = total
            return
        }
        val limit = (maxBytes * trimToFraction).toLong()
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= limit) return@forEach
            val length = file.length()
            if (file.delete()) total -= length
        }
        knownBytes = total
    }

    /**
     * One flat directory, one hashed name per key.
     *
     * Hashing keeps the layout independent of path depth and strips anything (slashes,
     * colons, quotes) that a path from another OS could smuggle into a file name.
     */
    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
        return File(directory, name + SUFFIX)
    }

    private companion object {
        const val SUFFIX = ".thumb"
    }
}
