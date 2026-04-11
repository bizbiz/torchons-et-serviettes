package com.torchons.app.ui.tasklist

import android.content.Context
import androidx.lifecycle.ViewModel
import com.torchons.app.data.local.TokenManager
import com.torchons.app.util.WifiHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.model.Category
import com.torchons.app.data.model.Completion
import com.torchons.app.data.model.Task
import com.torchons.app.data.repository.AuthRepository
import com.torchons.app.data.model.CompleteTaskRequest
import com.torchons.app.data.repository.TaskRepository
import com.torchons.app.util.FreshnessCalculator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Donnees d'une tache avec sa fraicheur calculee.
 */
data class TaskWithFreshness(
    val task: Task,
    val freshness: Double,
    val categoryName: String?,
    val categoryColor: String?,
    val myAffinity: Int? = null
)

/**
 * Etat de l'interface de la liste des taches.
 */
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
    // Filtres
    val filterTodo: Boolean = false,            // Seulement les a faire
    val filterAffinity: FilterState = FilterState.OFF,
    val filterEffort: FilterState = FilterState.OFF,
    val filterRecurrence: FilterState = FilterState.OFF, // HIGH = recurrentes, LOW = ponctuelles
    val filterHome: FilterState = FilterState.OFF, // HIGH = a la maison, LOW = pas a la maison
    val selectedRoom: String? = null,
    val availableRooms: List<String> = emptyList(),
    val error: String? = null,
    val completionMessage: String? = null
)

/**
 * ViewModel pour l'ecran de liste des taches.
 * Charge les taches, categories et completions depuis le backend,
 * calcule la fraicheur et gere le filtrage par categorie.
 */
@HiltViewModel
class TaskListViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val authRepository: AuthRepository,
    private val tokenManager: TokenManager,
    private val apiService: com.torchons.app.data.api.ApiService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(TaskListUiState(isLoading = true))
    val uiState: StateFlow<TaskListUiState> = _uiState.asStateFlow()

    /** Nom d'affichage de l'utilisateur connecte. */
    val displayName: StateFlow<String?> = authRepository.displayNameFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Identifiant de l'utilisateur connecte. */
    val userId: StateFlow<String?> = authRepository.userIdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Liste des utilisateurs du foyer. */
    private val _allUsers = MutableStateFlow<List<com.torchons.app.data.model.User>>(emptyList())
    val allUsers: StateFlow<List<com.torchons.app.data.model.User>> = _allUsers.asStateFlow()

    // Cache local des completions pour le calcul de fraicheur
    private var allCompletions: List<Completion> = emptyList()
    private var allTasks: List<Task> = emptyList()
    private var allCategories: List<Category> = emptyList()
    private var myAffinities: Map<String, Int> = emptyMap()
    private var freshnessThreshold: Int = 10

    init {
        loadData()
    }

    /**
     * Charge toutes les donnees depuis le backend (taches, categories, completions).
     */
    fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                // Charger les donnees en parallele
                val tasksResult = taskRepository.getTasks()
                val categoriesResult = taskRepository.getCategories()
                val completionsResult = taskRepository.getCompletions()
                val usersResult = taskRepository.getUsers()

                val tasks = tasksResult.getOrNull() ?: emptyList()
                val categories = categoriesResult.getOrNull() ?: emptyList()
                val completions = completionsResult.getOrNull() ?: emptyList()
                val users = usersResult.getOrNull() ?: emptyList()

                allTasks = tasks
                allCategories = categories
                allCompletions = completions
                _allUsers.value = users

                // Charger les affinites du user courant
                try {
                    val affResp = apiService.getMyAffinities()
                    if (affResp.isSuccessful) {
                        myAffinities = (affResp.body() ?: emptyList()).associate {
                            (it["task_id"] as? String ?: "") to ((it["score"] as? Double)?.toInt() ?: 5)
                        }
                    }
                } catch (_: Exception) {}

                // Charger le seuil de fraicheur depuis les settings
                try {
                    val sResp = apiService.getSettings()
                    if (sResp.isSuccessful) {
                        freshnessThreshold = (sResp.body()?.get("freshness_threshold") as? Double)?.toInt() ?: 10
                    }
                } catch (_: Exception) {}

                val error = when {
                    tasksResult.isFailure -> tasksResult.exceptionOrNull()?.message
                    categoriesResult.isFailure -> categoriesResult.exceptionOrNull()?.message
                    else -> null
                }

                updateFilteredTasks(error = error)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    error = e.message ?: "Erreur inconnue"
                )
            }
        }
    }

    /**
     * Rafraichit les donnees (pull-to-refresh).
     */
    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true, error = null)

            try {
                val tasksResult = taskRepository.getTasks()
                val categoriesResult = taskRepository.getCategories()
                val completionsResult = taskRepository.getCompletions()

                allTasks = tasksResult.getOrNull() ?: allTasks
                allCategories = categoriesResult.getOrNull() ?: allCategories
                allCompletions = completionsResult.getOrNull() ?: allCompletions

                updateFilteredTasks()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isRefreshing = false,
                    error = e.message
                )
            }
        }
    }

    /**
     * Filtre les taches par categorie.
     * @param categoryId Identifiant de la categorie, ou null pour toutes.
     */
    fun toggleCategory(categoryId: String) {
        val current = _uiState.value.selectedCategoryIds.toMutableSet()
        if (categoryId in current) current.remove(categoryId) else current.add(categoryId)
        _uiState.value = _uiState.value.copy(selectedCategoryIds = current)
        updateFilteredTasks()
    }

    fun clearCategoryFilter() {
        _uiState.value = _uiState.value.copy(selectedCategoryIds = emptySet())
        updateFilteredTasks()
    }

    /**
     * Marque une tache comme completee.
     */
    fun completeTask(taskId: String) {
        viewModelScope.launch {
            val result = taskRepository.completeTask(taskId)
            result.fold(
                onSuccess = { completion ->
                    // Ajouter la nouvelle completion au cache local
                    allCompletions = allCompletions + completion
                    updateFilteredTasks()
                    _uiState.value = _uiState.value.copy(
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

    /** Complete une tache a une date historique avec un user specifique. */
    fun completeTaskAtDate(taskId: String, isoDate: String, completedBy: String? = null) {
        viewModelScope.launch {
            val result = taskRepository.completeTask(taskId, completedAt = isoDate, completedBy = completedBy)
            result.fold(
                onSuccess = { completion ->
                    allCompletions = allCompletions + completion
                    updateFilteredTasks()
                    _uiState.value = _uiState.value.copy(completionMessage = "Tache completee !")
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(error = error.message)
                }
            )
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

    fun toggleFilterTodo() {
        _uiState.value = _uiState.value.copy(filterTodo = !_uiState.value.filterTodo)
        updateFilteredTasks()
    }

    fun toggleFilterAffinity() {
        _uiState.value = _uiState.value.copy(filterAffinity = nextFilterState(_uiState.value.filterAffinity))
        updateFilteredTasks()
    }

    fun toggleFilterEffort() {
        _uiState.value = _uiState.value.copy(filterEffort = nextFilterState(_uiState.value.filterEffort))
        updateFilteredTasks()
    }

    fun toggleFilterRecurrence() {
        _uiState.value = _uiState.value.copy(filterRecurrence = nextFilterState(_uiState.value.filterRecurrence))
        updateFilteredTasks()
    }

    fun toggleFilterHome() {
        _uiState.value = _uiState.value.copy(filterHome = nextFilterState(_uiState.value.filterHome))
        updateFilteredTasks()
    }

    fun selectRoom(room: String?) {
        _uiState.value = _uiState.value.copy(selectedRoom = room)
        updateFilteredTasks()
    }

    private fun nextFilterState(current: FilterState): FilterState = when (current) {
        FilterState.OFF -> FilterState.HIGH
        FilterState.HIGH -> FilterState.LOW
        FilterState.LOW -> FilterState.OFF
    }

    /** Bascule l'affichage de toutes les taches (ignore le filtre WiFi). */
    fun toggleShowAll() {
        _uiState.value = _uiState.value.copy(showAllTasks = !_uiState.value.showAllTasks)
        updateFilteredTasks()
    }

    /** Deconnexion de l'utilisateur. */
    suspend fun logout() {
        authRepository.logout()
    }

    /**
     * Met a jour la liste filtree des taches avec calcul de fraicheur.
     * Les taches sont triees par urgence (les plus urgentes d'abord).
     */
    private fun updateFilteredTasks(error: String? = null) {
        val state = _uiState.value
        val selectedCategoryIds = state.selectedCategoryIds
        val showAll = state.showAllTasks

        // Detecter si on est a la maison
        val homeSsids = kotlinx.coroutines.runBlocking {
            tokenManager.homeSsidsFlow.first()
        }
        val isAtHome = WifiHelper.isAtHome(context, homeSsids)

        // Categories qui necessitent d'etre a la maison
        val homeCategoryIds = allCategories.filter { it.requiresHome }.map { it.id }.toSet()

        // Extraire toutes les pieces uniques (apres le " — " dans le titre)
        val rooms = allTasks
            .mapNotNull {
                val parts = it.title.split(" — ")
                if (parts.size >= 2) parts.last().trim() else null
            }
            .toSet()
            .sorted()

        // Filtrer par categories selectionnees (multi-selection)
        var filteredTasks = if (selectedCategoryIds.isNotEmpty()) {
            allTasks.filter { it.categoryId in selectedCategoryIds }
        } else {
            allTasks
        }

        // Filtrer par piece
        if (state.selectedRoom != null) {
            filteredTasks = filteredTasks.filter {
                it.title.endsWith(" — ${state.selectedRoom}")
            }
        }

        // Filtre WiFi maison (filterHome)
        when (state.filterHome) {
            FilterState.HIGH -> filteredTasks = filteredTasks.filter { it.categoryId in homeCategoryIds }
            FilterState.LOW -> filteredTasks = filteredTasks.filter { it.categoryId !in homeCategoryIds }
            FilterState.OFF -> {}
        }

        // Filtrer les taches "a la maison" si pas au WiFi maison et pas en mode "tout afficher"
        var homeTasksHidden = 0
        if (!isAtHome && !showAll && homeCategoryIds.isNotEmpty()) {
            val before = filteredTasks.size
            filteredTasks = filteredTasks.filter { it.categoryId !in homeCategoryIds }
            homeTasksHidden = before - filteredTasks.size
        }

        // Calculer la fraicheur pour chaque tache
        var tasksWithFreshness = filteredTasks.map { task ->
            val lastCompletion = allCompletions
                .filter { it.taskId == task.id }
                .maxByOrNull { it.completedAt }

            val freshness = FreshnessCalculator.calculateFreshness(
                lastCompletedAt = lastCompletion?.completedAt,
                rule = task.recurrenceRule,
                tolerance = task.tolerance,
            )

            val category = allCategories.find { it.id == task.categoryId }

            TaskWithFreshness(
                task = task,
                freshness = freshness,
                categoryName = category?.name,
                categoryColor = category?.color
            )
        }

        // === FILTRES ===

        // Filtre "a faire" : seulement les taches sous le seuil de suggestion configure
        if (state.filterTodo) {
            tasksWithFreshness = tasksWithFreshness.filter { it.freshness < freshnessThreshold.toDouble() }
        }

        // Filtre recurrence
        when (state.filterRecurrence) {
            FilterState.HIGH -> tasksWithFreshness = tasksWithFreshness.filter { it.task.recurrenceRule != null }
            FilterState.LOW -> tasksWithFreshness = tasksWithFreshness.filter { it.task.recurrenceRule == null }
            FilterState.OFF -> {}
        }

        // Filtre effort : seuil absolu (1pt = 5min)
        // HIGH = long (> 30min, ≥ 7 pts), LOW = court (≤ 15min, ≤ 3 pts)
        when (state.filterEffort) {
            FilterState.HIGH -> tasksWithFreshness = tasksWithFreshness.filter { it.task.weight >= 7 }
            FilterState.LOW -> tasksWithFreshness = tasksWithFreshness.filter { it.task.weight <= 3 }
            FilterState.OFF -> {}
        }

        // Filtre affinite : seuil absolu (HIGH = aime ≥ 7, LOW = deteste ≤ 4)
        if (state.filterAffinity != FilterState.OFF) {
            tasksWithFreshness = tasksWithFreshness.map { it.copy(myAffinity = myAffinities[it.task.id]) }
            tasksWithFreshness = when (state.filterAffinity) {
                FilterState.HIGH -> tasksWithFreshness.filter { (it.myAffinity ?: 5) >= 7 }
                FilterState.LOW -> tasksWithFreshness.filter { (it.myAffinity ?: 5) <= 4 }
                else -> tasksWithFreshness
            }
        }

        // Tri par urgence
        tasksWithFreshness = tasksWithFreshness.sortedBy { FreshnessCalculator.getUrgencyOrder(it.freshness) }

        _uiState.value = _uiState.value.copy(
            isLoading = false,
            isRefreshing = false,
            tasks = tasksWithFreshness,
            categories = allCategories,
            availableRooms = rooms,
            isAtHome = isAtHome,
            homeTasksHidden = homeTasksHidden,
            error = error
        )
    }
}
