package com.torchons.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.model.AffinityRequest
import com.torchons.app.data.model.FairShareStatus
import com.torchons.app.data.model.PendingAffinity
import com.torchons.app.data.model.Suggestion
import com.torchons.app.data.model.TaskInProgress
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val isLoading: Boolean = false,
    val suggestions: List<Suggestion> = emptyList(),
    val inProgress: List<TaskInProgress> = emptyList(),
    val fairShare: List<FairShareStatus> = emptyList(),
    val pendingAffinity: PendingAffinity? = null,
    val showAffinityPopup: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val apiService: ApiService,
    private val authRepository: AuthRepository,
    private val tokenManager: TokenManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState(isLoading = true))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val userId: StateFlow<String?> = authRepository.userIdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val displayName: StateFlow<String?> = authRepository.displayNameFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _affinityPopupEnabled = MutableStateFlow(true)
    val affinityPopupEnabled: StateFlow<Boolean> = _affinityPopupEnabled.asStateFlow()

    private var cachedThreshold = 10.0

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                // Charger les settings depuis le backend
                val settingsResp = apiService.getSettings()
                if (settingsResp.isSuccessful) {
                    val data = settingsResp.body() ?: emptyMap()
                    _affinityPopupEnabled.value = data["affinity_popup_enabled"] as? Boolean ?: true
                    cachedThreshold = (data["freshness_threshold"] as? Double) ?: 10.0
                }
                val suggestionsResp = apiService.getSuggestions(cachedThreshold)
                val inProgressResp = apiService.getInProgress()
                val fairShareResp = apiService.getFairShareStatus()
                val pendingResp = apiService.getPendingAffinity()

                val suggestions = if (suggestionsResp.isSuccessful) suggestionsResp.body() ?: emptyList() else emptyList()
                val inProgress = if (inProgressResp.isSuccessful) inProgressResp.body() ?: emptyList() else emptyList()
                val fairShare = if (fairShareResp.isSuccessful) fairShareResp.body() ?: emptyList() else emptyList()
                val pending = if (pendingResp.isSuccessful) pendingResp.body()?.firstOrNull() else null

                _uiState.value = HomeUiState(
                    isLoading = false,
                    suggestions = suggestions,
                    inProgress = inProgress,
                    fairShare = fairShare,
                    pendingAffinity = pending,
                    showAffinityPopup = pending != null && !affinityDismissedThisSession,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }

    // Empeche loadData de reouvrir la popup apres un submit
    private var affinityDismissedThisSession = false

    private suspend fun saveRatingData(
        taskId: String,
        affinity: Int,
        effortPoints: Int,
        recurrence: com.torchons.app.ui.home.RatingRecurrence?
    ) {
        apiService.setAffinity(taskId, AffinityRequest(affinity))
        apiService.setEffort(taskId, mapOf("points" to effortPoints))
        if (recurrence != null) {
            // Mettre a jour la recurrence de la tache
            val rule = com.torchons.app.data.model.RecurrenceRule(
                type = "interval",
                everyNDays = recurrence.everyNDays
            )
            apiService.updateTask(
                taskId,
                com.torchons.app.data.model.UpdateTaskRequest(
                    recurrenceRule = rule,
                )
            )
        }
    }

    /** Envoie affinite + effort + recurrence puis ferme la popup. */
    fun submitRating(taskId: String, affinity: Int, effortPoints: Int, recurrence: com.torchons.app.ui.home.RatingRecurrence?) {
        viewModelScope.launch {
            try {
                saveRatingData(taskId, affinity, effortPoints, recurrence)
                affinityDismissedThisSession = true
                _uiState.value = _uiState.value.copy(showAffinityPopup = false)
                refreshSuggestionsOnly()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    /** Envoie et charge la tache suivante a noter. */
    fun submitRatingAndNext(taskId: String, affinity: Int, effortPoints: Int, recurrence: com.torchons.app.ui.home.RatingRecurrence?) {
        viewModelScope.launch {
            try {
                saveRatingData(taskId, affinity, effortPoints, recurrence)
                val pendingResp = apiService.getPendingAffinity()
                val next = if (pendingResp.isSuccessful) pendingResp.body()?.firstOrNull() else null
                _uiState.value = _uiState.value.copy(
                    pendingAffinity = next,
                    showAffinityPopup = next != null,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun dismissAffinityPopup() {
        affinityDismissedThisSession = true
        _uiState.value = _uiState.value.copy(showAffinityPopup = false)
    }

    /** Mode manuel : affiche la popup pour la tache la plus anciennement notee. */
    fun showManualRating() {
        viewModelScope.launch {
            try {
                val resp = apiService.getOldestRating()
                val task = if (resp.isSuccessful) resp.body()?.firstOrNull() else null
                if (task != null) {
                    _uiState.value = _uiState.value.copy(
                        pendingAffinity = task,
                        showAffinityPopup = true,
                    )
                }
            } catch (_: Exception) {}
        }
    }

    private fun refreshSuggestionsOnly() {
        viewModelScope.launch {
            try {
                val suggestionsResp = apiService.getSuggestions(cachedThreshold)
                val inProgressResp = apiService.getInProgress()
                val fairShareResp = apiService.getFairShareStatus()
                _uiState.value = _uiState.value.copy(
                    suggestions = if (suggestionsResp.isSuccessful) suggestionsResp.body() ?: emptyList() else _uiState.value.suggestions,
                    inProgress = if (inProgressResp.isSuccessful) inProgressResp.body() ?: emptyList() else _uiState.value.inProgress,
                    fairShare = if (fairShareResp.isSuccessful) fairShareResp.body() ?: emptyList() else _uiState.value.fairShare,
                )
            } catch (_: Exception) {}
        }
    }

    /** Marquer une tache comme en cours. */
    fun startTask(taskId: String) {
        viewModelScope.launch {
            try {
                apiService.startTask(taskId)
                loadData()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    /** Completer une tache. */
    fun completeTask(taskId: String) {
        viewModelScope.launch {
            try {
                apiService.completeTask(taskId)
                // Retirer immediatement des suggestions
                _uiState.value = _uiState.value.copy(
                    suggestions = _uiState.value.suggestions.filter { it.taskId != taskId },
                    inProgress = _uiState.value.inProgress.filter { it.taskId != taskId },
                )
                refreshSuggestionsOnly()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    /** Completer une tache avec une date historique et un user specifique. */
    fun completeTaskAtDate(taskId: String, isoDate: String, completedBy: String? = null) {
        viewModelScope.launch {
            try {
                apiService.completeTask(taskId, com.torchons.app.data.model.CompleteTaskRequest(completedAt = isoDate, completedBy = completedBy))
                _uiState.value = _uiState.value.copy(
                    suggestions = _uiState.value.suggestions.filter { it.taskId != taskId },
                    inProgress = _uiState.value.inProgress.filter { it.taskId != taskId },
                )
                refreshSuggestionsOnly()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    /** Annuler une tache en cours (pas faite). */
    fun cancelInProgress(taskId: String) {
        viewModelScope.launch {
            try {
                apiService.cancelTaskInProgress(taskId)
                _uiState.value = _uiState.value.copy(
                    inProgress = _uiState.value.inProgress.filter { it.taskId != taskId },
                )
                refreshSuggestionsOnly()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
