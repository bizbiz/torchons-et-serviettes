package com.torchons.app.ui.tasklist

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.Category
import com.torchons.app.data.model.Completion
import com.torchons.app.data.model.Room
import com.torchons.app.data.model.Task
import com.torchons.app.data.model.User
import com.torchons.app.data.repository.AuthRepository
import com.torchons.app.data.repository.TaskRepository
import com.torchons.app.util.TaskUrgencyCalculator
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
 * Donnees d'une tache avec son etat d'urgence calcule (modele due_date) +
 * infos d'affichage pre-resolues (categorie, piece). Pre-calcule dans
 * applyFilters pour eviter au TaskCard de faire des lookups dans des maps
 * a chaque recompose.
 *
 * @Immutable : permet a Compose de smart-skip les items LazyColumn dont la
 * valeur n'a pas change (meme contenu = pas de recompose). Sans cette
 * annotation, le `task: Task` (qui contient List<String>) rend la classe
 * instable et chaque emission de la liste recompose tous les items visibles.
 */
@Immutable
data class TaskWithUrgency(
    val task: Task,
    val state: TaskUrgencyCalculator.State,
    val daysUntilDue: Int,
    val countdownLabel: String,
    val categoryName: String?,
    val categoryColor: String?,
    val roomName: String?,
    val roomEmoji: String?,
    val myAffinity: Int? = null,
)

/** Etat tri-state pour les filtres : Off / High / Low */
enum class FilterState { OFF, HIGH, LOW }

data class TaskListUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val tasks: List<TaskWithUrgency> = emptyList(),
    val categories: List<Category> = emptyList(),
    val selectedCategoryIds: Set<String> = emptySet(),
    val isAtHome: Boolean = true,
    val showAllTasks: Boolean = false,
    val homeTasksHidden: Int = 0,
    val filterTodo: Boolean = true,
    val filterAffinity: FilterState = FilterState.OFF,
    val filterEffort: FilterState = FilterState.OFF,
    val filterRecurrence: FilterState = FilterState.OFF,
    val filterHome: FilterState = FilterState.OFF,
    val filterAssignment: FilterState = FilterState.OFF,
    val selectedRoomId: String? = null,
    val availableRooms: List<Room> = emptyList(),
    val error: String? = null,
    val completionMessage: String? = null,
    val hasCachedData: Boolean = false,
    val lastRefreshFailed: Boolean = false,
    /** Id de la tache en cours de "celebration" (animation post-completion). */
    val celebratingTaskId: String? = null,
    /** Compteur incremente a chaque celebration pour relancer konfetti. */
    val celebrationTrigger: Int = 0,
    /** Barre de recherche ouverte : masque les chips et affiche un champ texte. */
    val isSearchBarExpanded: Boolean = false,
    /** Texte saisi dans la barre de recherche. */
    val searchQuery: String = "",
    /** Toggle "garder filtres" : par defaut la recherche ignore tous les filtres UI. */
    val searchRespectFilters: Boolean = false,
)

/**
 * Etat des filtres en memoire. Stocke dans un Flow pour reactiver le
 * combine automatiquement a chaque changement de filtre.
 *
 * Note : filterTodo est ON par defaut. Au demarrage, on n'affiche que les
 * taches en etat ORANGE ou RED (du / en retard), ce qui reduit drastiquement
 * le nombre d'items rendu et donne un scroll fluide d'emblee. L'utilisateur
 * peut desactiver le filtre via la chip pour voir tout.
 */
private data class TaskListFilters(
    val filterTodo: Boolean = true,
    val filterAffinity: FilterState = FilterState.OFF,
    val filterEffort: FilterState = FilterState.OFF,
    val filterRecurrence: FilterState = FilterState.OFF,
    val filterHome: FilterState = FilterState.OFF,
    val filterAssignment: FilterState = FilterState.OFF,
    val selectedRoomId: String? = null,
    val selectedCategoryIds: Set<String> = emptySet(),
    val showAllTasks: Boolean = false,
    val myAffinities: Map<String, Int> = emptyMap(),
    val searchQuery: String = "",
    val searchRespectFilters: Boolean = false,
)

/** Conteneur interne pour le combine des 5 flows. */
private data class CachedTaskList(
    val tasks: List<Task>,
    val categories: List<Category>,
    val rooms: List<Room>,
    val completions: List<Completion>,
    val filters: TaskListFilters,
)

/** Resultat post-traitement (filtrage + freshness) pousse vers l'UI thread. */
private data class ProcessedTaskList(
    val filtered: FilteredResult,
    val hasCache: Boolean,
    val categories: List<Category>,
    val rooms: List<Room>,
    val filters: TaskListFilters,
)

/** Resultat du filtrage pur. */
private data class FilteredResult(
    val tasks: List<TaskWithUrgency>,
    val rooms: List<Room>,
    val isAtHome: Boolean,
    val homeTasksHidden: Int,
)

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
    private val authRepository: AuthRepository,
    private val tokenManager: TokenManager,
    private val apiService: ApiService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(TaskListUiState(isLoading = true))
    val uiState: StateFlow<TaskListUiState> = _uiState.asStateFlow()

    val displayName: StateFlow<String?> = authRepository.displayNameFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val userId: StateFlow<String?> = authRepository.userIdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val allUsers: StateFlow<List<User>> = taskRepository.observeUsers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
                taskRepository.observeCategories(),
                taskRepository.observeRooms(),
                taskRepository.observeRecentCompletions(500),
                filtersFlow,
            ) { tasks, categories, rooms, completions, filters ->
                val homeSsids = tokenManager.homeSsidsFlow.first()
                val isAtHome = WifiHelper.isAtHome(context, homeSsids)
                val currentUserId = tokenManager.userIdFlow.first()
                val filtered = applyFilters(tasks, categories, rooms, completions, filters, isAtHome, currentUserId)
                ProcessedTaskList(
                    filtered = filtered,
                    hasCache = tasks.isNotEmpty() || categories.isNotEmpty(),
                    categories = categories,
                    rooms = rooms,
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
                    categories = processed.categories,
                    availableRooms = processed.filtered.rooms,
                    isAtHome = processed.filtered.isAtHome,
                    homeTasksHidden = processed.filtered.homeTasksHidden,
                    selectedCategoryIds = processed.filters.selectedCategoryIds,
                    showAllTasks = processed.filters.showAllTasks,
                    filterTodo = processed.filters.filterTodo,
                    filterAffinity = processed.filters.filterAffinity,
                    filterEffort = processed.filters.filterEffort,
                    filterRecurrence = processed.filters.filterRecurrence,
                    filterHome = processed.filters.filterHome,
                    filterAssignment = processed.filters.filterAssignment,
                    selectedRoomId = processed.filters.selectedRoomId,
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
                async { taskRepository.refreshCategories() },
                async { taskRepository.refreshRooms() },
                async { taskRepository.refreshCompletions() },
                async { taskRepository.refreshUsers() },
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

    fun completeTask(taskId: String) {
        celebrateThen(taskId) {
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

    fun completeTaskAtDate(taskId: String, isoDate: String, completedBy: String? = null) {
        celebrateThen(taskId) {
            val result = taskRepository.completeTask(taskId, completedAt = isoDate, completedBy = completedBy)
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(completionMessage = "Tache completee !")
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(error = error.message)
                }
            )
        }
    }

    /** Completion collective ("fait ensemble"). Online-first, split ceil. */
    fun completeTaskCollective(
        taskId: String,
        participantUserIds: List<String>,
        isoDate: String? = null,
    ) {
        celebrateThen(taskId) {
            val result = taskRepository.completeTaskCollective(
                taskId = taskId,
                participantUserIds = participantUserIds,
                completedAt = isoDate,
            )
            result.fold(
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
        }
    }

    /**
     * Affiche la celebration (emoji + confetti) pendant ~2s avant d'executer
     * le bloc de completion. La tache reste visible dans la liste pendant
     * la celebration parce que l'ecriture Room n'a pas encore eu lieu, donc
     * le combine ne re-emet pas et le filtre "a faire" ne masque pas l'item.
     */
    private fun celebrateThen(taskId: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                celebratingTaskId = taskId,
                celebrationTrigger = _uiState.value.celebrationTrigger + 1,
            )
            kotlinx.coroutines.delay(2000L)
            _uiState.value = _uiState.value.copy(celebratingTaskId = null)
            block()
        }
    }

    fun clearCompletionMessage() {
        _uiState.value = _uiState.value.copy(completionMessage = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /** Preview bonus/malus pour le dialog de completion. null = offline/erreur. */
    suspend fun fetchCompletionPreview(
        taskId: String,
        isoDate: String,
        numParticipants: Int,
    ): com.torchons.app.data.model.CompletionPreview? {
        return try {
            val resp = apiService.getCompletionPreview(taskId, isoDate, numParticipants)
            if (resp.isSuccessful) resp.body() else null
        } catch (_: Exception) {
            null
        }
    }

    fun toggleFilterTodo() {
        filtersFlow.value = filtersFlow.value.copy(filterTodo = !filtersFlow.value.filterTodo)
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

    fun selectRoom(roomId: String?) {
        filtersFlow.value = filtersFlow.value.copy(selectedRoomId = roomId)
    }

    fun toggleCategory(categoryId: String) {
        val current = filtersFlow.value.selectedCategoryIds.toMutableSet()
        if (categoryId in current) current.remove(categoryId) else current.add(categoryId)
        filtersFlow.value = filtersFlow.value.copy(selectedCategoryIds = current)
    }

    fun clearCategoryFilter() {
        filtersFlow.value = filtersFlow.value.copy(selectedCategoryIds = emptySet())
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

    /**
     * Applique les filtres et calcule la fraicheur. Fonction pure.
     *
     * Depuis v0.6.0 : la piece est lue depuis task.roomId (joint avec
     * la table rooms) au lieu d'etre parsee du titre comme avant.
     */
    private fun applyFilters(
        tasks: List<Task>,
        categories: List<Category>,
        rooms: List<Room>,
        completions: List<Completion>,
        filters: TaskListFilters,
        isAtHome: Boolean,
        currentUserId: String?,
    ): FilteredResult {
        val homeCategoryIds = categories.filter { it.requiresHome }.map { it.id }.toSet()
        val roomsById = rooms.associateBy { it.id }

        // availableRooms = pieces qui ont au moins une tache associee, triees
        // alphabetiquement (mirror de la regle backend ORDER BY name).
        val roomIdsWithTasks = tasks.mapNotNullTo(mutableSetOf()) { it.roomId }
        val availableRooms = rooms
            .filter { it.id in roomIdsWithTasks }
            .sortedBy { it.name }

        // Tokens de recherche normalises (NFD + strip diacritiques + lowercase).
        // Multi-mots = AND sur tokens. Quand isSearching, on bypass tous les
        // filtres UI sauf si searchRespectFilters est active.
        val trimmedQuery = filters.searchQuery.trim()
        val searchTokens = if (trimmedQuery.isNotEmpty()) {
            normalize(trimmedQuery).split(Regex("\\s+")).filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        val isSearching = searchTokens.isNotEmpty()
        val applyUiFilters = !isSearching || filters.searchRespectFilters

        var filteredTasks = if (applyUiFilters && filters.selectedCategoryIds.isNotEmpty()) {
            tasks.filter { it.categoryId in filters.selectedCategoryIds }
        } else {
            tasks
        }

        if (applyUiFilters && filters.selectedRoomId != null) {
            filteredTasks = filteredTasks.filter { it.roomId == filters.selectedRoomId }
        }

        if (applyUiFilters) {
            when (filters.filterHome) {
                FilterState.HIGH -> filteredTasks = filteredTasks.filter { it.categoryId in homeCategoryIds }
                FilterState.LOW -> filteredTasks = filteredTasks.filter { it.categoryId !in homeCategoryIds }
                FilterState.OFF -> {}
            }
        }

        // Filtre affectation : Solo = je suis le seul assigne. Groupe = 2+
        // assignes ou tache non assignee (par defaut "on la fait a deux").
        // Les taches assignees exclusivement a l'autre sont filtrees dans les
        // deux modes (ni "pour moi seul" ni "ensemble").
        if (applyUiFilters && currentUserId != null) {
            when (filters.filterAssignment) {
                FilterState.HIGH -> filteredTasks = filteredTasks.filter {
                    it.assignedToIds.size == 1 && it.assignedToIds[0] == currentUserId
                }
                FilterState.LOW -> filteredTasks = filteredTasks.filter {
                    it.assignedToIds.size >= 2 || it.assignedToIds.isEmpty()
                }
                FilterState.OFF -> {}
            }
        }

        var homeTasksHidden = 0
        if (applyUiFilters && !isAtHome && !filters.showAllTasks && homeCategoryIds.isNotEmpty()) {
            val before = filteredTasks.size
            filteredTasks = filteredTasks.filter { it.categoryId !in homeCategoryIds }
            homeTasksHidden = before - filteredTasks.size
        }

        // Pre-calcul O(M) : pour chaque tache, on connait sa derniere completion
        // en une seule passe. Avant, on faisait completions.filter().maxBy() PAR
        // tache => O(N×M). Sur ~50 taches × 500 completions = 25 000 ops par
        // recompute (changement de filtre, refresh, scroll), suffisant pour
        // faire ramer le scroll de la liste.
        val lastCompletionByTaskId = HashMap<String, Completion>(completions.size)
        for (c in completions) {
            val existing = lastCompletionByTaskId[c.taskId]
            if (existing == null || c.completedAt > existing.completedAt) {
                lastCompletionByTaskId[c.taskId] = c
            }
        }
        val categoriesById = categories.associateBy { it.id }
        val today = LocalDate.now()

        var tasksWithUrgency = filteredTasks.map { task ->
            val lastCompletion = lastCompletionByTaskId[task.id]
            val nominal = TaskUrgencyCalculator.nominalIntervalDays(task.recurrenceRule)
            val dueDate = TaskUrgencyCalculator.computeDueDate(
                lastCompletedAt = lastCompletion?.completedAt,
                rule = task.recurrenceRule,
                createdAt = task.createdAt,
            )
            val state = TaskUrgencyCalculator.computeState(dueDate, today, nominal)
            val daysUntilDue = TaskUrgencyCalculator.daysUntilDue(dueDate, today)
            val countdownLabel = TaskUrgencyCalculator.countdownLabel(dueDate, today)
            val category = task.categoryId?.let { categoriesById[it] }
            val room = task.roomId?.let { roomsById[it] }

            TaskWithUrgency(
                task = task,
                state = state,
                daysUntilDue = daysUntilDue,
                countdownLabel = countdownLabel,
                categoryName = category?.name,
                categoryColor = category?.color,
                roomName = room?.name,
                roomEmoji = room?.emoji,
            )
        }

        if (applyUiFilters && filters.filterTodo) {
            tasksWithUrgency = tasksWithUrgency.filter {
                it.state != TaskUrgencyCalculator.State.GREEN
            }
        }

        if (applyUiFilters) {
            when (filters.filterRecurrence) {
                FilterState.HIGH -> tasksWithUrgency = tasksWithUrgency.filter { it.task.recurrenceRule != null }
                FilterState.LOW -> tasksWithUrgency = tasksWithUrgency.filter { it.task.recurrenceRule == null }
                FilterState.OFF -> {}
            }
        }

        if (applyUiFilters) {
            when (filters.filterEffort) {
                FilterState.HIGH -> tasksWithUrgency = tasksWithUrgency.filter { it.task.weight >= 7 }
                FilterState.LOW -> tasksWithUrgency = tasksWithUrgency.filter { it.task.weight <= 3 }
                FilterState.OFF -> {}
            }
        }

        if (applyUiFilters && filters.filterAffinity != FilterState.OFF) {
            tasksWithUrgency = tasksWithUrgency.map {
                it.copy(myAffinity = filters.myAffinities[it.task.id])
            }
            tasksWithUrgency = when (filters.filterAffinity) {
                FilterState.HIGH -> tasksWithUrgency.filter { (it.myAffinity ?: 5) >= 7 }
                FilterState.LOW -> tasksWithUrgency.filter { (it.myAffinity ?: 5) <= 4 }
                else -> tasksWithUrgency
            }
        }

        // Matching de recherche : titre + description + nom categorie + nom piece,
        // tous concatenes et normalises (case/accents insensibles). AND sur tokens.
        if (isSearching) {
            tasksWithUrgency = tasksWithUrgency.filter { t ->
                val haystack = normalize(buildString {
                    append(t.task.title); append(' ')
                    append(t.task.description ?: ""); append(' ')
                    append(t.categoryName ?: ""); append(' ')
                    append(t.roomName ?: "")
                })
                searchTokens.all { it in haystack }
            }
        }

        // Tri : etat le plus urgent en premier (RED > ORANGE > GREEN), tiebreak
        // par days_until_due asc (les plus en retard / les plus proches d'abord).
        tasksWithUrgency = tasksWithUrgency.sortedWith(
            compareBy({ TaskUrgencyCalculator.urgencyOrder(it.state) }, { it.daysUntilDue })
        )

        return FilteredResult(tasksWithUrgency, availableRooms, isAtHome, homeTasksHidden)
    }
}

/**
 * Normalise une chaine pour la recherche : NFD + strip diacritiques + lowercase.
 * Permet a "ecole" de matcher "École" et "cafe" de matcher "café".
 */
private fun normalize(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .lowercase()
