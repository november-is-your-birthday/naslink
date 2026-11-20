package com.naslink.stream

/**
 * A seekable byte source backing the loopback HTTP server.
 *
 * Implementations must tolerate repeated [readAt] calls at arbitrary offsets:
 * players jump to the tail of a file to read the MP4 `moov` box, then jump back.
 * The file handle behind this source is expected to stay open across those jumps.
 */
interface RandomAccessSource : AutoCloseable {

    /** Total length in bytes, or `-1` when unknown before the first successful read. */
    val size: Long

    /**
     * Reads up to [len] bytes from [offset] into [buffer] starting at index [off].
     *
     * @return the number of bytes read, or `0` at end of file or when [offset] is past [size].
     */
    fun readAt(offset: Long, buffer: ByteArray, off: Int, len: Int): Int

    /** Drops any cached handle so the next read reconnects. No-op for local sources. */
    fun requestReconnect() = Unit
}
