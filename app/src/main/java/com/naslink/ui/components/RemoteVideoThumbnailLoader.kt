package com.naslink.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import com.naslink.app.ThumbnailLoadingGate
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Frame previews for videos that live on an SMB share.
 *
 * The video is published on the in-process loopback server (the same pooled session the
 * photo grid uses), and [MediaMetadataRetriever] reads only the bytes a frame needs over
 * HTTP range requests — the moov box plus the first frames, never the whole file. The
 * two-permit throttle matches [RemoteImageThumbnailLoader] so a folder of clips cannot
 * flood the session.
 *
 * Three layers, cheapest first: a decoded-bitmap LRU, then [ThumbnailCaches.videos] (a
 * ~30 KB WebP per clip, so revisiting a folder does not re-extract every frame), then the
 * retriever itself.
 */
internal object RemoteVideoThumbnailLoader {

    private const val SMALL_TARGET_PX = 256
    private const val LARGE_TARGET_PX = 512
    private val permits = Semaphore(2)

    /**
     * Deliberately keyed on the version string, never on the URL: the URL carries a
     * random port and a random token per publish, so keying on it would make the cache
     * miss on every recomposition that re-published and re-extract the frame each time.
     * The version already covers the path, size and mtime, which is what identifies the
     * file; the dimensions belong here because a frame decoded for a different target
     * size is not interchangeable.
     */
    private data class Key(val version: String, val targetPx: Int)

    private val cache = object : LruCache<Key, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Bitmap) = value.allocationByteCount
    }

    fun clear() = cache.evictAll()

    /**
     * @param version invalidates the cache entry when the file changes on the server;
     *   `RemoteImagePublisher.cacheKey(file)` is exactly that string — path, size and
     *   mtime scoped to the connection the file came from.
     */
    suspend fun load(
        context: Context,
        url: String,
        version: String,
        maxWidth: Int,
        maxHeight: Int,
    ): Bitmap? {
        val requested = min(maxWidth, maxHeight).coerceAtLeast(1)
        val target = if (requested <= SMALL_TARGET_PX) SMALL_TARGET_PX else LARGE_TARGET_PX
        val key = Key(version, target)
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val storeKey = "$version|t$target"
            val stored = ThumbnailCaches.videos.get(storeKey)
            if (stored != null) {
                val cached = ThumbnailCodec.decode(stored)
                if (cached != null) {
                    cache.put(key, cached)
                    return@withContext cached
                }
            }
            // Same parking rule as photo previews: a fling should not start a media
            // extraction for a cell that is already gone.
            ThumbnailLoadingGate.awaitReady()
            permits.withPermit {
                cache.get(key)?.let { return@withPermit it }
                val frame = extract(url, target, target) ?: return@withPermit null
                ThumbnailCodec.encode(frame)?.let { ThumbnailCaches.videos.put(storeKey, it) }
                cache.put(key, frame)
                frame
            }
        }
    }

    private fun extract(url: String, width: Int, height: Int): Bitmap? = try {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(url, HashMap<String, String>())
            // Frame 0 is black on a lot of recordings; grab one 10% into the file instead.
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION,
            )?.toLongOrNull() ?: 0
            val atUs = if (durationMs > 2_000) (durationMs / 10) * 1000 else 0L
            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    atUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    width,
                    height,
                )
            } else {
                retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            frame?.bounded(width, height)
        } finally {
            retriever.release()
        }
    } catch (error: Exception) {
        android.util.Log.w("RemoteVideoThumb", "frame preview failed for $url", error)
        // Unreadable container, unsupported codec, dead session: fall back to the icon.
        null
    }

    private fun Bitmap.bounded(width: Int, height: Int): Bitmap {
        val scale = min(1f, min(width.toFloat() / this.width, height.toFloat() / this.height))
        if (scale == 1f) return this
        return try {
            Bitmap.createScaledBitmap(
                this,
                (this.width * scale).toInt().coerceAtLeast(1),
                (this.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } finally {
            recycle()
        }
    }
}
