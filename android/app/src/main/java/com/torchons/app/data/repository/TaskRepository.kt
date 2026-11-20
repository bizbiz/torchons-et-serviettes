package com.torchons.app.data.repository

import com.google.gson.Gson
import com.torchons.app.core.sync.SyncTrigger
import com.torchons.app.data.api.ApiHttpException
import com.torchons.app.core.sync.opsToDiscard
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.dao.CompletionDao
import com.torchons.app.data.local.dao.PendingOperationDao
import com.torchons.app.data.local.dao.ContextDao
import com.torchons.app.data.local.dao.TaskDao
import com.torchons.app.data.local.dao.TaskHistoryDao
import com.torchons.app.data.model.FormerMember
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.local.entities.PendingOperationEntity
import com.torchons.app.data.local.entities.toDomain
import com.torchons.app.data.local.entities.toEntity
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
    private val members: MemberRepository,
    private val pendingDao: PendingOperationDao,
    private val taskHistoryDao: TaskHistoryDao,
    private val contextDao: ContextDao,
    private val syncTrigger: SyncTrigger,
    private val tokenManager: TokenManager,
    private val completionWriter: CompletionWriter,
    private val collectiveWriter: CollectiveCompletionWriter,
    private val refreshGuard: CompletionRefreshGuard,
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
        // deleteAll, not replaceAll: replaceAll keeps rows with a pending op,
        // and nothing of the previous household may stay visible.
        completionDao.deleteAll()
        // task_history via DAO (clearForTask n'existe que per-task, donc on
        // passe par deleteAll). Les autres caches (users, rooms) sont aussi wipes.
        contextDao.replaceAll(emptyList())
        members.wipe()
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

    fun observeUsers(): Flow<List<User>> = members.observeUsers()

    fun observeFormerMembers(): Flow<List<FormerMember>> = members.observeFormerMembers()

    suspend fun refreshUsers(): Boolean = members.refreshUsers()

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
            fetchAndApplyCompletions(userId, taskId, effectiveLimit) != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * The one place a completions answer reaches Room, for [refreshCompletions] (task detail, task
     * list, widget, settings) and [getCompletions]: the request outside the guard's lock, the
     * replace inside it, and an answer older than a push dropped (see CompletionRefreshGuard).
     * Throws on failure; null when pushes kept landing and nothing was applied.
     */
    private suspend fun fetchAndApplyCompletions(
        userId: String?,
        taskId: String?,
        limit: Int?,
    ): List<Completion>? = refreshGuard.fetchThenApply(
        fetch = {
            val response = apiService.getCompletions(userId, taskId, limit)
            if (!response.isSuccessful) throw ApiHttpException.from(response)
            response.body() ?: emptyList()
        },
        apply = { completions ->
            // En ligne = serveur autoritaire : REMPLACE le scope (full ou par tache) pour propager
            // suppressions/edits serveur ; upsert-only seulement pour le scope userId.
            // Cf docs/cache-conventions.md.
            when {
                taskId == null && userId == null ->
                    completionDao.replaceAll(completions.map { it.toEntity() })
                taskId != null ->
                    completionDao.replaceForTask(taskId, completions.map { it.toEntity() })
                else ->
                    completionDao.upsertAll(completions.map { it.toEntity() })
            }
        },
    )

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
    ): Result<Completion> =
        // Duplicate check + optimistic row + queued op in one transaction, in the application's
        // scope: see CompletionLedger. Fails with DuplicateCompletionException on a duplicate.
        completionWriter.complete(taskId, completedAt, completedBy, notes)

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
     * Completion "fait ensemble" : optimiste-avec-reconciliation, en ligne. Le detail, et pourquoi
     * elle tourne dans le scope applicatif, est dans [CollectiveCompletionLedger].
     */
    suspend fun completeTaskCollective(
        taskId: String,
        participantUserIds: List<String>,
        completedAt: String? = null,
        notes: String? = null,
    ): Result<CollectiveCompletionResponse> =
        collectiveWriter.complete(taskId, participantUserIds, completedAt, notes)

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

    /**
     * Drops a queued op the user gave up on (sync dialog), with the ops that
     * depend on it ([opsToDiscard]), and undoes its optimistic local write:
     * a dropped INSERT leaves a row the server will never have, so it is
     * deleted; a dropped UPDATE or DELETE left the server row as it was, so
     * a refresh brings it back (the row is no longer pending, the replace
     * takes it).
     */
    suspend fun discardPendingOp(clientOpId: String) {
        val queue = pendingDao.getAll()
        val target = queue.firstOrNull { it.clientOpId == clientOpId } ?: return
        val dropped = opsToDiscard(queue, target)
        dropped.forEach { pendingDao.delete(it.clientOpId) }
        for (op in dropped.filter { it.operation == "INSERT" }) {
            when (op.tableName) {
                "tasks" -> taskDao.deleteById(op.rowId)
                "task_completions" -> completionDao.deleteById(op.rowId)
            }
        }
        if (dropped.any { it.tableName == "tasks" && it.operation != "INSERT" }) refreshTasks()
        if (dropped.any { it.tableName == "task_completions" && it.operation != "INSERT" }) refreshCompletions()
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

    suspend fun getCompletions(
        userId: String? = null,
        taskId: String? = null,
        limit: Int? = null,
    ): Result<List<Completion>> {
        return try {
            fetchAndApplyCompletions(userId, taskId, limit)
                ?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("completions answer outdated by a push"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

}
