package com.naslink.stream

import com.hierynomus.security.SecurityProvider
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.security.jce.JceSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One dialled SMB connection, held as a unit.
 *
 * The cache stores *this* rather than a bare [Session] on purpose. `Session.close()`
 * only issues `logoff()` — it never touches the [Connection] or the [SMBClient] that
 * were created to obtain it, so caching the session alone left the socket open for the
 * life of the process. That defeats the entire point of the pool: its own KDoc says
 * servers cap inbound sessions near twenty, and leaking one per miss exhausts that
 * budget faster than legitimate sharing ever would.
 */
class PooledConnection(
    val session: Session,
    private val connection: Connection,
    private val client: SMBClient,
) {
    /**
     * Closes the whole chain, innermost first: logging off releases the server-side
     * session, closing the connection drops the socket, closing the client releases its
     * resources. Each step is independent so one failure cannot strand the others.
     */
    fun closeAll() {
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }
}

/**
 * Shares authenticated SMB sessions across streams instead of dialling a fresh connection
 * for every file.
 *
 * Why it matters: Windows and most NASes cap inbound sessions at roughly 20. Browsing a
 * folder, then starting playback, then downloading a file burns three of those slots on its
 * own, and misbehaving servers start refusing connections well before the documented limit.
 *
 * This is the smbj-facing adapter over [SessionReuseCache]; the reuse rules live there so
 * they can be tested without a server. What this class adds is the SMB-specific binding:
 *
 * - A session is bound to the credentials it authenticated with, so reuse is keyed on
 *   [Identity]. The password is deliberately **not** part of [Identity]: it is passed per
 *   acquisition and used only for a handshake that actually happens, which keeps secrets out
 *   of the cache's keys and out of any log that prints an identity.
 * - SMB3 encryption is negotiated per connection, so [Identity.encryptData] is part of the
 *   key too — a session negotiated without encryption cannot stand in for one that did.
 *
 * @see SessionReuseCache for the eviction and contention rules.
 */
class SmbSessionPool(
    maxIdleSessions: Int = SessionReuseCache.DEFAULT_MAX_IDLE,
    idleTimeoutMillis: Long = SessionReuseCache.DEFAULT_IDLE_TIMEOUT_MILLIS,
    private val configFactory: (Identity) -> SmbConfig = { defaultConfig(it.encryptData) },
    now: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Everything that must match for a cached session to be reusable.
     */
    data class Identity(
        val host: String,
        val port: Int,
        val username: String,
        val domain: String?,
        val anonymous: Boolean,
        val encryptData: Boolean = false,
    ) {
        /** Keeps the username out of logs and crash reports. */
        override fun toString(): String =
            "Identity(host=$host, port=$port, user=${username.ifEmpty { "-" }}, " +
                "domain=${domain.orEmpty()}, anonymous=$anonymous, encrypt=$encryptData)"
    }

    private val cache = SessionReuseCache<Identity, PooledConnection>(
        maxIdle = maxIdleSessions,
        idleTimeoutMillis = idleTimeoutMillis,
        now = now,
        closeResource = { it.closeAll() },
    )
    /**
     * Borrows a session for [identity], connecting only when no reusable live one exists.
     *
     * Waits [SessionReuseCache.DEFAULT_WAIT_MILLIS] for a busy session before dialling a
     * private one. Without that wait, two overlapping thumbnail reads — the normal state
     * while a grid scrolls — meant one of them always dialled its own connection, and a
     * folder of a few hundred pictures turned into a few hundred logons. Consumer NASes and
     * Windows answer that with slower and slower session setups, then with refusals, which
     * is the "starts fast, ends at a few KB/s" failure mode.
     *
     * @param password used solely for the handshake; never retained.
     * @throws IOException when the server refuses or the handshake fails.
     */
    @Throws(IOException::class)
    fun acquire(
        identity: Identity,
        password: CharArray = CharArray(0),
        waitForMillis: Long = SessionReuseCache.DEFAULT_WAIT_MILLIS,
    ): SmbSessionLease =
        cache.acquire(
            key = identity,
            waitForMillis = waitForMillis,
            connect = {
                val client = SMBClient(configFactory(identity))
                var connection: Connection? = null
                try {
                    connection = client.connect(identity.host, identity.port)
                    val auth = if (identity.anonymous) {
                        AuthenticationContext.anonymous()
                    } else {
                        AuthenticationContext(identity.username, password, identity.domain ?: "")
                    }
                    val session = connection.authenticate(auth)
                    PooledConnection(session, connection, client)
                } catch (error: Throwable) {
                    runCatching { connection?.close() }
                    runCatching { client.close() }
                    throw asIoException(error)
                }
            },
        )

    /** Drops everything, including sessions still leased out. Call on sign-out/disconnect. */
    fun clear() = cache.clear()

    /** Number of sessions currently held for reuse. */
    val size: Int get() = cache.size

    private fun asIoException(error: Throwable): IOException =
        error as? IOException ?: IOException(error)

    companion object {
        fun identityOf(target: SmbTarget): Identity = Identity(
            host = target.host,
            port = target.port,
            username = target.username,
            domain = target.domain,
            anonymous = target.anonymous,
            encryptData = target.encryptData,
        )

        /**
         * Mirrors [SmbRandomAccessSource]'s private connection settings. Both paths must
         * agree, otherwise behaviour would depend on whether the pool happened to hit.
         *
         * The security provider is chosen by [securityProviderFor]. Read the caveats there
         * before changing this.
         */
        fun defaultConfig(encryptData: Boolean): SmbConfig = SmbConfig.builder()
            .withTimeout(DEFAULT_READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .withSoTimeout(DEFAULT_SO_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .withEncryptData(encryptData)
            .withSecurityProvider(securityProviderFor(encryptData))
            .withBufferSize(BUFFER_SIZE_BYTES)
            .build()

        /**
         * Picks the crypto backend.
         *
         * smbj defaults to a pure-Java Bouncy Castle bundled inside the jar. On Android that
         * means every algorithm runs in an interpreter-tier loop, and SMB3 signs *every*
         * message, so the cost lands on exactly the hot path a video stream spends its time
         * in. `JceSecurityProvider` routes the same algorithms through the platform provider,
         * which is hardware-accelerated. Measured on a desktop JVM, HMAC-SHA256 signing ran
         * 3x faster at 64 B and 7x at 1 KiB; the Android gap is larger, not smaller.
         *
         * It is used **only when encryption is off**, because the platform provider does not
         * implement `AES/CCM/NoPadding` — verified against both providers on JDK 17, under
         * the plain cipher accessor *and* the AEAD one — and CCM is the cipher SMB3
         * encryption negotiates by default. Turning encryption on therefore keeps the
         * bundled backend, which supports CCM, instead of failing the connection. A server
         * that demands encryption still works; it just does not get the speedup.
         *
         * Both backends cover everything else smbj asks for (`MD5`, `SHA256`, `HmacSHA256`,
         * `HmacMD5`, `DES/ECB/NoPadding`, `AES/GCM/NoPadding`, `AES/CCM/NoPadding` via the
         * bundled backend's AEAD interface, and `KDF/Counter/HMACSHA256`).
         */
        fun securityProviderFor(encryptData: Boolean): SecurityProvider =
            if (encryptData) BCSecurityProvider() else JceSecurityProvider()

        const val DEFAULT_READ_TIMEOUT_MILLIS = 60_000L

        /**
         * Socket read timeout.
         *
         * Used to be 180 s, which turned a dropped connection into a three-minute stall:
         * the loopback server has a fixed worker pool, and eight requests parked on dead
         * sockets were enough to wedge every later image and video behind them — the user
         * sees "a few KB/s, then source error". Twenty seconds is still far longer than any
         * healthy read of a 1 MiB chunk, and a failure now marks the pooled connection
         * broken so the retry dials a fresh one instead of queueing behind the corpse.
         */
        const val DEFAULT_SO_TIMEOUT_MILLIS = 20_000L

        /**
         * Matches smbj's own default, so a 1 MiB Range request is not split needlessly.
         * One constant for both the pooled and the private path: if they drifted,
         * throughput would depend on whether the pool happened to hit.
         */
        const val BUFFER_SIZE_BYTES = 1024 * 1024
    }
}

/** A borrowed SMB connection; closing its [SessionReuseCache.Lease] returns or discards it. */
typealias SmbSessionLease = SessionReuseCache.Lease<PooledConnection>
