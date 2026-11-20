package com.naslink.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.naslink.R
import com.naslink.stream.LoopbackStreamServer
import com.naslink.stream.RandomAccessSource
import com.naslink.stream.StreamSessionStore
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the full-screen image viewer over a real loopback HTTP server: the publications
 * are local-file-backed rather than SMB, which still exercises everything the viewer
 * actually does — Coil fetch over loopback, chrome, paging, dismissal.
 */
@RunWith(AndroidJUnit4::class)
class ImageViewerActivityTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var server: LoopbackStreamServer
    private lateinit var workDir: File

    /** Serves a local file through the loopback server's seekable-source contract. */
    private class LocalFileSource(file: File) : RandomAccessSource {
        private val delegate = java.io.RandomAccessFile(file, "r")
        override val size: Long get() = delegate.length()
        override fun readAt(offset: Long, buffer: ByteArray, off: Int, len: Int): Int {
            delegate.seek(offset)
            return delegate.read(buffer, off, len)
        }
        override fun close() = delegate.close()
    }

    private fun writePng(name: String, color: Int): File {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val target = workDir.resolve(name)
        FileOutputStream(target).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        bitmap.recycle()
        return target
    }

    @Before fun setUp() {
        server = LoopbackStreamServer()
        server.start()
        workDir = File(context.cacheDir, "viewer-test-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After fun tearDown() {
        server.stop()
        if (::workDir.isInitialized) workDir.deleteRecursively()
    }

    @Test fun opensFirstImagePagesBothWaysAndCloses() {
        val first = writePng("a.png", Color.RED)
        val second = writePng("b.png", Color.rgb(0, 128, 0))
        val published = listOf(first, second).map { file ->
            val url = server.publish(file.name, "image/png", StreamSessionStore.SourceFactory {
                LocalFileSource(file)
            })
            PublishedImage(url = url.url, title = file.name)
        }
        assertEquals(2, published.size)

        ActivityScenario.launch<ImageViewerActivity>(
            ImageViewerActivity.intent(context, published, startIndex = 0),
        ).use { scenario ->
            val of = context.getString(R.string.image_viewer_of_count, 1, 2)
            compose.onNodeWithText(of).assertExists()

            // The pager controls are icon buttons described by the same strings the
            // chrome used to render as text.
            val next = context.getString(R.string.image_viewer_next)
            val previous = context.getString(R.string.image_viewer_prev)
            compose.onNodeWithContentDescription(next).performClick()
            compose.onNodeWithText(context.getString(R.string.image_viewer_of_count, 2, 2)).assertExists()

            // Forward is disabled on the last page; going back must land on page 1 again.
            compose.onNodeWithContentDescription(previous).performClick()
            compose.onNodeWithText(of).assertExists()
        }
    }

    @Test fun singleImageHidesTheCounterAndPager() {
        val only = writePng("solo.png", Color.BLUE)
        val url = server.publish("solo.png", "image/png", StreamSessionStore.SourceFactory {
            LocalFileSource(only)
        })
        ActivityScenario.launch<ImageViewerActivity>(
            ImageViewerActivity.intent(context, listOf(PublishedImage(url.url, "solo.png")), startIndex = 0),
        ).use {
            compose.onNodeWithText(context.getString(R.string.image_viewer_of_count, 1, 1))
                .assertDoesNotExist()
        }
    }
}
