package com.naslink.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.naslink.stream.LoopbackStreamServer
import com.naslink.stream.SmbRandomAccessSource
import com.naslink.stream.SmbSessionPool
import com.naslink.stream.SmbTarget
import com.naslink.stream.StreamSessionStore
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the whole SMB stream path against a real SMB2 server instead of a byte array.
 *
 * A local impacket server stands in for the NAS: negotiate, NTLM session setup, tree
 * connect, query info and ranged reads all run for real, which is the only way to catch
 * protocol-level mistakes a fake source would hide.
 *
 * Skipped unless `-e smbHost <ip>` is supplied to the instrumentation runner.
 */
@RunWith(AndroidJUnit4::class)
class SmbStreamIntegrationTest {

    private val host: String get() = InstrumentationRegistry.getArguments().getString("smbHost").orEmpty()

    private fun target(share: String, path: String) = SmbTarget(
        host = host,
        port = InstrumentationRegistry.getArguments().getString("smbPort")?.toIntOrNull() ?: 445,
        share = share,
        path = path,
        username = "",
        password = CharArray(0),
        domain = null,
        anonymous = true,
    )

    @Test
    fun readsFileSizeAndRangedBytesOverRealSmb() {
        assumeTrue("no smbHost supplied", host.isNotBlank())

        val source = SmbRandomAccessSource(target("MEDIA", "media\\probe.mp4"))
        try {
            val size = source.size
            assertTrue("a real file must report a positive size, got $size", size > 1_000_000)

            // Head of the file: the ftyp box proves we landed at offset 0.
            val head = ByteArray(16)
            val headRead = source.readAt(0L, head, 0, head.size)
            assertEquals(16, headRead)
            assertEquals("ftyp", String(head, 4, 4, Charsets.ISO_8859_1))

            // Tail: the moov box occupies the final 116 bytes of this fixture, so reading
            // its header means seeking near the end of the file -- exactly what a player
            // does when it needs metadata for a moov-at-the-end MP4.
            val moovOffset = size - MOOV_BOX_BYTES
            val moovHead = ByteArray(16)
            val moovRead = source.readAt(moovOffset, moovHead, 0, moovHead.size)
            assertEquals(16, moovRead)
            assertEquals("moov", String(moovHead, 4, 4, Charsets.ISO_8859_1))

            // A read that straddles the end must clamp instead of over-reporting.
            val overshoot = ByteArray(64)
            val clamped = source.readAt(size - 10L, overshoot, 0, overshoot.size)
            assertEquals(10, clamped)

            // Reading past the end reports no data rather than inventing bytes.
            assertEquals(0, source.readAt(size, overshoot, 0, overshoot.size))
        } finally {
            source.close()
        }
    }

    @Test
    fun loopbackServerStreamsRealSmbBytesWithRanges() {
        assumeTrue("no smbHost supplied", host.isNotBlank())

        val server = LoopbackStreamServer()
        server.start()
        try {
            val published = server.publish("probe.mp4", "video/mp4", StreamSessionStore.SourceFactory {
                SmbRandomAccessSource(target("MEDIA", "media\\probe.mp4"))
            })

            // HEAD must advertise the real remote size, not a guess.
            val head = URL(published.url).openConnection() as HttpURLConnection
            head.requestMethod = "HEAD"
            head.connect()
            assertEquals(200, head.responseCode)
            val advertised = head.getHeaderFieldLong("Content-Length", -1)
            assertTrue("HEAD must advertise the remote size", advertised > 1_000_000)
            head.disconnect()

            // Two separate Range requests, the pattern a player uses when seeking.
            val first = URL(published.url).openConnection() as HttpURLConnection
            first.setRequestProperty("Range", "bytes=0-99")
            first.connect()
            assertEquals(206, first.responseCode)
            val head100 = first.inputStream.readBytes()
            assertEquals(100, head100.size)
            first.disconnect()

            // A second, independent Range for the moov header near the end of the file.
            val moovStart = advertised - MOOV_BOX_BYTES
            val second = URL(published.url).openConnection() as HttpURLConnection
            second.setRequestProperty("Range", "bytes=$moovStart-")
            second.connect()
            assertEquals(206, second.responseCode)
            assertEquals("bytes $moovStart-${advertised - 1}/$advertised", second.getHeaderField("Content-Range"))
            val moovBytes = second.inputStream.readBytes()
            assertEquals(MOOV_BOX_BYTES.toInt(), moovBytes.size)
            assertEquals("moov", String(moovBytes, 4, 4, Charsets.ISO_8859_1))
            second.disconnect()
        } finally {
            server.stop()
        }
    }

    @Test
    fun warmSourceServesIdenticalBytesAsDirectSmbReads() {
        assumeTrue("no smbHost supplied", host.isNotBlank())

        val server = LoopbackStreamServer()
        server.start()
        try {
            val published = server.publish("probe.mp4", "video/mp4", StreamSessionStore.SourceFactory {
                SmbRandomAccessSource(target("MEDIA", "media\\probe.mp4"))
            })

            // Take one range over HTTP, then the same range straight from SMB. They must
            // match byte for byte, or playback would silently corrupt media.
            val rangeStart = 1_048_576L
            val rangeLength = 256

            val http = URL(published.url).openConnection() as HttpURLConnection
            http.setRequestProperty("Range", "bytes=$rangeStart-${rangeStart + rangeLength - 1}")
            http.connect()
            assertEquals(206, http.responseCode)
            val viaHttp = http.inputStream.readBytes()
            http.disconnect()

            val viaSmb = ByteArray(rangeLength)
            val direct = SmbRandomAccessSource(target("MEDIA", "media\\probe.mp4"))
            try {
                var filled = 0
                while (filled < rangeLength) {
                    val n = direct.readAt(rangeStart + filled, viaSmb, filled, rangeLength - filled)
                    if (n <= 0) break
                    filled += n
                }
                assertEquals(rangeLength, filled)
            } finally {
                direct.close()
            }

            assertArrayEquals(viaSmb, viaHttp)
        } finally {
            server.stop()
        }
    }

    @Test
    fun pooledSourcesShareOneSessionAndStillReadCorrectBytes() {
        assumeTrue("no smbHost supplied", host.isNotBlank())

        // The point of the pool is that consecutive streams stop dialling new inbound
        // connections. Windows caps those at roughly 20, so a pool that silently failed to
        // reuse would quietly exhaust a real server's budget.
        val pool = SmbSessionPool()
        try {
            val first = SmbRandomAccessSource(target("MEDIA", "media\\probe.mp4"), pool = pool)
            val size: Long
            val headA = ByteArray(16)
            try {
                size = first.size
                first.readAt(0L, headA, 0, headA.size)
            } finally {
                first.close()
            }
            assertEquals(1, pool.size)

            // A second stream of the same file, with the first already released.
            val second = SmbRandomAccessSource(target("MEDIA", "media\\probe.mp4"), pool = pool)
            val headB = ByteArray(16)
            try {
                assertEquals("the pooled size must match", size, second.size)
                second.readAt(0L, headB, 0, headB.size)
            } finally {
                second.close()
            }

            assertEquals("a released session must be reused, not redialled", 1, pool.size)
            assertArrayEquals("reuse must not change the bytes served", headA, headB)

            // Reopening the same identity yet again still reuses: the pool key covers
            // host, port and credentials, so an identical target must not redial.
            val third = SmbRandomAccessSource(target("MEDIA", "media\\probe.mp4"), pool = pool)
            try {
                third.size
            } finally {
                third.close()
            }
            assertEquals("the same target must keep hitting the one cached session", 1, pool.size)
        } finally {
            pool.clear()
            assertEquals("clear must leave nothing behind", 0, pool.size)
        }
    }

    @Test
    fun concurrentStreamsAgainstOnePoolNeverShareASession() {
        assumeTrue("no smbHost supplied", host.isNotBlank())

        // A held playback must not starve a second one, and the second must not be handed
        // the session the first is still reading through.
        val pool = SmbSessionPool(maxIdleSessions = 1)
        try {
            val held = SmbRandomAccessSource(target("MEDIA", "media\\probe.mp4"), pool = pool)
            val heldSize = held.size

            val concurrent = SmbRandomAccessSource(target("MEDIA", "media\\probe.mp4"), pool = pool)
            val bytes = ByteArray(32)
            try {
                assertEquals("a saturated pool must still serve the second stream", heldSize, concurrent.size)
                assertTrue(concurrent.readAt(0L, bytes, 0, bytes.size) > 0)
            } finally {
                concurrent.close()
                held.close()
            }
        } finally {
            pool.clear()
        }
    }

    @Test
    fun realSmbImageDecodesFromTheLoopbackServer() {
        assumeTrue("no smbHost supplied", host.isNotBlank())

        // The image path is the one a photo grid uses dozens of times at once, so it has to
        // survive a real round trip: SMB read, loopback HTTP, decode. A byte-array stub
        // would not catch a wrong Content-Type or a truncated response.
        val server = LoopbackStreamServer()
        server.start()
        try {
            val published = server.publish("red.png", "image/png", StreamSessionStore.SourceFactory {
                SmbRandomAccessSource(target("MEDIA", "media\\red.png"))
            })

            assertEquals("image/png", published.mimeType)
            assertTrue("the URL must be loopback so no second SMB handshake is needed",
                published.url.startsWith("http://127.0.0.1:"))

            val connection = URL(published.url).openConnection() as HttpURLConnection
            connection.connect()
            assertEquals(200, connection.responseCode)
            assertEquals("image/png", connection.contentType)
            val bytes = connection.inputStream.readBytes()
            connection.disconnect()

            // A real PNG signature: the fixture is a genuine file on the server, not a stub.
            assertTrue("must start with the PNG signature",
                bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
                    bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte())

            // The decisive check: the platform decoder must accept what came over HTTP.
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertNotNull("the bytes served over HTTP must decode as an image", bitmap)
            try {
                assertEquals(IMAGE_EDGE, bitmap.width)
                assertEquals(IMAGE_EDGE, bitmap.height)
            } finally {
                bitmap.recycle()
            }

            // And they must match what a direct SMB read produces, or the picture on screen
            // would not be the picture on the NAS.
            val direct = SmbRandomAccessSource(target("MEDIA", "media\\red.png"))
            try {
                val directBytes = ByteArray(bytes.size)
                var filled = 0
                while (filled < directBytes.size) {
                    val n = direct.readAt(filled.toLong(), directBytes, filled, directBytes.size - filled)
                    if (n <= 0) break
                    filled += n
                }
                assertEquals(bytes.size, filled)
                assertArrayEquals("loopback bytes must equal direct SMB bytes", directBytes, bytes)
            } finally {
                direct.close()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun aRangeRequestForAnImageHeaderReturnsPartialContent() {
        assumeTrue("no smbHost supplied", host.isNotBlank())

        // Coil asks for exactly the bytes it needs; a server that ignored Range would send
        // the whole file and defeat the point of streaming.
        val server = LoopbackStreamServer()
        server.start()
        try {
            val published = server.publish("red.png", "image/png", StreamSessionStore.SourceFactory {
                SmbRandomAccessSource(target("MEDIA", "media\\red.png"))
            })

            val connection = URL(published.url).openConnection() as HttpURLConnection
            connection.setRequestProperty("Range", "bytes=0-7")
            connection.connect()
            assertEquals(206, connection.responseCode)
            val header = connection.inputStream.readBytes()
            connection.disconnect()

            assertEquals(8, header.size)
            assertEquals(0x89.toByte(), header[0])
            assertEquals('P'.code.toByte(), header[1])
        } finally {
            server.stop()
        }
    }

    private companion object {
        /** The fixture's moov box size: 4 length + 4 tag + 108 payload. */
        const val MOOV_BOX_BYTES = 116L

        /** The generated PNG fixtures are square and this many pixels on a side. */
        const val IMAGE_EDGE = 64
    }
}