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
import com.torchons.app.util.FreshnessCalculator
import com.torchons.app.util.WifiHelper
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
 * Donnees d'une tache avec sa fraicheur calculee + infos d'affichage
 * pre-resolues (categorie, piece). Pre-calcule dans applyFilters pour
 * eviter au TaskCard de faire des lookups dans des maps a chaque recompose.
 *
 * @Immutable : permet a Compose de smart-skip les items LazyColumn dont la
 * valeur n'a pas change (meme contenu = pas de recompose). Sans cette
 * annotation, le `task: Task` (qui contient List<String>) rend la classe
 * instable et chaque emission de la liste recompose tous les items visibles.
 */
@Immutable
data class TaskWithFreshness(
    val task: Task,
    val freshness: Double,
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
    val tasks: List<TaskWithFreshness> = emptyList(),
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
    val selectedRoomId: String? = null,
    val availableRooms: List<Room> = emptyList(),
    val error: String? = null,
    val completionMessage: String? = null,
    val hasCachedData: Boolean = false,
    val lastRefreshFailed: Boolean = false,
)

/**
 * Etat des filtres en memoire. Stocke dans un Flow pour reactiver le
 * combine automatiquement a chaque changement de filtre.
 *
 * Note : filterTodo est ON par defaut. Au demarrage, on n'affiche que les
 * taches urgentes (freshness < freshnessThreshold), ce qui reduit
 * drastiquement le nombre d'items rendu et donne un scroll fluide d'emblee.
 * L'utilisateur peut desactiver le filtre via la chip pour voir tout.
 */
private data class TaskListFilters(
    val filterTodo: Boolean = true,
    val filterAffinity: FilterState = FilterState.OFF,
    val filterEffort: FilterState = FilterState.OFF,
    val filterRecurrence: FilterState = FilterState.OFF,
    val filterHome: FilterState = FilterState.OFF,
    val selectedRoomId: String? = null,
    val selectedCategoryIds: Set<String> = emptySet(),
    val showAllTasks: Boolean = false,
    val myAffinities: Map<String, Int> = emptyMap(),
    val freshnessThreshold: Int = 10,
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
    val tasks: List<TaskWithFreshness>,
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
                val filtered = applyFilters(tasks, categories, rooms, completions, filters, isAtHome)
                ProcessedTaskList(
                    filtered = filtered,
                    hasCache = tasks.isNotEmpty() || categories.isNotEmpty(),
                    categories = categories,
                    rooms = rooms,
                    filters = filters,
                )
            }.flowOn(Dispatchers.Default).collect { processed ->
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

            try {
                val sResp = apiService.getSettings()
                if (sResp.isSuccessful) {
                    val threshold = (sResp.body()?.get("freshness_threshold") as? Double)?.toInt() ?: 10
                    filtersFlow.value = filtersFlow.value.copy(freshnessThreshold = threshold)
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

    fun completeTaskAtDate(taskId: String, isoDate: String, completedBy: String? = null) {
        viewModelScope.launch {
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

    fun clearCompletionMessage() {
        _uiState.value = _uiState.value.copy(completionMessage = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
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
    ): FilteredResult {
        val homeCategoryIds = categories.filter { it.requiresHome }.map { it.id }.toSet()
        val roomsById = rooms.associateBy { it.id }

        // availableRooms = pieces qui ont au moins une tache associee, triees
        // alphabetiquement (mirror de la regle backend ORDER BY name).
        val roomIdsWithTasks = tasks.mapNotNullTo(mutableSetOf()) { it.roomId }
        val availableRooms = rooms
            .filter { it.id in roomIdsWithTasks }
            .sortedBy { it.name }

        var filteredTasks = if (filters.selectedCategoryIds.isNotEmpty()) {
            tasks.filter { it.categoryId in filters.selectedCategoryIds }
        } else {
            tasks
        }

        if (filters.selectedRoomId != null) {
            filteredTasks = filteredTasks.filter { it.roomId == filters.selectedRoomId }
        }

        when (filters.filterHome) {
            FilterState.HIGH -> filteredTasks = filteredTasks.filter { it.categoryId in homeCategoryIds }
            FilterState.LOW -> filteredTasks = filteredTasks.filter { it.categoryId !in homeCategoryIds }
            FilterState.OFF -> {}
        }

        var homeTasksHidden = 0
        if (!isAtHome && !filters.showAllTasks && homeCategoryIds.isNotEmpty()) {
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

        var tasksWithFreshness = filteredTasks.map { task ->
            val lastCompletion = lastCompletionByTaskId[task.id]
            val freshness = FreshnessCalculator.calculateFreshness(
                lastCompletedAt = lastCompletion?.completedAt,
                rule = task.recurrenceRule,
                tolerance = task.tolerance,
            )
            val category = task.categoryId?.let { categoriesById[it] }
            val room = task.roomId?.let { roomsById[it] }

            TaskWithFreshness(
                task = task,
                freshness = freshness,
                categoryName = category?.name,
                categoryColor = category?.color,
                roomName = room?.name,
                roomEmoji = room?.emoji,
            )
        }

        if (filters.filterTodo) {
            tasksWithFreshness = tasksWithFreshness.filter {
                it.freshness < filters.freshnessThreshold.toDouble()
            }
        }

        when (filters.filterRecurrence) {
            FilterState.HIGH -> tasksWithFreshness = tasksWithFreshness.filter { it.task.recurrenceRule != null }
            FilterState.LOW -> tasksWithFreshness = tasksWithFreshness.filter { it.task.recurrenceRule == null }
            FilterState.OFF -> {}
        }

        when (filters.filterEffort) {
            FilterState.HIGH -> tasksWithFreshness = tasksWithFreshness.filter { it.task.weight >= 7 }
            FilterState.LOW -> tasksWithFreshness = tasksWithFreshness.filter { it.task.weight <= 3 }
            FilterState.OFF -> {}
        }

        if (filters.filterAffinity != FilterState.OFF) {
            tasksWithFreshness = tasksWithFreshness.map {
                it.copy(myAffinity = filters.myAffinities[it.task.id])
            }
            tasksWithFreshness = when (filters.filterAffinity) {
                FilterState.HIGH -> tasksWithFreshness.filter { (it.myAffinity ?: 5) >= 7 }
                FilterState.LOW -> tasksWithFreshness.filter { (it.myAffinity ?: 5) <= 4 }
                else -> tasksWithFreshness
            }
        }

        tasksWithFreshness = tasksWithFreshness.sortedBy { FreshnessCalculator.getUrgencyOrder(it.freshness) }

        return FilteredResult(tasksWithFreshness, availableRooms, isAtHome, homeTasksHidden)
    }
}
