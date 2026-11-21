package com.torchons.app.ui.taskedit

import android.content.Context
import com.torchons.app.R
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.model.ContextView
import com.torchons.app.data.model.User
import com.torchons.app.data.repository.TaskRepository
import com.torchons.app.ui.common.UserError
import com.torchons.app.util.DurationUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Loading of the edit form (reference data, the edited task, a clone source),
 * split out of TaskEditViewModel. It reads and writes the ViewModel's
 * [uiState] (passed in, never exposed).
 *
 * `internal` because TaskEditViewModel is its only user.
 */
internal class TaskEditLoader(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<TaskEditUiState>,
    private val taskRepository: TaskRepository,
    private val apiService: ApiService,
    private val context: Context,
) {

    /** Charge les donnees de reference (categories, rooms, users) et la tache si edition. */
    fun loadData(taskId: String?, cloneFromId: String? = null) {
        scope.launch {
            uiState.value = uiState.value.copy(isLoading = true)

            // Users, contexts and the task: refresh the cache when online (REPLACE), then
            // ALWAYS read the cache (docs/cache-conventions.md). Reading the network answer
            // left the form empty offline: "Nouvelle tâche" on an existing task (2026-11-02).
            // One-shot reads, not observed: the edit screen is not reactive.
            taskRepository.refreshUsers()
            taskRepository.refreshContexts()
            // Uniquement en creation : proposer des taches a qui est en train
            // d'en modifier une n'aurait aucun sens.
            val serverPropositions = if (taskId == null) {
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
            // A household without any context gets an empty list from the server: propose the
            // whole catalog instead (the title filter still narrows it).
            val propositions = if (taskId == null && contexts.isEmpty() && serverPropositions.isEmpty()) {
                runCatching { apiService.getCatalogContexts().body()?.tasks }
                    .getOrNull()?.let(::catalogTasksAsSuggestions).orEmpty()
            } else {
                serverPropositions
            }

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
                    uiState.value = uiState.value.copy(
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
                    ).let { it.copy(baseline = it.toBaseline()) }
                } else {
                    uiState.value = uiState.value.copy(
                        isLoading = false, error = UserError(context.getString(R.string.task_detail_not_found))
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
                uiState.value = uiState.value.copy(
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
        val state = uiState.value
        if (state.isEditMode) return
        scope.launch {
            applyCloneSourceInternal(
                sourceId = sourceId,
                contexts = state.contexts,
                users = state.users,
            )
        }
    }

    private suspend fun applyCloneSourceInternal(
        sourceId: String,
        contexts: List<ContextView>,
        users: List<User>,
    ) {
        val source = taskRepository.getCachedTaskById(sourceId)
        if (source == null) {
            uiState.value = uiState.value.copy(
                isLoading = false,
                error = UserError(context.getString(R.string.task_edit_source_not_found)),
                contexts = contexts,
                users = users,
                assignedToIds = if (uiState.value.assignedToIds.isEmpty()) {
                    users.map { it.id }
                } else {
                    uiState.value.assignedToIds
                },
            )
            return
        }
        val dpm = source.recurrenceRule?.daysPerMonth?.mapValues { it.value.toString() }
            ?: (1..12).associate { it.toString() to "" }
        val canonicalDays = source.recurrenceRule?.everyNDays ?: 7
        val unit = DurationUnit.bestFor(canonicalDays)
        uiState.value = uiState.value.copy(
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
}
