package com.naslink.stream

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * Reuse policy for expensive, identity-bound resources, kept free of any I/O so it can be
 * tested without a server.
 *
 * The rules it enforces, in order of why they matter:
 *
 * 1. **A busy resource is never handed out twice.** A caller that finds every candidate
 *    leased waits briefly for a release, then creates its own. Queueing indefinitely
 *    instead would mean a 30-minute playback stalls a download behind it.
 * 2. **A broken resource is discarded, not returned.** A caller that hits an I/O error on
 *    a borrowed resource flags it; the next caller reconnects rather than inheriting a
 *    half-dead handle.
 * 3. **Anything idle past the timeout is dropped.** Servers close idle connections on their
 *    own schedule, and a cached handle that outlived its server is worse than a new one.
 * 4. **The idle set is capped, least-recently-used first.** This is what keeps a long
 *    browsing session from exhausting a server's inbound connection budget.
 *
 * Eviction runs on release rather than on a timer, so there is no sweeper thread to leak.
 *
 * @param K the key type; anything that decides reusability (host + credentials, session id).
 * @param T the resource type; must be safe to use from multiple threads.
 */
class SessionReuseCache<K : Any, T : Any>(
    private val maxIdle: Int = DEFAULT_MAX_IDLE,
    private val idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT_MILLIS,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val closeResource: (T) -> Unit = {},
) {
    fun interface Connector<T> {
        /** @throws Exception when the resource cannot be created. */
        fun connect(): T
    }

    /**
     * A borrowed resource. Closing it returns it to the cache, or drops it when [broken].
     */
    class Lease<T : Any> internal constructor(
        val resource: T,
        /** False when the resource bypassed the cache and belongs solely to this borrower. */
        val cached: Boolean,
        private val onRelease: (T, Boolean) -> Unit,
    ) : AutoCloseable {
        private val released = AtomicBoolean(false)

        /** Marks the resource unusable so it is discarded instead of reused. */
        @Volatile
        var broken: Boolean = false

        override fun close() {
            if (released.compareAndSet(false, true)) onRelease(resource, broken)
        }
    }

    private class Entry<T : Any>(
        val resource: T,
        @Volatile var lastUsedMillis: Long,
        @Volatile var leased: Boolean = false,
    )

    private val entries = ConcurrentHashMap<K, Entry<T>>()

    /**
     * Signalled whenever a lease is returned, so a caller that found every candidate busy
     * can wait for the release instead of dialling a second connection.
     */
    private val releaseSignal = ReentrantLock()
    private val released = releaseSignal.newCondition()

    /**
     * Returns a lease for [key], reusing a cached resource when possible.
     *
     * [waitForMillis] is the budget for waiting on a *busy* cached resource before dialling
     * a private one. Waiting matters on a NAS: a caller that dials instead pays a full
     * handshake, and a burst of thumbnails that each dialled its own connection is how a
     * browsing session ends up past the server's inbound-session limit — after which every
     * new request is slow or refused until the app is restarted. A short wait lets a second
     * caller reuse the connection the first one is about to return; a long transfer (video
     * playback) still gets a private connection after the budget expires rather than
     * stalling.
     *
     * [connect] is invoked at most once per contention race: whoever loses the race closes
     * nothing on its own but still returns its own resource as a private lease, because a
     * second concurrent user is a legitimate reason to hold an extra connection.
     */
    fun acquire(
        key: K,
        connect: Connector<T>,
        waitForMillis: Long = 0L,
    ): Lease<T> {
        borrowExisting(key)?.let { return it }
        if (waitForMillis > 0) {
            // Monotonic, not [now]: `now` is the injectable idle clock, and idle time is a
            // wall-clock concern. A budget measured on a clock that does not advance (tests,
            // or a system time jump) would wait forever.
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitForMillis)
            while (true) {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) break
                val slice = TimeUnit.NANOSECONDS.toMillis(remainingNanos)
                    .coerceIn(1L, WAIT_SLICE_MILLIS)
                awaitRelease(slice)
                borrowExisting(key)?.let { return it }
            }
        }
        val created = connect.connect()
        val nowMillis = now()
        val entry = Entry(created, nowMillis, leased = true)
        val raced = entries.putIfAbsent(key, entry)
        if (raced == null) return leaseFor(key, entry)
        return privateLease(created)
    }

    private fun awaitRelease(millis: Long) {
        releaseSignal.lock()
        try {
            released.await(millis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            releaseSignal.unlock()
        }
    }

    private fun signalRelease() {
        releaseSignal.lock()
        try {
            released.signalAll()
        } finally {
            releaseSignal.unlock()
        }
    }

    /**
     * Claims a free, non-stale entry for [key], or returns null when the caller should
     * create its own. Never blocks.
     */
    private fun borrowExisting(key: K): Lease<T>? {
        val nowMillis = now()
        // Bounded so a pathological interleaving cannot spin; only a competing acquire can
        // invalidate a candidate, and it settles within a pass or two.
        repeat(MAX_BORROW_ATTEMPTS) {
            val entry = entries[key] ?: return null
            synchronized(entry) {
                if (entry.leased) return null
                if (nowMillis - entry.lastUsedMillis > idleTimeoutMillis) {
                    if (entries.remove(key, entry)) closeQuietly(entry.resource)
                    return@repeat
                }
                entry.leased = true
                entry.lastUsedMillis = nowMillis
                return leaseFor(key, entry)
            }
        }
        return null
    }

    private fun leaseFor(key: K, entry: Entry<T>) = Lease(entry.resource, cached = true) { resource, broken ->
        if (broken) {
            entries.remove(key, entry)
            closeQuietly(resource)
            signalRelease()
            return@Lease
        }
        synchronized(entry) {
            entry.leased = false
            entry.lastUsedMillis = now()
        }
        signalRelease()
        sweepIfNeeded()
    }

    private fun privateLease(resource: T) = Lease(resource, cached = false) { _, _ -> closeQuietly(resource) }

    /** Drops idle entries beyond [maxIdle], least-recently-used first. */
    private fun sweepIfNeeded() {
        if (entries.size <= maxIdle) return
        val idle = entries.entries
            .mapNotNull { (key, entry) ->
                synchronized(entry) { if (entry.leased) null else key to entry }
            }
            .sortedBy { it.second.lastUsedMillis }
        var excess = entries.size - maxIdle
        for ((key, entry) in idle) {
            if (excess <= 0) break
            if (entries.remove(key, entry)) {
                closeQuietly(entry.resource)
                excess--
            }
        }
    }

    /** Drops and closes everything, including entries still leased out. */
    fun clear() {
        val owned = entries.values.toList()
        entries.clear()
        owned.forEach { closeQuietly(it.resource) }
    }

    /** How many entries are held for reuse. Leased entries are included. */
    val size: Int get() = entries.size

    private fun closeQuietly(resource: T) {
        runCatching { closeResource(resource) }
    }

    companion object {
        const val DEFAULT_MAX_IDLE = 6
        const val DEFAULT_IDLE_TIMEOUT_MILLIS = 5 * 60 * 1000L
        private const val MAX_BORROW_ATTEMPTS = 3

        /**
         * How long [acquire] waits for a busy cached resource by default when the caller
         * asks it to wait at all. Shorter than a NAS handshake worth avoiding? No — a
         * handshake against a Windows or consumer NAS routinely takes 100–400 ms, so
         * waiting up to this long is cheaper in wall-clock time *and* leaves the server's
         * session table untouched.
         */
        const val DEFAULT_WAIT_MILLIS = 1_500L

        /** Re-check cadence while waiting; a release may free a different key. */
        private const val WAIT_SLICE_MILLIS = 100L
    }
}
