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

    /**
     * Wipe complet de la table. Appele au demarrage quand le versionCode de
     * l'app a change depuis le dernier lancement (cf TorchonsApp.onCreate) :
     * les snapshots JSON caches peuvent etre serialises avec un ancien
     * schema de data class et Gson met silencieusement null sur les champs
     * absents, faisant crasher l'UI en NPE. Purger a chaque montee de
     * version est plus simple que de versionner chaque schema.
     */
    @Query("DELETE FROM view_snapshots")
    suspend fun clearAll()
}
