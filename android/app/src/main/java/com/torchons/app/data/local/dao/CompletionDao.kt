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

    /** One-shot read of a task's completions, for the duplicate check inside a write transaction. */
    @Query("SELECT * FROM completions WHERE taskId = :taskId")
    suspend fun getByTask(taskId: String): List<CompletionEntity>

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

    /** Rows the offline queue still has to push (INSERT, UPDATE or DELETE). */
    @Query("SELECT rowId FROM pending_operations WHERE tableName = 'task_completions'")
    suspend fun pendingRowIds(): List<String>

    @Query(
        "DELETE FROM completions WHERE taskId = :taskId AND id NOT IN " +
            "(SELECT rowId FROM pending_operations WHERE tableName = 'task_completions')"
    )
    suspend fun deleteSyncedByTask(taskId: String)

    @Query(
        "DELETE FROM completions WHERE id NOT IN " +
            "(SELECT rowId FROM pending_operations WHERE tableName = 'task_completions')"
    )
    suspend fun deleteAllSynced()

    /**
     * Remplace les completions d'UNE tache (refresh scope tache = autoritaire
     * quand en ligne : les suppressions/edits serveur se propagent, pas
     * d'upsert-only qui laisserait des lignes fantomes). Cf
     * docs/cache-conventions.md.
     *
     * Sauf les lignes qu'une op de la file n'a pas encore poussees : le
     * serveur ne les connait pas encore (ou pas dans leur etat local), sa
     * reponse les effacerait ou les ressusciterait. Cf [serverRowsToApply].
     */
    @Transaction
    suspend fun replaceForTask(taskId: String, completions: List<CompletionEntity>) {
        val pending = pendingRowIds().toSet()
        deleteSyncedByTask(taskId)
        upsertAll(serverRowsToApply(completions, pending))
    }

    /**
     * Remplace l'integralite des completions recentes (utilise par les
     * refresh "feed historique" et "completions par tache"), avec la meme
     * reserve que [replaceForTask] pour les lignes encore en file.
     */
    @Transaction
    suspend fun replaceAll(completions: List<CompletionEntity>) {
        val pending = pendingRowIds().toSet()
        deleteAllSynced()
        upsertAll(serverRowsToApply(completions, pending))
    }
}

/**
 * Server rows an online refresh may write: none whose row still has an op in
 * the offline queue. The local state of such a row is the newer one -- an
 * INSERT the server has not received yet, an UPDATE it still has the old
 * values of, a DELETE it still has the row for. Until the op is pushed, the
 * server copy would erase the completion, revert the edit, or bring the
 * deleted row back. Prod, 2026-10-15: a completion for another member
 * showed its celebration, then was missing from every history until a
 * later refresh, after /sync had pushed it.
 */
internal fun serverRowsToApply(
    server: List<CompletionEntity>,
    pendingRowIds: Set<String>,
): List<CompletionEntity> = server.filter { it.id !in pendingRowIds }
