package com.naslink.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Behavioural tests for the reuse policy. These need no SMB server: the cache is generic
 * over the resource, so a counter stands in for a session.
 */
class SessionReuseCacheTest {

    /** Stands in for an authenticated SMB session. */
    private class FakeSession(val ordinal: Int) {
        var closed = false
    }

    private val connects = AtomicInteger(0)
    private val closes = AtomicInteger(0)

    private fun newCache(
        maxIdle: Int = SessionReuseCache.DEFAULT_MAX_IDLE,
        idleTimeoutMillis: Long = SessionReuseCache.DEFAULT_IDLE_TIMEOUT_MILLIS,
        clock: () -> Long = { 0L },
    ) = SessionReuseCache<String, FakeSession>(
        maxIdle = maxIdle,
        idleTimeoutMillis = idleTimeoutMillis,
        now = clock,
        closeResource = { session ->
            session.closed = true
            closes.incrementAndGet()
        },
    )

    private fun connect() = SessionReuseCache.Connector {
        FakeSession(connects.incrementAndGet())
    }

    @Test
    fun `a released session is reused instead of redialling`() {
        val cache = newCache()
        val first = cache.acquire("nas", connect())
        val firstSession = first.resource
        first.close()

        val second = cache.acquire("nas", connect())

        assertSame("expected the cached session back", firstSession, second.resource)
        assertTrue("second lease should be a cached one", second.cached)
        assertEquals("no second handshake should have happened", 1, connects.get())
        assertEquals("nothing should be closed yet", 0, closes.get())
    }

    @Test
    fun `a leased session is never handed out twice`() {
        val cache = newCache()
        val held = cache.acquire("nas", connect())
        val borrowed = held.resource

        val second = cache.acquire("nas", connect())

        assertFalse("pool was saturated, so this lease must be private", second.cached)
        assertEquals("the busy session must not be reissued", 2, connects.get())
        // The important part: two borrowers hold two distinct sessions.
        assertTrue(borrowed !== second.resource)
    }

    @Test
    fun `a private lease closes its own session and leaves the cache untouched`() {
        val cache = newCache()
        // Hold the pooled session so the next caller is forced to go private.
        val busy = cache.acquire("nas", connect())
        val crowded = cache.acquire("nas", connect())
        assertFalse(crowded.cached)

        crowded.close()

        assertTrue("a private lease owns its session and must close it", crowded.resource.closed)
        assertEquals("only the private lease should be closed", 1, closes.get())
        assertEquals("the pooled session must survive", 1, cache.size)
        assertFalse(busy.resource.closed)
    }

    @Test
    fun `a broken session is discarded rather than returned`() {
        val cache = newCache()
        val first = cache.acquire("nas", connect())
        val poisoned = first.resource
        first.broken = true
        first.close()

        assertTrue("a poisoned session must be closed, not pooled", poisoned.closed)
        assertEquals("and removed from the cache", 0, cache.size)

        val second = cache.acquire("nas", connect())
        assertTrue("the next caller must get a fresh session", second.resource !== poisoned)
    }

    @Test
    fun `an idle session past the timeout is dropped and redialled`() {
        var now = 0L
        val cache = newCache(idleTimeoutMillis = 1_000L, clock = { now })
        val first = cache.acquire("nas", connect())
        val stale = first.resource
        first.close()

        now = 5_000L
        val second = cache.acquire("nas", connect())

        assertTrue("a server may have dropped it while idle", stale.closed)
        assertTrue(second.resource !== stale)
        assertEquals(2, connects.get())
    }

    @Test
    fun `a session still inside the timeout is kept`() {
        var now = 0L
        val cache = newCache(idleTimeoutMillis = 10_000L, clock = { now })
        val first = cache.acquire("nas", connect())
        val kept = first.resource
        first.close()

        now = 9_000L
        val second = cache.acquire("nas", connect())

        assertSame(kept, second.resource)
        assertFalse(kept.closed)
    }

    @Test
    fun `the idle set is capped least-recently-used first`() {
        var now = 0L
        val cache = newCache(maxIdle = 2, clock = { now })
        val a = cache.acquire("a", connect()); val aSession = a.resource; a.close()
        val b = cache.acquire("b", connect()); val bSession = b.resource; b.close()
        assertEquals(2, cache.size)

        // Touch "a" so "b" becomes the least recently used, then overflow the cap.
        now = 10L
        val aAgain = cache.acquire("a", connect()); assertSame(aSession, aAgain.resource); aAgain.close()
        now = 20L
        val c = cache.acquire("c", connect()); val cSession = c.resource; c.close()

        assertEquals("cap must hold", 2, cache.size)
        assertTrue("the oldest idle session should be evicted", bSession.closed)
        assertFalse("a recently used session must survive", aSession.closed)
        assertFalse(cSession.closed)
    }

    @Test
    fun `leased entries are never evicted by the sweep`() {
        var now = 0L
        val cache = newCache(maxIdle = 1, clock = { now })
        val busy = cache.acquire("busy", connect())
        cache.acquire("cold1", connect()).close()
        cache.acquire("cold2", connect()).close()

        assertEquals("only idle entries are evictable", 1, cache.size)
        assertFalse("a session in use must never be closed", busy.resource.closed)
    }

    @Test
    fun `different keys never share a session`() {
        val cache = newCache()
        val a = cache.acquire("nas-a", connect())
        val b = cache.acquire("nas-b", connect())

        assertTrue("credentials differ, so sessions must differ", a.resource !== b.resource)
        assertEquals(2, cache.size)
    }

    @Test
    fun `clear closes everything including leased sessions`() {
        val cache = newCache()
        val leased = cache.acquire("nas", connect())
        cache.acquire("other", connect()).close()

        cache.clear()

        assertEquals(0, cache.size)
        assertTrue(leased.resource.closed)
    }

    @Test
    fun `closing a lease twice returns the session only once`() {
        val cache = newCache()
        val lease = cache.acquire("nas", connect())
        val session = lease.resource

        lease.close()
        lease.close()

        assertEquals("a double close must not evict or double-count", 1, cache.size)
        assertFalse(session.closed)
    }

    @Test
    fun `concurrent acquisitions each get a usable session and none is shared`() {
        val cache = newCache(maxIdle = 1)
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val results = java.util.Collections.synchronizedList(mutableListOf<SessionReuseCache.Lease<FakeSession>>())
        try {
            repeat(threads) {
                pool.submit {
                    start.await()
                    results += cache.acquire("nas", connect())
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue("workers should finish promptly", done.await(20, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals(threads, results.size)
        // The real invariant: no session was ever leased to two threads at once.
        assertEquals(
            "a session must never be leased to two threads at once",
            threads,
            results.map { it.resource }.distinct().size,
        )
        assertNotNull(results.firstOrNull())

        // On release a private lease must tear its session down, while a pooled one must
        // hand it back untouched. Neither may be left dangling in a half-open state.
        results.forEach { it.close() }
        results.forEach { lease ->
            if (lease.cached) {
                assertFalse("a pooled session must be returned, not closed", lease.resource.closed)
            } else {
                assertTrue("a private session must be closed on release", lease.resource.closed)
            }
        }
        assertEquals(
            "exactly the private sessions should have been closed",
            results.count { !it.cached },
            closes.get(),
        )
    }

    @Test
    fun `a failing connect propagates and leaves no entry behind`() {
        val cache = newCache()
        val boom = IllegalStateException("server refused")

        val error = runCatching {
            cache.acquire("nas", connect = { throw boom })
        }.exceptionOrNull()

        assertSame(boom, error)
        assertEquals("a failed handshake must not poison the cache", 0, cache.size)
    }

    @Test
    fun `a caller waits for a busy session instead of dialling a second one`() {
        // The behaviour this pins is what keeps a photo grid from opening one SMB session
        // per thumbnail: the second reader must reuse the connection the first is about to
        // return, not start its own handshake.
        val cache = newCache()
        val held = cache.acquire("nas", connect())
        val executor = Executors.newSingleThreadExecutor()
        try {
            val waiting = executor.submit(
                java.util.concurrent.Callable { cache.acquire("nas", connect(), waitForMillis = 5_000) },
            )
            Thread.sleep(150)
            held.close()

            val second = waiting.get(5, TimeUnit.SECONDS)
            assertSame("the released session should be lent to the waiter", held.resource, second.resource)
            assertTrue("the waiter must hold a cached lease", second.cached)
            assertEquals("no handshake should have happened while waiting", 1, connects.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `a caller dials privately once the wait budget expires`() {
        // A long transfer must not starve the rest of the app: after the budget, a private
        // session is the correct answer even though it costs a handshake.
        val cache = newCache()
        val held = cache.acquire("nas", connect())
        val startedNanos = System.nanoTime()

        val second = cache.acquire("nas", connect(), waitForMillis = 200)

        val waitedMillis = (System.nanoTime() - startedNanos) / 1_000_000
        assertFalse(second.cached)
        assertEquals(2, connects.get())
        assertTrue("should have waited roughly the budget, waited ${waitedMillis}ms", waitedMillis >= 150)
        held.close()
        second.close()
    }

    @Test
    fun `a zero budget does not wait at all`() {
        val cache = newCache()
        val held = cache.acquire("nas", connect())
        val startedNanos = System.nanoTime()

        val second = cache.acquire("nas", connect(), waitForMillis = 0)

        val waitedMillis = (System.nanoTime() - startedNanos) / 1_000_000
        assertFalse(second.cached)
        assertTrue("a zero budget must not block, waited ${waitedMillis}ms", waitedMillis < 100)
        held.close()
        second.close()
    }
}
