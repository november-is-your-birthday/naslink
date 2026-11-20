package com.naslink.app

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import com.naslink.ui.components.ThumbnailCaches
import com.naslink.viewmodel.FileBrowserViewModel
import com.naslink.viewmodel.TransferOperationController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class NasLinkApp : Application(), ViewModelStoreOwner, ImageLoaderFactory {

    override val viewModelStore = ViewModelStore()

    val browserViewModel: FileBrowserViewModel
        get() = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(this))[FileBrowserViewModel::class]

    val transfers by lazy {
        TransferOperationController(startForeground = {
            ContextCompat.startForegroundService(this, Intent(this, TransferService::class.java))
        })
    }

    /**
     * A scope that outlives any single ViewModel, for work that must finish *after* the
     * ViewModel that started it is gone.
     *
     * `viewModelScope` cannot be used for that: `ViewModel.clear()` cancels its job before
     * `onCleared()` runs, so a coroutine launched there never executes its body. Tearing
     * down network sessions is exactly such work — it has to outlive the ViewModel, or the
     * sessions it closes would stay open for the life of the process. Same pattern as
     * `WatchProgressRepository.persistScope`.
     */
    val teardownScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The app-wide Coil loader.
     *
     * Deliberately **without** a disk cache. Coil's disk cache stores the bytes a fetcher
     * produced, and for a remote photo that meant the entire original file per grid cell:
     * a folder of a few hundred pictures filled hundreds of megabytes with previews the
     * user never opened at full size, and the entries could not be replaced by smaller ones
     * because a cache entry is keyed by the request, not by the rendered size. Remote
     * previews are generated and cached separately by `RemoteImageThumbnailLoader`, at
     * preview resolution; what is left for Coil is local files, which decode from disk
     * quickly enough that the memory cache is all they need.
     *
     * The memory cache is capped at 20% of the heap so it cannot compete with playback
     * buffers.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(MEMORY_CACHE_PERCENT)
                .build()
        }
        // Caps concurrent *fetches*, not decodes. Local image previews are the only thing
        // Coil fetches now, and the parallel budget keeps a grid from flooding the decoder.
        .fetcherDispatcher(Dispatchers.IO.limitedParallelism(FETCH_CONCURRENCY))
        .crossfade(false)
        .build()

    /**
     * Thumbnail stores live under `cacheDir`, so they are created here rather than lazily
     * from a composable: every caller (grid, viewer placeholder, cache-size row) must see
     * the same instance, or clearing the cache would leave one of them reporting stale
     * bytes.
     */
    override fun onCreate() {
        super.onCreate()
        ThumbnailCaches.initialize(cacheDir)
        // One-time clean-up of the cache v0.2.1 and earlier used: Coil's disk cache held the
        // *original bytes* of every remote photo, which is exactly the "hundreds of
        // megabytes for previews" this release removes. Nothing creates that directory any
        // more, so leaving it would strand the bytes with no UI able to report or clear
        // them — the cache row would read 0 MB while half a gigabyte sat on disk. Off the
        // main thread because deleting a few hundred files is not free.
        teardownScope.launch {
            runCatching { File(cacheDir, LEGACY_IMAGE_CACHE_DIRECTORY).deleteRecursively() }
        }
    }

    private companion object {
        const val MEMORY_CACHE_PERCENT = 0.20
        const val FETCH_CONCURRENCY = 2
        const val LEGACY_IMAGE_CACHE_DIRECTORY = "image_cache"
    }
}
