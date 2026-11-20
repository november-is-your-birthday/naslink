package com.naslink.ui.components

import java.io.File

/**
 * The app's thumbnail stores, one budget per media kind.
 *
 * Split rather than shared so a folder of long videos cannot evict the photo previews the
 * user is actually looking at: frame extraction for a clip is far more expensive to redo
 * than re-decoding a picture, and the two sets are browsed in different folders anyway.
 *
 * Budgets are deliberately modest. A generated preview is 20–40 KB, so 48 MB holds well
 * over a thousand photos — enough that revisiting a folder is instant — while staying an
 * order of magnitude below the half-gigabyte the previous original-bytes cache could reach.
 */
internal object ThumbnailCaches {

    private const val ROOT_DIRECTORY = "thumbnails"
    private const val IMAGE_DIRECTORY = "images"
    private const val VIDEO_DIRECTORY = "videos"

    private const val IMAGE_BUDGET_BYTES = 48L * 1024 * 1024
    private const val VIDEO_BUDGET_BYTES = 24L * 1024 * 1024

    private val lock = Any()
    private var root: File? = null

    @Volatile
    private var imageStore: ThumbnailDiskCache? = null

    @Volatile
    private var videoStore: ThumbnailDiskCache? = null

    /** Call once from `Application.onCreate`; every later access reuses these stores. */
    fun initialize(cacheDir: File) {
        synchronized(lock) {
            if (root == null) root = File(cacheDir, ROOT_DIRECTORY)
        }
    }

    val images: ThumbnailDiskCache
        get() = imageStore ?: synchronized(lock) {
            imageStore ?: ThumbnailDiskCache(directory(IMAGE_DIRECTORY), IMAGE_BUDGET_BYTES)
                .also { imageStore = it }
        }

    val videos: ThumbnailDiskCache
        get() = videoStore ?: synchronized(lock) {
            videoStore ?: ThumbnailDiskCache(directory(VIDEO_DIRECTORY), VIDEO_BUDGET_BYTES)
                .also { videoStore = it }
        }

    /** Combined on-disk size, which is what the "clear cache" row reports. */
    fun sizeBytes(): Long = images.sizeBytes() + videos.sizeBytes()

    fun clear() {
        images.clear()
        videos.clear()
    }

    private fun directory(name: String): File {
        val base = root ?: error("ThumbnailCaches.initialize must run before first use")
        return File(base, name)
    }
}
