package com.torchons.app.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.torchons.app.data.model.RecurrenceRule
import com.torchons.app.util.TaskUrgencyCalculator

/**
 * The colour of a task's urgency state where it is drawn (stripe, dot, countdown). A task without
 * a cadence (one-off, or « uncertain ») is never green: green reads « nothing to do », misleading
 * for a task with no frequency (2026-11-18). It is grey instead, the theme's onSurfaceVariant,
 * readable in light and dark. Red and orange stay; tasks with a cadence keep their three colours.
 */
@Composable
fun taskStateColor(state: TaskUrgencyCalculator.State, withoutCadence: Boolean): Color =
    if (withoutCadence && state == TaskUrgencyCalculator.State.GREEN) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        TaskUrgencyCalculator.stateColor(state)
    }

/** Same rule for a state carried as a name (`Suggestion.state`: "RED", "ORANGE", "GREEN"). */
@Composable
fun taskStateColor(stateName: String, withoutCadence: Boolean): Color =
    if (withoutCadence && stateName != "RED" && stateName != "ORANGE") {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        TaskUrgencyCalculator.stateColor(stateName)
    }

/** A one-off task (no rule) or an « uncertain » one: no frequency to be late on. */
fun hasNoCadence(rule: RecurrenceRule?): Boolean = rule == null || rule.type == "uncertain"
