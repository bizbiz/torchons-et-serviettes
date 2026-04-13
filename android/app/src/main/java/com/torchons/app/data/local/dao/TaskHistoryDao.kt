package com.torchons.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.torchons.app.data.local.entities.TaskHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskHistoryDao {

    @Query(
        "SELECT * FROM task_history WHERE taskId = :taskId " +
            "ORDER BY COALESCE(at, '') DESC, id DESC"
    )
    fun observeByTask(taskId: String): Flow<List<TaskHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(events: List<TaskHistoryEntity>)

    @Query("DELETE FROM task_history WHERE taskId = :taskId")
    suspend fun clearForTask(taskId: String)

    /**
     * Remplace l'integralite de l'historique d'une tache — utilise par
     * refreshHistory pour garantir un etat coherent avec le serveur.
     */
    @Transaction
    suspend fun replaceForTask(taskId: String, events: List<TaskHistoryEntity>) {
        clearForTask(taskId)
        upsertAll(events)
    }
}
