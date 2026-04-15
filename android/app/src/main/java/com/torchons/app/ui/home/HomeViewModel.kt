package com.torchons.app.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.core.proposition.PropositionStateHolder
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.AffinityRequest
import com.torchons.app.data.model.FairShareStatus
import com.torchons.app.data.model.MaintenanceRateResponse
import com.torchons.app.data.model.ObjectiveUpdate
import com.torchons.app.data.model.PaceResponse
import com.torchons.app.data.model.PendingAffinity
import com.torchons.app.data.model.PresenceRequest
import com.torchons.app.data.model.PropositionCreate
import com.torchons.app.data.model.PropositionView
import com.torchons.app.data.model.RetardResponse
import com.torchons.app.data.model.Suggestion
import com.torchons.app.data.model.Category
import com.torchons.app.data.model.Room
import com.torchons.app.data.model.Task
import com.torchons.app.data.model.UserPace
import com.torchons.app.data.model.WelcomeBack
import com.torchons.app.data.repository.AuthRepository
import com.torchons.app.data.repository.HomeRepository
import com.torchons.app.data.repository.TaskRepository
import com.torchons.app.ui.common.RatingRecurrence
import com.torchons.app.util.PopupSessionGate
import com.torchons.app.util.WifiHelper
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
    val fairShare: List<FairShareStatus> = emptyList(),
    val pendingAffinity: PendingAffinity? = null,
    val showAffinityPopup: Boolean = false,
    val hasCachedData: Boolean = false,
    val lastRefreshFailed: Boolean = false,
    val error: String? = null,
    /** Id de la tache en cours de "celebration" (animation post-completion). */
    val celebratingTaskId: String? = null,
    /** Compteur incremente a chaque celebration pour relancer l'animation konfetti. */
    val celebrationTrigger: Int = 0,
    /**
     * Set des task_ids recemment completes cote client pour lesquels le
     * backend n'a pas encore integre la completion (SyncWorker pas encore
     * passe). Le HomeScreen les filtre pour afficher la suggestion suivante
     * du tampon au lieu de montrer la meme tache une seconde fois. Les ids
     * sont automatiquement nettoyes de ce set des que le backend ne les
     * renvoie plus dans /suggestions (= il a rattrape le retard de sync).
     */
    val justCompletedIds: Set<String> = emptySet(),
    /**
     * Message "bon retour" rempli par le backend quand le foyer est de nouveau
     * occupe apres une absence > 24h (v0.7.4 home freeze). null = pas de retour.
     */
    val welcomeBack: WelcomeBack? = null,
    /**
     * Donnees des 2 cards projection (v0.8.0 magic buttons). Tout null tant
     * que le premier fetch n'a pas abouti.
     */
    val retard: RetardResponse? = null,
    val pace: PaceResponse? = null,
    val maintenance: MaintenanceRateResponse? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    application: Application,
    private val apiService: ApiService,
    private val homeRepository: HomeRepository,
    private val authRepository: AuthRepository,
    private val taskRepository: TaskRepository,
    private val popupSessionGate: PopupSessionGate,
    private val tokenManager: TokenManager,
    private val propositionStateHolder: PropositionStateHolder,
) : AndroidViewModel(application) {

    /** Proposition active (v0.8.1) — forwarded depuis PropositionStateHolder */
    val currentProposition: StateFlow<PropositionView?> = propositionStateHolder.currentProposition
    val propositionCelebration: StateFlow<PropositionView?> = propositionStateHolder.celebration
    val propositionError: StateFlow<String?> = propositionStateHolder.error

    private val _uiState = MutableStateFlow(HomeUiState(isLoading = true))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val userId: StateFlow<String?> = authRepository.userIdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val displayName: StateFlow<String?> = authRepository.displayNameFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Caches partages avec TaskListViewModel pour resolver les pieces/categories
     * affichees sur les cartes Home (suggestions + in-progress). Pas de fetch
     * supplementaire — on consomme directement les flows Room du repo.
     */
    val rooms: StateFlow<List<Room>> = taskRepository.observeRooms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val categories: StateFlow<List<Category>> = taskRepository.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeTasks: StateFlow<List<Task>> = taskRepository.observeActiveTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _affinityPopupEnabled = MutableStateFlow(true)
    val affinityPopupEnabled: StateFlow<Boolean> = _affinityPopupEnabled.asStateFlow()

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
                homeRepository.observeFairShare(),
                homeRepository.observePendingAffinity(),
                homeRepository.observeUserSettings(),
            ) { suggestions, fairShare, pending, settings ->
                CachedHome(suggestions, fairShare, pending, settings)
            }.collect { cached ->
                val popupEnabled = (cached.settings?.get("affinity_popup_enabled") as? Boolean) ?: true
                _affinityPopupEnabled.value = popupEnabled

                val firstPending = cached.pending?.firstOrNull()
                val hasCache = cached.suggestions != null

                // Nettoyage des justCompletedIds : on garde uniquement les ids
                // que le serveur renvoie encore dans les suggestions (= sync
                // pas encore poussee, il faut continuer a les masquer). Des
                // qu'un id sort des suggestions serveur, le backend a rattrape
                // et on le retire du set.
                val rawSuggestions = cached.suggestions ?: emptyList()
                val serverSuggestedIds = rawSuggestions.map { it.taskId }.toSet()
                val cleanedJustCompleted =
                    _uiState.value.justCompletedIds.filter { it in serverSuggestedIds }.toSet()

                _uiState.value = _uiState.value.copy(
                    suggestions = rawSuggestions,
                    justCompletedIds = cleanedJustCompleted,
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

            // Settings en premier pour avoir affinity_popup_enabled a jour.
            val settingsOk = homeRepository.refreshUserSettings()

            // Sub-fetches en parallele. On rafraichit aussi rooms/categories/tasks
            // pour que les SuggestionCard puissent resoudre leur piece+categorie
            // au premier affichage Home (sinon il faut visiter TaskListScreen
            // d'abord pour que ces caches se remplissent).
            val results = listOf(
                async { homeRepository.refreshSuggestions() },
                async { homeRepository.refreshInProgress() },
                async { homeRepository.refreshFairShare() },
                async { homeRepository.refreshPendingAffinity(6) },
                async { taskRepository.refreshRooms() },
                async { taskRepository.refreshCategories() },
                async { taskRepository.refreshTasks() },
            ).awaitAll()

            // v0.8.0 : projection (retard, pace, maintenance) en parallele.
            // Non critique — si ca echoue les cards affichent un etat de loading
            // ou des valeurs par defaut.
            refreshProjection()

            val allOk = settingsOk && results.all { it }

            // Afficher la popup affinite UNE SEULE FOIS apres le refresh
            val pending = _uiState.value.pendingAffinity
            val popupEnabled = _affinityPopupEnabled.value

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                isRefreshing = false,
                lastRefreshFailed = !allOk,
                showAffinityPopup = pending != null
                    && popupSessionGate.armed.value
                    && popupEnabled,
            )
        }
    }

    /** Alias historique conserve pour la compat avec HomeScreen. */
    fun loadData() = refresh()

    /**
     * Reporte la presence WiFi au backend (v0.7.4 home freeze). Lit le SSID
     * actuel via WifiHelper, le compare aux home_ssids configures, et POST le
     * resultat. Si le backend repond avec un welcome_back (foyer de nouveau
     * occupe apres > 24h d'absence), on l'expose dans uiState pour declencher
     * la popup "bon retour" sur HomeScreen.
     *
     * Echec silencieux : pas de home_ssids, pas de WiFi, ou backend KO -> on
     * ne fait rien et on ne montre rien.
     */
    fun reportPresenceNow() {
        viewModelScope.launch {
            try {
                val homeSsids = tokenManager.homeSsidsFlow.first()
                if (homeSsids.isEmpty()) return@launch
                val ctx = getApplication<Application>().applicationContext
                val current = WifiHelper.getCurrentSsid(ctx)
                val isAtHome = current != null && homeSsids.any { it.equals(current, ignoreCase = true) }
                val resp = apiService.postPresence(PresenceRequest(isAtHome = isAtHome))
                if (resp.isSuccessful) {
                    val body = resp.body()
                    if (body?.welcomeBack != null) {
                        _uiState.value = _uiState.value.copy(welcomeBack = body.welcomeBack)
                    }
                }
            } catch (_: Exception) {
                // best effort
            }
        }
    }

    fun dismissWelcomeBack() {
        _uiState.value = _uiState.value.copy(welcomeBack = null)
    }

    /**
     * Recupere les 3 endpoints projection (v0.8.0) en parallele.
     * Non bloquant sur le cache Home — si ca echoue on garde les valeurs
     * precedentes (ou null au premier boot).
     */
    private suspend fun refreshProjection() {
        val retardDeferred = viewModelScope.async { runCatching { apiService.getRetard() }.getOrNull() }
        val paceDeferred = viewModelScope.async { runCatching { apiService.getPace() }.getOrNull() }
        val maintDeferred = viewModelScope.async { runCatching { apiService.getMaintenanceRate() }.getOrNull() }
        val retardResp = retardDeferred.await()
        val paceResp = paceDeferred.await()
        val maintResp = maintDeferred.await()
        _uiState.value = _uiState.value.copy(
            retard = if (retardResp?.isSuccessful == true) retardResp.body() else _uiState.value.retard,
            pace = if (paceResp?.isSuccessful == true) paceResp.body() else _uiState.value.pace,
            maintenance = if (maintResp?.isSuccessful == true) maintResp.body() else _uiState.value.maintenance,
        )
    }

    /**
     * Save l'objectif perso de l'user courant (appele depuis le popup
     * simulation mode "Juste pour moi"). Refresh la card apres.
     */
    fun saveObjective(minutes: Int?, unit: String?) {
        viewModelScope.launch {
            try {
                apiService.updateObjective(ObjectiveUpdate(dailyObjectiveMinutes = minutes, displayUnit = unit))
                refreshProjection()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    /**
     * Proposer un objectif multi-user au foyer (v0.8.1). Declenche le flow
     * live FCM — le proposer rentre en ecran d'attente bloquant, les
     * receveurs recoivent un modal accept/decline.
     */
    fun proposeToFoyer(sliderValues: Map<String, Int>, unit: String) {
        viewModelScope.launch {
            try {
                // Sauve d'abord la preference d'unite perso
                apiService.updateObjective(ObjectiveUpdate(displayUnit = unit))
                val resp = apiService.createProposition(PropositionCreate(sliderValues))
                if (resp.isSuccessful) {
                    val body = resp.body()
                    if (body != null) {
                        propositionStateHolder.setCurrent(body)
                    }
                } else {
                    _uiState.value = _uiState.value.copy(error = "Erreur proposition (${resp.code()})")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            }
        }
    }

    fun respondToProposition(propositionId: String, accepted: Boolean) {
        propositionStateHolder.respond(propositionId, accepted)
    }

    fun cancelProposition(propositionId: String) {
        propositionStateHolder.cancel(propositionId)
    }

    fun dismissPropositionCelebration() {
        propositionStateHolder.dismissCelebration()
        viewModelScope.launch { refreshProjection() }
    }

    fun dismissPropositionError() {
        propositionStateHolder.dismissError()
    }

    /** Poll fallback : refresh la proposition pending au LifecycleResume. */
    fun refreshPendingProposition() {
        viewModelScope.launch {
            propositionStateHolder.refreshFromPending()
        }
    }

    private suspend fun saveRatingData(
        taskId: String,
        affinity: Int,
        effortPoints: Int,
        recurrence: RatingRecurrence?,
    ) {
        apiService.setAffinity(taskId, AffinityRequest(affinity))
        apiService.setEffort(taskId, mapOf("points" to effortPoints))
        if (recurrence != null) {
            // Vote de frequence individuel — le serveur recalcule le consensus
            // (moyenne arrondie des votes) et met a jour tasks.recurrence_rule.
            taskRepository.voteFrequency(taskId, recurrence.everyNDays)
        }
    }

    fun submitRating(taskId: String, affinity: Int, effortPoints: Int, recurrence: RatingRecurrence?) {
        viewModelScope.launch {
            try {
                saveRatingData(taskId, affinity, effortPoints, recurrence)
                popupSessionGate.disarm()
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
        popupSessionGate.disarm()
        _uiState.value = _uiState.value.copy(showAffinityPopup = false)
    }

    fun showManualRating() {
        viewModelScope.launch {
            try {
                val resp = apiService.getOldestRating()
                val task = if (resp.isSuccessful) resp.body()?.firstOrNull() else null
                if (task != null) {
                    popupSessionGate.arm()
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

    fun completeTask(taskId: String) {
        celebrateThen(taskId) {
            taskRepository.completeTask(taskId).fold(
                onSuccess = { refresh() },
                onFailure = { _uiState.value = _uiState.value.copy(error = it.message) },
            )
        }
    }

    fun completeTaskAtDate(taskId: String, isoDate: String, completedBy: String? = null) {
        celebrateThen(taskId) {
            taskRepository.completeTask(taskId, completedAt = isoDate, completedBy = completedBy).fold(
                onSuccess = { refresh() },
                onFailure = { _uiState.value = _uiState.value.copy(error = it.message) },
            )
        }
    }

    /** Completion collective ("fait ensemble"). Online-first via endpoint dedie. */
    fun completeTaskCollective(
        taskId: String,
        participantUserIds: List<String>,
        isoDate: String? = null,
    ) {
        celebrateThen(taskId) {
            taskRepository.completeTaskCollective(
                taskId = taskId,
                participantUserIds = participantUserIds,
                completedAt = isoDate,
            ).fold(
                onSuccess = { refresh() },
                onFailure = { err ->
                    val msg = when (err) {
                        is TaskRepository.CollectiveCompletionException -> when (err.httpCode) {
                            409 -> "Impossible de valider : tache deja completee recemment par un participant"
                            else -> "Erreur ${err.httpCode}"
                        }
                        else -> err.message ?: "Erreur reseau"
                    }
                    _uiState.value = _uiState.value.copy(error = msg)
                },
            )
        }
    }

    /**
     * Affiche la celebration (emoji + confetti) pendant ~2s, puis execute
     * le bloc de completion. A la fin de la celebration, ajoute le taskId
     * a justCompletedIds pour que le HomeScreen affiche instantanement la
     * suggestion suivante du tampon (au lieu de re-flasher la meme carte
     * pendant que le serveur rattrape le retard de sync).
     */
    private fun celebrateThen(taskId: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                celebratingTaskId = taskId,
                celebrationTrigger = _uiState.value.celebrationTrigger + 1,
            )
            kotlinx.coroutines.delay(2000L)
            _uiState.value = _uiState.value.copy(
                celebratingTaskId = null,
                justCompletedIds = _uiState.value.justCompletedIds + taskId,
            )
            block()
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}

/** Conteneur interne pour le combine des flows snapshot. */
private data class CachedHome(
    val suggestions: List<Suggestion>?,
    val fairShare: List<FairShareStatus>?,
    val pending: List<PendingAffinity>?,
    val settings: Map<String, Any>?,
)
