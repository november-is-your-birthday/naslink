package com.naslink.data.repository

import android.content.Context
import com.naslink.data.local.AppDatabase
import com.naslink.data.local.WatchProgressDao
import com.naslink.data.model.WatchProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Playback resume positions, keyed by the stable media identity the browser hands
 * to the player. Checkpoints are fire-and-forget: the position is written every few
 * seconds while playing, so losing the very last write to process death is harmless.
 */
class WatchProgressRepository private constructor(private val dao: WatchProgressDao) {

    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Saved resume position in ms, or null when the file has never been played. */
    suspend fun load(mediaKey: String): Long? {
        if (mediaKey.isEmpty()) return null
        return dao.get(mediaKey)?.positionMs
    }

    fun checkpoint(mediaKey: String, positionMs: Long, durationMs: Long) {
        if (mediaKey.isEmpty() || positionMs <= MIN_TRACKED_POSITION_MS) return
        persistScope.launch {
            dao.upsert(WatchProgress(mediaKey, positionMs, durationMs.coerceAtLeast(0)))
        }
    }

    /** Drops the entry once a file has been watched to the end. */
    fun clear(mediaKey: String) {
        if (mediaKey.isEmpty()) return
        persistScope.launch { dao.clear(mediaKey) }
    }

    fun pruneOldEntries() {
        persistScope.launch { dao.prune(System.currentTimeMillis() - PRUNE_AFTER_MS) }
    }

    companion object {
        /** Positions below this are "barely started" and neither saved nor resumed. */
        const val MIN_TRACKED_POSITION_MS = 3_000L

        private const val PRUNE_AFTER_MS = 180L * 24 * 60 * 60 * 1000

        @Volatile
        private var instance: WatchProgressRepository? = null

        fun get(context: Context): WatchProgressRepository =
            instance ?: synchronized(this) {
                instance ?: WatchProgressRepository(AppDatabase.getInstance(context).watchProgressDao())
                    .also { instance = it }
            }
    }
}
