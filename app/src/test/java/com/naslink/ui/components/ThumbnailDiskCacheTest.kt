package com.naslink.ui.components

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Pins the properties that make the thumbnail store usable as a replacement for Coil's
 * disk cache.
 *
 * The bug it replaces was not "the cache is big" but "the cache is big and cannot be made
 * small": entries were the full original file, keyed by the request, so no preview could
 * ever be evicted in favour of a smaller one and clearing looked ineffective. These tests
 * cover the invariants that prevent a return to that state: bounded total size, eviction of
 * the least recently used entry first, and a hard refusal to store anything larger than the
 * whole budget.
 */
class ThumbnailDiskCacheTest {

    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("thumb-cache-test").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun cache(maxBytes: Long = 1024) = ThumbnailDiskCache(directory, maxBytes)

    private fun blob(size: Int, seed: Int): ByteArray =
        ByteArray(size) { index -> (index + seed).toByte() }

    @Test
    fun `a stored blob comes back byte for byte`() {
        val store = cache()
        val bytes = blob(120, seed = 7)
        store.put("SMB://nas/photos/a.jpg|2048|1700000000000|t512", bytes)
        assertArrayEquals(bytes, store.get("SMB://nas/photos/a.jpg|2048|1700000000000|t512"))
    }

    @Test
    fun `a miss is null rather than an exception`() {
        assertNull(cache().get("never-stored"))
    }

    @Test
    fun `entries survive a new instance over the same directory`() {
        // A process restart must not empty the store: the whole point is that revisiting
        // a folder after closing the app costs no SMB traffic.
        val key = "SMB://nas/photos/a.jpg|2048|1|t512"
        val bytes = blob(64, seed = 3)
        cache().put(key, bytes)
        assertArrayEquals(bytes, cache().get(key))
    }

    @Test
    fun `the least recently stored entries are evicted first`() {
        // 100-byte blobs against a 300-byte budget: each put trims back to 80%, so only
        // the two newest survive.
        val store = cache(maxBytes = 300)
        repeat(4) { index ->
            store.put("key-$index", blob(100, seed = index))
            Thread.sleep(5)
        }
        assertNull(store.get("key-0"))
        assertNull(store.get("key-1"))
        assertArrayEquals(blob(100, seed = 2), store.get("key-2"))
        assertArrayEquals(blob(100, seed = 3), store.get("key-3"))
        assertTrue("budget exceeded: ${store.sizeBytes()}", store.sizeBytes() <= 300)
    }

    @Test
    fun `a read counts as use, so a revisited entry outlives a newer one`() {
        // Budget 320 trims to 256, so the fourth 100-byte entry forces two evictions.
        // A was stored first but looked at again, which must be what saves it: eviction
        // by creation order would drop the picture the user had just scrolled back to.
        val store = cache(maxBytes = 320)
        store.put("A", blob(100, seed = 1))
        Thread.sleep(5)
        store.put("B", blob(100, seed = 2))
        Thread.sleep(5)
        store.put("C", blob(100, seed = 3))
        Thread.sleep(5)
        assertTrue(store.get("A") != null)
        Thread.sleep(5)
        store.put("D", blob(100, seed = 4))
        assertTrue(store.get("A") != null)
        assertTrue(store.get("D") != null)
        assertNull(store.get("B"))
        assertNull(store.get("C"))
    }

    @Test
    fun `a blob larger than the whole budget is not stored`() {
        // Writing it would only be followed by an immediate eviction, and the read path
        // already falls back to decoding from the source.
        val store = cache(maxBytes = 200)
        store.put("huge", blob(201, seed = 0))
        assertNull(store.get("huge"))
        assertEquals(0L, store.sizeBytes())
    }

    @Test
    fun `clearing empties both the directory and the reported size`() {
        val store = cache()
        repeat(3) { store.put("k$it", blob(50, seed = it)) }
        assertTrue(store.sizeBytes() > 0)
        store.clear()
        assertEquals(0L, store.sizeBytes())
        assertNull(store.get("k0"))
        assertEquals(0, directory.listFiles()?.size ?: 0)
    }

    @Test
    fun `keys from different folders and servers never collide`() {
        // The key is hashed, so a path with slashes and colons must not be able to walk
        // out of the directory or overwrite another entry.
        val store = cache()
        val first = "SMB://user@nas.local:445/MEDIA/a.jpg|1|1|t512"
        val second = "SMB://user@other.local:445/MEDIA/a.jpg|1|1|t512"
        store.put(first, blob(10, seed = 1))
        store.put(second, blob(10, seed = 2))
        assertArrayEquals(blob(10, seed = 1), store.get(first))
        assertArrayEquals(blob(10, seed = 2), store.get(second))
    }

    @Test
    fun `a deleted file reads as a miss instead of failing the grid`() {
        val store = cache()
        store.put("k", blob(20, seed = 1))
        directory.listFiles()?.forEach { it.delete() }
        assertNull(store.get("k"))
        assertEquals(0L, store.sizeBytes())
    }

    @Test
    fun `an empty blob is not written`() {
        val store = cache()
        store.put("empty", ByteArray(0))
        assertNull(store.get("empty"))
    }
}
