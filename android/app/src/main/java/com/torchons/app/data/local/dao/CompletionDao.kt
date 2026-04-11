package com.torchons.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.torchons.app.data.local.entities.CompletionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CompletionDao {

    @Query("SELECT * FROM completions ORDER BY completedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<CompletionEntity>>

    @Query("SELECT * FROM completions WHERE taskId = :taskId ORDER BY completedAt DESC")
    fun observeByTask(taskId: String): Flow<List<CompletionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(completions: List<CompletionEntity>)

    @Query("DELETE FROM completions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM completions")
    suspend fun deleteAll()

    /**
     * Remplace l'integralite des completions recentes (utilise par les
     * refresh "feed historique" et "completions par tache").
     */
    @Transaction
    suspend fun replaceAll(completions: List<CompletionEntity>) {
        deleteAll()
        upsertAll(completions)
    }
}
