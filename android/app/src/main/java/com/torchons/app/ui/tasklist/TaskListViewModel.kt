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
    val categoryColor: String?
)

/**
 * Etat de l'interface de la liste des taches.
 */
data class TaskListUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val tasks: List<TaskWithFreshness> = emptyList(),
    val categories: List<Category> = emptyList(),
    val selectedCategoryId: String? = null,
    val isAtHome: Boolean = true,
    val showAllTasks: Boolean = false,
    val homeTasksHidden: Int = 0,
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

    // Cache local des completions pour le calcul de fraicheur
    private var allCompletions: List<Completion> = emptyList()
    private var allTasks: List<Task> = emptyList()
    private var allCategories: List<Category> = emptyList()

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

                val tasks = tasksResult.getOrNull() ?: emptyList()
                val categories = categoriesResult.getOrNull() ?: emptyList()
                val completions = completionsResult.getOrNull() ?: emptyList()

                allTasks = tasks
                allCategories = categories
                allCompletions = completions

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
    fun selectCategory(categoryId: String?) {
        _uiState.value = _uiState.value.copy(selectedCategoryId = categoryId)
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

    /** Complete une tache a une date historique. */
    suspend fun completeTaskAtDate(taskId: String, isoDate: String) {
        val result = taskRepository.completeTask(taskId, isoDate)
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

    /** Efface le message de completion affiche. */
    fun clearCompletionMessage() {
        _uiState.value = _uiState.value.copy(completionMessage = null)
    }

    /** Efface le message d'erreur affiche. */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
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
        val selectedCategoryId = _uiState.value.selectedCategoryId
        val showAll = _uiState.value.showAllTasks

        // Detecter si on est a la maison
        val homeSsids = kotlinx.coroutines.runBlocking {
            tokenManager.homeSsidsFlow.first()
        }
        val isAtHome = WifiHelper.isAtHome(context, homeSsids)

        // Categories qui necessitent d'etre a la maison
        val homeCategoryIds = allCategories.filter { it.requiresHome }.map { it.id }.toSet()

        // Filtrer par categorie si une categorie est selectionnee
        var filteredTasks = if (selectedCategoryId != null) {
            allTasks.filter { it.categoryId == selectedCategoryId }
        } else {
            allTasks
        }

        // Filtrer les taches "a la maison" si pas au WiFi maison et pas en mode "tout afficher"
        var homeTasksHidden = 0
        if (!isAtHome && !showAll && homeCategoryIds.isNotEmpty()) {
            val before = filteredTasks.size
            filteredTasks = filteredTasks.filter { it.categoryId !in homeCategoryIds }
            homeTasksHidden = before - filteredTasks.size
        }

        // Calculer la fraicheur pour chaque tache
        val tasksWithFreshness = filteredTasks.map { task ->
            val lastCompletion = allCompletions
                .filter { it.taskId == task.id }
                .maxByOrNull { it.completedAt }

            val freshness = FreshnessCalculator.calculateFreshness(
                lastCompletedAt = lastCompletion?.completedAt,
                halfLifeHours = task.freshnessHalfLifeHours
            )

            val category = allCategories.find { it.id == task.categoryId }

            TaskWithFreshness(
                task = task,
                freshness = freshness,
                categoryName = category?.name,
                categoryColor = category?.color
            )
        }.sortedBy { FreshnessCalculator.getUrgencyOrder(it.freshness) }

        _uiState.value = _uiState.value.copy(
            isLoading = false,
            isRefreshing = false,
            tasks = tasksWithFreshness,
            categories = allCategories,
            isAtHome = isAtHome,
            homeTasksHidden = homeTasksHidden,
            error = error
        )
    }
}
