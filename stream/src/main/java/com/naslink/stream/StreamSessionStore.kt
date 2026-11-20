package com.naslink.stream

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps an opaque token to one streamable file.
 *
 * The token is the only thing that ever appears in a URL handed to a player, so a
 * remote path, host, share or credential can never leak through an intent. Opened
 * sources are kept warm across Range requests and dropped after an idle timeout.
 */
class StreamSessionStore(
    private val idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT_MILLIS,
    private val random: SecureRandom = SecureRandom(),
) {

    /** Opens the backing source. Called at most once per session while a warm copy exists. */
    fun interface SourceFactory {
        fun open(): RandomAccessSource
    }

    class Session internal constructor(
        val token: String,
        val displayName: String,
        val mimeType: String,
        private val factory: SourceFactory,
    ) {
        private val lock = Any()

        @Volatile
        var lastAccessMillis: Long = System.currentTimeMillis()
            private set

        @Volatile
        private var warm: RandomAccessSource? = null

        private var activeTransfers = 0

        private var revoked = false

        /**
         * Opens (or reuses) the source and marks the session busy.
         *
         * @return `null` when the session was revoked while this request was queued.
         */
        internal fun beginTransfer(): RandomAccessSource? = synchronized(lock) {
            if (revoked) return null
            // Count the transfer only once the source is actually in hand. Incrementing first
            // and opening second leaks the count when open() throws, and the session then looks
            // busy forever: sweep() skips it, so the source is never released.
            val source = warm ?: factory.open().also { warm = it }
            activeTransfers++
            touchLocked()
            source
        }

        internal fun endTransfer() = synchronized(lock) {
            if (activeTransfers > 0) activeTransfers--
            touchLocked()
            if (revoked && activeTransfers == 0) {
                releaseLocked()
            }
        }

        internal fun touch() = synchronized(lock) { touchLocked() }

        /**
         * Gives the SMB handle (and its pooled connection) back while keeping the
         * publication alive.
         *
         * Used when a viewer pages away from a picture: the URL stays valid and reopens
         * lazily, but the NAS session is returned to the pool instead of being held for the
         * rest of the slideshow. Without this, every picture viewed during one session kept
         * its own connection — a folder of thirty pictures was thirty inbound sessions,
         * which is exactly what NASes refuse to open.
         */
        internal fun releaseHandle() {
            synchronized(lock) {
                if (activeTransfers > 0 || revoked) return
                warm?.requestReconnect()
            }
        }

        /** Marks the session dead; the resources go away once the last transfer finishes. */
        internal fun invalidate() = synchronized(lock) {
            revoked = true
            if (activeTransfers == 0) releaseLocked()
        }

        internal fun idleFor(nowMillis: Long): Long = nowMillis - lastAccessMillis

        /** True while a request is still being served, which blocks [StreamSessionStore.sweep]. */
        internal fun isBusy(): Boolean = synchronized(lock) { activeTransfers > 0 }

        private fun touchLocked() {
            lastAccessMillis = System.currentTimeMillis()
        }

        private fun releaseLocked() {
            val current = warm ?: return
            warm = null
            runCatching { current.close() }
        }
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    /** Registers a file and returns its opaque 256-bit token. */
    fun publish(displayName: String, mimeType: String, factory: SourceFactory): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        sessions[token] = Session(token, displayName, mimeType, factory)
        return token
    }

    fun resolve(token: String): Session? {
        val session = sessions[token] ?: return null
        session.touch()
        return session
    }

    /**
     * Reads a session without counting as use of it.
     *
     * [resolve] deliberately refreshes the idle clock, which is right for a real request but
     * wrong for a caller merely asking whether a remembered token is still good: that would
     * hold an abandoned publication open forever and defeat the sweep.
     */
    fun lookup(token: String): Session? = sessions[token]

    /** Whether [token] still names a live publication. See [lookup] for why this is separate. */
    fun isPublished(token: String): Boolean = sessions.containsKey(token)

    /** Drops a session and closes its warm source as soon as it is idle. */
    fun revoke(token: String) {
        sessions.remove(token)?.invalidate()
    }

    /**
     * Returns a publication's SMB handle to the pool without dropping the publication.
     *
     * The URL keeps working: the next read reopens the file. Used by the image viewer when
     * the user pages away, so one slideshow does not hold one NAS session per picture.
     */
    fun releaseHandle(token: String) {
        sessions[token]?.releaseHandle()
    }

    /**
     * Closes sessions that have not been touched within the idle timeout.
     *
     * A session with a request in flight is never swept: a player that pauses for a while
     * keeps its own connection open and would otherwise lose the stream on resume.
     */
    fun sweep(nowMillis: Long = System.currentTimeMillis()) {
        val stale = sessions.values.filter { !it.isBusy() && it.idleFor(nowMillis) > idleTimeoutMillis }
        stale.forEach { session ->
            sessions.remove(session.token)?.invalidate()
        }
    }

    fun closeAll() {
        sessions.values.toList().forEach { it.invalidate() }
        sessions.clear()
    }

    val size: Int get() = sessions.size

    private companion object {
        const val TOKEN_BYTES = 32
        // Long enough that pausing a movie for a coffee break never drops the SMB handle.
        const val DEFAULT_IDLE_TIMEOUT_MILLIS = 30 * 60 * 1000L
    }
}
