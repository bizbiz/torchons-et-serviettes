package com.torchons.app.ui.home

import com.torchons.app.R
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.core.proposition.PropositionStateHolder
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.FairShareStatus
import com.torchons.app.data.model.ObjectiveUpdate
import com.torchons.app.data.model.PendingAffinity
import com.torchons.app.data.model.PropositionView
import com.torchons.app.data.model.Suggestion
import com.torchons.app.data.model.ContextView
import com.torchons.app.data.model.Task
import com.torchons.app.data.model.UserPace
import com.torchons.app.data.repository.AuthRepository
import com.torchons.app.data.repository.CompletionChecks
import com.torchons.app.data.repository.DelegationRepository
import com.torchons.app.ui.today.TodayDelegations
import com.torchons.app.data.repository.HomeRepository
import com.torchons.app.data.repository.TaskRepository
import com.torchons.app.ui.common.RatingRecurrence
import com.torchons.app.ui.common.UserError
import com.torchons.app.ui.common.UserErrors
import com.torchons.app.util.PopupSessionGate
import com.torchons.app.util.WifiHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    application: Application,
    private val apiService: ApiService,
    private val homeRepository: HomeRepository,
    private val authRepository: AuthRepository,
    private val taskRepository: TaskRepository,
    private val completionChecks: CompletionChecks,
    private val popupSessionGate: PopupSessionGate,
    private val ratingCadence: RatingCadence,
    private val tokenManager: TokenManager,
    private val propositionStateHolder: PropositionStateHolder,
    delegationRepository: DelegationRepository,
) : AndroidViewModel(application) {

    /** The red « À faire aujourd'hui » stack (HomeTodayStack.kt), and what leaves the suggestions. */
    internal val todayStack = HomeTodayStack(
        viewModelScope,
        TodayDelegations(viewModelScope, delegationRepository),
        taskRepository.observeActiveTasks(),
        taskRepository.observeRecentCompletions(limit = 200),
        authRepository.userIdFlow,
    ) { getApplication<Application>().resources }

    /** Member names by id, for « Confiée par Alice ». */
    val memberNames: StateFlow<Map<String, String>> = taskRepository.observeUsers()
        .map { users -> users.associate { it.id to it.displayName } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Proposition active (v0.8.1) — forwarded depuis PropositionStateHolder */
    val currentProposition: StateFlow<PropositionView?> = propositionStateHolder.currentProposition
    val propositionCelebration: StateFlow<PropositionView?> = propositionStateHolder.celebration
    val propositionError: StateFlow<UserError?> = propositionStateHolder.error

    private val _uiState = MutableStateFlow(HomeUiState(isLoading = true))
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /**
     * v0.16+ : toasts/snackbars du recycle dismiss. Emis une seule fois
     * quand un tier devient vide (toutes ses suggestions dismiss). Le
     * HomeScreen observe ce SharedFlow et appelle SnackbarHostState.
     * SharedFlow (pas StateFlow) : evite que le meme message ne soit
     * rejoue sur chaque recomposition.
     */
    private val _recycleToast = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1)
    val recycleToast: SharedFlow<String> = _recycleToast.asSharedFlow()

    /**
     * v0.16+ : nombre de candidats restants par tier (hors dismiss list).
     * Renseigne a chaque refresh/refetch — sert au declenchement du recycle.
     * Non expose car purement interne au flow de dismiss.
     */
    private var lastRemainingByTier: Map<String, Int> = emptyMap()

    val userId: StateFlow<String?> = authRepository.userIdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val displayName: StateFlow<String?> = authRepository.displayNameFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Cache partage avec TaskListViewModel pour resoudre les contextes
     * affiches sur les cartes Home (suggestions + in-progress). Pas de fetch
     * supplementaire — on consomme directement le flow du repo.
     */
    /** Contextes du foyer, pour les cartes de l'accueil. */
    val contexts: StateFlow<List<ContextView>> = taskRepository.observeContexts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeTasks: StateFlow<List<Task>> = taskRepository.observeActiveTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * True once the cache says the household has no active task; null until Room answers,
     * so a household with tasks never flashes the "no task yet" invitation at startup.
     */
    val hasNoTasks: StateFlow<Boolean?> = taskRepository.observeActiveTasks()
        .map { it.isEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Pour chaque tache, la completedAt la plus recente (ISO-8601). Utilise
     * par la carte "Sans cadence" (v0.14) pour afficher le label factuel
     * "Fait il y a X j". Derive du cache local des 200 dernieres completions
     * (suffisant pour un couple : ~1 task/j × 200 = 6 mois d'historique).
     */
    val lastCompletionsByTask: StateFlow<Map<String, String>> =
        taskRepository.observeRecentCompletions(limit = 200)
            .map { list ->
                list.groupBy { it.taskId }
                    .mapValues { (_, comps) -> comps.maxOf { it.completedAt } }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _affinityPopupEnabled = MutableStateFlow(true)
    val affinityPopupEnabled: StateFlow<Boolean> = _affinityPopupEnabled.asStateFlow()

    /** v0.32 : visibilite UI gamif (points/bonus/celebration/stats) — combine
     *  user-level (UserSettings.gamification_enabled) AND foyer-level
     *  (Foyer.gamification_enabled) cf. design design review. La home cache la
     *  ligne points, la pastille bonus, et "+X pts" sur la celebration. */
    val gamificationVisible: StateFlow<Boolean> = tokenManager.gamificationVisibleFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val prompts = HomePromptActions(
        scope = viewModelScope,
        uiState = _uiState,
        apiService = apiService,
        taskRepository = taskRepository,
        tokenManager = tokenManager,
        activeTasks = { activeTasks.value },
        refresh = ::refresh,
    )

    private val completions = HomeCompletionActions(
        scope = viewModelScope,
        uiState = _uiState,
        application = application,
        taskRepository = taskRepository,
        completionChecks = completionChecks,
        displayName = { displayName.value },
        refresh = ::refresh,
    )

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
                ratingCadence.syncDue(cached.settings)

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
            // Delegations before the suggestions: the tasks handed by or to me leave them.
            todayStack.today.refresh()

            // Sub-fetches en parallele. On rafraichit aussi rooms/categories/tasks
            // pour que les SuggestionCard puissent resoudre leur piece+categorie
            // au premier affichage Home (sinon il faut visiter TaskListScreen
            // d'abord pour que ces caches se remplissent).
            // v0.16+ : suggestions passe la dismiss list courante pour que le
            // refetch arrive pre-filtre et que remainingByTier soit precis.
            val currentDismissed = _uiState.value.dismissedIds
            val suggestionsDeferred = async {
                homeRepository.refreshSuggestions(excludeIds = currentDismissed + todayStack.excluded.value)
            }
            val otherResults = listOf(
                async { homeRepository.refreshFairShare() },
                async { homeRepository.refreshPendingAffinity(tokenManager.affinityExpiryFlow.first()) },
                async { taskRepository.refreshContexts() },
                async { taskRepository.refreshTasks() },
                // v0.32 : flag gamif du foyer courant (pour que l'UI gamif
                // disparaisse instantanement si l'autre membre a toggle).
                async { homeRepository.refreshCurrentFoyerGamification() },
            ).awaitAll()
            val suggestionsResult = suggestionsDeferred.await()
            if (suggestionsResult.success) {
                suggestionsResult.remainingByTier?.let { lastRemainingByTier = it }
            }
            val results = listOf(suggestionsResult.success) + otherResults

            // v0.8.0 : projection (retard, pace, maintenance) en parallele.
            // Non critique — si ca echoue les cards affichent un etat de loading
            // ou des valeurs par defaut.
            refreshProjection()

            // v1.1 : queue unifiee des prompts (remplace /pending-affinity).
            prompts.fetchNextPrompt()

            val allOk = settingsOk && results.all { it }

            // Afficher la popup affinite UNE SEULE FOIS apres le refresh
            // (legacy — garde le fallback tant que la queue n'est pas peuplee)
            val pending = _uiState.value.pendingAffinity
            val popupEnabled = _affinityPopupEnabled.value
            val promptAlreadyShown = _uiState.value.showPromptModal

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                isRefreshing = false,
                lastRefreshFailed = !allOk,
                showAffinityPopup = ratingCadence.resolveAuto(
                    eligible = pending != null
                        && popupSessionGate.armed.value
                        && popupEnabled
                        && !promptAlreadyShown,  // le prompt unifie a priorite
                    wasShown = _uiState.value.showAffinityPopup,
                ),
            )
        }
    }

    /** Alias historique conserve pour la compat avec HomeScreen. */
    fun loadData() = refresh()

    /**
     * Checkin presence WiFi au backend (v2 home freeze). Lit le SSID actuel
     * via WifiHelper et n'appelle POST /presence/checkin QUE si le WiFi maison
     * est detecte (signal positif uniquement). Si pas de WiFi ou WiFi inconnu,
     * on ne fait rien — l'absence de signal n'est pas un signal d'absence.
     *
     * Le backend insere un confirmed_present pour aujourd'hui et genere un
     * prompt RESOLVE_PRESENCE s'il y a des jours incertains a resoudre.
     */
    fun reportPresenceNow() {
        viewModelScope.launch {
            try {
                val homeSsids = tokenManager.homeSsidsFlow.first()
                if (homeSsids.isEmpty()) return@launch
                val ctx = getApplication<Application>().applicationContext
                val current = WifiHelper.getCurrentSsid(ctx) ?: return@launch
                val isAtHome = homeSsids.any { it.equals(current, ignoreCase = true) }
                if (!isAtHome) return@launch
                // Signal positif : on est sur le WiFi maison -> checkin
                apiService.presenceCheckin()
            } catch (_: Exception) {
                // best effort
            }
        }
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
        val timelineDeferred = viewModelScope.async {
            runCatching { apiService.getRetardTimeline("month") }.getOrNull()
        }
        val retardResp = retardDeferred.await()
        val paceResp = paceDeferred.await()
        val maintResp = maintDeferred.await()
        val timelineResp = timelineDeferred.await()
        _uiState.value = _uiState.value.copy(
            retard = if (retardResp?.isSuccessful == true) retardResp.body() else _uiState.value.retard,
            pace = if (paceResp?.isSuccessful == true) paceResp.body() else _uiState.value.pace,
            maintenance = if (maintResp?.isSuccessful == true) maintResp.body() else _uiState.value.maintenance,
            retardTimelineMonth = if (timelineResp?.isSuccessful == true)
                timelineResp.body() else _uiState.value.retardTimelineMonth,
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
                _uiState.value = _uiState.value.copy(error = UserErrors.from(getApplication(), e))
            }
        }
    }

    /**
     * Proposer un objectif multi-user au foyer (v0.8.1). Declenche le flow
     * live FCM — le proposer rentre en ecran d'attente bloquant, les
     * receveurs recoivent un modal accept/decline.
     */
    fun proposeToFoyer(sliderValues: Map<String, Int>, unit: String) {
        // Moved to the shared holder: the Foyer screen proposes through it too.
        propositionStateHolder.propose(sliderValues, unit)
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

    fun submitRating(taskId: String, affinity: Int, effortPoints: Int, recurrence: RatingRecurrence?) {
        viewModelScope.launch {
            try {
                saveRatingData(apiService, taskRepository, taskId, affinity, effortPoints, recurrence)
                popupSessionGate.disarm()
                _uiState.value = _uiState.value.copy(showAffinityPopup = false)
                ratingCadence.onRated()
                refresh()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = UserErrors.from(getApplication(), e))
            }
        }
    }

    fun submitRatingAndNext(taskId: String, affinity: Int, effortPoints: Int, recurrence: RatingRecurrence?) {
        viewModelScope.launch {
            try {
                saveRatingData(apiService, taskRepository, taskId, affinity, effortPoints, recurrence)
                _uiState.value = _uiState.value.copy(showAffinityPopup = false)
                ratingCadence.onRated()

                // Essayer d'abord les taches non-notees
                homeRepository.refreshPendingAffinity(tokenManager.affinityExpiryFlow.first())
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
                        error = UserError(getApplication<Application>().getString(R.string.home_all_rated)),
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = UserErrors.from(getApplication(), e))
            }
        }
    }

    fun dismissAffinityPopup() {
        ratingCadence.onDismissed()
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
                    ratingCadence.onManualOpened()
                    _uiState.value = _uiState.value.copy(
                        pendingAffinity = task,
                        showAffinityPopup = true,
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        error = UserError(getApplication<Application>().getString(R.string.home_all_rated)),
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    fun completeTask(taskId: String, previewPoints: Int? = null, previewReason: String? = null) =
        completions.completeTask(taskId, previewPoints, previewReason)

    fun completeTaskAtDate(
        taskId: String,
        isoDate: String,
        completedBy: String? = null,
        previewPoints: Int? = null,
        previewReason: String? = null,
    ) = completions.completeTaskAtDate(taskId, isoDate, completedBy, previewPoints, previewReason)

    /** Completion collective ("fait ensemble"). Online-first via endpoint dedie. */
    fun completeTaskCollective(
        taskId: String,
        participantUserIds: List<String>,
        isoDate: String? = null,
        previewPoints: Int? = null,
        previewReason: String? = null,
    ) = completions.completeTaskCollective(taskId, participantUserIds, isoDate, previewPoints, previewReason)

    /** Points preview of a completion; null offline or on error (the UI falls back to /suggestions values). */
    suspend fun fetchCompletionPreview(taskId: String, isoDate: String, numParticipants: Int) =
        completions.fetchCompletionPreview(taskId, isoDate, numParticipants)

    /**
     * v0.16+ : clic sur le ✕ d'une SuggestionCard.
     *
     * 1) Masque immediatement la tache (ajout a dismissedIds) — le filtre
     *    cote UI fait apparaitre la suivante du pool sans latence.
     * 2) Fire-and-forget log backend pour analytics.
     * 3) Refetch /suggestions avec ?exclude=<dismissedIds> pour rafraichir
     *    le pool local et obtenir remaining_by_tier mis a jour.
     * 4) Si remaining_by_tier[tier] == 0 ET le pool filtre ne contient plus
     *    rien pour ce tier, on recycle : clear global de dismissedIds +
     *    refetch sans exclude + Snackbar tier-aware.
     */
    fun dismissSuggestion(taskId: String, tier: String) {
        viewModelScope.launch {
            // 1) Masquage immediat
            val newDismissed = _uiState.value.dismissedIds + taskId
            _uiState.value = _uiState.value.copy(dismissedIds = newDismissed)

            // 2) Log analytics (fire-and-forget — les erreurs sont deja
            //    silencieuses cote repository)
            launch { homeRepository.logSuggestionDismiss(taskId, tier) }

            // 3) Refetch avec la dismiss list a jour
            val result = homeRepository.refreshSuggestions(excludeIds = newDismissed + todayStack.excluded.value)
            if (!result.success) return@launch
            val remaining = result.remainingByTier ?: emptyMap()
            lastRemainingByTier = remaining

            // 4) Recycle si le tier est epuise (backend n'a plus rien a
            //    proposer hors dismiss)
            if ((remaining[tier] ?: 0) == 0) {
                _uiState.value = _uiState.value.copy(dismissedIds = emptySet())
                val recycleResult = homeRepository.refreshSuggestions(excludeIds = todayStack.excluded.value)
                if (recycleResult.success) {
                    recycleResult.remainingByTier?.let { lastRemainingByTier = it }
                }
                _recycleToast.tryEmit(recycleMessage(tier))
            }
        }
    }

    /** Message Snackbar tier-aware pour le recycle dismiss. */
    private fun recycleMessage(tier: String): String = when (tier) {
        "urgent" -> getApplication<Application>().getString(R.string.home_recycle_urgent)
        "affinity" -> getApplication<Application>().getString(R.string.home_recycle_affinity)
        "uncertain" -> getApplication<Application>().getString(R.string.home_recycle_uncertain)
        else -> getApplication<Application>().getString(R.string.home_recycle_other)
    }

    /** The cross of the red stack: the next card; after the last one, they all come back. */
    fun passTodayCard(taskId: String) {
        if (todayStack.pass(taskId)) {
            _recycleToast.tryEmit(getApplication<Application>().getString(R.string.today_recycle))
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    // --- Queue unifiee des prompts (BUMP_EFFORT / VOTE_RATING) : HomePromptActions ---

    /** User repond au prompt (ANSWERED avec payload). */
    fun resolveCurrentPrompt(resolution: String, payload: Map<String, Any?>? = null) =
        prompts.resolveCurrentPrompt(resolution, payload)

    /** User ferme le prompt (DISMISSED). */
    fun dismissCurrentPrompt() = prompts.dismissCurrentPrompt()

    fun openFrequencyVoteFromPrompt(taskId: String, requesterName: String) =
        prompts.openFrequencyVoteFromPrompt(taskId, requesterName)

    fun openEffortVoteFromPrompt(taskId: String, requesterName: String) =
        prompts.openEffortVoteFromPrompt(taskId, requesterName)

    fun submitPromptFrequencyVote(request: com.torchons.app.data.model.FrequencyRequest) =
        prompts.submitPromptFrequencyVote(request)

    fun submitPromptEffortVote(points: Int) = prompts.submitPromptEffortVote(points)

    fun dismissPromptFrequencyVote() = prompts.dismissPromptFrequencyVote()

    fun dismissPromptEffortVote() = prompts.dismissPromptEffortVote()

    /** Reouvrir le prompt RESOLVE_PRESENCE depuis le bandeau. */
    fun reopenPresencePrompt() = prompts.reopenPresencePrompt()
}

/** Conteneur interne pour le combine des flows snapshot. */
private data class CachedHome(
    val suggestions: List<Suggestion>?,
    val fairShare: List<FairShareStatus>?,
    val pending: List<PendingAffinity>?,
    val settings: Map<String, Any>?,
)
