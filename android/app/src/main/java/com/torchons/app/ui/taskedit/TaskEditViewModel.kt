package com.torchons.app.ui.taskedit

import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import com.torchons.app.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.model.CreateTaskRequest
import com.torchons.app.data.model.RecurrenceRule
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.model.CatalogSuggestion
import com.torchons.app.data.model.Task
import com.torchons.app.data.model.UpdateTaskRequest
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel pour l'ecran de creation/edition de tache.
 */
@HiltViewModel
class TaskEditViewModel @Inject constructor(
    private val taskRepository: TaskRepository,
    private val authRepository: AuthRepository,
    private val apiService: ApiService,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TaskEditUiState())
    val uiState: StateFlow<TaskEditUiState> = _uiState.asStateFlow()

    private val loader = TaskEditLoader(
        scope = viewModelScope,
        uiState = _uiState,
        taskRepository = taskRepository,
        apiService = apiService,
        context = context,
    )

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

    /**
     * Ajoute ou retire un contexte, en conservant l'ordre de selection.
     *
     * L'ordre est porteur de sens : le premier coche devient le contexte
     * principal, celui sous lequel la tache s'affiche. Un Set ferait perdre
     * cette information, et « Nettoyer la litiere » se retrouverait sous Salon
     * plutot que sous Chat selon l'ordre alphabetique.
     */
    /**
     * Remplit le formulaire depuis une proposition du catalogue.
     *
     * Titre, contextes, frequence, effort et tolerance d'un coup : c'est tout
     * l'interet d'avoir un catalogue, et laisser l'utilisateur ressaisir une
     * cadence qu'on connait deja serait lui faire faire le travail deux fois.
     *
     * Rien n'est verrouille ensuite — tout reste modifiable, et
     * `appliedSuggestion` sert seulement a montrer d'ou vient le pre-remplissage.
     */
    fun applySuggestion(suggestion: CatalogSuggestion) {
        val rule = suggestion.defaultRecurrence
        val dpm = rule?.daysPerMonth?.mapValues { it.value.toString() }
            ?: (1..12).associate { it.toString() to "" }
        val canonicalDays = rule?.everyNDays ?: 7
        val unit = DurationUnit.bestFor(canonicalDays)
        _uiState.value = _uiState.value.copy(
            title = suggestion.title,
            description = suggestion.description ?: _uiState.value.description,
            contextIds = suggestion.contextIds,
            recurrenceType = rule?.type ?: "none",
            recurrenceMonths = rule?.months?.toSet() ?: emptySet(),
            recurrenceDay = rule?.day ?: 1,
            recurrenceGraceDays = rule?.graceDays ?: 0,
            anniversaryAmount = anniversaryFieldsOf(rule).first,
            anniversaryInYears = anniversaryFieldsOf(rule).second,
            recurrenceFieldText = unit.fromDays(canonicalDays).toString(),
            recurrenceUnit = unit,
            daysPerMonth = dpm,
            weight = suggestion.defaultWeight,
            tolerance = suggestion.defaultTolerance,
            appliedSuggestion = suggestion.title,
        )
    }

    /** Repart d'un formulaire vierge apres avoir applique une proposition. */
    fun clearSuggestion() {
        _uiState.value = _uiState.value.copy(
            title = "",
            description = "",
            contextIds = emptyList(),
            appliedSuggestion = null,
        )
    }

    /**
     * Propositions filtrees par ce que l'utilisateur tape.
     *
     * Recherche tokenisee sur le titre normalise, la meme que l'autocomplete
     * des taches existantes : taper « litiere » doit trouver « Nettoyer la
     * litiere » sans exiger le mot exact ni les accents.
     */
    fun matchingSuggestions(query: String): List<CatalogSuggestion> {
        val state = _uiState.value
        if (state.isEditMode) return emptyList()
        val toutes = state.suggestions
        if (query.isBlank()) return toutes
        val tokens = normalize(query).split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return toutes
        return toutes.filter { s ->
            val botte = normalize(s.title)
            tokens.all { botte.contains(it) }
        }
    }

    fun toggleContext(id: String) {
        val liste = _uiState.value.contextIds.toMutableList()
        if (!liste.remove(id)) liste.add(id)
        _uiState.value = _uiState.value.copy(contextIds = liste)
    }

    /** Charge les donnees de reference (categories, rooms, users) et la tache si edition. */
    fun loadData(taskId: String?, cloneFromId: String? = null) = loader.loadData(taskId, cloneFromId)

    /**
     * Applique un clone depuis l'autocomplete (selection d'une suggestion).
     * Reutilise les categories/users/rooms deja en memoire pour eviter un
     * re-fetch reseau. Appele sur tap dans la dropdown du champ titre.
     */
    fun applyCloneFrom(sourceId: String) = loader.applyCloneFrom(sourceId)

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
            users = state.users,
            clonedFromTitle = null,
        )
    }

    fun updateTitle(value: String) {
        _uiState.value = _uiState.value.copy(title = value)
    }

    fun updateDescription(value: String) {
        _uiState.value = _uiState.value.copy(description = value)
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

    /** Coche / decoche un mois d'echeance (type `calendar`). */
    fun toggleRecurrenceMonth(month: Int) {
        val cur = _uiState.value.recurrenceMonths.toMutableSet()
        if (!cur.add(month)) cur.remove(month)
        _uiState.value = _uiState.value.copy(recurrenceMonths = cur)
    }

    fun updateRecurrenceDay(day: Int) {
        // Borne a 28 comme le serveur : le 31 fevrier n'existe pas.
        _uiState.value = _uiState.value.copy(recurrenceDay = day.coerceIn(1, 28))
    }

    fun updateRecurrenceGraceDays(days: Int) {
        _uiState.value = _uiState.value.copy(recurrenceGraceDays = days.coerceAtLeast(0))
    }

    fun updateAnniversaryAmount(amount: Int) {
        _uiState.value = _uiState.value.copy(anniversaryAmount = amount.coerceIn(1, 100))
    }

    fun updateAnniversaryInYears(inYears: Boolean) {
        _uiState.value = _uiState.value.copy(anniversaryInYears = inYears)
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
            _uiState.value = state.copy(error = context.getString(R.string.task_edit_title_required))
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
                "calendar" -> RecurrenceRule(
                    type = "calendar",
                    // Le serveur normalise (tri, dedoublonnage, bornes) ;
                    // on envoie l'intention telle que l'user l'a exprimee.
                    months = state.recurrenceMonths.sorted().ifEmpty { listOf(1) },
                    day = state.recurrenceDay,
                    graceDays = state.recurrenceGraceDays,
                )
                "anniversary" -> RecurrenceRule(
                    type = "anniversary",
                    everyNMonths = state.anniversaryEveryNMonths(),
                )
                else -> null
            }

            val result = if (taskId != null) {
                taskRepository.updateTask(
                    taskId,
                    UpdateTaskRequest(
                        title = state.title,
                        description = state.description.ifBlank { null },
                        contextIds = state.contextIds,
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
                        contextIds = state.contextIds,
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
                        error = error.message ?: context.getString(R.string.task_edit_save_error)
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
                        error = error.message ?: context.getString(R.string.common_error_delete)
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
                        error = error.message ?: context.getString(R.string.common_error_vote),
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
