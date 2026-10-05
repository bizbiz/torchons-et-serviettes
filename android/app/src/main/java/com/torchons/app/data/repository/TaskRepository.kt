package com.torchons.app.data.repository

import com.google.gson.Gson
import com.torchons.app.core.sync.SyncTrigger
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.dao.CompletionDao
import com.torchons.app.data.local.dao.PendingOperationDao
import com.torchons.app.data.local.dao.ContextDao
import com.torchons.app.data.local.dao.TaskDao
import com.torchons.app.data.local.dao.TaskHistoryDao
import com.torchons.app.data.local.dao.UserDao
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.local.entities.PendingOperationEntity
import com.torchons.app.data.local.entities.toDomain
import com.torchons.app.data.local.entities.toEntity
import com.torchons.app.data.model.CollectiveCompleteRequest
import com.torchons.app.data.model.CollectiveCompletionResponse
import com.torchons.app.data.model.Completion
import com.torchons.app.data.model.CreateTaskRequest
import com.torchons.app.data.model.FrequencyRequest
import com.torchons.app.data.model.ContextView
import com.torchons.app.data.model.TaskContextRef
import com.torchons.app.data.model.Task
import com.torchons.app.data.model.TaskHistoryEvent
import com.torchons.app.data.model.UpdateTaskRequest
import com.torchons.app.data.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
    private val completionDao: CompletionDao,
    private val userDao: UserDao,
    private val pendingDao: PendingOperationDao,
    private val taskHistoryDao: TaskHistoryDao,
    private val contextDao: ContextDao,
    private val syncTrigger: SyncTrigger,
    private val tokenManager: TokenManager,
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
        completionDao.replaceAll(emptyList())
        // task_history via DAO (clearForTask n'existe que per-task, donc on
        // passe par deleteAll). Les autres caches (users, rooms) sont aussi wipes.
        contextDao.replaceAll(emptyList())
        userDao.replaceAll(emptyList())
    }

    // -------------------------------------------------------------------
    // Rooms (pieces)
    // -------------------------------------------------------------------

    // -------------------------------------------------------------------
    // Contextes
    // -------------------------------------------------------------------

    fun observeContexts(): Flow<List<ContextView>> =
        contextDao.observeAll().map { list -> list.map { it.toDomain() } }

    /**
     * Recharge les contextes du foyer.
     *
     * REPLACE et non upsert : un contexte supprime depuis un autre appareil
     * doit disparaitre d'ici. Un refresh upsert-only le laisserait en cache
     * pour toujours, parce que rien ne dit jamais a ce client qu'une ligne a
     * disparu. Cf `docs/cache-conventions.md`.
     */
    suspend fun refreshContexts(): Boolean {
        return try {
            val response = apiService.getContexts()
            if (response.isSuccessful) {
                contextDao.replaceAll(response.body().orEmpty().map { it.toEntity() })
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
                // En ligne = serveur autoritaire : REMPLACE le scope (full
                // ou par tache) pour propager suppressions/edits serveur ;
                // upsert-only seulement pour le scope userId. Cf
                // docs/cache-conventions.md.
                when {
                    taskId == null && userId == null ->
                        completionDao.replaceAll(completions.map { it.toEntity() })
                    taskId != null ->
                        completionDao.replaceForTask(taskId, completions.map { it.toEntity() })
                    else ->
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

    /**
     * Traduit des ids de contexte en references affichables, depuis le CACHE.
     *
     * L'etat derive doit etre recalcule a chaque mutation sans reseau : sans
     * ca, une tache creee hors ligne s'afficherait sans contexte jusqu'a la
     * prochaine synchronisation. Cf `docs/cache-conventions.md`.
     */
    private suspend fun resolveContextRefs(ids: List<String>?): List<TaskContextRef> {
        if (ids.isNullOrEmpty()) return emptyList()
        val connus = contextDao.getAll().associateBy { it.id }
        return ids.mapIndexedNotNull { position, id ->
            connus[id]?.let {
                TaskContextRef(
                    id = it.id, name = it.name, color = it.color,
                    emoji = it.emoji, position = position,
                )
            }
        }
    }

    suspend fun createTask(request: CreateTaskRequest): Result<Task> {
        val clientOpId = UUID.randomUUID().toString()
        val rowId = UUID.randomUUID().toString()
        val now = Instant.now().toString()

        val optimistic = Task(
            id = rowId,
            title = request.title,
            description = request.description,
            assignedToIds = request.assignedToIds,
            recurrenceRule = request.recurrenceRule,
            weight = request.weight,
            tolerance = request.tolerance,
            isActive = true,
            sortOrder = 0,
            createdAt = now,
            updatedAt = now,
            contexts = resolveContextRefs(request.contextIds),
        )

        taskDao.upsert(optimistic.toEntity())

        val payload = mapOf(
            "title" to request.title,
            "description" to request.description,
            "assigned_to_ids" to request.assignedToIds,
            "recurrence_rule" to request.recurrenceRule,
            "weight" to request.weight,
            "tolerance" to request.tolerance,
            "context_ids" to request.contextIds,
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
            assignedToIds = request.assignedToIds ?: existing.assignedToIds,
            recurrenceRule = request.recurrenceRule ?: existing.recurrenceRule,
            weight = request.weight ?: existing.weight,
            tolerance = request.tolerance ?: existing.tolerance,
            isActive = request.isActive ?: existing.isActive,
            updatedAt = Instant.now().toString(),
            contexts = if (request.contextIds != null)
                resolveContextRefs(request.contextIds) else existing.contexts,
        )

        if (updated != null) {
            taskDao.upsert(updated.toEntity())
        }

        val payload = mutableMapOf<String, Any?>()
        request.title?.let { payload["title"] = it }
        request.description?.let { payload["description"] = it }
        request.assignedToIds?.let { payload["assigned_to_ids"] = it }
        request.recurrenceRule?.let { payload["recurrence_rule"] = it }
        request.weight?.let { payload["weight"] = it }
        request.tolerance?.let { payload["tolerance"] = it }
        request.isActive?.let { payload["is_active"] = it }
        // `?.let` et non `takeIf { it.isNotEmpty() }` : une liste VIDE est une
        // instruction — « detache tous les contextes » — et doit partir telle
        // quelle. Seul `null` signifie « ne touche pas ».
        request.contextIds?.let { payload["context_ids"] = it }

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

        // L'ecriture optimiste doit MIMER le resultat serveur : sans
        // completedBy explicite, le backend attribue la completion au user du
        // JWT (cf sync.py _handle_completion : data.get("completed_by",
        // user_id)). On resout donc l'id du user courant ici, sinon la row
        // locale a completedBy="" et l'historique affiche "?" jusqu'au prochain
        // refresh. Cf docs/cache-conventions.md (etat derive juste sans reseau).
        val effectiveCompletedBy = completedBy ?: tokenManager.userIdFlow.first()

        val optimistic = Completion(
            id = rowId,
            taskId = taskId,
            completedBy = effectiveCompletedBy ?: "",
            completedAt = now,
            pointsEarned = 1,
            notes = notes,
        )

        completionDao.upsertAll(listOf(optimistic.toEntity()))

        val payload = mapOf<String, Any?>(
            "task_id" to taskId,
            "completed_by" to effectiveCompletedBy,
            "completed_at" to completedAt,
            "notes" to notes,
        )
        enqueue(clientOpId, "task_completions", "INSERT", rowId, payload)

        return Result.success(optimistic)
    }

    /**
     * Supprime une completion en offline-first : delete local OPTIMISTE
     * immediat (l'urgence de la tache se recalcule aussitot depuis le cache
     * — offline = on fait confiance au front) + enqueue d'une op DELETE
     * (le serveur reconcilie quand en ligne ; SyncWorker propage aussi les
     * DELETE serveur via change_log). Remplace l'ancien chemin
     * apiService.deleteCompletion (REST online-only, sans delete local, qui
     * laissait une completion fantome -> lastCompletedAt perime).
     * Cf docs/cache-conventions.md.
     */
    suspend fun deleteCompletion(completionId: String): Result<Unit> {
        val clientOpId = UUID.randomUUID().toString()
        completionDao.deleteById(completionId)
        enqueue(clientOpId, "task_completions", "DELETE", completionId, null)
        return Result.success(Unit)
    }

    /**
     * Supprime un evenement collectif entier en offline-first : delete local
     * OPTIMISTE de chaque ligne + une op DELETE enqueue par ligne (meme chemin
     * que [deleteCompletion] : le backend _handle_completion DELETE soft-delete
     * chaque row, le SyncWorker propage). Remplace l'ancien chemin online-only
     * apiService.deleteCollectiveCompletion + refresh immediat (anti-pattern :
     * casse l'offline et le refresh re-ajoutait les lignes fantomes tant que
     * l'op n'etait pas poussee). Cf docs/cache-conventions.md.
     */
    suspend fun deleteCollectiveCompletion(rowIds: List<String>): Result<Unit> {
        for (id in rowIds) {
            completionDao.deleteById(id)
            enqueue(UUID.randomUUID().toString(), "task_completions", "DELETE", id, null)
        }
        return Result.success(Unit)
    }

    /**
     * Edite une completion (qui / quand) en offline-first : upsert local
     * OPTIMISTE immediat (l'historique + l'urgence se recalculent aussitot)
     * + enqueue d'une op UPDATE (le backend _handle_completion UPDATE applique
     * completed_by/completed_at, le SyncWorker propage le change_log). Pas de
     * refresh immediat : l'op est en file, le serveur a encore l'ancienne
     * valeur, un refetch ecraserait l'optimiste. Cf docs/cache-conventions.md.
     */
    suspend fun updateCompletion(
        completionId: String,
        completedBy: String,
        completedAt: String,
    ): Result<Unit> {
        val existing = completionDao.getById(completionId)
            ?: return Result.failure(IllegalStateException("Completion introuvable en cache"))
        completionDao.upsertAll(
            listOf(existing.copy(completedBy = completedBy, completedAt = completedAt))
        )
        enqueue(
            UUID.randomUUID().toString(),
            "task_completions",
            "UPDATE",
            completionId,
            mapOf("completed_by" to completedBy, "completed_at" to completedAt),
        )
        return Result.success(Unit)
    }

    /**
     * Completion "fait ensemble" : optimiste-avec-reconciliation.
     *
     * 1. Insere immediatement N lignes locales (collective_id client, points
     *    estimes) -> l'historique s'affiche sans attendre le reseau.
     * 2. Appelle l'endpoint dedie (online-first : le split des points en
     *    ceil + l'anti-doublon sont atomiques cote serveur ; on ne peut PAS
     *    rejouer ca via N INSERT /sync — chaque INSERT recalculerait les
     *    points pleins, pas divises).
     * 3. Succes -> on retire l'optimiste et on insere les lignes serveur
     *    autoritaires (vrais ids + points + reason).
     * 4. Echec (409 anti-doublon / reseau) -> rollback de l'optimiste : le
     *    backend gagne, rien n'a ete persiste donc l'UI ne doit pas mentir.
     *
     * Cf docs/cache-conventions.md (offline = on fait confiance au front ;
     * online = serveur autoritaire ; ici la persistance est online-only par
     * contrainte metier, mais le retour visuel reste immediat).
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

        val clientCollectiveId = UUID.randomUUID().toString()
        val now = completedAt ?: Instant.now().toString()
        val optimisticRows = participantUserIds.map { uid ->
            Completion(
                id = UUID.randomUUID().toString(),
                taskId = taskId,
                completedBy = uid,
                completedAt = now,
                pointsEarned = 1,
                notes = notes,
                collectiveId = clientCollectiveId,
            )
        }
        completionDao.upsertAll(optimisticRows.map { it.toEntity() })

        suspend fun rollback() {
            optimisticRows.forEach { completionDao.deleteById(it.id) }
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
                if (body == null) {
                    rollback()
                    return Result.failure(IllegalStateException("Reponse vide"))
                }
                // Reconcilie : retire l'optimiste, insere les rows serveur.
                rollback()
                completionDao.upsertAll(body.completions.map { it.toEntity() })
                Result.success(body)
            } else {
                rollback()
                val code = resp.code()
                val errorBody = resp.errorBody()?.string().orEmpty()
                Result.failure(CollectiveCompletionException(code, errorBody))
            }
        } catch (e: Exception) {
            rollback()
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
    suspend fun voteFrequency(taskId: String, request: FrequencyRequest): Result<Unit> {
        return try {
            val response = apiService.setFrequency(taskId, request)
            if (response.isSuccessful) {
                refreshTaskHistory(taskId)
                Result.success(Unit)
            } else {
                enqueueFrequencyVote(taskId, request)
                Result.success(Unit)
            }
        } catch (_: Exception) {
            enqueueFrequencyVote(taskId, request)
            Result.success(Unit)
        }
    }

    /** Helper raccourci interval (mode "Régulière" — 12 mois identiques). */
    suspend fun voteFrequency(taskId: String, everyNDays: Int): Result<Unit> =
        voteFrequency(taskId, FrequencyRequest(everyNDays = everyNDays))

    private suspend fun enqueueFrequencyVote(taskId: String, request: FrequencyRequest) {
        val clientOpId = UUID.randomUUID().toString()
        val payload: MutableMap<String, Any> = mutableMapOf()
        request.everyNDays?.let { payload["every_n_days"] = it }
        request.daysPerMonth?.let { payload["days_per_month"] = it }
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
                foyerId = tokenManager.foyerIdFlow.first(),
            )
        )
        syncTrigger.requestSync()
    }

    // -------------------------------------------------------------------
    // Legacy one-shot (gardes pour compat)
    // -------------------------------------------------------------------

    suspend fun getTasks(
        contextId: String? = null,
        activeOnly: Boolean? = true,
    ): Result<List<Task>> {
        return try {
            val response = apiService.getTasks(contextId, activeOnly)
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

    suspend fun getCompletions(
        userId: String? = null,
        taskId: String? = null,
        limit: Int? = null,
    ): Result<List<Completion>> {
        return try {
            val response = apiService.getCompletions(userId, taskId, limit)
            if (response.isSuccessful) {
                val completions = response.body() ?: emptyList()
                when {
                    taskId == null && userId == null ->
                        completionDao.replaceAll(completions.map { it.toEntity() })
                    taskId != null ->
                        completionDao.replaceForTask(taskId, completions.map { it.toEntity() })
                    else ->
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
