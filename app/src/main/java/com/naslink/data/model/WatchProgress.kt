package com.naslink.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Last known playback position for one streamed media file, keyed by the stable
 * identity produced by `StreamPlaybackCoordinator.mediaKeyFor` so the same file
 * addressed through a pinned share or through discovery mode maps to one row.
 */
@Entity(tableName = "watch_progress")
data class WatchProgress(
    @PrimaryKey
    val mediaKey: String,
    val positionMs: Long,
    /** Reported duration in ms; 0 when the player had not resolved it yet. */
    val durationMs: Long = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)
