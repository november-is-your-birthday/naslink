package com.naslink.stream

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A published file together with the URL a player should open. */
data class StreamUrl(
    val token: String,
    val url: String,
    val mimeType: String,
)

/**
 * Minimal HTTP server bound to `127.0.0.1` that turns a [RandomAccessSource] into a
 * seekable URL any player can consume.
 *
 * Why this exists: Android players accept `http://` URIs and know how to issue byte
 * Range requests, but none of them understand SMB. Serving over loopback keeps the
 * SMB session inside this process while still allowing playback to be handed to
 * MX Player, VLC or mpv.
 *
 * Only requests from the loopback interface are served, and the URL carries an opaque
 * token instead of the remote path.
 */
class LoopbackStreamServer(
    private val sessions: StreamSessionStore = StreamSessionStore(),
    private val workerThreads: Int = DEFAULT_WORKER_THREADS,
    /** Optional sink for diagnostics; the server must never log on its own on Android. */
    private val logger: ((String) -> Unit)? = null,
) {

    private val running = AtomicBoolean(false)

    @Volatile
    private var serverSocket: ServerSocket? = null

    private var acceptThread: Thread? = null
    private var sweepThread: Thread? = null
    private var executor: ExecutorService? = null

    /** Local port once [start] has returned, otherwise `-1`. */
    val port: Int get() = serverSocket?.localPort ?: -1

    /**
     * Binds to a random free loopback port.
     *
     * @return the chosen port.
     */
    @Synchronized
    @Throws(IOException::class)
    fun start(): Int {
        if (running.get()) return port
        // A failed bind must leave the server startable again, so the flag is only set
        // once the socket actually exists.
        val socket = try {
            ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK_ADDRESS))
        } catch (error: IOException) {
            logger?.invoke("NasLink stream server failed to bind: ${error.message}")
            throw error
        }
        running.set(true)
        serverSocket = socket
        val pool = Executors.newFixedThreadPool(workerThreads) { runnable ->
            Thread(runnable, "naslink-stream-${STREAM_THREAD_IDS.incrementAndGet()}").apply { isDaemon = true }
        }
        executor = pool
        acceptThread = Thread({
            while (running.get()) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    // Normal when [stop] closes the listening socket.
                    if (running.get()) continue
                    break
                }
                try {
                    pool.execute { serve(client) }
                } catch (error: Throwable) {
                    logger?.invoke("NasLink stream server rejected a client: ${error.message}")
                    runCatching { client.close() }
                }
            }
        }, "naslink-stream-accept").apply { isDaemon = true; start() }

        sweepThread = Thread({
            while (running.get()) {
                try {
                    Thread.sleep(SWEEP_INTERVAL_MILLIS)
                    sessions.sweep()
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "naslink-stream-sweep").apply { isDaemon = true; start() }

        return socket.localPort
    }

    @Synchronized
    fun stop() {
        if (running.compareAndSet(true, false).not()) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        executor?.shutdownNow()
        executor = null
        acceptThread?.interrupt()
        acceptThread = null
        sweepThread?.interrupt()
        sweepThread = null
        sessions.closeAll()
    }

    /**
     * Publishes a file and returns the URL to hand to a player.
     *
     * @param displayName file name used in the URL path; it is cosmetic and optional.
     */
    @Throws(IllegalStateException::class)
    fun publish(
        displayName: String,
        mimeType: String,
        factory: StreamSessionStore.SourceFactory,
    ): StreamUrl {
        val currentPort = port
        check(currentPort > 0) { "NasLink stream server is not running" }
        val token = sessions.publish(displayName, mimeType, factory)
        val safeName = encodePathSegment(displayName).ifBlank { "stream" }
        val url = "http://$LOOPBACK_ADDRESS:$currentPort/s/$token/$safeName"
        return StreamUrl(token, url, mimeType)
    }

    fun revoke(token: String) = sessions.revoke(token)

    /** Returns a publication's read handle to the pool; the token keeps working. */
    fun releaseHandle(token: String) = sessions.releaseHandle(token)

    /**
     * Whether [token] still names a live publication.
     *
     * Distinct from resolving it: probing existence must not count as using the session, or a
     * caller merely checking a remembered token would keep an otherwise-idle publication from
     * ever being swept.
     */
    fun isPublished(token: String): Boolean = sessions.isPublished(token)

    /** The URL for a still-live [token], or null once the session is gone. */
    fun urlFor(token: String): String? {
        val session = sessions.lookup(token) ?: return null
        val currentPort = port
        if (currentPort <= 0) return null
        val safeName = encodePathSegment(session.displayName).ifBlank { "stream" }
        return "http://$LOOPBACK_ADDRESS:$currentPort/s/$token/$safeName"
    }

    private fun serve(client: Socket) {
        var output: OutputStream? = null
        var responded = false
        try {
            client.use { socket ->
                val stream = socket.getOutputStream()
                output = stream
                if (socket.inetAddress?.isLoopbackAddress != true) {
                    writeHead(stream, STATUS_FORBIDDEN, 0, null, null)
                    responded = true
                    return
                }
                socket.soTimeout = CLIENT_SOCKET_TIMEOUT_MILLIS
                // Headers are tiny but read one byte at a time, so they need a buffer or
                // every request pays a syscall per character.
                val input = BufferedInputStream(socket.getInputStream(), HEADER_BUFFER_BYTES)
                val requestLine = readLine(input) ?: return
                val parts = requestLine.trim().split(WHITESPACE).filter { it.isNotEmpty() }
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val rawPath = parts[1]
                val headers = readHeaders(input)
                if (method != "GET" && method != "HEAD") {
                    writeHead(stream, STATUS_METHOD_NOT_ALLOWED, 0, null, null)
                    responded = true
                    return
                }
                val token = extractToken(rawPath)
                val session = token?.let { sessions.resolve(it) }
                if (session == null) {
                    writeHead(stream, STATUS_NOT_FOUND, 0, null, null)
                    responded = true
                    return
                }
                handleRange(session, method, headers["range"], stream) { responded = true }
            }
        } catch (error: Throwable) {
            // A player that seeks or closes early simply drops the connection; that is not
            // an error worth reporting. Anything else must still leave the client with a
            // response, because a socket closed with no bytes at all surfaces as
            // "unexpected end of stream" and hides the real cause from the user entirely.
            logger?.invoke("NasLink stream request failed: ${error::class.java.simpleName}: ${error.message}")
            if (!responded) {
                runCatching {
                    writeHead(
                        output ?: return@runCatching,
                        STATUS_BAD_GATEWAY,
                        0,
                        "application/octet-stream",
                        null,
                    )
                }
            }
        } finally {
            runCatching { client.close() }
        }
    }

    private fun handleRange(
        session: StreamSessionStore.Session,
        method: String,
        rangeHeader: String?,
        output: OutputStream,
        onResponded: () -> Unit = {},
    ) {
        val source = try {
            session.beginTransfer()
        } catch (error: Throwable) {
            logger?.invoke("NasLink stream source could not be opened: ${error.message}")
            writeHead(output, STATUS_BAD_GATEWAY, 0, null, null)
            onResponded()
            return
        }
        if (source == null) {
            writeHead(output, STATUS_NOT_FOUND, 0, null, null)
            onResponded()
            return
        }
        var committed = false
        try {
            val total = source.size
            if (total < 0) {
                writeHead(output, STATUS_BAD_GATEWAY, 0, null, null)
                onResponded()
                return
            }
            val range = Range.parse(rangeHeader, total)
            if (range == null) {
                // Unsatisfiable or malformed range: tell the client the truth about the size.
                writeHead(
                    output,
                    STATUS_RANGE_NOT_SATISFIABLE,
                    0,
                    session.mimeType,
                    "bytes */$total",
                )
                onResponded()
                return
            }
            val length = range.endExclusive - range.start
            if (range.isPartial) {
                writeHead(
                    output,
                    STATUS_PARTIAL_CONTENT,
                    length,
                    session.mimeType,
                    "bytes ${range.start}-${range.endExclusive - 1}/$total",
                )
            } else {
                writeHead(output, STATUS_OK, length, session.mimeType, null)
            }
            committed = true
            onResponded()
            if (method == "HEAD") return

            val buffer = ByteArray(TRANSFER_CHUNK_BYTES)
            var offset = range.start
            var remaining = length
            while (remaining > 0) {
                val want = minOf(remaining, buffer.size.toLong()).toInt()
                val read = source.readAt(offset, buffer, 0, want)
                // 0 means "no progress"; keep the promise made by Content-Length honest by
                // failing loudly instead of sending a silently truncated body.
                if (read <= 0) {
                    throw IOException("NasLink stream source ended $remaining bytes early")
                }
                output.write(buffer, 0, read)
                offset += read
                remaining -= read
            }
            output.flush()
        } catch (error: Throwable) {
            // Anything the source can throw, not just IOException: an SMB library raises its
            // own exception types, and letting those escape would close the socket with no
            // response at all.
            logger?.invoke(
                "NasLink stream transfer interrupted: ${error::class.java.simpleName}: ${error.message}",
            )
            // Before the status line goes out there is still something useful to say, and the
            // client needs to hear it: swallowing the error here would close the socket with
            // zero bytes, which surfaces as "unexpected end of file" and hides the cause.
            // Afterwards the status line is already spent, so log and drop the connection.
            if (!committed) {
                runCatching { writeHead(output, STATUS_BAD_GATEWAY, 0, null, null) }
                onResponded()
            }
        } finally {
            session.endTransfer()
        }
    }

    /** Reads one CRLF or LF terminated line, decoding bytes as ISO-8859-1. */
    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (builder.isEmpty()) null else builder.toString()
            if (byte == '\n'.code) return builder.trim().toString()
            if (byte != '\r'.code) builder.append(byte.toChar())
        }
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
        }
        return headers
    }

    private fun extractToken(rawPath: String): String? {
        val path = rawPath.substringBefore('?')
        val segments = path.split('/').filter { it.isNotEmpty() }
        // /s/{token}/{filename}
        if (segments.size < 2) return null
        if (segments[0] != "s") return null
        return segments[1]
    }

    private fun writeHead(
        output: OutputStream,
        status: String,
        contentLength: Long,
        contentType: String?,
        contentRange: String?,
    ) {
        val builder = StringBuilder()
        builder.append("HTTP/1.1 ").append(status).append(CRLF)
        builder.append("Accept-Ranges: bytes").append(CRLF)
        if (contentType != null) builder.append("Content-Type: ").append(contentType).append(CRLF)
        builder.append("Content-Length: ").append(contentLength.coerceAtLeast(0)).append(CRLF)
        if (contentRange != null) builder.append("Content-Range: ").append(contentRange).append(CRLF)
        builder.append("Connection: close").append(CRLF)
        builder.append(CRLF)
        output.write(builder.toString().toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    /** Percent-encodes a path segment the way RFC 3986 expects, not the way form posts do. */
    private fun encodePathSegment(value: String): String {
        val builder = StringBuilder(value.length)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and 0xFF
            val safe = code in 'a'.code..'z'.code ||
                code in 'A'.code..'Z'.code ||
                code in '0'.code..'9'.code ||
                code == '-'.code || code == '.'.code || code == '_'.code || code == '~'.code
            if (safe) {
                builder.append(code.toChar())
            } else {
                builder.append('%')
                builder.append(HEX_DIGITS[code shr 4])
                builder.append(HEX_DIGITS[code and 0x0F])
            }
        }
        return builder.toString()
    }

    /** A resolved byte range, always clamped to the source length. */
    internal data class Range(val start: Long, val endExclusive: Long, val isPartial: Boolean) {
        companion object {
            fun parse(header: String?, total: Long): Range? {
                val full = if (total <= 0) Range(0, 0, false) else Range(0, total, false)
                val value = header?.trim()?.takeIf { it.isNotEmpty() } ?: return full
                if (!value.startsWith("bytes=")) return null
                val spec = value.removePrefix("bytes=")
                // Multi-range responses would need multipart/byteranges; serving the whole
                // file instead is allowed and keeps every player happy.
                if (spec.contains(',')) return full
                val dash = spec.indexOf('-')
                if (dash < 0) return null
                val startText = spec.substring(0, dash).trim()
                val endText = spec.substring(dash + 1).trim()
                if (total <= 0) return null
                return if (startText.isEmpty()) {
                    // Suffix form: bytes=-N means the last N bytes.
                    val suffix = endText.toLongOrNull() ?: return null
                    if (suffix <= 0) return null
                    val start = (total - suffix).coerceAtLeast(0)
                    Range(start, total, true)
                } else {
                    val start = startText.toLongOrNull() ?: return null
                    if (start >= total) return null
                    val endExclusive = if (endText.isEmpty()) total else (endText.toLongOrNull() ?: return null) + 1
                    if (endExclusive <= start) return null
                    Range(start, minOf(endExclusive, total), true)
                }
            }
        }
    }

    // Internal, not private: the transfer tests size their payload against the chunk so a
    // mid-transfer failure test keeps failing *mid*-transfer if the chunk ever changes.
    internal companion object {
        const val LOOPBACK_ADDRESS = "127.0.0.1"
        const val BACKLOG = 32
        const val DEFAULT_WORKER_THREADS = 8
        const val CLIENT_SOCKET_TIMEOUT_MILLIS = 30_000
        const val SWEEP_INTERVAL_MILLIS = 30_000L
        // Matches SmbSessionPool.BUFFER_SIZE_BYTES: every readAt call is one SMB READ
        // round trip, so a 256 KiB chunk paid four times the round trips the negotiated
        // 1 MiB read size allows. One buffer per worker thread; 8 workers × 1 MiB is a
        // bounded, short-lived cost.
        const val TRANSFER_CHUNK_BYTES = 1024 * 1024
        const val HEADER_BUFFER_BYTES = 8 * 1024
        const val STATUS_OK = "200 OK"
        const val STATUS_PARTIAL_CONTENT = "206 Partial Content"
        const val STATUS_RANGE_NOT_SATISFIABLE = "416 Range Not Satisfiable"
        const val STATUS_NOT_FOUND = "404 Not Found"
        const val STATUS_FORBIDDEN = "403 Forbidden"
        const val STATUS_METHOD_NOT_ALLOWED = "405 Method Not Allowed"
        const val STATUS_BAD_GATEWAY = "502 Bad Gateway"
        const val CRLF = "\r\n"
        const val HEX_DIGITS = "0123456789ABCDEF"
        val WHITESPACE = Regex("\\s+")
        val STREAM_THREAD_IDS = AtomicInteger(0)
    }
}
