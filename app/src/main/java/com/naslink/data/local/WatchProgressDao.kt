package com.naslink.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.naslink.data.model.WatchProgress

@Dao
interface WatchProgressDao {
    @Query("SELECT * FROM watch_progress WHERE mediaKey = :mediaKey")
    suspend fun get(mediaKey: String): WatchProgress?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: WatchProgress)

    @Query("DELETE FROM watch_progress WHERE mediaKey = :mediaKey")
    suspend fun clear(mediaKey: String)

    @Query("DELETE FROM watch_progress WHERE updatedAt < :staleBefore")
    suspend fun prune(staleBefore: Long)
}
