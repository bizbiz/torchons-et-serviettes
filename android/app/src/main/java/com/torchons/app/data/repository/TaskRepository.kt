package com.torchons.app.data.repository

import com.torchons.app.data.api.ApiHttpException
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.dao.CompletionDao
import com.torchons.app.data.local.dao.ContextDao
import com.torchons.app.data.local.dao.TaskDao
import com.torchons.app.data.local.dao.TaskHistoryDao
import com.torchons.app.data.model.FormerMember
import com.torchons.app.data.local.entities.toDomain
import com.torchons.app.data.local.entities.toEntity
import com.torchons.app.data.model.CollectiveCompletionResponse
import com.torchons.app.data.model.Completion
import com.torchons.app.data.model.CreateTaskRequest
import com.torchons.app.data.model.FrequencyRequest
import com.torchons.app.data.model.ContextView
import com.torchons.app.data.model.Task
import com.torchons.app.data.model.TaskHistoryEvent
import com.torchons.app.data.model.UpdateTaskRequest
import com.torchons.app.data.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository des taches — stale-while-revalidate + ecritures offline.
 *
 * Lectures : observe* retourne un Flow Room, refresh* fetch l'API et upsert.
 * Ecritures : write-local-first. L'operation est ecrite dans Room immediatement
 * (UI optimiste), enqueue dans pending_operations, et le SyncWorker la pousse
 * vers le backend quand la connexion est disponible. Le write local et l'enqueue sont UNE
 * transaction dans le scope applicatif (LocalMutation) : quitter l'ecran ne les separe jamais.
 */
@Singleton
class TaskRepository @Inject constructor(
    private val apiService: ApiService,
    private val taskDao: TaskDao,
    private val completionDao: CompletionDao,
    private val members: MemberRepository,
    private val taskHistoryDao: TaskHistoryDao,
    private val contextDao: ContextDao,
    private val completionWriter: CompletionWriter,
    private val completionEdits: CompletionEditWriter,
    private val taskWriter: TaskWriter,
    private val pendingDiscard: PendingDiscardWriter,
    private val collectiveWriter: CollectiveCompletionWriter,
    private val refreshGuard: CompletionRefreshGuard,
) {

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

    /**
     * REPLACE of the tasks cache, spared for rows with a queued op (TaskDao.replaceAll) and
     * guarded by the tasks epoch: an answer sent before a push the worker settled meanwhile is
     * dropped (the op is no longer queued, so the replace would no longer spare its row). False
     * when the request failed or two answers in a row were outdated by pushes.
     */
    suspend fun refreshTasks(): Boolean =
        refreshTasksCache(refreshGuard, taskDao) { apiService.getTasks(activeOnly = null) }

    // -------------------------------------------------------------------
    // Categories
    // -------------------------------------------------------------------

    // The cache wipe of a household switch (every table but the queue) is HouseholdSwitcher's:
    // data/repository/HouseholdCacheWipe.kt.

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

    // Each of these is ONE LocalMutation (cache change + queued op in one transaction, in the
    // application's scope): see TaskLedger and LocalMutation. Return values and errors are the
    // callers' contract and did not change.

    suspend fun createTask(request: CreateTaskRequest): Result<Task> = taskWriter.create(request)

    suspend fun updateTask(taskId: String, request: UpdateTaskRequest): Result<Task> =
        taskWriter.update(taskId, request)

    suspend fun deleteTask(taskId: String): Result<Unit> = taskWriter.delete(taskId)

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
     * Supprime une completion en offline-first : delete local OPTIMISTE immediat (l'urgence de la
     * tache se recalcule aussitot depuis le cache -- offline = on fait confiance au front) + op
     * DELETE en file, dans UNE transaction (CompletionEditLedger). Le serveur reconcilie quand en
     * ligne. Cf docs/cache-conventions.md.
     */
    suspend fun deleteCompletion(completionId: String): Result<Unit> = completionEdits.delete(completionId)

    /**
     * Supprime un evenement collectif entier en offline-first : delete local OPTIMISTE de chaque
     * ligne + une op DELETE par ligne, le tout dans UNE transaction (meme chemin que
     * [deleteCompletion]). Cf docs/cache-conventions.md.
     */
    suspend fun deleteCollectiveCompletion(rowIds: List<String>): Result<Unit> =
        completionEdits.deleteCollective(rowIds)

    /**
     * Edite une completion (qui / quand) en offline-first : upsert local OPTIMISTE + op UPDATE en
     * file, dans UNE transaction. Pas de refresh immediat : le serveur a encore l'ancienne valeur,
     * un refetch ecraserait l'optimiste. Cf docs/cache-conventions.md.
     */
    suspend fun updateCompletion(
        completionId: String,
        completedBy: String,
        completedAt: String,
    ): Result<Unit> = completionEdits.update(completionId, completedBy, completedAt)

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
    suspend fun voteFrequency(taskId: String, request: FrequencyRequest): Result<Unit> =
        sendOrQueue(
            taskHasQueuedOp = { taskId in taskDao.pendingRowIds() },
            send = { apiService.setFrequency(taskId, request) },
            queue = { taskWriter.enqueueFrequencyVote(taskId, request) },
            onSent = { refreshTaskHistory(taskId) },
        )

    /** Helper raccourci interval (mode "Régulière" — 12 mois identiques). */
    suspend fun voteFrequency(taskId: String, everyNDays: Int): Result<Unit> =
        voteFrequency(taskId, FrequencyRequest(everyNDays = everyNDays))

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
     * Drops a queued op the user gave up on (sync dialog), with the ops that depend on it, and
     * undoes the optimistic local write of a dropped INSERT, all in one transaction
     * (PendingDiscardLedger). A dropped UPDATE or DELETE left the server row as it was, so the
     * refresh below brings it back (the row is no longer pending, the replace takes it).
     */
    suspend fun discardPendingOp(clientOpId: String) {
        val dropped = pendingDiscard.discard(clientOpId)
        if (dropped.any { it.tableName == "tasks" && it.operation != "INSERT" }) refreshTasks()
        if (dropped.any { it.tableName == "task_completions" && it.operation != "INSERT" }) refreshCompletions()
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
