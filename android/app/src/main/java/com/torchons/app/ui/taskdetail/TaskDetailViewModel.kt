package com.torchons.app.ui.taskdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.model.Completion
import com.torchons.app.data.model.Task
import com.torchons.app.data.model.TaskHistoryEvent
import com.torchons.app.data.model.User
import com.torchons.app.data.repository.TaskRepository
import com.torchons.app.util.FreshnessCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TaskRating(
    val displayName: String,
    val affinity: Int?,
    val effortTimeMin: Int?
)

data class TaskDetailUiState(
    val isLoading: Boolean = false,
    val task: Task? = null,
    val completions: List<Completion> = emptyList(),
    val userNames: Map<String, String> = emptyMap(),
    val ratings: List<TaskRating> = emptyList(),
    val avgEffortTimeMin: Int? = null,
    val scorePoints: Int? = null,
    val freshness: Double = 0.0,
    val history: List<TaskHistoryEvent> = emptyList(),
    val showEditRating: Boolean = false,
    val editingCompletion: Completion? = null,
    val error: String? = null,
    val completionMessage: String? = null,
    val hasCachedData: Boolean = false,
    val lastRefreshFailed: Boolean = false,
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
    private val apiService: ApiService,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val taskId: String = savedStateHandle.get<String>("taskId")
        ?: throw IllegalArgumentException("taskId manquant dans les arguments de navigation")

    private val _uiState = MutableStateFlow(TaskDetailUiState(isLoading = true))
    val uiState: StateFlow<TaskDetailUiState> = _uiState.asStateFlow()

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
            ) { task, completions, users, history ->
                CachedDetail(task, completions, users, history)
            }.collect { cached ->
                val hasCache = cached.task != null
                val userNames = cached.users.associate { it.id to it.displayName }

                val sortedCompletions = cached.completions.sortedByDescending { it.completedAt }
                val lastCompletion = sortedCompletions.firstOrNull()
                val freshness = FreshnessCalculator.calculateFreshness(
                    lastCompletedAt = lastCompletion?.completedAt,
                    rule = cached.task?.recurrenceRule,
                    tolerance = cached.task?.tolerance ?: "NORMAL",
                )

                _uiState.value = _uiState.value.copy(
                    task = cached.task,
                    completions = sortedCompletions,
                    userNames = userNames,
                    freshness = freshness,
                    history = cached.history,
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
    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = _uiState.value.task == null)

            val results = listOf(
                async { taskRepository.refreshTasks() },
                async { taskRepository.refreshCompletions(taskId = taskId) },
                async { taskRepository.refreshUsers() },
                async { taskRepository.refreshTaskHistory(taskId) },
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

            val allOk = results.all { it }

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                lastRefreshFailed = !allOk,
            )
        }
    }

    fun completeTask() {
        viewModelScope.launch {
            val result = taskRepository.completeTask(taskId)
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(completionMessage = "Tache completee !")
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        error = error.message ?: "Erreur lors de la completion"
                    )
                }
            )
        }
    }

    fun deleteCompletion(completionId: String) {
        viewModelScope.launch {
            try {
                apiService.deleteCompletion(taskId, completionId)
                taskRepository.refreshCompletions(taskId = taskId)
                _uiState.value = _uiState.value.copy(completionMessage = "Completion supprimee")
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: "Erreur lors de la suppression"
                )
            }
        }
    }

    fun showEditCompletion(completion: Completion) {
        _uiState.value = _uiState.value.copy(editingCompletion = completion)
    }

    fun dismissEditCompletion() {
        _uiState.value = _uiState.value.copy(editingCompletion = null)
    }

    fun saveCompletionEdit(completionId: String, completedBy: String, completedAt: String) {
        viewModelScope.launch {
            try {
                apiService.updateCompletion(completionId, mapOf(
                    "completed_by" to completedBy,
                    "completed_at" to completedAt
                ))
                _uiState.value = _uiState.value.copy(editingCompletion = null, completionMessage = "Completion modifiee")
                taskRepository.refreshCompletions(taskId = taskId)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun showEditRating() {
        _uiState.value = _uiState.value.copy(showEditRating = true)
    }

    fun dismissEditRating() {
        _uiState.value = _uiState.value.copy(showEditRating = false)
    }

    fun saveRating(affinity: Int, effortPoints: Int) {
        viewModelScope.launch {
            try {
                apiService.setAffinity(taskId, com.torchons.app.data.model.AffinityRequest(affinity))
                apiService.setEffort(taskId, mapOf("points" to effortPoints))
                _uiState.value = _uiState.value.copy(showEditRating = false, completionMessage = "Notes mises a jour")
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

/** Conteneur interne pour le combine des flows. */
private data class CachedDetail(
    val task: Task?,
    val completions: List<Completion>,
    val users: List<User>,
    val history: List<TaskHistoryEvent>,
)
