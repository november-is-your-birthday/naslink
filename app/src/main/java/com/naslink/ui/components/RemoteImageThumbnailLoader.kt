package com.naslink.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.naslink.data.model.FileItem
import com.naslink.app.ThumbnailLoadingGate
import com.naslink.data.model.RemoteConnection
import com.naslink.playback.StreamPlaybackCoordinator
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Grid and list previews for photos on an SMB share.
 *
 * Three layers, cheapest first, and none of them store the original file:
 *
 * 1. an in-memory LRU of decoded bitmaps, so scrolling back is free;
 * 2. [ThumbnailCaches] — one ~30 KB WebP per file, so revisiting a folder costs no traffic;
 * 3. a bounded read of the source itself, decoded straight down to preview size.
 *
 * The old path handed the file to Coil, whose fetcher pulls the whole response and writes
 * it to the disk cache before decoding. A folder of 300 photos therefore put ~1 GB on disk
 * for previews, and because Coil's cache key had to carry the request, there was no way to
 * keep the preview and drop the bulk. Here the *preview* is what gets stored.
 *
 * Loading is a suspension point rather than a fire-and-forget request: [ThumbnailLoadingGate]
 * parks it while the user is flinging the grid, and disposal of the cell cancels it. That
 * is what keeps a fast scroll through a folder from reading every file it passed.
 */
internal object RemoteImageThumbnailLoader {

    /** Preview targets. Two buckets keep one entry per file for both grid and list. */
    private const val SMALL_TARGET_PX = 256
    private const val LARGE_TARGET_PX = 512

    /**
     * Largest original this will buffer to build a preview.
     *
     * Above this the file is only prefix-read for its embedded EXIF preview, because
     * holding several dozen megabytes of JPEG in memory on a phone is how a grid becomes
     * an OOM. 16 MB covers everything a phone or a normal camera produces.
     */
    private const val MAX_BUFFERED_SOURCE_BYTES = 16L * 1024 * 1024

    /** Enough for a JPEG's header segments including an embedded preview. */
    private const val EXIF_PREFIX_BYTES = 256 * 1024

    /** Rejects decompression bombs before allocating a full-size bitmap. */
    private const val MAX_DECODE_PIXELS = 64L * 1024 * 1024

    /** Matches the previous fetch concurrency: two reads share the pooled SMB session. */
    private val permits = Semaphore(2)

    private data class Key(val identity: String, val targetPx: Int)

    private val memory = object : LruCache<Key, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Bitmap) = value.allocationByteCount
    }

    /**
     * The best already-decoded preview for [identity], for the full-screen viewer to show
     * while the original is still in flight. Never touches the network.
     */
    fun cachedPreview(identity: String): Bitmap? {
        if (identity.isEmpty()) return null
        return memory.snapshot().entries
            .filter { it.key.identity == identity }
            .maxByOrNull { it.key.targetPx }
            ?.value
    }

    fun clear() = memory.evictAll()

    /**
     * @param identity stable per-file cache identity, `RemoteImagePublisher.cacheKey(file)`.
     * @return null when the file cannot be read or decoded — the caller shows the type icon.
     */
    suspend fun load(
        connection: RemoteConnection?,
        file: FileItem,
        identity: String,
        targetPx: Int,
    ): Bitmap? {
        val target = if (targetPx <= SMALL_TARGET_PX) SMALL_TARGET_PX else LARGE_TARGET_PX
        val key = Key(identity, target)
        memory.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val stored = ThumbnailCaches.images.get(storeKey(identity, target))
            if (stored != null) {
                val cached = ThumbnailCodec.decode(stored)
                if (cached != null) {
                    memory.put(key, cached)
                    return@withContext cached
                }
            }
            // Parked while the user is still scrolling: a fling composes far more cells
            // than it shows, and each one would otherwise start a file read.
            ThumbnailLoadingGate.awaitReady()
            permits.withPermit {
                memory.get(key)?.let { return@withPermit it }
                val bytes = readSource(connection, file) ?: return@withPermit null
                val bitmap = decode(bytes, target) ?: return@withPermit null
                ThumbnailCodec.encode(bitmap)?.let { encoded ->
                    ThumbnailCaches.images.put(storeKey(identity, target), encoded)
                }
                memory.put(key, bitmap)
                bitmap
            }
        }
    }

    private fun storeKey(identity: String, target: Int) = "$identity|t$target"

    /**
     * Reads the file, or just its header when it is too large to buffer.
     *
     * Cancellation is checked between chunks: the HTTP fetcher this replaces could not be
     * interrupted at all once its blocking copy had started, which is why leaving the
     * viewer still spent bandwidth on a picture nobody was looking at any more.
     */
    private suspend fun readSource(connection: RemoteConnection?, file: FileItem): ByteArray? {
        val target = connection ?: return null
        val source = runCatching {
            StreamPlaybackCoordinator.openRandomAccess(target, file.path)
        }.getOrNull() ?: return null
        return source.use { stream ->
            val total = runCatching { stream.size }.getOrNull() ?: return@use null
            if (total <= 0) return@use null
            val wholeFile = total <= MAX_BUFFERED_SOURCE_BYTES
            val budget = (if (wholeFile) total else min(total, EXIF_PREFIX_BYTES.toLong())).toInt()
            val buffer = ByteArray(budget)
            var read = 0
            while (read < budget) {
                currentCoroutineContext().ensureActive()
                val count = runCatching {
                    stream.readAt(read.toLong(), buffer, read, budget - read)
                }.getOrNull() ?: break
                if (count <= 0) break
                read += count
            }
            if (read <= 0) return@use null
            // No defensive copy when the whole budget arrived: that would double the peak
            // allocation for the one case (a full-size photo) where memory already matters.
            val bytes = if (read == buffer.size) buffer else buffer.copyOf(read)
            if (wholeFile) bytes else JpegExifThumbnail.extract(bytes)
        }
    }

    /**
     * Decodes [bytes] no larger than [target] on its longest side.
     *
     * Two passes over the same in-memory array: `inSampleSize` has to be known before the
     * decode, and re-reading the file to learn it would double the SMB traffic.
     */
    private fun decode(bytes: ByteArray, target: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        if (width.toLong() * height > MAX_DECODE_PIXELS) return null
        var sample = 1
        while (width / (sample * 2) >= target && height / (sample * 2) >= target) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return decoded.scaledToFit(target)
    }

    private fun Bitmap.scaledToFit(maxSide: Int): Bitmap {
        val longest = maxOf(width, height)
        if (longest <= maxSide) return this
        val scale = maxSide.toFloat() / longest
        val scaled = runCatching {
            Bitmap.createScaledBitmap(
                this,
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1),
                true,
            )
        }.getOrNull() ?: return this
        if (scaled !== this) recycle()
        return scaled
    }
}
