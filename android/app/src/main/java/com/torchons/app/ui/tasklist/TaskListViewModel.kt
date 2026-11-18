package com.torchons.app.ui.tasklist

import com.torchons.app.R
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.User
import com.torchons.app.data.repository.AuthRepository
import com.torchons.app.data.repository.CompletionChecks
import com.torchons.app.data.repository.DelegationRepository
import com.torchons.app.data.repository.TaskRepository
import com.torchons.app.ui.today.TodayDelegations
import com.torchons.app.util.CompletionDateLabels
import com.torchons.app.util.WifiHelper
import java.time.LocalDate
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel de la liste des taches — pattern stale-while-revalidate.
 *
 * Observe les donnees depuis Room en continu via combine() et lance un
 * refresh() API en background. L'ecran affiche le cache instantanement
 * meme si le backend est injoignable.
 */
@HiltViewModel
class TaskListViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val completionChecks: CompletionChecks,
    private val authRepository: AuthRepository,
    private val tokenManager: TokenManager,
    private val apiService: ApiService,
    @ApplicationContext private val context: Context,
    delegationRepository: DelegationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TaskListUiState(isLoading = true))
    val uiState: StateFlow<TaskListUiState> = _uiState.asStateFlow()

    val displayName: StateFlow<String?> = authRepository.displayNameFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val userId: StateFlow<String?> = authRepository.userIdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** « À faire aujourd'hui »: delegations by task, hand / cancel (offline-first). */
    val today = TodayDelegations(viewModelScope, delegationRepository)

    val allUsers: StateFlow<List<User>> = taskRepository.observeUsers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** v0.32 : visibilite UI gamif (user-level AND foyer-level). */
    val gamificationVisible: StateFlow<Boolean> = tokenManager.gamificationVisibleFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val filtersFlow = MutableStateFlow(TaskListFilters())

    init {
        observeCache()
        refresh()
    }

    /**
     * Observe les donnees Room + filtres en continu. Le combine emet a chaque
     * fois qu'un des flux change (nouveau cache apres refresh, ou changement
     * de filtre). Garantit affichage instantane au demarrage.
     */
    private fun observeCache() {
        viewModelScope.launch {
            // Tout le pipeline (combine + applyFilters + WifiHelper) tourne sur
            // Dispatchers.Default pour ne pas bloquer le main thread. Sinon
            // chaque changement de filtre / refresh fait un freeze visible
            // pendant le scroll.
            combine(
                taskRepository.observeActiveTasks(),
                taskRepository.observeRecentCompletions(2000),
                filtersFlow,
            ) { tasks, completions, filters ->
                val homeSsids = tokenManager.homeSsidsFlow.first()
                val isAtHome = WifiHelper.isAtHome(context, homeSsids)
                val currentUserId = tokenManager.userIdFlow.first()
                val hideHomeTasksOffWifi = tokenManager.hideHomeTasksOffWifiFlow.first()
                val filtered = applyFilters(
                    tasks, completions, filters,
                    isAtHome, currentUserId, hideHomeTasksOffWifi, context.resources,
                )
                ProcessedTaskList(
                    filtered = filtered,
                    hasCache = tasks.isNotEmpty(),
                    filters = filters,
                )
            }.flowOn(Dispatchers.Default).collect { processed ->
                // Attention : on ne recopie PAS searchQuery / searchRespectFilters
                // ici. Ces champs sont mis a jour synchroniquement dans
                // updateSearchQuery / toggleSearchRespectFilters pour eviter que
                // le pipeline async (qui fait applyFilters sur Dispatchers.Default)
                // ne remette une version en retard dans le TextField. Sans ca,
                // le curseur "revient en arriere" quand l'utilisateur tape vite.
                _uiState.value = _uiState.value.copy(
                    tasks = processed.filtered.tasks,
                    availableContexts = processed.filtered.contexts,
                    isAtHome = processed.filtered.isAtHome,
                    homeTasksHidden = processed.filtered.homeTasksHidden,
                    showAllTasks = processed.filters.showAllTasks,
                    filterTodo = processed.filters.filterTodo,
                    filterAffinity = processed.filters.filterAffinity,
                    filterEffort = processed.filters.filterEffort,
                    filterRecurrence = processed.filters.filterRecurrence,
                    filterHome = processed.filters.filterHome,
                    filterAssignment = processed.filters.filterAssignment,
                    selectedContextIds = processed.filters.selectedContextIds,
                    hasCachedData = processed.hasCache,
                    isLoading = if (processed.hasCache) false else _uiState.value.isLoading,
                )
            }
        }
    }

    /**
     * Refresh les donnees depuis l'API en parallele. Echec silencieux :
     * si le backend ne repond pas, le cache Room reste affiche et
     * lastRefreshFailed passe a true.
     */
    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true)

            val results = listOf(
                async { taskRepository.refreshTasks() },
                async { taskRepository.refreshContexts() },
                // limit=2000 : sans ca, le backend defaulte a 50 et le repo
                // fait replaceAll, ce qui wipe toute completion plus ancienne
                // que le 50e rang global. Conseequence : tasks recurrentes a
                // long intervalle (ex: 45j) perdaient leur derniere completion
                // du cache, retombaient sur createdAt dans computeDueDate et
                // s'affichaient faussement en retard. Doit couvrir plus
                // largement que observeRecentCompletions(2000) pour rester
                // aligne.
                async { taskRepository.refreshCompletions(limit = 2000) },
                async { taskRepository.refreshUsers() },
                async { today.refresh() },
            ).awaitAll()

            try {
                val affResp = apiService.getMyAffinities()
                if (affResp.isSuccessful) {
                    val affinities = (affResp.body() ?: emptyList()).associate {
                        (it["task_id"] as? String ?: "") to ((it["score"] as? Double)?.toInt() ?: 5)
                    }
                    filtersFlow.value = filtersFlow.value.copy(myAffinities = affinities)
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

    fun loadData() = refresh()

    /**
     * Completion immediate par l'utilisateur courant, datee maintenant (bouton
     * "fait" ✓). On recupere le preview des points (J-0, solo) pour que la
     * celebration affiche "+N pts" attribues a "Moi" — parite avec l'Accueil.
     */
    fun completeTask(taskId: String) = unlessDuplicate(taskId) {
        celebrate(
            taskId = taskId,
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
                        _uiState.value = _uiState.value.copy(
                            completionMessage = context.getString(R.string.common_task_completed),
                        )
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(
                            error = error.message ?: context.getString(R.string.common_error_complete),
                        )
                    },
                )
            },
        )
    }

    /**
     * Completion datee (dialog "Deja fait"), potentiellement au nom d'un autre
     * membre. Le preview vient du dialog (deja calcule) ; l'attribution suit
     * [completedBy] pour montrer "pour qui vont les points" si ce n'est pas moi.
     */
    fun completeTaskAtDate(
        taskId: String,
        isoDate: String,
        completedBy: String? = null,
        previewPoints: Int? = null,
        previewReason: String? = null,
    ) = unlessDuplicate(taskId, isoDate, completedBy?.let(::listOf)) {
        celebrate(
            taskId = taskId,
            resolve = {
                CelebrationContent(previewPoints, previewReason, earnedByFor(completedBy, previewPoints))
            },
            write = {
                taskRepository.completeTask(taskId, completedAt = isoDate, completedBy = completedBy).fold(
                    onSuccess = {
                        _uiState.value = _uiState.value.copy(
                            completionMessage = context.getString(R.string.common_task_completed),
                        )
                    },
                    onFailure = { error ->
                        _uiState.value = _uiState.value.copy(error = error.message)
                    },
                )
            },
        )
    }

    /** Completion collective ("fait ensemble"). Online-first, split ceil. */
    fun completeTaskCollective(
        taskId: String,
        participantUserIds: List<String>,
        isoDate: String? = null,
        previewPoints: Int? = null,
        previewReason: String? = null,
    ) = unlessDuplicate(taskId, isoDate, participantUserIds) {
        celebrate(
            taskId = taskId,
            resolve = {
                // Points par personne : soit le preview passe par le dialog,
                // soit un fetch (chemin rapide 2-pers sans dialog). Le preview
                // divise deja par le nombre de participants (final_per_user).
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
                            completionMessage = context.resources.getQuantityString(
                                R.plurals.common_task_completed_by_count,
                                participantUserIds.size,
                                participantUserIds.size,
                            ),
                        )
                    },
                    onFailure = { err ->
                        val msg = when (err) {
                            is TaskRepository.CollectiveCompletionException -> when (err.httpCode) {
                                409 -> context.getString(R.string.common_collective_conflict)
                                else -> context.getString(R.string.common_error_code_format, err.httpCode)
                            }
                            else -> err.message ?: context.getString(R.string.common_error_network)
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
     * Attribution single (displayName, points) pour la celebration.
     * [completedBy] null = utilisateur courant. Null si les points ou le nom
     * sont inconnus (preview offline) — la celebration retombe sur sa variante
     * sans points.
     */
    private fun earnedByFor(completedBy: String?, points: Int?): List<Pair<String, Int>>? {
        if (points == null) return null
        val name = if (completedBy == null) {
            displayName.value ?: return null
        } else {
            allUsers.value.firstOrNull { it.id == completedBy }?.displayName ?: return null
        }
        return listOf(name to points)
    }

    /**
     * Attribution collective : une entree (displayName, pointsParPersonne) par
     * participant. [points] est deja le split par personne (final_per_user du
     * preview). Null si les points sont inconnus.
     */
    private fun earnedByForMany(participantUserIds: List<String>, points: Int?): List<Pair<String, Int>>? {
        if (points == null || participantUserIds.isEmpty()) return null
        val names = allUsers.value.associate { it.id to it.displayName }
        return participantUserIds.mapNotNull { id -> names[id]?.let { it to points } }.ifEmpty { null }
    }

    /**
     * Joue la celebration post-completion (parite Accueil) : emoji + confetti
     * immediatement, puis points + attribution des que [resolve] les renvoie
     * (typiquement un preview). L'ecriture Room reelle ([write]) est faite
     * APRES l'animation (~3s) pour que la tache completee reste visible pendant
     * la celebration — l'ecrire plus tot la ferait passer en GREEN et le filtre
     * "a faire" retirerait la carte en pleine animation.
     */
    private fun celebrate(
        taskId: String,
        resolve: suspend () -> CelebrationContent,
        write: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                celebratingTaskId = taskId,
                celebrationTrigger = _uiState.value.celebrationTrigger + 1,
                celebrationPoints = null,
                celebrationReason = null,
                celebrationEarnedBy = null,
            )
            // Resolution concurrente : le confetti s'affiche instantanement meme
            // si le preview est lent / timeout offline.
            val filler = launch {
                val content = resolve()
                if (_uiState.value.celebratingTaskId == taskId) {
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
                celebratingTaskId = null,
                celebrationPoints = null,
                celebrationReason = null,
                celebrationEarnedBy = null,
            )
            write()
        }
    }

    fun clearCompletionMessage() {
        _uiState.value = _uiState.value.copy(completionMessage = null)
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

    /** Preview bonus/malus pour le dialog de completion. null = offline/erreur. */
    suspend fun fetchCompletionPreview(taskId: String, isoDate: String, numParticipants: Int) =
        completionChecks.preview(taskId, isoDate, numParticipants)

    fun toggleFilterTodo() {
        filtersFlow.value = filtersFlow.value.copy(
            filterTodo = nextFilterState(filtersFlow.value.filterTodo)
        )
    }

    fun toggleFilterAffinity() {
        filtersFlow.value = filtersFlow.value.copy(filterAffinity = nextFilterState(filtersFlow.value.filterAffinity))
    }

    fun toggleFilterEffort() {
        filtersFlow.value = filtersFlow.value.copy(filterEffort = nextFilterState(filtersFlow.value.filterEffort))
    }

    fun toggleFilterRecurrence() {
        filtersFlow.value = filtersFlow.value.copy(filterRecurrence = nextFilterState(filtersFlow.value.filterRecurrence))
    }

    fun toggleFilterHome() {
        filtersFlow.value = filtersFlow.value.copy(filterHome = nextFilterState(filtersFlow.value.filterHome))
    }

    fun toggleFilterAssignment() {
        filtersFlow.value = filtersFlow.value.copy(filterAssignment = nextFilterState(filtersFlow.value.filterAssignment))
    }

    /**
     * Coche ou decoche un contexte dans la barre de filtres.
     *
     * Selection multiple en OU : cocher Chat et Jardin montre les taches de
     * l'un OU de l'autre. Les deux anciennes barres se combinaient en ET sans
     * jamais le dire, ce qui produisait des listes vides inexplicables des
     * qu'on touchait aux deux.
     */
    fun toggleContextFilter(id: String) {
        val actuels = filtersFlow.value.selectedContextIds.toMutableSet()
        if (!actuels.add(id)) actuels.remove(id)
        filtersFlow.value = filtersFlow.value.copy(selectedContextIds = actuels)
    }

    fun clearContextFilter() {
        filtersFlow.value = filtersFlow.value.copy(selectedContextIds = emptySet())
    }

    fun toggleShowAll() {
        filtersFlow.value = filtersFlow.value.copy(showAllTasks = !filtersFlow.value.showAllTasks)
    }

    /**
     * Ouvre la barre de recherche. N'affecte pas filtersFlow : l'expansion est
     * purement UI et n'impacte pas le pipeline de filtrage.
     */
    fun openSearchBar() {
        _uiState.value = _uiState.value.copy(isSearchBarExpanded = true)
    }

    /**
     * Ferme la barre de recherche : vide la query et reset le toggle.
     * La prochaine ouverture repart donc sur les valeurs par defaut
     * (query vide, filtres ignores).
     */
    fun closeSearchBar() {
        _uiState.value = _uiState.value.copy(
            isSearchBarExpanded = false,
            searchQuery = "",
            searchRespectFilters = false,
        )
        filtersFlow.value = filtersFlow.value.copy(
            searchQuery = "",
            searchRespectFilters = false,
        )
    }

    /**
     * Double-ecriture synchrone : _uiState (source de verite pour le TextField,
     * mise a jour immediate pour que le curseur ne regresse pas) + filtersFlow
     * (declenche le pipeline de filtrage async via observeCache).
     */
    fun updateSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        filtersFlow.value = filtersFlow.value.copy(searchQuery = query)
    }

    fun toggleSearchRespectFilters() {
        val next = !filtersFlow.value.searchRespectFilters
        _uiState.value = _uiState.value.copy(searchRespectFilters = next)
        filtersFlow.value = filtersFlow.value.copy(searchRespectFilters = next)
    }

    suspend fun logout() {
        authRepository.logout()
    }

    private fun nextFilterState(current: FilterState): FilterState = when (current) {
        FilterState.OFF -> FilterState.HIGH
        FilterState.HIGH -> FilterState.LOW
        FilterState.LOW -> FilterState.OFF
    }
}
