package com.torchons.app.ui.taskdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.model.Completion
import com.torchons.app.data.model.Task
import com.torchons.app.data.repository.TaskRepository
import com.torchons.app.util.FreshnessCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Etat de l'interface du detail d'une tache.
 */
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
    val showEditRating: Boolean = false,
    val editingCompletion: Completion? = null,
    val error: String? = null,
    val completionMessage: String? = null
)

/**
 * ViewModel pour l'ecran de detail d'une tache.
 * Charge les informations de la tache et son historique de completions.
 */
@HiltViewModel
class TaskDetailViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val apiService: ApiService,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    /** Identifiant de la tache recupere depuis les arguments de navigation. */
    private val taskId: String = savedStateHandle.get<String>("taskId")
        ?: throw IllegalArgumentException("taskId manquant dans les arguments de navigation")

    private val _uiState = MutableStateFlow(TaskDetailUiState(isLoading = true))
    val uiState: StateFlow<TaskDetailUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    /**
     * Charge les donnees de la tache et ses completions.
     */
    private fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                // Charger les taches, completions et users
                val tasksResult = taskRepository.getTasks()
                val completionsResult = taskRepository.getCompletions(taskId = taskId)
                val usersResult = taskRepository.getUsers()

                val tasks = tasksResult.getOrNull() ?: emptyList()
                val task = tasks.find { it.id == taskId }
                val completions = completionsResult.getOrNull() ?: emptyList()
                val users = usersResult.getOrNull() ?: emptyList()
                val userNames = users.associate { it.id to it.displayName }

                // Charger les ratings (affinite + effort par user)
                var ratings = emptyList<TaskRating>()
                var avgEffortTimeMin: Int? = null
                var scorePoints: Int? = null
                try {
                    val ratingsResp = apiService.getTaskRatings(taskId)
                    if (ratingsResp.isSuccessful) {
                        val data = ratingsResp.body() ?: emptyMap()
                        @Suppress("UNCHECKED_CAST")
                        val ratingsData = data["ratings"] as? List<Map<String, Any?>> ?: emptyList()
                        ratings = ratingsData.map { r ->
                            TaskRating(
                                displayName = r["display_name"] as? String ?: "?",
                                affinity = (r["affinity"] as? Double)?.toInt(),
                                effortTimeMin = (r["effort_time_min"] as? Double)?.toInt(),
                            )
                        }
                        avgEffortTimeMin = (data["avg_effort_time_min"] as? Double)?.toInt()
                        scorePoints = (data["score_points"] as? Double)?.toInt()
                    }
                } catch (_: Exception) {}

                // Calculer la fraicheur
                val lastCompletion = completions.maxByOrNull { it.completedAt }
                val freshness = FreshnessCalculator.calculateFreshness(
                    lastCompletedAt = lastCompletion?.completedAt,
                    halfLifeHours = task?.freshnessHalfLifeHours ?: 168.0
                )

                _uiState.value = TaskDetailUiState(
                    isLoading = false,
                    task = task,
                    completions = completions.sortedByDescending { it.completedAt },
                    userNames = userNames,
                    ratings = ratings,
                    avgEffortTimeMin = avgEffortTimeMin,
                    scorePoints = scorePoints,
                    freshness = freshness,
                    error = if (task == null) "Tache introuvable" else null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Erreur lors du chargement"
                )
            }
        }
    }

    /**
     * Marque la tache comme completee.
     */
    fun completeTask() {
        viewModelScope.launch {
            val result = taskRepository.completeTask(taskId)
            result.fold(
                onSuccess = { completion ->
                    // Mettre a jour l'etat avec la nouvelle completion
                    val updatedCompletions = listOf(completion) + _uiState.value.completions
                    val freshness = FreshnessCalculator.calculateFreshness(
                        lastCompletedAt = completion.completedAt,
                        halfLifeHours = _uiState.value.task?.freshnessHalfLifeHours ?: 168.0
                    )

                    _uiState.value = _uiState.value.copy(
                        completions = updatedCompletions,
                        freshness = freshness,
                        completionMessage = "Tache completee !"
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        error = error.message ?: "Erreur lors de la completion"
                    )
                }
            )
        }
    }

    /** Supprime une completion (soft delete). */
    fun deleteCompletion(completionId: String) {
        viewModelScope.launch {
            try {
                apiService.deleteCompletion(taskId, completionId)
                // Retirer de la liste locale
                _uiState.value = _uiState.value.copy(
                    completions = _uiState.value.completions.filter { it.id != completionId },
                    completionMessage = "Completion supprimee"
                )
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
                loadData()
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
                loadData()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    /** Efface le message de completion affiche. */
    fun clearCompletionMessage() {
        _uiState.value = _uiState.value.copy(completionMessage = null)
    }

    /** Efface le message d'erreur affiche. */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
