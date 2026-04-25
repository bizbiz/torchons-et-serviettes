package com.torchons.app.data.repository

import com.google.gson.Gson
import com.torchons.app.core.sync.SyncTrigger
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.dao.CategoryDao
import com.torchons.app.data.local.dao.CompletionDao
import com.torchons.app.data.local.dao.PendingOperationDao
import com.torchons.app.data.local.dao.RoomDao
import com.torchons.app.data.local.dao.TaskDao
import com.torchons.app.data.local.dao.TaskHistoryDao
import com.torchons.app.data.local.dao.UserDao
import com.torchons.app.data.local.entities.PendingOperationEntity
import com.torchons.app.data.local.entities.toDomain
import com.torchons.app.data.local.entities.toEntity
import com.torchons.app.data.model.Category
import com.torchons.app.data.model.CollectiveCompleteRequest
import com.torchons.app.data.model.CollectiveCompletionResponse
import com.torchons.app.data.model.Completion
import com.torchons.app.data.model.CreateTaskRequest
import com.torchons.app.data.model.FrequencyRequest
import com.torchons.app.data.model.Room
import com.torchons.app.data.model.Task
import com.torchons.app.data.model.TaskHistoryEvent
import com.torchons.app.data.model.UpdateTaskRequest
import com.torchons.app.data.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository des taches — stale-while-revalidate + ecritures offline.
 *
 * Lectures : observe* retourne un Flow Room, refresh* fetch l'API et upsert.
 * Ecritures : write-local-first. L'operation est ecrite dans Room immediatement
 * (UI optimiste), enqueue dans pending_operations, et le SyncWorker la pousse
 * vers le backend quand la connexion est disponible.
 */
@Singleton
class TaskRepository @Inject constructor(
    private val apiService: ApiService,
    private val taskDao: TaskDao,
    private val categoryDao: CategoryDao,
    private val completionDao: CompletionDao,
    private val userDao: UserDao,
    private val pendingDao: PendingOperationDao,
    private val taskHistoryDao: TaskHistoryDao,
    private val roomDao: RoomDao,
    private val syncTrigger: SyncTrigger,
) {
    private val gson = Gson()

    // -------------------------------------------------------------------
    // Tasks — observe / refresh
    // -------------------------------------------------------------------

    fun observeActiveTasks(): Flow<List<Task>> =
        taskDao.observeActive().map { list -> list.map { it.toDomain() } }

    fun observeTaskById(id: String): Flow<Task?> =
        taskDao.observeById(id).map { it?.toDomain() }

    /** Lookup one-shot dans le cache Room. Pour le pre-remplissage du clone. */
    suspend fun getCachedTaskById(id: String): Task? =
        taskDao.getById(id)?.toDomain()

    /**
     * Pool de taches candidates a la duplication via autocomplete.
     * Inclut actives + archivees, exclut les inactives. Filtrage par titre
     * fait cote ViewModel (normalize + tokenize) pour gerer les accents.
     */
    fun observeTasksForSuggestions(): Flow<List<Task>> =
        taskDao.observeForSuggestions().map { list -> list.map { it.toDomain() } }

    suspend fun refreshTasks(): Boolean {
        return try {
            val response = apiService.getTasks(activeOnly = null)
            if (response.isSuccessful) {
                val tasks = response.body() ?: emptyList()
                taskDao.replaceAll(tasks.map { it.toEntity() })
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    // -------------------------------------------------------------------
    // Categories
    // -------------------------------------------------------------------

    fun observeCategories(): Flow<List<Category>> =
        categoryDao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun refreshCategories(): Boolean {
        return try {
            val response = apiService.getCategories()
            if (response.isSuccessful) {
                val categories = response.body() ?: emptyList()
                categoryDao.replaceAll(categories.map { it.toEntity() })
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    // -------------------------------------------------------------------
    // Cache wipe (multi-foyer switch)
    // -------------------------------------------------------------------

    /**
     * Vide l'ensemble du cache Room local. Utilise quand l'user switche de
     * foyer — les donnees du foyer precedent ne doivent pas rester visibles.
     *
     * Ne touche PAS aux pending_operations (on garde les ecritures offline
     * en attente d'etre sync, elles seront rattachees au bon foyer via leur
     * JWT au prochain sync).
     */
    suspend fun wipeLocalCache() {
        taskDao.replaceAll(emptyList())
        categoryDao.replaceAll(emptyList())
        completionDao.replaceAll(emptyList())
        // task_history via DAO (clearForTask n'existe que per-task, donc on
        // passe par deleteAll). Les autres caches (users, rooms) sont aussi wipes.
        roomDao.replaceAll(emptyList())
        userDao.replaceAll(emptyList())
    }

    // -------------------------------------------------------------------
    // Rooms (pieces)
    // -------------------------------------------------------------------

    fun observeRooms(): Flow<List<Room>> =
        roomDao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun refreshRooms(): Boolean {
        return try {
            val response = apiService.getRooms()
            if (response.isSuccessful) {
                val rooms = response.body() ?: emptyList()
                roomDao.replaceAll(rooms.map { it.toEntity() })
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    // -------------------------------------------------------------------
    // Users
    // -------------------------------------------------------------------

    fun observeUsers(): Flow<List<User>> =
        userDao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun refreshUsers(): Boolean {
        return try {
            val response = apiService.getUsers()
            if (response.isSuccessful) {
                val users = response.body() ?: emptyList()
                userDao.replaceAll(users.map { it.toEntity() })
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    // -------------------------------------------------------------------
    // Completions
    // -------------------------------------------------------------------

    fun observeRecentCompletions(limit: Int = 50): Flow<List<Completion>> =
        completionDao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    fun observeCompletionsByTask(taskId: String): Flow<List<Completion>> =
        completionDao.observeByTask(taskId).map { list -> list.map { it.toDomain() } }

    suspend fun refreshCompletions(
        userId: String? = null,
        taskId: String? = null,
        limit: Int? = null,
    ): Boolean {
        // Full refresh (pas de filtre) + pas de limit explicite : on force 2000
        // au lieu de laisser partir une requete sans limit (le backend defaulte
        // a 50). Sans ca, le replaceAll ci-dessous wipe toute completion plus
        // ancienne que le 50e rang global, et les taches a long intervalle
        // (coiffeur/45j, etc.) retombent sur createdAt dans le calcul due_date
        // et s'affichent faussement "en retard" apres chaque refresh.
        val effectiveLimit = limit ?: if (taskId == null && userId == null) 2000 else null
        return try {
            val response = apiService.getCompletions(userId, taskId, effectiveLimit)
            if (response.isSuccessful) {
                val completions = response.body() ?: emptyList()
                if (taskId == null && userId == null) {
                    completionDao.replaceAll(completions.map { it.toEntity() })
                } else {
                    completionDao.upsertAll(completions.map { it.toEntity() })
                }
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    // -------------------------------------------------------------------
    // Ecritures offline-first
    // -------------------------------------------------------------------

    suspend fun createTask(request: CreateTaskRequest): Result<Task> {
        val clientOpId = UUID.randomUUID().toString()
        val rowId = UUID.randomUUID().toString()
        val now = Instant.now().toString()

        val optimistic = Task(
            id = rowId,
            title = request.title,
            description = request.description,
            roomId = request.roomId,
            categoryId = request.categoryId,
            assignedToIds = request.assignedToIds,
            recurrenceRule = request.recurrenceRule,
            weight = request.weight,
            tolerance = request.tolerance,
            isActive = true,
            sortOrder = 0,
            createdAt = now,
            updatedAt = now,
        )

        taskDao.upsert(optimistic.toEntity())

        val payload = mapOf(
            "title" to request.title,
            "description" to request.description,
            "room_id" to request.roomId,
            "category_id" to request.categoryId,
            "assigned_to_ids" to request.assignedToIds,
            "recurrence_rule" to request.recurrenceRule,
            "weight" to request.weight,
            "tolerance" to request.tolerance,
        )
        enqueue(clientOpId, "tasks", "INSERT", rowId, payload)

        return Result.success(optimistic)
    }

    suspend fun updateTask(taskId: String, request: UpdateTaskRequest): Result<Task> {
        val clientOpId = UUID.randomUUID().toString()

        val existing = taskDao.getById(taskId)?.toDomain()
        val updated = existing?.copy(
            title = request.title ?: existing.title,
            description = request.description ?: existing.description,
            roomId = request.roomId ?: existing.roomId,
            categoryId = request.categoryId ?: existing.categoryId,
            assignedToIds = request.assignedToIds ?: existing.assignedToIds,
            recurrenceRule = request.recurrenceRule ?: existing.recurrenceRule,
            weight = request.weight ?: existing.weight,
            tolerance = request.tolerance ?: existing.tolerance,
            isActive = request.isActive ?: existing.isActive,
            updatedAt = Instant.now().toString(),
        )

        if (updated != null) {
            taskDao.upsert(updated.toEntity())
        }

        val payload = mutableMapOf<String, Any?>()
        request.title?.let { payload["title"] = it }
        request.description?.let { payload["description"] = it }
        request.roomId?.let { payload["room_id"] = it }
        request.categoryId?.let { payload["category_id"] = it }
        request.assignedToIds?.let { payload["assigned_to_ids"] = it }
        request.recurrenceRule?.let { payload["recurrence_rule"] = it }
        request.weight?.let { payload["weight"] = it }
        request.tolerance?.let { payload["tolerance"] = it }
        request.isActive?.let { payload["is_active"] = it }

        enqueue(clientOpId, "tasks", "UPDATE", taskId, payload)

        return Result.success(updated ?: existing ?: Task(id = taskId, title = ""))
    }

    suspend fun deleteTask(taskId: String): Result<Unit> {
        val clientOpId = UUID.randomUUID().toString()

        taskDao.deleteById(taskId)
        enqueue(clientOpId, "tasks", "DELETE", taskId, null)

        return Result.success(Unit)
    }

    suspend fun completeTask(
        taskId: String,
        completedAt: String? = null,
        completedBy: String? = null,
        notes: String? = null,
    ): Result<Completion> {
        val clientOpId = UUID.randomUUID().toString()
        val rowId = UUID.randomUUID().toString()
        val now = completedAt ?: Instant.now().toString()

        val optimistic = Completion(
            id = rowId,
            taskId = taskId,
            completedBy = completedBy ?: "",
            completedAt = now,
            pointsEarned = 1,
            notes = notes,
        )

        completionDao.upsertAll(listOf(optimistic.toEntity()))

        val payload = mapOf<String, Any?>(
            "task_id" to taskId,
            "completed_by" to completedBy,
            "completed_at" to completedAt,
            "notes" to notes,
        )
        enqueue(clientOpId, "task_completions", "INSERT", rowId, payload)

        return Result.success(optimistic)
    }

    /**
     * Completion "fait ensemble" : cree N lignes cote serveur via l'endpoint
     * dedie (calcul des points + anti-doublon atomique cote backend). En cas
     * d'erreur reseau ou de conflit 409, retourne Result.failure et n'ecrit
     * rien localement. Online-first, pas d'offline queue v1.
     */
    suspend fun completeTaskCollective(
        taskId: String,
        participantUserIds: List<String>,
        completedAt: String? = null,
        notes: String? = null,
    ): Result<CollectiveCompletionResponse> {
        if (participantUserIds.isEmpty()) {
            return Result.failure(IllegalArgumentException("Aucun participant"))
        }
        return try {
            val resp = apiService.completeTaskCollective(
                taskId = taskId,
                request = CollectiveCompleteRequest(
                    participantUserIds = participantUserIds,
                    completedAt = completedAt,
                    notes = notes,
                ),
            )
            if (resp.isSuccessful) {
                val body = resp.body()
                    ?: return Result.failure(IllegalStateException("Reponse vide"))
                // Insere les N rows en local pour affichage immediat.
                completionDao.upsertAll(body.completions.map { it.toEntity() })
                Result.success(body)
            } else {
                val code = resp.code()
                val errorBody = resp.errorBody()?.string().orEmpty()
                Result.failure(CollectiveCompletionException(code, errorBody))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    class CollectiveCompletionException(
        val httpCode: Int,
        val errorBody: String,
    ) : Exception("HTTP $httpCode: $errorBody")

    // -------------------------------------------------------------------
    // Historique de modification — observe / refresh / vote frequence
    // -------------------------------------------------------------------

    fun observeTaskHistory(taskId: String): Flow<List<TaskHistoryEvent>> =
        taskHistoryDao.observeByTask(taskId).map { list -> list.map { it.toDomain() } }

    suspend fun refreshTaskHistory(taskId: String, limit: Int = 100): Boolean {
        return try {
            val response = apiService.getTaskHistory(taskId, limit)
            if (response.isSuccessful) {
                val dtos = response.body() ?: emptyList()
                val entities = dtos.map { it.toEntity(taskId) }
                taskHistoryDao.replaceForTask(taskId, entities)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Envoie un vote de frequence au backend. En cas d'echec reseau,
     * l'op est mise en file d'attente (SyncWorker appellera setFrequency
     * directement — la table task_frequency_votes n'est pas geree par /sync).
     */
    suspend fun voteFrequency(taskId: String, everyNDays: Int): Result<Unit> {
        return try {
            val response = apiService.setFrequency(taskId, FrequencyRequest(everyNDays))
            if (response.isSuccessful) {
                refreshTaskHistory(taskId)
                Result.success(Unit)
            } else {
                enqueueFrequencyVote(taskId, everyNDays)
                Result.success(Unit)
            }
        } catch (_: Exception) {
            enqueueFrequencyVote(taskId, everyNDays)
            Result.success(Unit)
        }
    }

    private suspend fun enqueueFrequencyVote(taskId: String, everyNDays: Int) {
        val clientOpId = UUID.randomUUID().toString()
        val payload = mapOf("every_n_days" to everyNDays)
        enqueue(clientOpId, "task_frequency_votes", "VOTE", taskId, payload)
    }

    /**
     * Retourne l'etat des votes de frequence pour une tache (consensus,
     * my_vote, others). null si la tache n'est pas accessible ou si offline.
     * Sert a alimenter la fiche d'edition (champ grise) et le mini-dialog
     * "Modifier via le vote".
     */
    suspend fun getFrequencyVotes(taskId: String): com.torchons.app.data.model.FrequencyVotesState? {
        return try {
            val response = apiService.getFrequencyVotes(taskId)
            if (response.isSuccessful) response.body() else null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun enqueue(
        clientOpId: String,
        table: String,
        operation: String,
        rowId: String,
        payload: Any?,
    ) {
        pendingDao.insert(
            PendingOperationEntity(
                clientOpId = clientOpId,
                tableName = table,
                operation = operation,
                rowId = rowId,
                payloadJson = gson.toJson(payload),
                createdAt = System.currentTimeMillis(),
            )
        )
        syncTrigger.requestSync()
    }

    // -------------------------------------------------------------------
    // Legacy one-shot (gardes pour compat)
    // -------------------------------------------------------------------

    suspend fun getTasks(
        categoryId: String? = null,
        activeOnly: Boolean? = true,
    ): Result<List<Task>> {
        return try {
            val response = apiService.getTasks(categoryId, activeOnly)
            if (response.isSuccessful) {
                val tasks = response.body() ?: emptyList()
                taskDao.replaceAll(tasks.map { it.toEntity() })
                Result.success(tasks)
            } else {
                Result.failure(Exception("Erreur lors du chargement des taches: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getCategories(): Result<List<Category>> {
        return try {
            val response = apiService.getCategories()
            if (response.isSuccessful) {
                val categories = response.body() ?: emptyList()
                categoryDao.replaceAll(categories.map { it.toEntity() })
                Result.success(categories)
            } else {
                Result.failure(Exception("Erreur lors du chargement des categories: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getCompletions(
        userId: String? = null,
        taskId: String? = null,
        limit: Int? = null,
    ): Result<List<Completion>> {
        return try {
            val response = apiService.getCompletions(userId, taskId, limit)
            if (response.isSuccessful) {
                val completions = response.body() ?: emptyList()
                if (taskId == null && userId == null) {
                    completionDao.replaceAll(completions.map { it.toEntity() })
                } else {
                    completionDao.upsertAll(completions.map { it.toEntity() })
                }
                Result.success(completions)
            } else {
                Result.failure(Exception("Erreur lors du chargement des completions: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUsers(): Result<List<User>> {
        return try {
            val response = apiService.getUsers()
            if (response.isSuccessful) {
                val users = response.body() ?: emptyList()
                userDao.replaceAll(users.map { it.toEntity() })
                Result.success(users)
            } else {
                Result.failure(Exception("Erreur lors du chargement des utilisateurs: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
