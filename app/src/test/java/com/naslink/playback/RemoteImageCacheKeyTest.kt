package com.naslink.playback

import com.naslink.data.model.ConnectionProtocol
import com.naslink.data.model.FileItem
import com.naslink.data.model.FileSource
import com.naslink.data.model.RemoteConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Date

/**
 * The cache key is what makes the remote image cache worth having at all.
 *
 * It was originally derived from the URL Coil fetched, and that URL contains a loopback
 * port drawn at random when the server starts plus a 32-byte random token minted per
 * publish call. Keying on it meant every visit wrote a fresh disk entry and hit none of
 * the old ones: the cache grew to tens of megabytes of unreachable data, and clearing it
 * looked like it did nothing because the next browse refilled it with brand-new keys.
 *
 * These tests pin the properties that make the key usable instead.
 */
class RemoteImageCacheKeyTest {

    private val connection = RemoteConnection(
        id = 7,
        name = "home nas",
        protocol = ConnectionProtocol.SMB,
        host = "nas.local",
        port = 445,
        username = "user",
        password = "secret",
        shareName = "MEDIA",
    )

    private val otherConnection = connection.copy(
        id = 8,
        host = "other.local",
        username = "someone",
    )

    private fun item(
        path: String = "/MEDIA/photos/holiday.jpg",
        size: Long = 2048,
        modified: Long = 1_700_000_000_000,
    ) = FileItem(
        name = path.substringAfterLast('/'),
        path = path,
        isDirectory = false,
        size = size,
        lastModified = Date(modified),
        source = FileSource.SMB,
    )

    @Test
    fun `the key does not change between two publishes of the same file`() {
        // This is the whole point: publish() mints a new token and the loopback port is
        // random, so anything derived from the URL would differ on every call.
        val first = RemoteImagePublisher.cacheKey(connection, item())
        val second = RemoteImagePublisher.cacheKey(connection, item())
        assertEquals(first, second)
    }

    @Test
    fun `the key survives a missing connection`() {
        // A viewer opened from a stale state can outlive its connection; it must still
        // produce a usable key rather than crash or return an empty string.
        val key = RemoteImagePublisher.cacheKey(null, item())
        assertEquals(RemoteImagePublisher.cacheKey(null, item()), key)
        assertEquals("unconnected://@:0/MEDIA/photos/holiday.jpg|2048|1700000000000", key)
    }

    @Test
    fun `the instance form agrees with the companion form`() {
        val publisher = RemoteImagePublisher(connection)
        assertEquals(RemoteImagePublisher.cacheKey(connection, item()), publisher.cacheKey(item()))
    }

    @Test
    fun `two servers exporting the same share name do not collide`() {
        // FileItem.path only carries the share and the path inside it, so without the
        // connection in the key two different NASes would serve each other's pictures.
        assertNotEquals(
            RemoteImagePublisher.cacheKey(connection, item()),
            RemoteImagePublisher.cacheKey(otherConnection, item()),
        )
    }

    @Test
    fun `the same path on two accounts of one server does not collide`() {
        val secondAccount = connection.copy(id = 9, username = "family")
        assertNotEquals(
            RemoteImagePublisher.cacheKey(connection, item()),
            RemoteImagePublisher.cacheKey(secondAccount, item()),
        )
    }

    @Test
    fun `an overwritten file is re-fetched rather than served stale`() {
        val original = RemoteImagePublisher.cacheKey(connection, item(size = 2048))
        val overwritten = RemoteImagePublisher.cacheKey(connection, item(size = 4096))
        assertNotEquals(original, overwritten)
    }

    @Test
    fun `a file touched without changing size is still re-fetched`() {
        // mtime is in the key precisely so that a same-size overwrite is not served from
        // cache, which would show the old picture for a file the user just replaced.
        val original = RemoteImagePublisher.cacheKey(connection, item(modified = 1_700_000_000_000))
        val touched = RemoteImagePublisher.cacheKey(connection, item(modified = 1_700_000_009_000))
        assertNotEquals(original, touched)
    }

    @Test
    fun `a pinned share does not duplicate the share name into the key`() {
        // A connection may fix one share, in which case the path is relative to it. The
        // key must stay readable rather than growing a second copy of the share.
        val key = RemoteImagePublisher.cacheKey(connection, item(path = "photos/a.jpg"))
        assertEquals(
            "SMB://user@nas.local:445/MEDIA/photos/a.jpg|2048|1700000000000",
            key,
        )
    }

    @Test
    fun `different files in one folder stay distinct`() {
        val first = RemoteImagePublisher.cacheKey(connection, item("/MEDIA/photos/a.jpg"))
        val second = RemoteImagePublisher.cacheKey(connection, item("/MEDIA/photos/b.jpg"))
        assertNotEquals(first, second)
    }
}
