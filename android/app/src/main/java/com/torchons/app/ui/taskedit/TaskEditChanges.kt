package com.torchons.app.ui.taskedit

import com.torchons.app.data.model.CreateTaskRequest
import com.torchons.app.data.model.RecurrenceRule
import com.torchons.app.data.model.UpdateTaskRequest

/**
 * The editable values the form was LOADED with, kept to tell what the user changed.
 *
 * An edit used to send every field on every save: the history said "changed 5 fields" for a
 * no-op, and a form opened on stale data silently reverted what it had not shown right. Now only
 * the fields that differ from this snapshot go out ([changesFrom]).
 *
 * The recurrence is stored as the rule the form would SAVE ([previewRecurrenceRule]) for the
 * loaded values, never the stored rule itself: both sides of the comparison come out of the same
 * function, so a conversion round trip (unit, month filter) cannot show up as a change.
 */
data class TaskEditBaseline(
    val title: String,
    val description: String,
    val contextIds: List<String>,
    val assignedToIds: List<String>,
    val recurrence: RecurrenceRule?,
    val weight: Int,
    val tolerance: String,
)

/** The snapshot of the form as it stands now (taken right after a load). */
internal fun TaskEditUiState.toBaseline() = TaskEditBaseline(
    title = title,
    description = description,
    contextIds = contextIds,
    assignedToIds = assignedToIds,
    recurrence = previewRecurrenceRule(),
    weight = weight,
    tolerance = tolerance,
)

/**
 * What the user changed against [base], as an update where an untouched field is null; null when
 * nothing changed (the caller sends no op at all).
 *
 * - Context ORDER counts (the first one is the task's main context), assignee order does not.
 * - A cleared description goes out as an empty string (null means "untouched").
 * - An explicit change to NO context goes out as an empty list ("detach every context").
 * - A recurrence switched to "none" cannot be expressed (null means "untouched" on the wire and
 *   in the queue), so it is not sent, as before.
 */
internal fun TaskEditUiState.changesFrom(base: TaskEditBaseline): UpdateTaskRequest? {
    val rule = previewRecurrenceRule()
    val request = UpdateTaskRequest(
        title = title.takeIf { it != base.title },
        description = description.takeIf { it != base.description }?.ifBlank { "" },
        contextIds = contextIds.takeIf { it != base.contextIds },
        assignedToIds = assignedToIds.takeIf { it.toSet() != base.assignedToIds.toSet() },
        recurrenceRule = rule.takeIf { it != null && it != base.recurrence },
        weight = weight.takeIf { it != base.weight },
        tolerance = tolerance.takeIf { it != base.tolerance },
    )
    return request.takeIf { it != UpdateTaskRequest() }
}

/** What a save of the form does. */
internal sealed interface SaveAction {
    /** An edit with no change: no op, no history line. */
    data object Nothing : SaveAction
    data class Update(val taskId: String, val request: UpdateTaskRequest) : SaveAction
    data class Create(val request: CreateTaskRequest) : SaveAction
}

/** The one place that decides what a save sends; the ViewModel only carries it out. */
internal fun TaskEditUiState.saveAction(taskId: String?): SaveAction {
    if (taskId == null) return SaveAction.Create(toCreateRequest())
    val base = baseline ?: return SaveAction.Update(taskId, wholeUpdate())
    return changesFrom(base)?.let { SaveAction.Update(taskId, it) } ?: SaveAction.Nothing
}

/** The whole form as an update: the fallback of a form that was never loaded (no baseline). */
internal fun TaskEditUiState.wholeUpdate() = UpdateTaskRequest(
    title = title,
    description = description.ifBlank { null },
    contextIds = contextIds,
    assignedToIds = assignedToIds,
    recurrenceRule = previewRecurrenceRule(),
    weight = weight,
    tolerance = tolerance,
)

/** The form as a new task. */
internal fun TaskEditUiState.toCreateRequest() = CreateTaskRequest(
    title = title,
    description = description.ifBlank { null },
    contextIds = contextIds,
    assignedToIds = assignedToIds,
    recurrenceRule = previewRecurrenceRule(),
    weight = weight,
    tolerance = tolerance,
)
