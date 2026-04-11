package com.torchons.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.torchons.app.data.local.entities.TaskEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO pour les taches mises en cache localement.
 *
 * Les ViewModels observent observeAll/observeById pour avoir un affichage
 * instantane depuis le cache, pendant que le repository declenche un refresh
 * en background qui upsert les nouvelles donnees.
 */
@Dao
interface TaskDao {

    @Query("SELECT * FROM tasks WHERE isActive = 1 ORDER BY sortOrder, title")
    fun observeActive(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks ORDER BY sortOrder, title")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tasks: List<TaskEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM tasks")
    suspend fun deleteAll()

    /**
     * Remplace integralement le contenu de la table par la liste fournie.
     * Utile apres un GET /api/tasks pour purger les taches qui n'existent
     * plus cote serveur (suppression non encore observee localement).
     */
    @Transaction
    suspend fun replaceAll(tasks: List<TaskEntity>) {
        deleteAll()
        upsertAll(tasks)
    }
}
