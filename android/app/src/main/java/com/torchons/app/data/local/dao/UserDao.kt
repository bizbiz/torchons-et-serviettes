package com.torchons.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.torchons.app.data.local.entities.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {

    @Query("SELECT * FROM users ORDER BY displayName")
    fun observeAll(): Flow<List<UserEntity>>

    @Query("SELECT * FROM users")
    suspend fun getAll(): List<UserEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(users: List<UserEntity>)

    @Query("DELETE FROM users")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(users: List<UserEntity>) {
        deleteAll()
        upsertAll(users)
    }
}
