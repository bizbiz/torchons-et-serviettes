package com.torchons.app.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.core.proposition.PropositionStateHolder
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.FairShareStatus
import com.torchons.app.data.model.MaintenanceRateResponse
import com.torchons.app.data.model.ObjectiveUpdate
import com.torchons.app.data.model.PaceResponse
import com.torchons.app.data.model.PendingAffinity
import com.torchons.app.data.model.PromptResolveRequest
import com.torchons.app.data.model.PropositionView
import com.torchons.app.data.model.UserPrompt
import com.torchons.app.data.model.RetardResponse
import com.torchons.app.data.model.Suggestion
import com.torchons.app.data.model.ContextView
import com.torchons.app.data.model.Task
import com.torchons.app.data.model.UserPace
import com.torchons.app.data.repository.AuthRepository
import com.torchons.app.data.repository.CompletionChecks
import com.torchons.app.data.repository.HomeRepository
import com.torchons.app.data.repository.TaskRepository
import com.torchons.app.ui.common.RatingRecurrence
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
/** Contexte d'un FrequencyVoteDialog / EffortVoteDialog ouvert via prompt (v0.28). */
data class PromptVoteContext(
    val promptId: String,
    val taskId: String,
    val header: com.torchons.app.ui.common.VoteTaskHeader,
    val requesterName: String,
    val frequencyState: com.torchons.app.data.model.FrequencyVotesState? = null,
    val effortState: com.torchons.app.data.model.EffortVotesState? = null,
)

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
    /** Points gagnes a afficher dans la celebration (preview ou response reelle). */
    val celebrationPoints: Int? = null,
    /** Raison du multiplicateur (BONUS_RETARD / MALUS_AVANCE / FIRST_COMPLETION / NEUTRAL / PERSO). */
    val celebrationReason: String? = null,
    /**
     * Per-participant attribution shown in the celebration: (displayName,
     * pointsEarned) pairs. Single completion -> one entry; collective ->
     * one entry per participant (exact server split).
     */
    val celebrationEarnedBy: List<Pair<String, Int>>? = null,
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
     * v0.16+ : set des task_ids dismiss par l'user sur l'ecran Home (clic
     * sur le ✕ d'une SuggestionCard). Session-only in-memory : reset au
     * cold start, preservee a travers les LifecycleResumeEffect. Le HomeScreen
     * les filtre comme pour justCompletedIds. Quand un tier est entierement
     * dismiss (remainingByTier[tier] == 0), le ViewModel clear ce set et
     * emet un message sur `recycleToast`.
     */
    val dismissedIds: Set<String> = emptySet(),
    /**
     * True quand il y a des jours incertains non resolus (home freeze v2).
     * Le bandeau s'affiche en haut de la home.
     */
    val hasUnresolvedPresenceDays: Boolean = false,
    /** Nombre de jours incertains (pour le bandeau). */
    val unresolvedPresenceDaysCount: Int = 0,
    /**
     * Donnees des 2 cards projection (v0.8.0 magic buttons). Tout null tant
     * que le premier fetch n'a pas abouti.
     */
    val retard: RetardResponse? = null,
    val pace: PaceResponse? = null,
    val maintenance: MaintenanceRateResponse? = null,
    /**
     * Historique du retard du foyer sur les 30 derniers jours, fetch en
     * parallele pour la mini-courbe Home (period=month, sans trim creation).
     */
    val retardTimelineMonth: com.torchons.app.data.model.RetardTimelineResponse? = null,
    /** Prompt de la queue unifiee (BUMP_EFFORT / VOTE_RATING / VOTE_FREQUENCY / VOTE_EFFORT). */
    val currentPrompt: UserPrompt? = null,
    /** v0.28 : si un VOTE_FREQUENCY est dispatche, contexte du dialog en cours. */
    val pendingFrequencyVoteContext: PromptVoteContext? = null,
    /** v0.28 : si un VOTE_EFFORT est dispatche, contexte du dialog en cours. */
    val pendingEffortVoteContext: PromptVoteContext? = null,
    /** True quand le modal prompt est actif. */
    val showPromptModal: Boolean = false,
)

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
) : AndroidViewModel(application) {

    /** Proposition active (v0.8.1) — forwarded depuis PropositionStateHolder */
    val currentProposition: StateFlow<PropositionView?> = propositionStateHolder.currentProposition
    val propositionCelebration: StateFlow<PropositionView?> = propositionStateHolder.celebration
    val propositionError: StateFlow<String?> = propositionStateHolder.error

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

            // Sub-fetches en parallele. On rafraichit aussi rooms/categories/tasks
            // pour que les SuggestionCard puissent resoudre leur piece+categorie
            // au premier affichage Home (sinon il faut visiter TaskListScreen
            // d'abord pour que ces caches se remplissent).
            // v0.16+ : suggestions passe la dismiss list courante pour que le
            // refetch arrive pre-filtre et que remainingByTier soit precis.
            val currentDismissed = _uiState.value.dismissedIds
            val suggestionsDeferred = async {
                homeRepository.refreshSuggestions(excludeIds = currentDismissed)
            }
            val otherResults = listOf(
                async { homeRepository.refreshFairShare() },
                async { homeRepository.refreshPendingAffinity(6) },
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
            fetchNextPrompt()

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
                _uiState.value = _uiState.value.copy(error = e.message)
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
                        error = "Toutes les taches sont deja notees !",
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    fun completeTask(taskId: String, previewPoints: Int? = null, previewReason: String? = null) =
        unlessDuplicate(taskId) {
        celebrateThen(taskId, previewPoints, previewReason, earnedByFor(null, previewPoints)) {
            taskRepository.completeTask(taskId).fold(
                onSuccess = { refresh() },
                onFailure = { _uiState.value = _uiState.value.copy(error = it.message) },
            )
        }
    }

    /**
     * Builds the celebration attribution list for a single completion:
     * (displayName, points). completedBy null = current user. Returns null
     * when points are unknown (offline preview failure) — the celebration
     * then falls back to its no-points variant.
     */
    private fun earnedByFor(completedBy: String?, points: Int?): List<Pair<String, Int>>? {
        if (points == null) return null
        val name = if (completedBy == null) {
            displayName.value ?: return null
        } else {
            _uiState.value.fairShare.firstOrNull { it.userId == completedBy }?.displayName
                ?: return null
        }
        return listOf(name to points)
    }

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
            val result = homeRepository.refreshSuggestions(excludeIds = newDismissed)
            if (!result.success) return@launch
            val remaining = result.remainingByTier ?: emptyMap()
            lastRemainingByTier = remaining

            // 4) Recycle si le tier est epuise (backend n'a plus rien a
            //    proposer hors dismiss)
            if ((remaining[tier] ?: 0) == 0) {
                _uiState.value = _uiState.value.copy(dismissedIds = emptySet())
                val recycleResult = homeRepository.refreshSuggestions(excludeIds = emptySet())
                if (recycleResult.success) {
                    recycleResult.remainingByTier?.let { lastRemainingByTier = it }
                }
                _recycleToast.tryEmit(recycleMessage(tier))
            }
        }
    }

    /** Message Snackbar tier-aware pour le recycle dismiss. */
    private fun recycleMessage(tier: String): String = when (tier) {
        "urgent" -> "Plus de suggestions urgentes — reaffichage des taches ignorees"
        "affinity" -> "Plus de suggestions affinite — reaffichage des taches ignorees"
        "uncertain" -> "Plus de suggestions sans cadence — reaffichage des taches ignorees"
        else -> "Reaffichage des taches ignorees"
    }

    fun completeTaskAtDate(
        taskId: String,
        isoDate: String,
        completedBy: String? = null,
        previewPoints: Int? = null,
        previewReason: String? = null,
    ) = unlessDuplicate(taskId, isoDate, completedBy?.let(::listOf)) {
        celebrateThen(taskId, previewPoints, previewReason, earnedByFor(completedBy, previewPoints)) {
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
        previewPoints: Int? = null,
        previewReason: String? = null,
    ) = unlessDuplicate(taskId, isoDate, participantUserIds) {
        celebrateThen(taskId, previewPoints, previewReason) {
            taskRepository.completeTaskCollective(
                taskId = taskId,
                participantUserIds = participantUserIds,
                completedAt = isoDate,
            ).fold(
                onSuccess = { response ->
                    // Refresh the celebration with the exact server values:
                    // per-participant attribution (split) + points/reason.
                    // Guard on celebratingTaskId — a late response must not
                    // write stale content after the celebration ended.
                    if (_uiState.value.celebratingTaskId == taskId) {
                        val first = response.completions.firstOrNull()
                        val names = _uiState.value.fairShare.associate { it.userId to it.displayName }
                        val earnedBy = response.completions.mapNotNull { c ->
                            names[c.completedBy]?.let { it to c.pointsEarned }
                        }.ifEmpty { null }
                        _uiState.value = _uiState.value.copy(
                            celebrationPoints = _uiState.value.celebrationPoints ?: first?.pointsEarned,
                            celebrationReason = _uiState.value.celebrationReason ?: first?.reason,
                            celebrationEarnedBy = earnedBy,
                        )
                    }
                    refresh()
                },
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
    private fun celebrateThen(
        taskId: String,
        points: Int?,
        reason: String?,
        earnedBy: List<Pair<String, Int>>? = null,
        block: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                celebratingTaskId = taskId,
                celebrationTrigger = _uiState.value.celebrationTrigger + 1,
                celebrationPoints = points,
                celebrationReason = reason,
                celebrationEarnedBy = earnedBy,
            )
            // Run the completion CONCURRENTLY with the celebration so server
            // responses (exact collective split) can refresh the celebration
            // content while it is still on screen. The celebrating card stays
            // visible through celebratingTaskId; justCompletedIds takes over
            // the filtering at reset time.
            val work = launch { block() }
            kotlinx.coroutines.delay(3000L)
            _uiState.value = _uiState.value.copy(
                celebratingTaskId = null,
                celebrationPoints = null,
                celebrationReason = null,
                celebrationEarnedBy = null,
                justCompletedIds = _uiState.value.justCompletedIds + taskId,
            )
            work.join()
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /** Refuses a duplicate completion BEFORE the celebration (CompletionChecks.refusal); [by] null = me. */
    private fun unlessDuplicate(taskId: String, at: String? = null, by: List<String>? = null, go: () -> Unit) {
        viewModelScope.launch {
            val refusal = completionChecks.refusal(taskId, at, by)
            if (refusal == null) go() else _uiState.value = _uiState.value.copy(error = refusal)
        }
    }

    /** Points preview of a completion; null offline or on error (the UI falls back to /suggestions values). */
    suspend fun fetchCompletionPreview(taskId: String, isoDate: String, numParticipants: Int) =
        completionChecks.preview(taskId, isoDate, numParticipants)

    // --- Queue unifiee des prompts (BUMP_EFFORT / VOTE_RATING) ---

    /**
     * Fetch le prochain prompt non-resolu. Respecte la tolerance 2h : si un
     * prompt a ete affiche il y a moins de 2h, on ne fetch pas.
     *
     * Appele dans refresh() apres les projections.
     */
    private suspend fun fetchNextPrompt() {
        try {
            val resp = apiService.getNextPrompt()
            if (resp.isSuccessful && resp.body() != null) {
                val prompt = resp.body()!!

                // Never replace a prompt the user is already looking at (modal or vote dialog).
                val open = _uiState.value
                if (PromptPolicy.isPromptOpen(
                        open.showPromptModal,
                        open.pendingFrequencyVoteContext != null,
                        open.pendingEffortVoteContext != null,
                    )
                ) return
                val isPresencePrompt = prompt.type == "RESOLVE_PRESENCE"
                // Presence and member-requested re-votes bypass the 2 h tolerance.
                val lastShown = tokenManager.lastPromptShownAtFlow.first()
                if (PromptPolicy.isHeldBackByTolerance(prompt.type, lastShown, System.currentTimeMillis())) return
                apiService.markPromptShown(prompt.id)
                if (!PromptPolicy.bypassesTolerance(prompt.type)) {
                    tokenManager.setLastPromptShownAt(System.currentTimeMillis())
                }
                _uiState.value = _uiState.value.copy(
                    currentPrompt = prompt,
                    showPromptModal = true,
                    // Mettre a jour le bandeau si c'est un prompt RESOLVE_PRESENCE
                    hasUnresolvedPresenceDays = isPresencePrompt,
                    unresolvedPresenceDaysCount = if (isPresencePrompt) {
                        @Suppress("UNCHECKED_CAST")
                        val days = prompt.context["uncertain_days"] as? List<*>
                        val count = prompt.context["uncertain_days_count"] as? Number
                        days?.size ?: count?.toInt() ?: 0
                    } else _uiState.value.unresolvedPresenceDaysCount,
                )
            } else {
                // Pas de prompt pending -> pas de jours incertains
                _uiState.value = _uiState.value.copy(
                    hasUnresolvedPresenceDays = false,
                    unresolvedPresenceDaysCount = 0,
                )
            }
        } catch (_: Exception) {
            // offline — pas de prompt
        }
    }

    /** User repond au prompt (ANSWERED avec payload). */
    fun resolveCurrentPrompt(resolution: String, payload: Map<String, Any?>? = null) {
        val prompt = _uiState.value.currentPrompt ?: return
        val wasPresencePrompt = prompt.type == "RESOLVE_PRESENCE"
        val wasDismissed = resolution == "DISMISSED"
        viewModelScope.launch {
            try {
                apiService.resolvePrompt(prompt.id, PromptResolveRequest(resolution, payload))
            } catch (_: Exception) {}
            _uiState.value = _uiState.value.copy(
                currentPrompt = null,
                showPromptModal = false,
                // Si un RESOLVE_PRESENCE est DISMISSED ("Plus tard"), on garde
                // le bandeau visible — les taches restent freezees.
                hasUnresolvedPresenceDays = wasPresencePrompt && wasDismissed,
            )
            refresh()
        }
    }

    /** User ferme le prompt (DISMISSED). */
    fun dismissCurrentPrompt() {
        resolveCurrentPrompt("DISMISSED")
    }

    // --- v0.28 : dispatch des prompts VOTE_FREQUENCY / VOTE_EFFORT ---

    /**
     * Ouvre le FrequencyVoteDialog en reponse a un prompt VOTE_FREQUENCY.
     * Fetch le state a la volee, garde le promptId pour resolve apres submit.
     */
    fun openFrequencyVoteFromPrompt(taskId: String, requesterName: String) {
        val prompt = _uiState.value.currentPrompt ?: return
        viewModelScope.launch {
            try {
                val resp = apiService.getFrequencyVotes(taskId)
                if (resp.isSuccessful) {
                    val state = resp.body()
                    val task = activeTasks.value.firstOrNull { it.id == taskId }
                    if (state != null && task != null) {
                        _uiState.value = _uiState.value.copy(
                            pendingFrequencyVoteContext = PromptVoteContext(
                                promptId = prompt.id,
                                taskId = taskId,
                                header = com.torchons.app.ui.common.voteHeader(task),
                                requesterName = requesterName,
                                frequencyState = state,
                            ),
                            // On garde currentPrompt pour pouvoir resolve, mais on
                            // cache le PromptModal de queue (le dialog vote prend la main).
                            showPromptModal = false,
                        )
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun openEffortVoteFromPrompt(taskId: String, requesterName: String) {
        val prompt = _uiState.value.currentPrompt ?: return
        viewModelScope.launch {
            try {
                val resp = apiService.getEffortVotes(taskId)
                if (resp.isSuccessful) {
                    val state = resp.body()
                    val task = activeTasks.value.firstOrNull { it.id == taskId }
                    if (state != null && task != null) {
                        _uiState.value = _uiState.value.copy(
                            pendingEffortVoteContext = PromptVoteContext(
                                promptId = prompt.id,
                                taskId = taskId,
                                header = com.torchons.app.ui.common.voteHeader(task),
                                requesterName = requesterName,
                                effortState = state,
                            ),
                            showPromptModal = false,
                        )
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun submitPromptFrequencyVote(request: com.torchons.app.data.model.FrequencyRequest) {
        val ctx = _uiState.value.pendingFrequencyVoteContext ?: return
        viewModelScope.launch {
            taskRepository.voteFrequency(ctx.taskId, request)
            try {
                apiService.resolvePrompt(ctx.promptId, PromptResolveRequest("ANSWERED"))
            } catch (_: Exception) {}
            _uiState.value = _uiState.value.copy(
                pendingFrequencyVoteContext = null,
                currentPrompt = null,
            )
            refresh()
        }
    }

    fun submitPromptEffortVote(points: Int) {
        val ctx = _uiState.value.pendingEffortVoteContext ?: return
        viewModelScope.launch {
            try {
                apiService.setEffort(ctx.taskId, mapOf("points" to points))
                apiService.resolvePrompt(ctx.promptId, PromptResolveRequest("ANSWERED"))
            } catch (_: Exception) {}
            _uiState.value = _uiState.value.copy(
                pendingEffortVoteContext = null,
                currentPrompt = null,
            )
            refresh()
        }
    }

    fun dismissPromptFrequencyVote() {
        val ctx = _uiState.value.pendingFrequencyVoteContext ?: return
        viewModelScope.launch {
            try {
                apiService.resolvePrompt(ctx.promptId, PromptResolveRequest("DISMISSED"))
            } catch (_: Exception) {}
            _uiState.value = _uiState.value.copy(pendingFrequencyVoteContext = null, currentPrompt = null)
            refresh() // chain to the next queued re-vote, as a submit does
        }
    }

    fun dismissPromptEffortVote() {
        val ctx = _uiState.value.pendingEffortVoteContext ?: return
        viewModelScope.launch {
            try {
                apiService.resolvePrompt(ctx.promptId, PromptResolveRequest("DISMISSED"))
            } catch (_: Exception) {}
            _uiState.value = _uiState.value.copy(pendingEffortVoteContext = null, currentPrompt = null)
            refresh() // chain to the next queued re-vote, as a submit does
        }
    }

    /** Reouvrir le prompt RESOLVE_PRESENCE depuis le bandeau. */
    fun reopenPresencePrompt() {
        viewModelScope.launch {
            try {
                val resp = apiService.getNextPrompt()
                if (resp.isSuccessful && resp.body() != null) {
                    val prompt = resp.body()!!
                    if (prompt.type == "RESOLVE_PRESENCE") {
                        apiService.markPromptShown(prompt.id)
                        _uiState.value = _uiState.value.copy(
                            currentPrompt = prompt,
                            showPromptModal = true,
                        )
                    }
                }
            } catch (_: Exception) {}
        }
    }

}

/** Conteneur interne pour le combine des flows snapshot. */
private data class CachedHome(
    val suggestions: List<Suggestion>?,
    val fairShare: List<FairShareStatus>?,
    val pending: List<PendingAffinity>?,
    val settings: Map<String, Any>?,
)
