package com.naslink.stream

import com.naslink.stream.LoopbackStreamServer.Range
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackStreamServerTest {

    private class ByteArraySource(private val data: ByteArray) : RandomAccessSource {
        override val size: Long get() = data.size.toLong()
        override fun readAt(offset: Long, buffer: ByteArray, off: Int, len: Int): Int {
            if (offset >= data.size.toLong()) return 0
            val count = minOf(len, (data.size.toLong() - offset).toInt())
            System.arraycopy(data, offset.toInt(), buffer, off, count)
            return count
        }
        override fun close() = Unit
    }

    /** Declares a size it never delivers, reproducing a remote file that disappears. */
    private class StalledSource(private val declaredSize: Long) : RandomAccessSource {
        override val size: Long get() = declaredSize
        override fun readAt(offset: Long, buffer: ByteArray, off: Int, len: Int): Int = 0
        override fun close() = Unit
    }

    /**
     * Raises a checked exception that is *not* an IOException, the way the SMB library
     * reports a protocol or transport failure.
     */
    private class ProtocolErrorSource(private val error: () -> Throwable) : RandomAccessSource {
        override val size: Long get() = throw error()
        override fun readAt(offset: Long, buffer: ByteArray, off: Int, len: Int): Int = throw error()
        override fun close() = Unit
    }

    /** Fails only once the body is being written, after the status line is committed. */
    private class MidTransferFailure(private val data: ByteArray) : RandomAccessSource {
        override val size: Long get() = data.size.toLong()
        override fun readAt(offset: Long, buffer: ByteArray, off: Int, len: Int): Int {
            if (offset > 0) throw IllegalStateException("smbj: transport closed")
            val count = minOf(len, data.size)
            System.arraycopy(data, 0, buffer, off, count)
            return count
        }
        override fun close() = Unit
    }

    // Larger than one transfer chunk on purpose: the mid-transfer failure test needs the
    // first chunk to come up short so the failure lands after the status line, inside the
    // body. Sized against the server's constant so a chunk-size change cannot silently
    // turn that test into a full, clean transfer.
    private val payload = ByteArray(LoopbackStreamServer.TRANSFER_CHUNK_BYTES + 48_000) { (it % 251).toByte() }

    private fun publish(server: LoopbackStreamServer): StreamUrl =
        server.publish("clip.mkv", "video/x-matroska", StreamSessionStore.SourceFactory { ByteArraySource(payload) })

    private fun open(server: LoopbackStreamServer, url: String, range: String? = null): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        if (range != null) connection.setRequestProperty("Range", range)
        connection.connect()
        return connection
    }

    @Test
    fun headReportsSizeAndRangeSupport() {
        val server = LoopbackStreamServer()
        server.start()
        try {
            val published = publish(server)
            val connection = URL(published.url).openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connect()
            assertEquals(200, connection.responseCode)
            assertEquals(payload.size.toLong(), connection.getHeaderFieldLong("Content-Length", -1))
            assertEquals("bytes", connection.getHeaderField("Accept-Ranges"))
            connection.disconnect()
        } finally {
            server.stop()
        }
    }

    @Test
    fun fullGetReturnsEveryByte() {
        val server = LoopbackStreamServer()
        server.start()
        try {
            val published = publish(server)
            val connection = open(server, published.url)
            assertEquals(200, connection.responseCode)
            assertArrayEquals(payload, connection.inputStream.readBytes())
            connection.disconnect()
        } finally {
            server.stop()
        }
    }

    @Test
    fun rangeRequestReturnsPartialContent() {
        val server = LoopbackStreamServer()
        server.start()
        try {
            val published = publish(server)
            val connection = open(server, published.url, "bytes=1000-1999")
            assertEquals(206, connection.responseCode)
            assertEquals("bytes 1000-1999/${payload.size}", connection.getHeaderField("Content-Range"))
            val body = connection.inputStream.readBytes()
            assertEquals(1000, body.size)
            assertArrayEquals(payload.copyOfRange(1000, 2000), body)
            connection.disconnect()
        } finally {
            server.stop()
        }
    }

    @Test
    fun openEndedRangeIsClampedToSize() {
        val server = LoopbackStreamServer()
        val port = server.start()
        try {
            val published = publish(server)
            assertEquals("http://127.0.0.1:$port/s/${published.token}/clip.mkv", published.url)
            val connection = open(server, published.url, "bytes=${payload.size - 500}-")
            assertEquals(206, connection.responseCode)
            assertEquals(500, connection.inputStream.readBytes().size)
            connection.disconnect()
        } finally {
            server.stop()
        }
    }

    @Test
    fun repeatedRangesReuseOneWarmSource() {
        val server = LoopbackStreamServer()
        server.start()
        try {
            var opens = 0
            val published = server.publish("clip.mkv", "video/x-matroska", StreamSessionStore.SourceFactory {
                opens++
                ByteArraySource(payload)
            })
            repeat(5) { index ->
                val connection = open(server, published.url, "bytes=${index * 100}-${index * 100 + 99}")
                assertEquals(206, connection.responseCode)
                assertEquals(100, connection.inputStream.readBytes().size)
                connection.disconnect()
            }
            assertEquals("warm source must be reused across Range requests", 1, opens)
        } finally {
            server.stop()
        }
    }

    @Test
    fun unknownTokenIsNotFound() {
        val server = LoopbackStreamServer()
        server.start()
        try {
            val connection = URL("http://127.0.0.1:${server.port}/s/nope/clip.mkv").openConnection() as HttpURLConnection
            assertEquals(404, connection.responseCode)
            connection.disconnect()
        } finally {
            server.stop()
        }
    }

    @Test
    fun revokedTokenIsNotFound() {
        val server = LoopbackStreamServer()
        server.start()
        try {
            val published = publish(server)
            server.revoke(published.token)
            val connection = URL(published.url).openConnection() as HttpURLConnection
            assertEquals(404, connection.responseCode)
            connection.disconnect()
        } finally {
            server.stop()
        }
    }

    @Test
    fun rangeBeyondSizeIsNotSatisfiableAndAdvertisesTheRealSize() {
        val server = LoopbackStreamServer()
        server.start()
        try {
            val published = publish(server)
            val connection = URL(published.url).openConnection() as HttpURLConnection
            connection.setRequestProperty("Range", "bytes=${payload.size + 10}-")
            assertEquals(416, connection.responseCode)
            assertEquals("bytes */${payload.size}", connection.getHeaderField("Content-Range"))
            connection.disconnect()
        } finally {
            server.stop()
        }
    }

    @Test
    fun publishBeforeStartIsRejected() {
        val server = LoopbackStreamServer()
        assertTrue(server.port < 0)
        var failed = false
        try {
            publish(server)
        } catch (error: IllegalStateException) {
            failed = true
        }
        assertTrue("publishing without start must fail", failed)
    }

    @Test
    fun startIsIdempotentAndStopAllowsRestart() {
        val server = LoopbackStreamServer()
        try {
            val first = server.start()
            assertEquals(first, server.start())
            assertTrue(first > 0)
            assertEquals(first, server.port)
            server.stop()
            assertTrue(server.port < 0)
            val second = server.start()
            assertTrue("a restart must bind a usable port", second > 0)
        } finally {
            server.stop()
        }
    }

    @Test
    fun emptyFileIsServedAsAnEmptyBody() {
        val server = LoopbackStreamServer()
        server.start()
        try {
            val published = server.publish("empty.mkv", "video/x-matroska", StreamSessionStore.SourceFactory {
                ByteArraySource(ByteArray(0))
            })
            val connection = open(server, published.url)
            assertEquals(200, connection.responseCode)
            assertEquals(0L, connection.getHeaderFieldLong("Content-Length", -1))
            assertEquals(0, connection.inputStream.readBytes().size)
            connection.disconnect()
        } finally {
            server.stop()
        }
    }

    @Test
    fun fileNamesWithSpacesAndNonAsciiStayAsciiInTheUrl() {
        val server = LoopbackStreamServer()
        server.start()
        try {
            val published = server.publish("我的 假期 01.mkv", "video/x-matroska", StreamSessionStore.SourceFactory {
                ByteArraySource(payload)
            })
            assertTrue("the URL must be percent-encoded: ${published.url}", published.url.all { it.code < 128 })
            assertTrue(published.url.contains("%"))
            val connection = open(server, published.url)
            assertEquals(200, connection.responseCode)
            assertEquals(payload.size, connection.inputStream.readBytes().size)
            connection.disconnect()
        } finally {
            server.stop()
        }
    }

    @Test
    fun aSourceThatUnderDeliversDoesNotProduceAFullBody() {
        val server = LoopbackStreamServer()
        server.start()
        try {
            val published = server.publish("broken.mkv", "video/x-matroska", StreamSessionStore.SourceFactory {
                StalledSource(100_000)
            })
            val connection = open(server, published.url)
            assertEquals(200, connection.responseCode)
            val declared = connection.getHeaderFieldLong("Content-Length", -1)
            assertEquals(100_000L, declared)
            val body = connection.inputStream.readBytes()
            assertTrue("a stalled source must never look like a complete body", body.size < declared)
            connection.disconnect()
        } finally {
            server.stop()
        }
    }

    @Test
    fun sweepNeverDropsASessionWithARequestInFlight() {
        val store = StreamSessionStore(idleTimeoutMillis = 1)
        val server = LoopbackStreamServer(sessions = store)
        server.start()
        try {
            val published = publish(server)
            val session = store.resolve(published.token)
            assertNotNull(session)
            val source = session!!.beginTransfer()
            assertNotNull(source)
            try {
                store.sweep(nowMillis = System.currentTimeMillis() + 10_000)
                assertEquals("a busy session must survive the sweep", 1, store.size)
                assertNotNull(store.resolve(published.token))
            } finally {
                session.endTransfer()
            }
            store.sweep(nowMillis = System.currentTimeMillis() + 10_000)
            assertEquals("an idle session must be swept", 0, store.size)
        } finally {
            server.stop()
        }
    }

    @Test
    fun rangeParsingCoversSuffixAndOpenEndedForms() {
        assertEquals(Range(0, 100, false), Range.parse(null, 100))
        assertEquals(Range(90, 100, true), Range.parse("bytes=-10", 100))
        assertEquals(Range(10, 100, true), Range.parse("bytes=10-", 100))
        assertEquals(Range(10, 21, true), Range.parse("bytes=10-20", 100))
        assertEquals(Range(10, 100, true), Range.parse("bytes=10-9999", 100))
        assertNull(Range.parse("bytes=abc-10", 100))
        assertNull(Range.parse("bytes=50-10", 100))
        assertNull(Range.parse("bytes=100-", 100))
        assertNull(Range.parse("items=0-10", 100))
    }

    @Test
    fun multiRangeRequestsFallBackToTheWholeFile() {
        // Serving only the first range would be wrong; multipart is overkill for a player.
        assertEquals(Range(0, 100, false), Range.parse("bytes=0-9,20-29", 100))
    }

    @Test
    fun aZeroByteFileRejectsRangesButAnswersPlainGets() {
        assertEquals(Range(0, 0, false), Range.parse(null, 0))
        assertNull(Range.parse("bytes=0-", 0))
    }

    @Test
    fun aNonIoExceptionWhileOpeningStillProducesAResponse() {
        // A socket closed with no bytes at all surfaces as "unexpected end of stream",
        // which tells the user nothing. The client must always get a status line, even when
        // the failure is an exception type the server did not anticipate.
        val server = LoopbackStreamServer()
        server.start()
        try {
            val published = server.publish(
                "broken.jpg",
                "image/jpeg",
                StreamSessionStore.SourceFactory {
                    ProtocolErrorSource { IllegalStateException("smbj: no session") }
                },
            )
            val connection = open(server, published.url)
            val code = connection.responseCode
            connection.disconnect()
            assertEquals("a failure must still answer with a status, not an empty socket", 502, code)
        } finally {
            server.stop()
        }
    }

    @Test
    fun aNonIoExceptionMidTransferDoesNotCorruptTheStatusLine() {
        // Here the header is already committed, so the server must not try to write a
        // second one; it may only drop the connection. A 200 followed by a short body is the
        // correct outcome and must not turn into a protocol error.
        val server = LoopbackStreamServer()
        server.start()
        try {
            val published = server.publish(
                "half.jpg",
                "image/jpeg",
                StreamSessionStore.SourceFactory { MidTransferFailure(payload) },
            )
            val connection = open(server, published.url)
            val code = connection.responseCode
            val declared = connection.getHeaderFieldLong("Content-Length", -1)
            val body = runCatching { connection.inputStream.readBytes() }.getOrDefault(ByteArray(0))
            connection.disconnect()

            assertEquals(200, code)
            assertEquals(payload.size.toLong(), declared)
            assertTrue("some bytes should still have arrived", body.isNotEmpty())
            assertTrue("the body must be short rather than padded", body.size < payload.size)
        } finally {
            server.stop()
        }
    }
}
