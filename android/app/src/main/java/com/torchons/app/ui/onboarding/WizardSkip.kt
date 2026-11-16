package com.torchons.app.ui.onboarding

import com.torchons.app.data.model.CatalogTaskContextView

/*
 * Pure state transformations of the "create a household" wizard.
 *
 * Kept outside the ViewModel so that they run in plain JVM unit tests. Rule
 * decided by the owner: skipping a step adds nothing from that step.
 */

/**
 * Tasks shown in step 4: a task appears when one of its SELECTABLE contexts is
 * checked. Implicit contexts (not selectable) never make a task visible alone.
 */
internal fun visibleTasksOf(state: WizardState): List<CatalogTaskContextView> {
    val implicit = state.contexts.filter { !it.selectable }.map { it.key }.toSet()
    return state.contextTasks.filter { t ->
        t.contexts.any { c -> c !in implicit && c in state.selectedContextKeys }
    }
}

/**
 * Derives the checked tasks from the checked contexts and the manual deviations:
 * a task is checked if selected by default and not opted out, or opted in.
 */
internal fun recomputeSelection(state: WizardState): WizardState {
    val checked = visibleTasksOf(state)
        .filter { (it.defaultSelected && it.key !in state.taskOptOut) || it.key in state.taskOptIn }
        .map { it.key }
        .toSet()
    return state.copy(selectedTaskKeys = checked)
}

/**
 * Step 4 skipped: no task is created. Every visible task is opted out and the
 * opt-in set is emptied, so that [recomputeSelection] cannot rebuild a selection.
 */
internal fun withTasksSkipped(state: WizardState): WizardState =
    recomputeSelection(
        state.copy(
            taskOptOut = visibleTasksOf(state).map { it.key }.toSet(),
            taskOptIn = emptySet(),
        ),
    )

/**
 * Step 3 skipped: no context checked and no custom room added. The
 * `contextsSkipped` flag stops a later catalog (re)load from re-checking the
 * defaults on the empty selection.
 */
internal fun withContextsSkipped(state: WizardState): WizardState =
    recomputeSelection(
        state.copy(
            selectedContextKeys = emptySet(),
            customRooms = emptyList(),
            contextsSkipped = true,
        ),
    )
