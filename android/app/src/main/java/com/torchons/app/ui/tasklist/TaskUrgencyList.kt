package com.torchons.app.ui.tasklist

import android.content.res.Resources
import com.torchons.app.data.model.Completion
import com.torchons.app.data.model.Task
import com.torchons.app.util.TaskUrgencyCalculator
import java.time.LocalDate

/**
 * Urgency of each task and the order of the Tasks tab, shared by the tab
 * (TaskListViewModel.applyFilters) and the home-screen widget, so both always
 * show the same states in the same order. Moved out of the view model on
 * 2026-10-18, behaviour unchanged.
 */
internal fun computeTasksWithUrgency(
    tasks: List<Task>,
    completions: List<Completion>,
    today: LocalDate,
    resources: Resources,
): List<TaskWithUrgency> {
    // Pre-calcul O(M) : pour chaque tache, on connait sa derniere completion
    // en une seule passe. Avant, on faisait completions.filter().maxBy() PAR
    // tache => O(N×M). Sur ~50 taches × 500 completions = 25 000 ops par
    // recompute (changement de filtre, refresh, scroll), suffisant pour
    // faire ramer le scroll de la liste.
    val lastCompletionByTaskId = HashMap<String, Completion>(completions.size)
    // Same pass for the earliest one: the anchor of an `anniversary` rule.
    val firstCompletionByTaskId = HashMap<String, Completion>(completions.size)
    for (c in completions) {
        val existing = lastCompletionByTaskId[c.taskId]
        if (existing == null || c.completedAt > existing.completedAt) {
            lastCompletionByTaskId[c.taskId] = c
        }
        val earliest = firstCompletionByTaskId[c.taskId]
        if (earliest == null || c.completedAt < earliest.completedAt) {
            firstCompletionByTaskId[c.taskId] = c
        }
    }

    return tasks.map { task ->
        val lastCompletion = lastCompletionByTaskId[task.id]
        // Online = serveur autoritaire (task.lastCompletedAt calcule
        // serveur) ; offline = completion optimiste locale plus recente.
        // max() des deux -> evite le fallback errone sur created_at quand
        // le cache local de completions est vide pour la tache.
        val effectiveLastCompleted = TaskUrgencyCalculator.latestCompletedAt(
            serverLastCompletedAt = task.lastCompletedAt,
            localCacheLatest = lastCompletion?.completedAt,
        )
        val nominal = TaskUrgencyCalculator.nominalIntervalDays(task.recurrenceRule)
        val dueDate = TaskUrgencyCalculator.computeDueDate(
            lastCompletedAt = effectiveLastCompleted,
            rule = task.recurrenceRule,
            createdAt = task.createdAt,
            firstCompletedAt = TaskUrgencyCalculator.earliestCompletedAt(
                serverFirstCompletedAt = task.firstCompletedAt,
                localCacheEarliest = firstCompletionByTaskId[task.id]?.completedAt,
            ),
        )
        val absentDays = task.absentDaysSinceLastCompletion
        val state = TaskUrgencyCalculator.computeState(
            dueDate, today, nominal, absentDays,
            graceDays = TaskUrgencyCalculator.graceDaysOf(task.recurrenceRule),
            windowCapDays = TaskUrgencyCalculator.orangeWindowCapOf(task.recurrenceRule),
        )
        val daysUntilDue = TaskUrgencyCalculator.daysUntilDue(dueDate, today, absentDays)
        val countdownLabel = TaskUrgencyCalculator.countdownLabel(
            resources, dueDate, today, absentDays,
        )

        // Classification v0.20. Une 'uncertain' est recurrente de nom mais
        // sans cadence effective : on la range dans SANS_CADENCE pour
        // qu'elle soit filtrable via le chip 🔁 LOW aux cotes des ponctuelles.
        val isUncertain = task.recurrenceRule?.type == "uncertain"
        val group = if (task.recurrenceRule == null || isUncertain) {
            RecurrenceGroup.SANS_CADENCE
        } else {
            RecurrenceGroup.CADENCE
        }

        TaskWithUrgency(
            task = task,
            state = state,
            daysUntilDue = daysUntilDue,
            countdownLabel = countdownLabel,
            // Le contexte PRINCIPAL remplace la piece et la categorie. Le
            // repli sur les deux anciennes valeurs n'est pas defensif : une
            // tache creee avant le chantier et jamais rouverte n'a aucun
            // rattachement, et l'afficher sans lieu serait une regression
            // visible pour l'utilisateur.
            categoryName = task.contexts.getOrNull(1)?.name,
            categoryColor = task.contexts.getOrNull(1)?.color,
            roomName = task.contexts.firstOrNull()?.name,
            roomEmoji = task.contexts.firstOrNull()?.emoji,
            group = group,
            lastCompletedAt = lastCompletion?.completedAt,
            isUncertain = isUncertain,
        )
    }
}

/**
 * Tri v0.20 — empilement en trois blocs :
 * 1. Groupe CADENCE trie par urgence (RED > ORANGE > GREEN puis
 *    days_until_due asc : les plus en retard d'abord).
 * 2. Groupe SANS_CADENCE jamais completees, tri createdAt DESC
 *    (plus recemment creees d'abord).
 * 3. Groupe SANS_CADENCE deja completees, tri completedAt DESC
 *    (plus recemment faites d'abord).
 * En mode chip HIGH seul le bloc 1 survit, en LOW les blocs 2+3.
 * En OFF les trois s'empilent sans separateur visuel (decision UI).
 */
internal fun sortForTaskList(tasks: List<TaskWithUrgency>): List<TaskWithUrgency> {
    val cadence = tasks
        .filter { it.group == RecurrenceGroup.CADENCE }
        .sortedWith(compareBy(
            { TaskUrgencyCalculator.urgencyOrder(it.state) },
            { it.daysUntilDue },
        ))
    val sansCadenceNeverDone = tasks
        .filter { it.group == RecurrenceGroup.SANS_CADENCE && it.lastCompletedAt == null }
        .sortedByDescending { it.task.createdAt }
    val sansCadenceDone = tasks
        .filter { it.group == RecurrenceGroup.SANS_CADENCE && it.lastCompletedAt != null }
        .sortedByDescending { it.lastCompletedAt }
    return cadence + sansCadenceNeverDone + sansCadenceDone
}
