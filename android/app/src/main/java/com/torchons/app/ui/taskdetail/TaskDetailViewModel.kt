package com.torchons.app.ui.taskdetail

import com.torchons.app.R
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.model.Completion
import com.torchons.app.data.model.PendingAffinity
import com.torchons.app.data.model.User
import com.torchons.app.data.repository.AuthRepository
import com.torchons.app.data.repository.CompletionChecks
import com.torchons.app.data.repository.TaskRepository
import com.torchons.app.ui.common.RatingRecurrence
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

    private val completions = TaskDetailCompletionActions(
        scope = viewModelScope,
        uiState = _uiState,
        taskId = taskId,
        taskRepository = taskRepository,
        completionChecks = completionChecks,
        appContext = appContext,
        currentUserId = { currentUserId.value },
    )

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
                        appContext.getString(R.string.task_detail_not_found) else _uiState.value.error,
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

    fun completeTask() = completions.completeTask()

    /** Deja fait : completion solo a une date passee (optionnellement pour un autre user). */
    fun completeTaskAtDate(
        isoDate: String,
        completedBy: String? = null,
        previewPoints: Int? = null,
        previewReason: String? = null,
    ) = completions.completeTaskAtDate(isoDate, completedBy, previewPoints, previewReason)

    /** Fait ensemble : completion collective (N participants, split ceil). */
    fun completeTaskCollective(
        participantUserIds: List<String>,
        isoDate: String? = null,
        previewPoints: Int? = null,
        previewReason: String? = null,
    ) = completions.completeTaskCollective(participantUserIds, isoDate, previewPoints, previewReason)

    /** Preview bonus/malus pour le dialog de completion. null = offline/erreur. */
    suspend fun fetchCompletionPreview(taskId: String, isoDate: String, numParticipants: Int) =
        completions.fetchCompletionPreview(taskId, isoDate, numParticipants)

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
                _uiState.value.copy(completionMessage = appContext.getString(R.string.task_detail_completion_deleted))
            } else {
                _uiState.value.copy(error = appContext.getString(R.string.common_error_delete))
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
                _uiState.value.copy(completionMessage = appContext.getString(R.string.task_detail_collective_deleted))
            } else {
                _uiState.value.copy(error = appContext.getString(R.string.task_detail_collective_delete_error))
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
                _uiState.value.copy(
                    editingCompletion = null,
                    completionMessage = appContext.getString(R.string.task_detail_completion_edited),
                )
            } else {
                _uiState.value.copy(error = appContext.getString(R.string.task_detail_edit_error))
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
                        completionMessage = appContext.getString(R.string.task_detail_vote_saved),
                    )
                    // Refetch pour rafraichir le sous-texte et le modal "+ d'info".
                    refreshFrequencyVotes()
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        showFrequencyVoteDialog = false,
                        error = e.message ?: appContext.getString(R.string.common_error_vote),
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
                        completionMessage = appContext.getString(R.string.task_detail_vote_saved),
                    )
                    refreshEffortVotes()
                    refresh()  // pour les ratings + scorePoints recalcule
                } else {
                    _uiState.value = _uiState.value.copy(
                        showEffortVoteDialog = false,
                        error = appContext.getString(R.string.common_error_code_format, resp.code()),
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    showEffortVoteDialog = false,
                    error = e.message ?: appContext.getString(R.string.common_error_network),
                )
            }
        }
    }

    /** Refetches both vote states (a re-vote request or its answer changes who is pending). */
    fun refreshVotes() {
        viewModelScope.launch { refreshFrequencyVotes(); refreshEffortVotes() }
    }

    fun showMessage(message: String) {
        _uiState.value = _uiState.value.copy(completionMessage = message)
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
                    completionMessage = appContext.getString(R.string.task_detail_notes_saved),
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
}
