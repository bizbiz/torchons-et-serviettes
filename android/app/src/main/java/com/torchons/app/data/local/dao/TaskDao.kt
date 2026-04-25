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

    // v0.20 : les taches archivees (ponctuelles completees >7j) sont filtrees
    // pour ne pas polluer la liste principale. Le drapeau isArchived est
    // pilote exclusivement par le serveur via change_log — le client ne
    // l'ecrit jamais lui-meme.
    @Query("SELECT * FROM tasks WHERE isActive = 1 AND isArchived = 0 ORDER BY sortOrder, title")
    fun observeActive(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE isArchived = 0 ORDER BY sortOrder, title")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<TaskEntity?>

    @Query("SELECT * FROM tasks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TaskEntity?

    // Pool pour l'autocomplete de duplication (TaskEditScreen v0.21).
    // Inclut les taches archivees (ponctuelles completees >7j) car cloner une
    // ponctuelle passee est un cas d'usage legitime ; exclut les inactives
    // que l'user a explicitement desactivees.
    @Query("SELECT * FROM tasks WHERE isActive = 1 OR isArchived = 1 ORDER BY title")
    fun observeForSuggestions(): Flow<List<TaskEntity>>

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
