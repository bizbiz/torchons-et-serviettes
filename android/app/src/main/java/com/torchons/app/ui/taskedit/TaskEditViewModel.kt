package com.torchons.app.ui.taskedit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.model.Category
import com.torchons.app.data.model.CreateTaskRequest
import com.torchons.app.data.model.FrequencyVotesState
import com.torchons.app.data.model.RecurrenceRule
import com.torchons.app.data.model.Room
import com.torchons.app.data.model.Task
import com.torchons.app.data.model.UpdateTaskRequest
import com.torchons.app.data.model.User
import com.torchons.app.data.repository.AuthRepository
import com.torchons.app.data.repository.TaskRepository
import com.torchons.app.util.DurationUnit
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    /**
     * Texte affiche dans le champ "Tous les X" — exprime la valeur dans
     * l'unite [recurrenceUnit] courante (ex: "3" en MOIS = 90 jours).
     * La conversion vers le canonique en jours se fait au save() via
     * [recurrenceUnit].toDays(...).
     */
    val recurrenceFieldText: String = "7",
    /** Unite de saisie pour le champ de recurrence (Jours / Semaines / Mois). */
    val recurrenceUnit: DurationUnit = DurationUnit.JOURS,
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
     * le champ recurrenceFieldText est grise dans l'UI et seul le mini-dialog
     * "Modifier via le vote" peut le faire bouger.
     */
    val frequencyVotes: FrequencyVotesState? = null,
    /** Affiche le mini-dialog de re-vote frequence. */
    val showFrequencyVoteDialog: Boolean = false,
    /**
     * Titre de la tache source quand on est en mode "creation par clonage"
     * (via bouton Dupliquer dans TaskDetail ou selection d'une suggestion
     * d'autocomplete). Non-null = un chip "Duplique de [titre]" est affiche
     * en haut du formulaire et l'autocomplete est desactive le temps que
     * l'user n'ait pas reset le clone.
     */
    val clonedFromTitle: String? = null,
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

    /**
     * Suggestions d'autocomplete pour le champ titre en mode creation.
     * Pipeline : titre saisi -> debounce 150ms -> filtre tokenise (NFD +
     * lowercase) sur le pool des taches actives + archivees du cache Room.
     * Vide en mode edition, vide quand un clone est deja applique
     * (clonedFromTitle != null), vide tant que la query fait moins de 2 chars.
     */
    @OptIn(FlowPreview::class)
    val suggestions: StateFlow<List<Task>> = combine(
        _uiState.map { it.title }.distinctUntilChanged().debounce(150L),
        taskRepository.observeTasksForSuggestions(),
    ) { query, all ->
        val state = _uiState.value
        if (state.isEditMode || state.clonedFromTitle != null || query.length < 2) {
            emptyList()
        } else {
            val tokens = normalize(query).split(Regex("\\s+")).filter { it.isNotBlank() }
            if (tokens.isEmpty()) {
                emptyList()
            } else {
                all.asSequence()
                    .filter { task ->
                        val haystack = normalize(task.title)
                        tokens.all { haystack.contains(it) }
                    }
                    .take(5)
                    .toList()
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Charge les donnees de reference (categories, rooms, users) et la tache si edition. */
    fun loadData(taskId: String?, cloneFromId: String? = null) {
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
                    val canonicalDays = task.recurrenceRule?.everyNDays ?: 7
                    val unit = DurationUnit.bestFor(canonicalDays)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isEditMode = true,
                        title = task.title,
                        description = task.description ?: "",
                        categoryId = task.categoryId,
                        roomId = task.roomId,
                        assignedToIds = task.assignedToIds,
                        recurrenceType = task.recurrenceRule?.type ?: "none",
                        recurrenceFieldText = unit.fromDays(canonicalDays).toString(),
                        recurrenceUnit = unit,
                        daysPerMonth = dpm,
                        weight = task.weight,
                        tolerance = task.tolerance,
                        categories = categories,
                        rooms = rooms,
                        users = users,
                        frequencyVotes = votes,
                        clonedFromTitle = null,
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false, error = "Tache introuvable"
                    )
                }
            } else if (cloneFromId != null) {
                // Mode creation par clonage — projete la source dans le formulaire.
                // recurrenceRule.everyNDays est deja le consensus des votes
                // (synchronise par upsert_vote_and_sync_consensus cote backend),
                // donc on copie tel quel : le clone herite du consensus actuel
                // sans copier les votes individuels, qui sont propres au task_id.
                applyCloneSourceInternal(
                    sourceId = cloneFromId,
                    categories = categories,
                    rooms = rooms,
                    users = users,
                )
            } else {
                // Mode creation neuve — initialiser avec tous les users assignes (commune)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isEditMode = false,
                    assignedToIds = users.map { it.id },
                    categories = categories,
                    rooms = rooms,
                    users = users,
                    clonedFromTitle = null,
                )
            }
        }
    }

    /**
     * Applique un clone depuis l'autocomplete (selection d'une suggestion).
     * Reutilise les categories/users/rooms deja en memoire pour eviter un
     * re-fetch reseau. Appele sur tap dans la dropdown du champ titre.
     */
    fun applyCloneFrom(sourceId: String) {
        val state = _uiState.value
        if (state.isEditMode) return
        viewModelScope.launch {
            applyCloneSourceInternal(
                sourceId = sourceId,
                categories = state.categories,
                rooms = state.rooms,
                users = state.users,
            )
        }
    }

    /**
     * Reset du clone : remet le formulaire dans l'etat "creation neuve"
     * (tous les users assignes, champs vides). Appele par le X du chip
     * "Duplique de [titre]" pour annuler la duplication.
     */
    fun resetClone() {
        val state = _uiState.value
        if (state.isEditMode) return
        _uiState.value = TaskEditUiState(
            isEditMode = false,
            assignedToIds = state.users.map { it.id },
            categories = state.categories,
            rooms = state.rooms,
            users = state.users,
            clonedFromTitle = null,
        )
    }

    private suspend fun applyCloneSourceInternal(
        sourceId: String,
        categories: List<Category>,
        rooms: List<Room>,
        users: List<User>,
    ) {
        val source = taskRepository.getCachedTaskById(sourceId)
        if (source == null) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = "Tache source introuvable",
                categories = categories,
                rooms = rooms,
                users = users,
                assignedToIds = if (_uiState.value.assignedToIds.isEmpty()) users.map { it.id } else _uiState.value.assignedToIds,
            )
            return
        }
        val dpm = source.recurrenceRule?.daysPerMonth?.mapValues { it.value.toString() }
            ?: (1..12).associate { it.toString() to "" }
        val canonicalDays = source.recurrenceRule?.everyNDays ?: 7
        val unit = DurationUnit.bestFor(canonicalDays)
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            isEditMode = false,
            title = source.title,
            description = source.description ?: "",
            categoryId = source.categoryId,
            roomId = source.roomId,
            assignedToIds = source.assignedToIds,
            recurrenceType = source.recurrenceRule?.type ?: "none",
            recurrenceFieldText = unit.fromDays(canonicalDays).toString(),
            recurrenceUnit = unit,
            daysPerMonth = dpm,
            weight = source.weight,
            tolerance = source.tolerance,
            categories = categories,
            rooms = rooms,
            users = users,
            // Pas de votes : le clone n'a pas de TaskFrequencyVote tant qu'aucun
            // user n'a vote — chaque user re-vote au prochain prompt.
            frequencyVotes = null,
            clonedFromTitle = source.title,
        )
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

    fun updateRecurrenceFieldText(value: String) {
        _uiState.value = _uiState.value.copy(recurrenceFieldText = value)
    }

    /**
     * Bascule l'unite de saisie (Jours / Semaines / Mois) en preservant la
     * duree affichee. La duree canonique en jours est snappee sur la nouvelle
     * unite (ex: 92 j en JOURS -> 3 mois en MOIS = 90 j) — c'est explicite et
     * coherent avec l'affichage qui montrera "3 mois (90 j)".
     */
    fun updateRecurrenceUnit(unit: DurationUnit) {
        val state = _uiState.value
        val currentCanonical = state.recurrenceFieldText.toIntOrNull()
            ?.let { state.recurrenceUnit.toDays(it) } ?: 7
        val newDisplay = unit.fromDays(currentCanonical)
        _uiState.value = state.copy(
            recurrenceUnit = unit,
            recurrenceFieldText = newDisplay.toString(),
        )
    }

    /** Duree canonique en jours derivee de [recurrenceFieldText] et [recurrenceUnit]. */
    private fun TaskEditUiState.canonicalEveryNDays(): Int {
        val raw = recurrenceFieldText.toIntOrNull() ?: 7
        return recurrenceUnit.toDays(raw)
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
                    everyNDays = state.canonicalEveryNDays()
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
     * valeur). Le champ recurrenceFieldText local est aussi mis a jour
     * (converti dans l'unite courante) pour que le save() final ne renvoie
     * pas l'ancienne valeur au backend (sinon 409 FREQUENCY_VOTE_LOCKED).
     */
    fun submitFrequencyVote(taskId: String, request: com.torchons.app.data.model.FrequencyRequest) {
        viewModelScope.launch {
            val result = taskRepository.voteFrequency(taskId, request)
            result.fold(
                onSuccess = {
                    val refreshed = taskRepository.getFrequencyVotes(taskId)
                    val state = _uiState.value
                    val newAvg = refreshed?.consensusAverageDays
                        ?: request.everyNDays
                        ?: request.daysPerMonth?.values?.let { it.sum() / it.size }
                        ?: 7
                    val newFieldText = state.recurrenceUnit.fromDays(newAvg).toString()
                    _uiState.value = state.copy(
                        showFrequencyVoteDialog = false,
                        frequencyVotes = refreshed,
                        recurrenceFieldText = newFieldText,
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

/** NFD + strip diacritiques + lowercase. Idem TaskListViewModel.normalize. */
private fun normalize(s: String): String =
    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .lowercase()
