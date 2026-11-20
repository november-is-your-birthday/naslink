package com.naslink.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.naslink.stream.LoopbackStreamServer
import com.naslink.stream.RandomAccessSource
import com.naslink.stream.StreamSessionStore
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end proof that the piece this app actually added works on a device: the
 * in-process loopback server, the Range protocol it speaks, and ExoPlayer consuming it.
 *
 * A generated PCM WAV stands in for the SMB file so the test never needs a NAS.
 */
@RunWith(AndroidJUnit4::class)
class LoopbackStreamPlaybackTest {

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

    /** A 16-bit mono PCM WAV, which ExoPlayer decodes without any external codec. */
    private fun sineWav(sampleRate: Int = 8000, seconds: Int = 3): ByteArray {
        val samples = sampleRate * seconds
        val pcm = ByteArray(samples * 2)
        for (index in 0 until samples) {
            val value = (sin(index * 2.0 * Math.PI * 440.0 / sampleRate) * 12_000.0).toInt().toShort()
            pcm[index * 2] = (value.toInt() and 0xFF).toByte()
            pcm[index * 2 + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
        }
        val out = ByteArrayOutputStream()
        fun ascii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
        fun int32(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 24) and 0xFF)
        }
        fun int16(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
        }
        ascii("RIFF")
        int32(36 + pcm.size)
        ascii("WAVE")
        ascii("fmt ")
        int32(16)
        int16(1) // PCM
        int16(1) // mono
        int32(sampleRate)
        int32(sampleRate * 2) // byte rate
        int16(2) // block align
        int16(16) // bits per sample
        ascii("data")
        int32(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    @Test
    fun loopbackServerServesRangesToPlainHttpOnAndroid() {
        val server = LoopbackStreamServer()
        val port = server.start()
        assertTrue("the server must bind a real port", port > 0)
        try {
            val payload = sineWav(seconds = 1)
            val published = server.publish("tone.wav", "audio/wav", StreamSessionStore.SourceFactory {
                ByteArraySource(payload)
            })
            val connection = URL(published.url).openConnection() as HttpURLConnection
            connection.setRequestProperty("Range", "bytes=100-199")
            connection.connect()
            try {
                assertEquals(206, connection.responseCode)
                assertEquals("bytes 100-199/${payload.size}", connection.getHeaderField("Content-Range"))
                val body = connection.inputStream.readBytes()
                assertEquals(100, body.size)
                assertEquals(payload.copyOfRange(100, 200).toList(), body.toList())
            } finally {
                connection.disconnect()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun exoPlayerPlaysAndSeeksOverTheLoopbackServer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mainHandler = Handler(Looper.getMainLooper())
        val wav = sineWav()
        val server = LoopbackStreamServer()
        server.start()

        val ready = CountDownLatch(1)
        val playing = CountDownLatch(1)
        val seekDone = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val durationMs = AtomicLong(0)
        val seekPositionMs = AtomicLong(-1)
        val playerRef = AtomicReference<ExoPlayer?>(null)

        try {
            val published = server.publish("tone.wav", "audio/wav", StreamSessionStore.SourceFactory {
                ByteArraySource(wav)
            })
            // ExoPlayer must be created and driven from its own looper thread.
            instrumentation.runOnMainSync {
                val player = ExoPlayer.Builder(context).build()
                playerRef.set(player)
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            durationMs.set(player.duration)
                            ready.countDown()
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) playing.countDown()
                    }

                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int,
                    ) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                            seekPositionMs.set(newPosition.positionMs)
                            seekDone.countDown()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        failure.compareAndSet(null, error)
                        ready.countDown()
                        playing.countDown()
                        seekDone.countDown()
                    }
                })
                player.setMediaItem(MediaItem.fromUri(published.url))
                player.prepare()
                player.play()
            }

            assertTrue(
                "ExoPlayer must reach STATE_READY over the loopback server: ${failure.get()}",
                ready.await(20, TimeUnit.SECONDS),
            )
            val total = durationMs.get()
            assertTrue("the served WAV is about three seconds long, got $total", total in 2_900..3_100)

            assertTrue("playback must actually start: ${failure.get()}", playing.await(20, TimeUnit.SECONDS))
            Thread.sleep(600)

            // Seeking is what really exercises Range requests against the remote source.
            val target = total - 400
            mainHandler.post { playerRef.get()?.seekTo(target) }
            assertTrue("seeking mid-file must complete: ${failure.get()}", seekDone.await(20, TimeUnit.SECONDS))
            assertTrue(
                "seek landed at $target but reported ${seekPositionMs.get()}",
                abs(seekPositionMs.get() - target) < 200,
            )
            assertEquals(null, failure.get())
        } finally {
            instrumentation.runOnMainSync { playerRef.get()?.release() }
            server.stop()
        }
    }
}
