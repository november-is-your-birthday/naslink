package com.naslink.stream

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Everything needed to open one remote file. */
data class SmbTarget(
    val host: String,
    /** Share name without leading separators, for example `video`. */
    val share: String,
    /** Path inside the share with SMB separators, for example `Movies\a.mkv`. */
    val path: String,
    val username: String = "",
    val password: CharArray = CharArray(0),
    val domain: String? = null,
    val port: Int = DEFAULT_PORT,
    val anonymous: Boolean = false,
    /**
     * SMB3 encryption. Off by default: forcing it breaks against servers that advertise
     * the capability but mishandle it, and it costs CPU on every read. Turn it on only
     * for a NAS that requires encryption.
     */
    val encryptData: Boolean = false,
) {
    companion object {
        const val DEFAULT_PORT = 445
    }
}

/**
 * Random-access reader over SMB2/SMB3.
 *
 * The session, tree connect and file handle are opened lazily on the first read and then
 * kept open, so a player that issues many small Range requests does not pay a full SMB
 * handshake each time. A failed read drops the handle once and retries, which covers idle
 * disconnects without masking real errors.
 *
 * When a [pool] is supplied the authenticated session is borrowed rather than dialled,
 * so several streams against the same NAS share one inbound connection. Without a pool
 * each source owns a private client/connection pair.
 */
class SmbRandomAccessSource(
    private val target: SmbTarget,
    private val readTimeoutMillis: Long = SmbSessionPool.DEFAULT_READ_TIMEOUT_MILLIS,
    private val soTimeoutMillis: Long = SmbSessionPool.DEFAULT_SO_TIMEOUT_MILLIS,
    private val pool: SmbSessionPool? = null,
) : RandomAccessSource {

    /** A private connection owned outright by this source, used when there is no pool. */
    private class OwnedSession(
        val client: SMBClient,
        val connection: Connection,
        val session: Session,
    )

    /**
     * @param owned non-null when this source dialled its own connection, because there was
     *   no pool or the pool was saturated. Closing a pooled lease returns the session for
     *   reuse; closing a private one tears it down. Either way [Lease.close] is required.
     */
    private class Handle(
        val owned: OwnedSession?,
        val session: Session,
        val lease: SmbSessionLease?,
        val share: DiskShare,
        val file: SmbFile,
        val size: Long,
    )

    private val lock = Any()
    private val closed = AtomicBoolean(false)

    @Volatile
    private var handle: Handle? = null

    /** `System.nanoTime()` of the last open or successful read; 0 before the first open. */
    @Volatile
    private var lastUsedNanos: Long = 0L

    /** Reads in flight, so an idle release can never close a handle someone is using. */
    private val activeReads = AtomicInteger(0)

    override val size: Long
        get() {
            releaseIfIdle()
            return ensureHandle().size
        }

    override fun readAt(offset: Long, buffer: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        // Done before the read counter goes up, because a release must only happen while
        // nothing is reading. Between requests a handle that has been idle is pure cost:
        // an open file, a tree connect and a leased session, one per published URL.
        releaseIfIdle()
        activeReads.incrementAndGet()
        try {
            var attempt = 0
            while (true) {
                val current = ensureHandle()
                if (offset >= current.size) return 0
                val want = minOf(len.toLong(), current.size - offset).toInt()
                val read = try {
                    current.file.read(buffer, offset, off, want)
                } catch (error: Exception) {
                    // Any I/O failure here is a socket-level failure, not a per-file one: the
                    // session behind it is shared, so re-borrowing it would fail again after
                    // another full timeout. Discard it and let the retry dial a fresh one.
                    current.lease?.broken = true
                    if (closed.get() || attempt >= MAX_RECONNECT_ATTEMPTS) {
                        throw asIoException(error)
                    }
                    attempt++
                    resetHandle()
                    continue
                }
                if (read > 0) lastUsedNanos = System.nanoTime()
                return if (read <= 0) 0 else read
            }
        } finally {
            activeReads.decrementAndGet()
        }
    }

    override fun requestReconnect() {
        if (!closed.get()) resetHandle()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) { releaseHandle() }
    }

    private fun resetHandle() {
        synchronized(lock) { releaseHandle() }
    }

    /** Must be called with [lock] held. */
    private fun releaseHandle() {
        val current = handle ?: return
        handle = null
        runCatching { current.file.close() }
        runCatching { current.share.close() }
        runCatching { current.lease?.close() }
        // Closing the lease returns the session to the pool; only a self-owned connection
        // has to be torn down here.
        current.owned?.let { owned ->
            runCatching { owned.session.close() }
            runCatching { owned.connection.close() }
            runCatching { owned.client.close() }
        }
    }

    private fun ensureHandle(): Handle {
        handle?.let { return it }
        if (closed.get()) throw IOException("NasLink stream source is closed")
        return synchronized(lock) {
            handle ?: openHandle().also {
                handle = it
                lastUsedNanos = System.nanoTime()
            }
        }
    }

    /**
     * Gives the handle back once it has been unused for [HANDLE_IDLE_RELEASE_NANOS].
     *
     * Called at the start of every operation rather than from a timer: there is no sweep
     * thread to leak, and a source that is being read never qualifies. Skipped while a read
     * is in flight, so a slow chunk cannot have its handle closed underneath it.
     */
    private fun releaseIfIdle() {
        val current = handle ?: return
        if (activeReads.get() != 0) return
        if (!isHandleIdle(lastUsedNanos, System.nanoTime(), HANDLE_IDLE_RELEASE_NANOS)) return
        synchronized(lock) {
            if (handle === current && activeReads.get() == 0) releaseHandle()
        }
    }

    private fun openHandle(): Handle {
        val lease = pool?.acquire(SmbSessionPool.identityOf(target), target.password)
        var owned: OwnedSession? = null
        try {
            val session = lease?.resource?.session ?: connectPrivately().also { owned = it }.session
            val share = session.connectShare(target.share) as DiskShare
            val file = share.openFile(
                target.path,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
            val size = share.getFileInformation(target.path).standardInformation.endOfFile
            return Handle(
                owned = owned,
                session = session,
                lease = lease,
                share = share,
                file = file,
                size = size,
            )
        } catch (error: Throwable) {
            // Returning the lease with broken=true stops the pool from handing a session
            // back out after a failure that may have poisoned it.
            runCatching { lease?.broken = true }
            runCatching { lease?.close() }
            owned?.let {
                runCatching { it.session.close() }
                runCatching { it.connection.close() }
                runCatching { it.client.close() }
            }
            throw asIoException(error)
        }
    }

    /** The un-pooled path: this source owns its client and connection outright. */
    private fun connectPrivately(): OwnedSession {
        val config = SmbConfig.builder()
            .withTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
            .withSoTimeout(soTimeoutMillis, TimeUnit.MILLISECONDS)
            .withEncryptData(target.encryptData)
            .withBufferSize(SmbSessionPool.BUFFER_SIZE_BYTES)
            .build()
        val client = SMBClient(config)
        var connection: Connection? = null
        try {
            connection = client.connect(target.host, target.port)
            val auth = if (target.anonymous) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(target.username, target.password, target.domain ?: "")
            }
            return OwnedSession(client, connection, connection.authenticate(auth))
        } catch (error: Throwable) {
            runCatching { connection?.close() }
            runCatching { client.close() }
            throw asIoException(error)
        }
    }

    private fun asIoException(error: Throwable): IOException =
        error as? IOException ?: IOException(error)

    private companion object {
        const val MAX_RECONNECT_ATTEMPTS = 1
    }
}

/**
 * Whether a source's open handle has been idle long enough to give it back.
 *
 * Pure so it can be tested: the sign of this subtraction is the difference between
 * "release after ten seconds" and "release on every read" (or never).
 */
internal fun isHandleIdle(lastUsedNanos: Long, nowNanos: Long, timeoutNanos: Long): Boolean =
    lastUsedNanos != 0L && nowNanos - lastUsedNanos > timeoutNanos

/**
 * How long a handle may sit unused before its connection is returned to the pool.
 *
 * Long enough that sequential range requests during playback reuse one handle, short
 * enough that browsing a folder cannot accumulate one connection per preview.
 */
internal const val HANDLE_IDLE_RELEASE_NANOS = 10_000_000_000L