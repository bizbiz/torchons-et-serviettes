package com.torchons.app.ui.taskedit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.model.Category
import com.torchons.app.data.model.CreateTaskRequest
import com.torchons.app.data.model.FrequencyVotesState
import com.torchons.app.data.model.RecurrenceRule
import com.torchons.app.data.model.Room
import com.torchons.app.data.model.UpdateTaskRequest
import com.torchons.app.data.model.User
import com.torchons.app.data.repository.AuthRepository
import com.torchons.app.data.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Etat du formulaire de creation/edition de tache.
 */
data class TaskEditUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isEditMode: Boolean = false,
    val title: String = "",
    val description: String = "",
    val categoryId: String? = null,
    val roomId: String? = null,
    val assignedToIds: List<String> = emptyList(),
    val recurrenceType: String = "none",
    val recurrenceEveryNDays: String = "7",
    val daysPerMonth: Map<String, String> = (1..12).associate { it.toString() to "" },
    val weight: Int = 1,
    val tolerance: String = "NORMAL",
    val categories: List<Category> = emptyList(),
    val rooms: List<Room> = emptyList(),
    val users: List<User> = emptyList(),
    val error: String? = null,
    val saved: Boolean = false,
    val deleted: Boolean = false,
    /**
     * Etat des votes de frequence pour la tache en cours d'edition.
     * Non-null uniquement en mode edition + type interval. Si votesCount >= 1,
     * le champ recurrenceEveryNDays est grise dans l'UI et seul le mini-dialog
     * "Modifier via le vote" peut le faire bouger.
     */
    val frequencyVotes: FrequencyVotesState? = null,
    /** Affiche le mini-dialog de re-vote frequence. */
    val showFrequencyVoteDialog: Boolean = false,
)

/**
 * ViewModel pour l'ecran de creation/edition de tache.
 */
@HiltViewModel
class TaskEditViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TaskEditUiState())
    val uiState: StateFlow<TaskEditUiState> = _uiState.asStateFlow()

    val currentUserId: StateFlow<String?> = authRepository.userIdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Charge les donnees de reference (categories, rooms, users) et la tache si edition. */
    fun loadData(taskId: String?) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            val categoriesResult = taskRepository.getCategories()
            val usersResult = taskRepository.getUsers()
            // Refresh rooms via cache + API (pattern observe non utilise ici car
            // l'ecran d'edition est one-shot, pas reactif).
            taskRepository.refreshRooms()
            val rooms = try {
                taskRepository.observeRooms().first()
            } catch (_: Exception) {
                emptyList()
            }

            val categories = categoriesResult.getOrNull() ?: emptyList()
            val users = usersResult.getOrNull() ?: emptyList()

            if (taskId != null) {
                // Mode edition — charger la tache
                val tasksResult = taskRepository.getTasks(activeOnly = false)
                val task = tasksResult.getOrNull()?.find { it.id == taskId }
                if (task != null) {
                    val dpm = task.recurrenceRule?.daysPerMonth?.mapValues { it.value.toString() }
                        ?: (1..12).associate { it.toString() to "" }
                    // Fetch des votes de frequence si la tache est en interval —
                    // sert a griser le champ + afficher le consensus + bouton vote.
                    val votes = if (task.recurrenceRule?.type == "interval") {
                        taskRepository.getFrequencyVotes(taskId)
                    } else {
                        null
                    }
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isEditMode = true,
                        title = task.title,
                        description = task.description ?: "",
                        categoryId = task.categoryId,
                        roomId = task.roomId,
                        assignedToIds = task.assignedToIds,
                        recurrenceType = task.recurrenceRule?.type ?: "none",
                        recurrenceEveryNDays = task.recurrenceRule?.everyNDays?.toString() ?: "7",
                        daysPerMonth = dpm,
                        weight = task.weight,
                        tolerance = task.tolerance,
                        categories = categories,
                        rooms = rooms,
                        users = users,
                        frequencyVotes = votes,
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false, error = "Tache introuvable"
                    )
                }
            } else {
                // Mode creation — initialiser avec tous les users assignes (commune)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isEditMode = false,
                    assignedToIds = users.map { it.id },
                    categories = categories,
                    rooms = rooms,
                    users = users,
                )
            }
        }
    }

    fun updateTitle(value: String) {
        _uiState.value = _uiState.value.copy(title = value)
    }

    fun updateDescription(value: String) {
        _uiState.value = _uiState.value.copy(description = value)
    }

    fun updateCategoryId(value: String?) {
        _uiState.value = _uiState.value.copy(categoryId = value)
    }

    fun updateRoomId(value: String?) {
        _uiState.value = _uiState.value.copy(roomId = value)
    }

    fun toggleUserAssignment(userId: String) {
        val current = _uiState.value.assignedToIds.toMutableList()
        if (current.contains(userId)) {
            if (current.size > 1) current.remove(userId) // Garder au moins 1
        } else {
            current.add(userId)
        }
        _uiState.value = _uiState.value.copy(assignedToIds = current)
    }

    fun updateRecurrenceType(value: String) {
        _uiState.value = _uiState.value.copy(recurrenceType = value)
    }

    fun updateRecurrenceEveryNDays(value: String) {
        _uiState.value = _uiState.value.copy(recurrenceEveryNDays = value)
    }

    fun updateDaysPerMonth(month: String, value: String) {
        val updated = _uiState.value.daysPerMonth.toMutableMap()
        updated[month] = value
        _uiState.value = _uiState.value.copy(daysPerMonth = updated)
    }

    fun updateWeight(value: Int) {
        _uiState.value = _uiState.value.copy(weight = value.coerceIn(1, 18))
    }

    fun updateTolerance(value: String) {
        _uiState.value = _uiState.value.copy(tolerance = value)
    }

    /** Sauvegarde la tache (creation ou mise a jour). */
    fun save(taskId: String?) {
        val state = _uiState.value
        if (state.title.isBlank()) {
            _uiState.value = state.copy(error = "Le titre est obligatoire")
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isSaving = true, error = null)

            val recurrenceRule = when (state.recurrenceType) {
                "interval" -> RecurrenceRule(
                    type = "interval",
                    everyNDays = state.recurrenceEveryNDays.toIntOrNull() ?: 7
                )
                "monthly_variable" -> {
                    val dpm = state.daysPerMonth
                        .filter { it.value.isNotBlank() && (it.value.toIntOrNull() ?: 0) > 0 }
                        .mapValues { it.value.toInt() }
                    RecurrenceRule(type = "monthly_variable", daysPerMonth = dpm)
                }
                "uncertain" -> RecurrenceRule(type = "uncertain")
                else -> null
            }

            val result = if (taskId != null) {
                taskRepository.updateTask(
                    taskId,
                    UpdateTaskRequest(
                        title = state.title,
                        description = state.description.ifBlank { null },
                        categoryId = state.categoryId,
                        roomId = state.roomId,
                        assignedToIds = state.assignedToIds,
                        recurrenceRule = recurrenceRule,
                        weight = state.weight,
                        tolerance = state.tolerance,
                    )
                )
            } else {
                taskRepository.createTask(
                    CreateTaskRequest(
                        title = state.title,
                        description = state.description.ifBlank { null },
                        categoryId = state.categoryId,
                        roomId = state.roomId,
                        assignedToIds = state.assignedToIds,
                        recurrenceRule = recurrenceRule,
                        weight = state.weight,
                        tolerance = state.tolerance,
                    )
                )
            }

            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(isSaving = false, saved = true)
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        error = error.message ?: "Erreur lors de la sauvegarde"
                    )
                }
            )
        }
    }

    /** Supprime la tache. */
    fun delete(taskId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            val result = taskRepository.deleteTask(taskId)
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(isSaving = false, deleted = true)
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        error = error.message ?: "Erreur lors de la suppression"
                    )
                }
            )
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /** Ouvre le mini-dialog de re-vote frequence. */
    fun openFrequencyVoteDialog() {
        _uiState.value = _uiState.value.copy(showFrequencyVoteDialog = true)
    }

    /** Ferme le mini-dialog sans voter. */
    fun dismissFrequencyVoteDialog() {
        _uiState.value = _uiState.value.copy(showFrequencyVoteDialog = false)
    }

    /**
     * Soumet un vote de frequence via PUT /tasks/{id}/frequency.
     * En cas de succes : refetch l'etat des votes pour mettre a jour le
     * consensus affiche sur la fiche (et griser l'affichage avec la nouvelle
     * valeur). Le champ recurrenceEveryNDays local est aussi mis a jour pour
     * que le save() final ne renvoie pas l'ancienne valeur au backend
     * (sinon 409 FREQUENCY_VOTE_LOCKED).
     */
    fun submitFrequencyVote(taskId: String, everyNDays: Int) {
        viewModelScope.launch {
            val result = taskRepository.voteFrequency(taskId, everyNDays)
            result.fold(
                onSuccess = {
                    val refreshed = taskRepository.getFrequencyVotes(taskId)
                    val newConsensus = refreshed?.consensusEveryNDays ?: everyNDays
                    _uiState.value = _uiState.value.copy(
                        showFrequencyVoteDialog = false,
                        frequencyVotes = refreshed,
                        recurrenceEveryNDays = newConsensus.toString(),
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        showFrequencyVoteDialog = false,
                        error = error.message ?: "Erreur lors du vote",
                    )
                },
            )
        }
    }
}
