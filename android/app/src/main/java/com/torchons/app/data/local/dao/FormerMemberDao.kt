package com.torchons.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.torchons.app.data.local.entities.FormerMemberEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FormerMemberDao {

    @Query("SELECT * FROM former_members")
    fun observeAll(): Flow<List<FormerMemberEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(members: List<FormerMemberEntity>)

    @Query("DELETE FROM former_members")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(members: List<FormerMemberEntity>) {
        deleteAll()
        upsertAll(members)
    }
}
