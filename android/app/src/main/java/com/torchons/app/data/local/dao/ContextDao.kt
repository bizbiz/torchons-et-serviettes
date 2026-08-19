package com.torchons.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.torchons.app.data.local.entities.ContextEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContextDao {

    @Query("SELECT * FROM contexts ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<ContextEntity>>

    @Query("SELECT * FROM contexts ORDER BY sortOrder, name")
    suspend fun getAll(): List<ContextEntity>

    @Query("SELECT * FROM contexts WHERE id = :id")
    suspend fun getById(id: String): ContextEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(contexts: List<ContextEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(context: ContextEntity)

    @Query("DELETE FROM contexts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM contexts")
    suspend fun deleteAllContexts()

    /**
     * Replaces the whole cached scope, never upsert-only.
     *
     * An upsert-only refresh leaves ghost rows: a context deleted on another
     * device stays in the local cache forever, because nothing ever tells this
     * client that a row disappeared. Cf `docs/cache-conventions.md`.
     */
    @Transaction
    suspend fun replaceAll(contexts: List<ContextEntity>) {
        deleteAllContexts()
        upsertAll(contexts)
    }

}
