package com.torchons.app.ui.taskdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.model.Completion
import com.torchons.app.data.model.EffortVotesState
import com.torchons.app.data.model.FrequencyVotesState
import com.torchons.app.data.model.PendingAffinity
import com.torchons.app.data.model.RequestRevoteBody
import com.torchons.app.data.model.TaskContextRef
import com.torchons.app.data.model.Task
import com.torchons.app.data.model.TaskHistoryEvent
import com.torchons.app.data.model.FormerMember
import com.torchons.app.data.model.User
import com.torchons.app.data.repository.AuthRepository
import com.torchons.app.data.repository.CompletionChecks
import com.torchons.app.data.repository.TaskRepository
import com.torchons.app.ui.common.RatingRecurrence
import com.torchons.app.util.CompletionDateLabels
import com.torchons.app.util.TaskUrgencyCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TaskRating(
    val userId: String?,
    val displayName: String,
    val affinity: Int?,
    val effortTimeMin: Int?,
)

data class TaskDetailUiState(
    val isLoading: Boolean = false,
    val task: Task? = null,
    val completions: List<Completion> = emptyList(),
    val userNames: Map<String, String> = emptyMap(),
    /** Members who left, by id: author labels only, never a selector. */
    val formerMembers: Map<String, FormerMember> = emptyMap(),
    val ratings: List<TaskRating> = emptyList(),
    val avgEffortTimeMin: Int? = null,
    val scorePoints: Int? = null,
    val urgencyState: TaskUrgencyCalculator.State = TaskUrgencyCalculator.State.GREEN,
    val daysUntilDue: Int = 0,
    val countdownLabel: String = "",
    val history: List<TaskHistoryEvent> = emptyList(),
    /** Contexte principal de la tache, tel qu'il arrive avec elle. */
    val room: TaskContextRef? = null,
    /** Contexte de notation pour le modal unifie. Non-null = modal ouvert. */
    val ratingContext: PendingAffinity? = null,
    val editingCompletion: Completion? = null,
    val error: String? = null,
    val completionMessage: String? = null,
    val hasCachedData: Boolean = false,
    val lastRefreshFailed: Boolean = false,
    /** True pendant un refresh declenche par le pull-to-refresh (indicateur
     *  de tirage). Distinct de [isLoading] (spinner plein ecran au 1er load). */
    val isRefreshing: Boolean = false,
    /** True pendant l'animation de celebration post-completion. */
    val isCelebrating: Boolean = false,
    /** Compteur incremente a chaque celebration pour relancer konfetti. */
    val celebrationTrigger: Int = 0,
    /** Points affiches dans la celebration (null = inconnu / offline). */
    val celebrationPoints: Int? = null,
    /** Raison bonus/malus de la celebration (BONUS_RETARD, MALUS_AVANCE, ...). */
    val celebrationReason: String? = null,
    /** Attribution par participant (displayName, points) dans la celebration. */
    val celebrationEarnedBy: List<Pair<String, Int>>? = null,
    /** Preview bonus/malus pour affichage dans le header urgence de la fiche.
     * null tant que le fetch n'a pas abouti ou si pas de recurrence. */
    val bonusPreview: com.torchons.app.data.model.CompletionPreview? = null,
    /** v0.28 : etat des votes de frequence (consensus + others + pending). */
    val frequencyVotes: FrequencyVotesState? = null,
    /** v0.28 : etat des votes d'effort (consensus + others + pending). */
    val effortVotes: EffortVotesState? = null,
    /** v0.28 : modal "+ d'info" frequence ouvert. */
    val showFrequencyInfo: Boolean = false,
    /** v0.29 : modal "+ d'info" effort ouvert. */
    val showEffortInfo: Boolean = false,
    /** v0.28 : modal "+ d'info" affinite ouvert. */
    val showAffinityInfo: Boolean = false,
    /** v0.28 : FrequencyVoteDialog ouvert (depuis le bandeau ou + d'info). */
    val showFrequencyVoteDialog: Boolean = false,
    /** v0.28 : EffortVoteDialog ouvert (depuis le bandeau ou + d'info). */
    val showEffortVoteDialog: Boolean = false,
)

/**
 * ViewModel du detail d'une tache — pattern stale-while-revalidate.
 *
 * Observe task + completions + users depuis Room en continu via combine().
 * Les ratings (affinite/effort par user) restent en appel direct API car
 * trop peu structurees pour Room.
 */
@HiltViewModel
class TaskDetailViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val completionChecks: CompletionChecks,
    private val apiService: ApiService,
    authRepository: AuthRepository,
    tokenManager: com.torchons.app.data.local.TokenManager,
    savedStateHandle: SavedStateHandle,
    @dagger.hilt.android.qualifiers.ApplicationContext
    private val appContext: android.content.Context,
) : ViewModel() {

    private val taskId: String = savedStateHandle.get<String>("taskId")
        ?: throw IllegalArgumentException("taskId manquant dans les arguments de navigation")

    private val _uiState = MutableStateFlow(TaskDetailUiState(isLoading = true))
    val uiState: StateFlow<TaskDetailUiState> = _uiState.asStateFlow()

    /** Id de l'utilisateur courant — utilise pour identifier sa notation. */
    val currentUserId: StateFlow<String?> = authRepository.userIdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** v0.32 : visibilite UI gamif (user-level AND foyer-level). */
    val gamificationVisible: StateFlow<Boolean> = tokenManager.gamificationVisibleFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    init {
        observeCache()
        refresh()
    }

    /**
     * Observe la tache, ses completions et les users depuis Room en continu.
     * Le combine emet a chaque changement (ex: apres un refresh ou une
     * completion reussie qui upsert dans Room).
     */
    private fun observeCache() {
        viewModelScope.launch {
            combine(
                taskRepository.observeTaskById(taskId),
                taskRepository.observeCompletionsByTask(taskId),
                taskRepository.observeUsers(),
                taskRepository.observeTaskHistory(taskId),
                taskRepository.observeFormerMembers(),
            ) { task, completions, users, history, former ->
                CachedDetail(task, completions, users, history, former)
            }.collect { cached ->
                val hasCache = cached.task != null
                val userNames = cached.users.associate { it.id to it.displayName }

                val sortedCompletions = cached.completions.sortedByDescending { it.completedAt }
                val lastCompletion = sortedCompletions.firstOrNull()
                val today = LocalDate.now()
                val nominal = TaskUrgencyCalculator.nominalIntervalDays(cached.task?.recurrenceRule)
                val dueDate = TaskUrgencyCalculator.computeDueDate(
                    lastCompletedAt = lastCompletion?.completedAt,
                    rule = cached.task?.recurrenceRule,
                    createdAt = cached.task?.createdAt,
                    firstCompletedAt = TaskUrgencyCalculator.earliestCompletedAt(
                        serverFirstCompletedAt = cached.task?.firstCompletedAt,
                        localCacheEarliest = sortedCompletions.lastOrNull()?.completedAt,
                    ),
                )
                val absentDays = cached.task?.absentDaysSinceLastCompletion ?: 0
                val state = TaskUrgencyCalculator.computeState(
                    dueDate, today, nominal, absentDays,
                    graceDays = TaskUrgencyCalculator.graceDaysOf(cached.task?.recurrenceRule),
                    windowCapDays = TaskUrgencyCalculator.orangeWindowCapOf(cached.task?.recurrenceRule),
                )
                val daysUntilDue = TaskUrgencyCalculator.daysUntilDue(dueDate, today, absentDays)
                val countdownLabel = TaskUrgencyCalculator.countdownLabel(
                    appContext.resources, dueDate, today, absentDays,
                )

                // Le contexte principal voyage avec la tache : plus rien a
                // resoudre depuis un cache separe.
                val room = cached.task?.contexts?.firstOrNull()

                _uiState.value = _uiState.value.copy(
                    task = cached.task,
                    completions = sortedCompletions,
                    userNames = userNames,
                    formerMembers = cached.former.associateBy { it.id },
                    urgencyState = state,
                    daysUntilDue = daysUntilDue,
                    countdownLabel = countdownLabel,
                    history = cached.history,
                    room = room,
                    hasCachedData = hasCache,
                    isLoading = if (hasCache) false else _uiState.value.isLoading,
                    error = if (!hasCache && !_uiState.value.isLoading && _uiState.value.lastRefreshFailed)
                        "Tache introuvable" else _uiState.value.error,
                )
            }
        }
    }

    /**
     * Refresh les donnees depuis l'API. Les ratings sont chargees en direct
     * (non cachees) ; si l'appel echoue, l'UI conserve la derniere valeur.
     */
    /**
     * @param userInitiated true si declenche par le geste pull-to-refresh —
     *   alimente alors l'indicateur de tirage. Le refresh auto au resume
     *   (LifecycleResumeEffect) passe false : pas de flash d'indicateur.
     */
    fun refresh(userInitiated: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = _uiState.value.task == null,
                isRefreshing = if (userInitiated) true else _uiState.value.isRefreshing,
            )

            val results = listOf(
                async { taskRepository.refreshTasks() },
                async { taskRepository.refreshCompletions(taskId = taskId) },
                async { taskRepository.refreshUsers() },
                async { taskRepository.refreshTaskHistory(taskId) },
                async { taskRepository.refreshContexts() },
            ).awaitAll()

            // Ratings en direct API (best-effort)
            try {
                val ratingsResp = apiService.getTaskRatings(taskId)
                if (ratingsResp.isSuccessful) {
                    val data = ratingsResp.body() ?: emptyMap()
                    @Suppress("UNCHECKED_CAST")
                    val ratingsData = data["ratings"] as? List<Map<String, Any?>> ?: emptyList()
                    val ratings = ratingsData.map { r ->
                        TaskRating(
                            userId = r["user_id"] as? String,
                            displayName = r["display_name"] as? String ?: "?",
                            affinity = (r["affinity"] as? Double)?.toInt(),
                            effortTimeMin = (r["effort_time_min"] as? Double)?.toInt(),
                        )
                    }
                    val avgEffortTimeMin = (data["avg_effort_time_min"] as? Double)?.toInt()
                    val scorePoints = (data["score_points"] as? Double)?.toInt()
                    _uiState.value = _uiState.value.copy(
                        ratings = ratings,
                        avgEffortTimeMin = avgEffortTimeMin,
                        scorePoints = scorePoints,
                    )
                }
            } catch (_: Exception) {}

            // Bonus/malus preview pour affichage dans le header (best-effort).
            try {
                val previewResp = apiService.getCompletionPreview(taskId)
                if (previewResp.isSuccessful) {
                    _uiState.value = _uiState.value.copy(bonusPreview = previewResp.body())
                }
            } catch (_: Exception) {}

            // v0.28 : votes frequence + effort (best-effort).
            try {
                val freq = apiService.getFrequencyVotes(taskId)
                if (freq.isSuccessful) {
                    _uiState.value = _uiState.value.copy(frequencyVotes = freq.body())
                }
            } catch (_: Exception) {}
            try {
                val effort = apiService.getEffortVotes(taskId)
                if (effort.isSuccessful) {
                    _uiState.value = _uiState.value.copy(effortVotes = effort.body())
                }
            } catch (_: Exception) {}

            val allOk = results.all { it }

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                isRefreshing = false,
                lastRefreshFailed = !allOk,
            )
        }
    }

    fun completeTask() = unlessDuplicate {
        celebrate(
            resolve = {
                val preview = fetchCompletionPreview(
                    taskId, CompletionDateLabels.toIsoNoonUtc(LocalDate.now()), 1,
                )
                CelebrationContent(
                    points = preview?.finalPoints,
                    reason = preview?.reason,
                    earnedBy = earnedByFor(null, preview?.finalPoints),
                )
            },
            write = {
                taskRepository.completeTask(taskId).fold(
                    onSuccess = {
                        _uiState.value = _uiState.value.copy(completionMessage = "Tache completee !")
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(
                            error = error.message ?: "Erreur lors de la completion",
                        )
                    },
                )
            },
        )
    }

    /** Deja fait : completion solo a une date passee (optionnellement pour un autre user). */
    fun completeTaskAtDate(
        isoDate: String,
        completedBy: String? = null,
        previewPoints: Int? = null,
        previewReason: String? = null,
    ) = unlessDuplicate(isoDate, completedBy?.let(::listOf)) {
        celebrate(
            resolve = {
                CelebrationContent(previewPoints, previewReason, earnedByFor(completedBy, previewPoints))
            },
            write = {
                taskRepository.completeTask(taskId, completedAt = isoDate, completedBy = completedBy).fold(
                    onSuccess = {
                        _uiState.value = _uiState.value.copy(completionMessage = "Tache completee !")
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(error = error.message)
                    },
                )
            },
        )
    }

    /** Fait ensemble : completion collective (N participants, split ceil). */
    fun completeTaskCollective(
        participantUserIds: List<String>,
        isoDate: String? = null,
        previewPoints: Int? = null,
        previewReason: String? = null,
    ) = unlessDuplicate(isoDate, participantUserIds) {
        celebrate(
            resolve = {
                // Points par personne : preview du dialog, ou fetch (chemin
                // rapide 2-pers). Le preview divise deja par le nb de participants.
                val preview = if (previewPoints != null) null
                    else fetchCompletionPreview(
                        taskId,
                        isoDate ?: CompletionDateLabels.toIsoNoonUtc(LocalDate.now()),
                        participantUserIds.size,
                    )
                val points = previewPoints ?: preview?.finalPoints
                CelebrationContent(
                    points = points,
                    reason = previewReason ?: preview?.reason,
                    earnedBy = earnedByForMany(participantUserIds, points),
                )
            },
            write = {
                taskRepository.completeTaskCollective(
                    taskId = taskId,
                    participantUserIds = participantUserIds,
                    completedAt = isoDate,
                ).fold(
                    onSuccess = {
                        _uiState.value = _uiState.value.copy(
                            completionMessage = "Tache completee a ${participantUserIds.size} !",
                        )
                    },
                    onFailure = { err ->
                        val msg = when (err) {
                            is TaskRepository.CollectiveCompletionException -> when (err.httpCode) {
                                409 -> "Impossible : tache deja completee recemment par un participant"
                                else -> "Erreur ${err.httpCode}"
                            }
                            else -> err.message ?: "Erreur reseau"
                        }
                        _uiState.value = _uiState.value.copy(error = msg)
                    },
                )
            },
        )
    }

    /** Contenu enrichi d'une celebration : points + raison + attribution. */
    private data class CelebrationContent(
        val points: Int?,
        val reason: String?,
        val earnedBy: List<Pair<String, Int>>?,
    )

    /**
     * Attribution single (displayName, points). [completedBy] null = user
     * courant. Null si points ou nom inconnus (preview offline) — la
     * celebration retombe sur sa variante sans points.
     */
    private fun earnedByFor(completedBy: String?, points: Int?): List<Pair<String, Int>>? {
        if (points == null) return null
        val names = _uiState.value.userNames
        val name = if (completedBy == null) {
            names[currentUserId.value] ?: return null
        } else {
            names[completedBy] ?: return null
        }
        return listOf(name to points)
    }

    /**
     * Attribution collective : une entree (displayName, pointsParPersonne) par
     * participant. [points] est deja le split par personne (final_per_user).
     */
    private fun earnedByForMany(participantUserIds: List<String>, points: Int?): List<Pair<String, Int>>? {
        if (points == null || participantUserIds.isEmpty()) return null
        val names = _uiState.value.userNames
        return participantUserIds.mapNotNull { id -> names[id]?.let { it to points } }.ifEmpty { null }
    }

    /**
     * Celebration visuelle (card + confetti) pendant ~3s : emoji immediat, puis
     * points + attribution des que [resolve] les renvoie (preview). L'ecriture
     * Room reelle ([write]) est faite APRES l'animation (parite Accueil /
     * onglet Taches).
     */
    private fun celebrate(
        resolve: suspend () -> CelebrationContent,
        write: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isCelebrating = true,
                celebrationTrigger = _uiState.value.celebrationTrigger + 1,
                celebrationPoints = null,
                celebrationReason = null,
                celebrationEarnedBy = null,
            )
            val filler = launch {
                val content = resolve()
                if (_uiState.value.isCelebrating) {
                    _uiState.value = _uiState.value.copy(
                        celebrationPoints = content.points,
                        celebrationReason = content.reason,
                        celebrationEarnedBy = content.earnedBy,
                    )
                }
            }
            kotlinx.coroutines.delay(3000L)
            filler.cancel()
            _uiState.value = _uiState.value.copy(
                isCelebrating = false,
                celebrationPoints = null,
                celebrationReason = null,
                celebrationEarnedBy = null,
            )
            write()
        }
    }

    fun deleteCompletion(completionId: String) {
        viewModelScope.launch {
            // Offline-first : delete local OPTIMISTE + enqueue DELETE (le
            // serveur reconcilie via /sync ; SyncWorker propage aussi les
            // DELETE serveur). Pas de refresh immediat : l'op DELETE est en
            // file, un refetch re-ajouterait la completion fantome tant
            // qu'elle n'est pas poussee. L'urgence se recalcule aussitot
            // depuis le cache. Cf docs/cache-conventions.md.
            val res = taskRepository.deleteCompletion(completionId)
            _uiState.value = if (res.isSuccess) {
                _uiState.value.copy(completionMessage = "Completion supprimee")
            } else {
                _uiState.value.copy(error = "Erreur lors de la suppression")
            }
        }
    }

    /**
     * Supprime un evenement collectif entier (toutes les lignes du groupe).
     * Offline-first via le repository : delete local OPTIMISTE de chaque row +
     * enqueue DELETE par row (le serveur reconcilie, SyncWorker propage). Pas
     * de refresh immediat. Cf docs/cache-conventions.md.
     */
    fun deleteCollective(rowIds: List<String>) {
        viewModelScope.launch {
            val res = taskRepository.deleteCollectiveCompletion(rowIds)
            _uiState.value = if (res.isSuccess) {
                _uiState.value.copy(completionMessage = "Collectif supprime")
            } else {
                _uiState.value.copy(error = "Erreur lors de la suppression du collectif")
            }
        }
    }

    fun showEditCompletion(completion: Completion) {
        _uiState.value = _uiState.value.copy(editingCompletion = completion)
    }

    fun dismissEditCompletion() {
        _uiState.value = _uiState.value.copy(editingCompletion = null)
    }

    /**
     * Edite une completion (qui / quand). Offline-first via le repository :
     * upsert local OPTIMISTE + enqueue UPDATE, pas de refresh immediat (le
     * serveur a encore l'ancienne valeur). Cf docs/cache-conventions.md.
     */
    fun saveCompletionEdit(completionId: String, completedBy: String, completedAt: String) {
        viewModelScope.launch {
            val res = taskRepository.updateCompletion(completionId, completedBy, completedAt)
            _uiState.value = if (res.isSuccess) {
                _uiState.value.copy(editingCompletion = null, completionMessage = "Completion modifiee")
            } else {
                _uiState.value.copy(error = "Erreur lors de la modification")
            }
        }
    }

    /**
     * Ouvre le modal de notation unifie. Strategie cache-first :
     * 1. Construit immediatement un PendingAffinity depuis l'uiState
     *    (task + ma valeur extraite des ratings) — affichage instantane.
     * 2. Fetch GET /tasks/{id}/rating-context en parallele pour avoir les
     *    valeurs serveur fraiches + last_rated_at — overwrite le state quand
     *    la reponse arrive.
     */
    fun openRatingDialog(currentUserId: String?) {
        val task = _uiState.value.task ?: return

        // Cache-first : construire un PendingAffinity local depuis l'uiState.
        // Identifie ma notation par user_id (les ratings sont retournes avec
        // user_id par GET /tasks/{id}/ratings).
        val myRating = _uiState.value.ratings.firstOrNull {
            currentUserId != null && it.userId == currentUserId
        }
        val cachedContext = PendingAffinity(
            taskId = task.id,
            title = task.title,
            currentAffinity = myRating?.affinity,
            currentEffortPoints = myRating?.effortTimeMin?.let { it / 5 },
            recurrenceRule = task.recurrenceRule,
            pendingTotal = null,
            lastRatedAt = null,
        )
        _uiState.value = _uiState.value.copy(ratingContext = cachedContext)

        // Fetch serveur en background
        viewModelScope.launch {
            try {
                val resp = apiService.getRatingContext(taskId)
                if (resp.isSuccessful) {
                    resp.body()?.let { fresh ->
                        // Ne remplace que si le modal est toujours ouvert
                        if (_uiState.value.ratingContext != null) {
                            _uiState.value = _uiState.value.copy(ratingContext = fresh)
                        }
                    }
                }
            } catch (_: Exception) {
                // Cache reste affiche, c'est OK
            }
        }
    }

    fun dismissRatingDialog() {
        _uiState.value = _uiState.value.copy(ratingContext = null)
    }

    // --- v0.28 : actions sur les modaux "+ d'info" et votes individuels ---

    fun openFrequencyInfo() {
        _uiState.value = _uiState.value.copy(showFrequencyInfo = true)
    }
    fun dismissFrequencyInfo() {
        _uiState.value = _uiState.value.copy(showFrequencyInfo = false)
    }
    fun openEffortInfo() {
        _uiState.value = _uiState.value.copy(showEffortInfo = true)
    }
    fun dismissEffortInfo() {
        _uiState.value = _uiState.value.copy(showEffortInfo = false)
    }
    fun openAffinityInfo() {
        _uiState.value = _uiState.value.copy(showAffinityInfo = true)
    }
    fun dismissAffinityInfo() {
        _uiState.value = _uiState.value.copy(showAffinityInfo = false)
    }
    fun openFrequencyVoteDialog() {
        _uiState.value = _uiState.value.copy(showFrequencyVoteDialog = true)
    }
    fun dismissFrequencyVoteDialog() {
        _uiState.value = _uiState.value.copy(showFrequencyVoteDialog = false)
    }
    fun openEffortVoteDialog() {
        _uiState.value = _uiState.value.copy(showEffortVoteDialog = true)
    }
    fun dismissEffortVoteDialog() {
        _uiState.value = _uiState.value.copy(showEffortVoteDialog = false)
    }

    /** Soumet le vote de frequence (depuis FrequencyVoteDialog ouvert via "+ d'info"). */
    fun submitFrequencyVote(request: com.torchons.app.data.model.FrequencyRequest) {
        viewModelScope.launch {
            taskRepository.voteFrequency(taskId, request).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        showFrequencyVoteDialog = false,
                        completionMessage = "Vote enregistre",
                    )
                    // Refetch pour rafraichir le sous-texte et le modal "+ d'info".
                    refreshFrequencyVotes()
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        showFrequencyVoteDialog = false,
                        error = e.message ?: "Erreur lors du vote",
                    )
                },
            )
        }
    }

    /** Soumet le vote d'effort (depuis EffortVoteDialog). */
    fun submitEffortVote(points: Int) {
        viewModelScope.launch {
            try {
                val resp = apiService.setEffort(taskId, mapOf("points" to points))
                if (resp.isSuccessful) {
                    _uiState.value = _uiState.value.copy(
                        showEffortVoteDialog = false,
                        completionMessage = "Vote enregistre",
                    )
                    refreshEffortVotes()
                    refresh()  // pour les ratings + scorePoints recalcule
                } else {
                    _uiState.value = _uiState.value.copy(
                        showEffortVoteDialog = false,
                        error = "Erreur ${resp.code()}",
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    showEffortVoteDialog = false,
                    error = e.message ?: "Erreur reseau",
                )
            }
        }
    }

    /** Demande a un autre membre de revoter (frequence ou effort). */
    fun requestRevote(targetUserId: String, kind: String) {
        viewModelScope.launch {
            try {
                val resp = apiService.requestRevote(
                    RequestRevoteBody(targetUserId = targetUserId, taskId = taskId, kind = kind)
                )
                if (resp.isSuccessful || resp.code() == 409) {
                    // 409 = deja en pending, on traite comme succes (idempotent).
                    _uiState.value = _uiState.value.copy(
                        completionMessage = "Demande envoyee",
                    )
                    if (kind == "frequency") refreshFrequencyVotes()
                    else if (kind == "effort") refreshEffortVotes()
                } else {
                    _uiState.value = _uiState.value.copy(
                        error = "Erreur ${resp.code()}",
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: "Erreur reseau",
                )
            }
        }
    }

    private suspend fun refreshFrequencyVotes() {
        try {
            val freq = apiService.getFrequencyVotes(taskId)
            if (freq.isSuccessful) {
                _uiState.value = _uiState.value.copy(frequencyVotes = freq.body())
            }
        } catch (_: Exception) {}
    }
    private suspend fun refreshEffortVotes() {
        try {
            val effort = apiService.getEffortVotes(taskId)
            if (effort.isSuccessful) {
                _uiState.value = _uiState.value.copy(effortVotes = effort.body())
            }
        } catch (_: Exception) {}
    }

    fun submitRating(affinity: Int, effortPoints: Int, recurrence: RatingRecurrence?) {
        viewModelScope.launch {
            try {
                apiService.setAffinity(taskId, com.torchons.app.data.model.AffinityRequest(affinity))
                apiService.setEffort(taskId, mapOf("points" to effortPoints))
                if (recurrence != null) {
                    taskRepository.voteFrequency(taskId, recurrence.everyNDays)
                }
                _uiState.value = _uiState.value.copy(
                    ratingContext = null,
                    completionMessage = "Notes enregistrees",
                )
                refresh()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun clearCompletionMessage() {
        _uiState.value = _uiState.value.copy(completionMessage = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /** Refuses a duplicate completion BEFORE the celebration (CompletionChecks.refusal); [by] null = me. */
    private fun unlessDuplicate(at: String? = null, by: List<String>? = null, go: () -> Unit) {
        viewModelScope.launch {
            val refusal = completionChecks.refusal(taskId, at, by)
            if (refusal == null) go() else _uiState.value = _uiState.value.copy(error = refusal)
        }
    }

    /** Preview bonus/malus pour le dialog de completion. null = offline/erreur. */
    suspend fun fetchCompletionPreview(taskId: String, isoDate: String, numParticipants: Int) =
        completionChecks.preview(taskId, isoDate, numParticipants)
}
