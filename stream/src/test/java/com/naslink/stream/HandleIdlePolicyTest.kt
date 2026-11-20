package com.naslink.stream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the arithmetic behind giving an idle SMB handle back.
 *
 * Two ways to get this wrong, both of which have been seen in file-cache code before:
 * releasing on every read (which turns a sequential transfer into a handshake per chunk),
 * and never releasing (which pins one NAS connection per published URL). The predicate is
 * isolated here precisely so the sign and the zero case are testable without a server.
 */
class HandleIdlePolicyTest {

    @Test
    fun `a handle used just now is kept`() {
        assertFalse(isHandleIdle(lastUsedNanos = 1_000, nowNanos = 2_000, timeoutNanos = 10_000))
    }

    @Test
    fun `a handle past the timeout is released`() {
        assertTrue(isHandleIdle(lastUsedNanos = 1_000, nowNanos = 12_000, timeoutNanos = 10_000))
    }

    @Test
    fun `the boundary itself is not released`() {
        // Exactly at the timeout the handle is still considered warm; releasing here would
        // make a transfer that pauses for the timeout reopen for its next chunk.
        assertFalse(isHandleIdle(lastUsedNanos = 1_000, nowNanos = 11_000, timeoutNanos = 10_000))
    }

    @Test
    fun `a source that never opened is not idle`() {
        // Encodes "no handle" as lastUsed == 0 rather than as an arbitrary ancient time,
        // so a fresh source cannot be treated as stale on its very first call.
        assertFalse(isHandleIdle(lastUsedNanos = 0, nowNanos = 1_000_000, timeoutNanos = 10_000))
    }

    @Test
    fun `a monotonic clock that wrapped is still handled`() {
        // System.nanoTime() may be negative; only the difference is meaningful.
        val lastUsed = -9_000_000_000_000L
        assertFalse(isHandleIdle(lastUsed, lastUsed + 5_000, 10_000))
        assertTrue(isHandleIdle(lastUsed, lastUsed + 15_000, 10_000))
    }

    @Test
    fun `the configured window is ten seconds`() {
        assertFalse(isHandleIdle(1, 1 + HANDLE_IDLE_RELEASE_NANOS / 2, HANDLE_IDLE_RELEASE_NANOS))
        assertTrue(isHandleIdle(1, 1 + HANDLE_IDLE_RELEASE_NANOS * 2, HANDLE_IDLE_RELEASE_NANOS))
    }
}
