package com.torchons.app.ui.taskdetail

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.torchons.app.R
import com.torchons.app.data.model.RecurrenceRule
import com.torchons.app.ui.common.anniversaryPeriodLabel
import com.torchons.app.util.DurationFormat
import kotlin.math.roundToInt

/**
 * The task's frequency in one line, on its detail screen.
 *
 * Moved out of TaskDetailScreen.kt on 2026-11-13, when two labels said too little for a
 * newcomer (seen on the Play Store demo captures): « Variable selon le mois » now gives the
 * range (« tous les 3 à 10 jours selon le mois »), and « Irrégulière » the pace the server
 * learned from the history ([inferredIntervalDays], « environ tous les 4 jours »), the promise
 * « l'app apprend votre rythme » made visible.
 */
@Composable
internal fun formatRecurrence(rule: RecurrenceRule, inferredIntervalDays: Double? = null): String {
    val res = LocalContext.current.resources
    return when (rule.type) {
        "interval" -> rule.everyNDays?.let {
            val every = DurationFormat.formatDaysHuman(res, it, long = true)
            stringResource(R.string.task_recurrence_every_n_days_format, every)
        } ?: stringResource(R.string.task_recurrence_every_unknown)
        "monthly_variable" -> {
            val days = rule.daysPerMonth?.values?.filter { it > 0 }.orEmpty()
            if (days.isEmpty() || days.min() == days.max()) stringResource(R.string.task_recurrence_monthly_variable)
            else stringResource(R.string.task_recurrence_monthly_range_format, days.min(), days.max())
        }
        "uncertain" -> inferredIntervalDays?.takeIf { it >= 1 }?.let {
            stringResource(R.string.task_recurrence_uncertain_learned_format, it.roundToInt())
        } ?: stringResource(R.string.task_recurrence_uncertain)
        "calendar" -> stringResource(R.string.task_edit_recurrence_calendar)
        "anniversary" -> anniversaryPeriodLabel(rule.everyNMonths ?: 12)
        else -> rule.type
    }
}
