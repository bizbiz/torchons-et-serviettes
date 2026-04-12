package com.torchons.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.AffinityRequest
import com.torchons.app.data.model.FairShareStatus
import com.torchons.app.data.model.PendingAffinity
import com.torchons.app.data.model.Suggestion
import com.torchons.app.data.model.TaskInProgress
import com.torchons.app.data.repository.AuthRepository
import com.torchons.app.data.repository.HomeRepository
import com.torchons.app.data.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Etat UI de l'accueil.
 *
 * @param hasCachedData true des qu'au moins un snapshot non-null a ete observe
 *   depuis le cache Room. Permet de distinguer "ecran vide initial" de
 *   "tout est frais reellement".
 * @param lastRefreshFailed true si le dernier refresh API a echoue (au moins
 *   un des sub-fetches). Combine avec hasCachedData = false, indique au UI
 *   qu'on est dans un etat "rien a afficher car backend injoignable".
 */
data class HomeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val suggestions: List<Suggestion> = emptyList(),
    val inProgress: List<TaskInProgress> = emptyList(),
    val fairShare: List<FairShareStatus> = emptyList(),
    val pendingAffinity: PendingAffinity? = null,
    val showAffinityPopup: Boolean = false,
    val hasCachedData: Boolean = false,
    val lastRefreshFailed: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val apiService: ApiService,
    private val homeRepository: HomeRepository,
    private val authRepository: AuthRepository,
    private val taskRepository: TaskRepository,
    @Suppress("unused") private val tokenManager: TokenManager,
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
    private var affinityDismissedThisSession = false

    init {
        observeCache()
        refresh()
    }

    /**
     * Observe les snapshots Room en continu. Le combine emet a chaque fois
     * qu'un des snapshots est mis a jour (par refresh ou par une ecriture).
     * Garantit affichage instantane au demarrage et reactif aux changements.
     */
    private fun observeCache() {
        viewModelScope.launch {
            combine(
                homeRepository.observeSuggestions(),
                homeRepository.observeInProgress(),
                homeRepository.observeFairShare(),
                homeRepository.observePendingAffinity(),
                homeRepository.observeUserSettings(),
            ) { suggestions, inProgress, fairShare, pending, settings ->
                CachedHome(suggestions, inProgress, fairShare, pending, settings)
            }.collect { cached ->
                val popupEnabled = (cached.settings?.get("affinity_popup_enabled") as? Boolean) ?: true
                _affinityPopupEnabled.value = popupEnabled
                cachedThreshold = (cached.settings?.get("freshness_threshold") as? Double) ?: cachedThreshold

                val firstPending = cached.pending?.firstOrNull()
                val hasCache = cached.suggestions != null || cached.inProgress != null

                _uiState.value = _uiState.value.copy(
                    suggestions = cached.suggestions ?: emptyList(),
                    inProgress = cached.inProgress ?: emptyList(),
                    fairShare = cached.fairShare ?: emptyList(),
                    pendingAffinity = firstPending,
                    // Ne PAS toggler showAffinityPopup ici — le combine emet
                    // plusieurs fois pendant le refresh et ca provoque un flicker.
                    // showAffinityPopup est controle par refresh() et submitRatingAndNext().
                    hasCachedData = hasCache,
                    isLoading = if (hasCache) false else _uiState.value.isLoading,
                )
            }
        }
    }

    /**
     * Refresh tous les snapshots de l'accueil depuis l'API.
     * Echec silencieux : si l'API ne repond pas, le cache reste affiche et
     * lastRefreshFailed est mis a true pour que l'UI puisse signaler l'etat.
     */
    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)

            // Settings en premier pour avoir le freshness_threshold a jour.
            val settingsOk = homeRepository.refreshUserSettings()
            val settings = try {
                homeRepository.observeUserSettings().first()
            } catch (_: Exception) {
                null
            }
            val threshold = (settings?.get("freshness_threshold") as? Double) ?: cachedThreshold
            cachedThreshold = threshold

            // Sub-fetches en parallele.
            val results = listOf(
                async { homeRepository.refreshSuggestions(threshold) },
                async { homeRepository.refreshInProgress() },
                async { homeRepository.refreshFairShare() },
                async { homeRepository.refreshPendingAffinity(6) },
            ).awaitAll()

            val allOk = settingsOk && results.all { it }

            // Afficher la popup affinite UNE SEULE FOIS apres le refresh
            val pending = _uiState.value.pendingAffinity
            val popupEnabled = _affinityPopupEnabled.value

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                isRefreshing = false,
                lastRefreshFailed = !allOk,
                showAffinityPopup = pending != null
                    && !affinityDismissedThisSession
                    && popupEnabled,
            )
        }
    }

    /** Alias historique conserve pour la compat avec HomeScreen. */
    fun loadData() = refresh()

    private suspend fun saveRatingData(
        taskId: String,
        affinity: Int,
        effortPoints: Int,
        recurrence: RatingRecurrence?,
    ) {
        apiService.setAffinity(taskId, AffinityRequest(affinity))
        apiService.setEffort(taskId, mapOf("points" to effortPoints))
        if (recurrence != null) {
            val rule = com.torchons.app.data.model.RecurrenceRule(
                type = "interval",
                everyNDays = recurrence.everyNDays,
            )
            apiService.updateTask(
                taskId,
                com.torchons.app.data.model.UpdateTaskRequest(recurrenceRule = rule),
            )
        }
    }

    fun submitRating(taskId: String, affinity: Int, effortPoints: Int, recurrence: RatingRecurrence?) {
        viewModelScope.launch {
            try {
                saveRatingData(taskId, affinity, effortPoints, recurrence)
                affinityDismissedThisSession = true
                _uiState.value = _uiState.value.copy(showAffinityPopup = false)
                refresh()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun submitRatingAndNext(taskId: String, affinity: Int, effortPoints: Int, recurrence: RatingRecurrence?) {
        viewModelScope.launch {
            try {
                saveRatingData(taskId, affinity, effortPoints, recurrence)
                _uiState.value = _uiState.value.copy(showAffinityPopup = false)

                // Essayer d'abord les taches non-notees
                homeRepository.refreshPendingAffinity(6)
                var next = homeRepository.observePendingAffinity().first()?.firstOrNull()

                // Si plus de non-notees, fallback sur oldest-rating (re-notation)
                if (next == null) {
                    val resp = apiService.getOldestRating()
                    next = if (resp.isSuccessful) resp.body()?.firstOrNull() else null
                }

                if (next != null) {
                    _uiState.value = _uiState.value.copy(
                        pendingAffinity = next,
                        showAffinityPopup = true,
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        error = "Toutes les taches sont deja notees !",
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun dismissAffinityPopup() {
        affinityDismissedThisSession = true
        _uiState.value = _uiState.value.copy(showAffinityPopup = false)
    }

    fun showManualRating() {
        viewModelScope.launch {
            try {
                val resp = apiService.getOldestRating()
                val task = if (resp.isSuccessful) resp.body()?.firstOrNull() else null
                if (task != null) {
                    affinityDismissedThisSession = false
                    _uiState.value = _uiState.value.copy(
                        pendingAffinity = task,
                        showAffinityPopup = true,
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        error = "Toutes les taches sont deja notees !",
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    fun startTask(taskId: String) {
        viewModelScope.launch {
            try {
                apiService.startTask(taskId)
                refresh()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun completeTask(taskId: String) {
        viewModelScope.launch {
            taskRepository.completeTask(taskId).fold(
                onSuccess = { refresh() },
                onFailure = { _uiState.value = _uiState.value.copy(error = it.message) },
            )
        }
    }

    fun completeTaskAtDate(taskId: String, isoDate: String, completedBy: String? = null) {
        viewModelScope.launch {
            taskRepository.completeTask(taskId, completedAt = isoDate, completedBy = completedBy).fold(
                onSuccess = { refresh() },
                onFailure = { _uiState.value = _uiState.value.copy(error = it.message) },
            )
        }
    }

    fun cancelInProgress(taskId: String) {
        viewModelScope.launch {
            try {
                apiService.cancelTaskInProgress(taskId)
                refresh()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}

/** Conteneur interne pour le combine des 5 flows snapshot. */
private data class CachedHome(
    val suggestions: List<Suggestion>?,
    val inProgress: List<TaskInProgress>?,
    val fairShare: List<FairShareStatus>?,
    val pending: List<PendingAffinity>?,
    val settings: Map<String, Any>?,
)
