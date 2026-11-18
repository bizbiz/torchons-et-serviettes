package com.torchons.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.torchons.app.data.local.entities.DelegationEntity
import kotlinx.coroutines.flow.Flow

/**
 * « À faire aujourd'hui » cache. Derived state without network (docs/cache-conventions.md):
 * a delegation whose task has a completion dated on or after the delegation's day (UTC) is
 * over -- the server settles it by the same rule (services/delegations.py) -- so the read
 * hides it at once, whoever completed the task and by whichever path (tick, dialog, together).
 */
@Dao
interface DelegationDao {

    @Query(
        "SELECT * FROM delegations d WHERE NOT EXISTS (" +
            "SELECT 1 FROM completions c WHERE c.taskId = d.taskId " +
            "AND substr(c.completedAt, 1, 10) >= substr(d.createdAt, 1, 10)) " +
            "ORDER BY d.createdAt"
    )
    fun observeActive(): Flow<List<DelegationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<DelegationEntity>)

    @Query("DELETE FROM delegations WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM delegations")
    suspend fun deleteAll()

    /** Rows the offline queue still has to push (a hand-over or its cancellation). */
    @Query("SELECT rowId FROM pending_operations WHERE tableName = 'task_delegations'")
    suspend fun pendingRowIds(): List<String>

    @Query(
        "DELETE FROM delegations WHERE id NOT IN " +
            "(SELECT rowId FROM pending_operations WHERE tableName = 'task_delegations')"
    )
    suspend fun deleteAllSynced()

    /**
     * Online refresh = REPLACE of the whole scope, except the rows a queued op still has to
     * push: the server does not have the hand-over yet, or still has the cancelled one.
     */
    @Transaction
    suspend fun replaceAll(rows: List<DelegationEntity>) {
        val pending = pendingRowIds().toSet()
        deleteAllSynced()
        upsertAll(rows.filter { it.id !in pending })
    }
}
