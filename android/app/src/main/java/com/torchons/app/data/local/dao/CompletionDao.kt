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

    /** Lookup one-shot pour patcher une row (edition optimiste + UPDATE sync partiel). */
    @Query("SELECT * FROM completions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CompletionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(completions: List<CompletionEntity>)

    @Query("DELETE FROM completions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM completions WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: String)

    @Query("DELETE FROM completions")
    suspend fun deleteAll()

    /**
     * Remplace les completions d'UNE tache (refresh scope tache = autoritaire
     * quand en ligne : les suppressions/edits serveur se propagent, pas
     * d'upsert-only qui laisserait des lignes fantomes). Cf
     * docs/cache-conventions.md.
     */
    @Transaction
    suspend fun replaceForTask(taskId: String, completions: List<CompletionEntity>) {
        deleteByTask(taskId)
        upsertAll(completions)
    }

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
