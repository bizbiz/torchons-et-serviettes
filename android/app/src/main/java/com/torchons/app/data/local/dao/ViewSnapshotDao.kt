package com.torchons.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.torchons.app.data.local.entities.ViewSnapshotEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO pour les snapshots de vues derivees (suggestions, fair-share, etc.)
 * stockes en JSON dans une table key/value.
 */
@Dao
interface ViewSnapshotDao {

    @Query("SELECT * FROM view_snapshots WHERE key = :key LIMIT 1")
    fun observe(key: String): Flow<ViewSnapshotEntity?>

    @Query("SELECT * FROM view_snapshots WHERE key = :key LIMIT 1")
    suspend fun get(key: String): ViewSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: ViewSnapshotEntity)

    @Query("SELECT MIN(updatedAt) FROM view_snapshots WHERE key IN (:keys)")
    suspend fun getOldestUpdatedAt(keys: List<String>): Long?
}
