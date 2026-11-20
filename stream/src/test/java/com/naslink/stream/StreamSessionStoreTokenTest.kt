package com.naslink.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the "is my remembered token still good?" contract that [RemoteImagePublisher]'s
 * thumbnail reuse depends on.
 *
 * The reuse cache hands back a token it saw earlier. Two things can retire that token behind
 * its back — the idle sweep and an explicit revoke — so the lookup has to be honest, and it
 * has to stay honest *without* counting as use. A probe that refreshed the idle clock would
 * keep an abandoned publication alive forever and quietly disable the sweep it depends on.
 */
class StreamSessionStoreTokenTest {

    private fun factory(counter: IntArray = intArrayOf(0)): StreamSessionStore.SourceFactory =
        StreamSessionStore.SourceFactory {
            counter[0]++
            object : RandomAccessSource {
                override val size: Long get() = 1
                override fun readAt(offset: Long, buffer: ByteArray, off: Int, len: Int): Int {
                    if (offset > 0) return 0
                    buffer[off] = 1
                    return 1
                }

                override fun close() = Unit
            }
        }

    @Test
    fun `a freshly published token is live and yields its URL`() {
        val store = StreamSessionStore()
        val token = store.publish("a.jpg", "image/jpeg", factory())

        assertTrue(store.isPublished(token))
        assertNotNull(store.lookup(token))
    }

    @Test
    fun `an unknown token is neither live nor resolvable`() {
        val store = StreamSessionStore()

        assertFalse(store.isPublished("never-published"))
        assertNull(store.lookup("never-published"))
    }

    @Test
    fun `a revoked token stops being live`() {
        val store = StreamSessionStore()
        val token = store.publish("a.jpg", "image/jpeg", factory())
        assertTrue(store.isPublished(token))

        store.revoke(token)

        // The failure this guards: a caller that trusted a stale token would hand Coil a URL
        // the server no longer serves, which renders as a broken image instead of a re-fetch.
        assertFalse(store.isPublished(token))
        assertNull(store.lookup(token))
    }

    @Test
    fun `probing a token does not refresh its idle clock`() {
        val idleTimeout = 1_000L
        val store = StreamSessionStore(idleTimeoutMillis = idleTimeout)
        val token = store.publish("a.jpg", "image/jpeg", factory())

        // Idle past the timeout, but probed the whole way. If the probe counted as use, the
        // publication would survive — and so would its warm source — past its own expiry.
        val past = System.currentTimeMillis() + idleTimeout + 500
        repeat(10) {
            assertTrue(store.isPublished(token))
            assertNotNull(store.lookup(token))
        }
        store.sweep(past)

        assertFalse(
            "probing must not count as use, or the idle sweep can never reclaim a session",
            store.isPublished(token),
        )
    }

    @Test
    fun `a session with a transfer in flight survives the sweep while an abandoned one does not`() {
        val idleTimeout = 1_000L
        val store = StreamSessionStore(idleTimeoutMillis = idleTimeout)
        val streaming = store.publish("watched.mkv", "video/x-matroska", factory())
        val abandoned = store.publish("forgotten.mkv", "video/x-matroska", factory())

        // A player pauses mid-stream: the transfer is open, so the session is busy and the
        // sweep must leave it alone — otherwise resume would find a closed source.
        val source = store.resolve(streaming)?.beginTransfer()
        assertNotNull(source)

        store.sweep(System.currentTimeMillis() + idleTimeout + 500)

        assertTrue("a busy session must not be swept", store.isPublished(streaming))
        assertFalse(store.isPublished(abandoned))

        store.resolve(streaming)?.endTransfer()
    }

    @Test
    fun `a session left idle past the timeout is swept even if it was once used`() {
        val idleTimeout = 1_000L
        val store = StreamSessionStore(idleTimeoutMillis = idleTimeout)
        val token = store.publish("a.jpg", "image/jpeg", factory())

        // Used once, then forgotten: the refresh buys time, not an exemption.
        store.resolve(token)
        store.sweep(System.currentTimeMillis() + idleTimeout + 500)

        assertFalse(store.isPublished(token))
    }

    @Test
    fun `resolving the same token twice yields the same session`() {
        val store = StreamSessionStore()
        val token = store.publish("a.jpg", "image/jpeg", factory())

        assertEquals(store.resolve(token), store.resolve(token))
    }

    @Test
    fun `urlFor round-trips the URL publish returned`() {
        val server = LoopbackStreamServer()
        server.start()
        try {
            val published = server.publish("clip.mkv", "video/x-matroska", factory())

            assertTrue(server.isPublished(published.token))
            assertEquals(published.url, server.urlFor(published.token))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `urlFor yields nothing for a retired token`() {
        val server = LoopbackStreamServer()
        server.start()
        try {
            val published = server.publish("clip.mkv", "video/x-matroska", factory())
            server.revoke(published.token)

            assertFalse(server.isPublished(published.token))
            assertNull(server.urlFor(published.token))
        } finally {
            server.stop()
        }
    }
}