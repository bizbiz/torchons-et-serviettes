package com.torchons.app.ui.taskedit

import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import com.torchons.app.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.data.model.CreateTaskRequest
import com.torchons.app.data.model.FrequencyVotesState
import com.torchons.app.data.model.RecurrenceRule
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.model.CatalogSuggestion
import com.torchons.app.data.model.ContextView
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
    /** Contextes choisis, dans l'ORDRE : le premier decide de l'endroit ou la
     *  tache s'affiche. Une liste et non un Set pour cette raison. */
    val contextIds: List<String> = emptyList(),
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
    /** `calendar` : mois ou la tache devient due. Vide = janvier par defaut. */
    val recurrenceMonths: Set<Int> = emptySet(),
    /** `calendar` : jour dans le mois (1..28). */
    val recurrenceDay: Int = 1,
    /**
     * `calendar` : tolerance en jours apres l'echeance avant le rouge. C'est
     * ce qui exprime « acceptable jusqu'a la fin de l'ete » sans creer une
     * seconde echeance.
     */
    val recurrenceGraceDays: Int = 0,
    /** `anniversary` : period amount, in years or months per [anniversaryInYears]. */
    val anniversaryAmount: Int = 1,
    /** `anniversary` : true = the amount counts years, false = months. */
    val anniversaryInYears: Boolean = true,
    val daysPerMonth: Map<String, String> = (1..12).associate { it.toString() to "" },
    val weight: Int = 1,
    val tolerance: String = "NORMAL",
    val contexts: List<ContextView> = emptyList(),
    /** Propositions du catalogue pertinentes pour ce foyer. */
    val suggestions: List<CatalogSuggestion> = emptyList(),
    /** Titre de la proposition appliquee, pour l'afficher et pouvoir l'annuler. */
    val appliedSuggestion: String? = null,
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

/** `anniversary` period in months, as stored (`every_n_months`). */
fun TaskEditUiState.anniversaryEveryNMonths(): Int =
    anniversaryAmount.coerceAtLeast(1) * (if (anniversaryInYears) 12 else 1)

/** Editor fields of a stored `anniversary` rule: whole years when possible. */
internal fun anniversaryFieldsOf(rule: RecurrenceRule?): Pair<Int, Boolean> {
    val months = rule?.everyNMonths?.coerceAtLeast(1) ?: 12
    return if (months % 12 == 0) (months / 12) to true else months to false
}

/** Duree canonique en jours derivee de [recurrenceFieldText] et [recurrenceUnit]. */
fun TaskEditUiState.canonicalEveryNDays(): Int {
    val raw = recurrenceFieldText.toIntOrNull() ?: 7
    return recurrenceUnit.toDays(raw)
}

/**
 * La regle telle qu'elle serait enregistree maintenant, pour l'apercu.
 *
 * Construite ici et non dans l'ecran pour qu'il n'existe qu'UN endroit qui
 * sache traduire l'etat en regle : la phrase affichee decrit alors exactement
 * ce que la sauvegarde produira, jamais une approximation qui divergerait.
 */
fun TaskEditUiState.previewRecurrenceRule(): RecurrenceRule? = when (recurrenceType) {
    "interval" -> RecurrenceRule(type = "interval", everyNDays = canonicalEveryNDays())
    "monthly_variable" -> RecurrenceRule(
        type = "monthly_variable",
        daysPerMonth = daysPerMonth
            .filter { (it.value.toIntOrNull() ?: 0) > 0 }
            .mapValues { it.value.toInt() },
    )
    "uncertain" -> RecurrenceRule(type = "uncertain")
    "calendar" -> RecurrenceRule(
        type = "calendar",
        months = recurrenceMonths.sorted().ifEmpty { listOf(1) },
        day = recurrenceDay,
        graceDays = recurrenceGraceDays,
    )
    "anniversary" -> RecurrenceRule(type = "anniversary", everyNMonths = anniversaryEveryNMonths())
    else -> null
}

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
    fun loadData(taskId: String?, cloneFromId: String? = null) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            // Users, contexts and the task: refresh the cache when online (REPLACE), then
            // ALWAYS read the cache (docs/cache-conventions.md). Reading the network answer
            // left the form empty offline: "Nouvelle tâche" on an existing task (2026-11-02).
            // One-shot reads, not observed: the edit screen is not reactive.
            taskRepository.refreshUsers()
            taskRepository.refreshContexts()
            // Uniquement en creation : proposer des taches a qui est en train
            // d'en modifier une n'aurait aucun sens.
            val propositions = if (taskId == null) {
                runCatching { apiService.getCatalogSuggestions().body()?.suggestions }
                    .getOrNull().orEmpty()
            } else {
                emptyList()
            }
            val contexts = try {
                taskRepository.observeContexts().first()
            } catch (e: Exception) {
                emptyList()
            }
            val users = taskRepository.observeUsers().first()

            if (taskId != null) {
                // Edit mode: the task from the cache, refreshed first when online.
                taskRepository.refreshTasks()
                val task = taskRepository.getCachedTaskById(taskId)
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
                        contextIds = task.contexts.sortedBy { it.position }.map { it.id },
                        assignedToIds = task.assignedToIds,
                        recurrenceType = task.recurrenceRule?.type ?: "none",
                        recurrenceMonths = task.recurrenceRule?.months?.toSet() ?: emptySet(),
                        recurrenceDay = task.recurrenceRule?.day ?: 1,
                        recurrenceGraceDays = task.recurrenceRule?.graceDays ?: 0,
                        anniversaryAmount = anniversaryFieldsOf(task.recurrenceRule).first,
                        anniversaryInYears = anniversaryFieldsOf(task.recurrenceRule).second,
                        recurrenceFieldText = unit.fromDays(canonicalDays).toString(),
                        recurrenceUnit = unit,
                        daysPerMonth = dpm,
                        weight = task.weight,
                        tolerance = task.tolerance,
                        contexts = contexts,
                        suggestions = propositions,
                        users = users,
                        frequencyVotes = votes,
                        clonedFromTitle = null,
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false, error = context.getString(R.string.task_detail_not_found)
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
                    contexts = contexts,
                    users = users,
                )
            } else {
                // Mode creation neuve — initialiser avec tous les users assignes (commune)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isEditMode = false,
                    assignedToIds = users.map { it.id },
                    contexts = contexts,
                    suggestions = propositions,
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
                contexts = state.contexts,
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
            users = state.users,
            clonedFromTitle = null,
        )
    }

    private suspend fun applyCloneSourceInternal(
        sourceId: String,
        contexts: List<ContextView>,
        users: List<User>,
    ) {
        val source = taskRepository.getCachedTaskById(sourceId)
        if (source == null) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = context.getString(R.string.task_edit_source_not_found),
                contexts = contexts,
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
            contextIds = source.contexts.sortedBy { it.position }.map { it.id },
            assignedToIds = source.assignedToIds,
            recurrenceType = source.recurrenceRule?.type ?: "none",
            recurrenceMonths = source.recurrenceRule?.months?.toSet() ?: emptySet(),
            recurrenceDay = source.recurrenceRule?.day ?: 1,
            recurrenceGraceDays = source.recurrenceRule?.graceDays ?: 0,
            anniversaryAmount = anniversaryFieldsOf(source.recurrenceRule).first,
            anniversaryInYears = anniversaryFieldsOf(source.recurrenceRule).second,
            recurrenceFieldText = unit.fromDays(canonicalDays).toString(),
            recurrenceUnit = unit,
            daysPerMonth = dpm,
            weight = source.weight,
            tolerance = source.tolerance,
            contexts = contexts,
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
