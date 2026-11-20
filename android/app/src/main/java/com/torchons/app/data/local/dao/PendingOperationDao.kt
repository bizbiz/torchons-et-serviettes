package com.torchons.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.torchons.app.data.local.entities.PendingOperationEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO pour la queue d'operations offline en attente de sync.
 */
@Dao
interface PendingOperationDao {

    @Query("SELECT * FROM pending_operations ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<PendingOperationEntity>>

    @Query("SELECT COUNT(*) FROM pending_operations")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM pending_operations ORDER BY createdAt ASC")
    suspend fun getAll(): List<PendingOperationEntity>

    /** Row ids the queue still has to push for [table] (a prompt answer, for instance, is keyed by prompt). */
    @Query("SELECT rowId FROM pending_operations WHERE tableName = :table")
    suspend fun rowIdsOf(table: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(op: PendingOperationEntity)

    @Query("DELETE FROM pending_operations WHERE clientOpId = :clientOpId")
    suspend fun delete(clientOpId: String)

    @Query("UPDATE pending_operations SET attemptCount = attemptCount + 1, lastError = :error WHERE clientOpId = :clientOpId")
    suspend fun incrementAttempt(clientOpId: String, error: String)
}
