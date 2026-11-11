package com.torchons.app.worker

import android.content.res.Resources
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.FairShareStatus
import com.torchons.app.data.repository.TaskRepository
import com.torchons.app.ui.tasklist.computeTasksWithUrgency
import com.torchons.app.util.TaskUrgencyCalculator
import com.torchons.app.widget.visibleOnWidget
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/** What both reminder workers read: the Room cache and the DataStore, never the network. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderEntryPoint {
    fun taskRepository(): TaskRepository
    fun tokenManager(): TokenManager
}

/**
 * Urgency of every task visible to me, today and yesterday, from the Room cache.
 *
 * Same path as the Tasks tab and the widget: [visibleOnWidget] for what I may
 * see (solo tasks of others excluded) and [computeTasksWithUrgency] for the
 * state, so the count equals the red tasks on screen. The "presence" rule
 * (home tasks hidden off the home WiFi) is NOT applied: a reminder counts what
 * is overdue in the household, wherever the phone is.
 */
internal suspend fun loadStatePairs(
    repo: TaskRepository,
    tokens: TokenManager,
    resources: Resources,
    today: LocalDate = LocalDate.now(),
): List<TaskStatePair> {
    val tasks = repo.observeActiveTasks().first()
    val completions = repo.observeRecentCompletions(2000).first()
    val visible = visibleOnWidget(tasks, tokens.userIdFlow.first(), hidePresence = false)
    val now = computeTasksWithUrgency(visible, completions, today, resources)
    val before = computeTasksWithUrgency(visible, completions, today.minusDays(1), resources)
    return now.zip(before) { a, b -> TaskStatePair(a.task.title, a.state, b.state) }
}

/** My target share: the cached one, else an equal split among the active members. */
internal suspend fun myTargetRatio(repo: TaskRepository, tokens: TokenManager): Double =
    tokens.cachedMyTargetRatio() ?: defaultTargetRatio(repo.observeUsers().first().size)

/** Stores my own share out of a fair-share answer; no-op when I am not in it. */
suspend fun TokenManager.cacheMyTargetRatio(statuses: List<FairShareStatus>) {
    val me = userIdFlow.first() ?: return
    val mine = statuses.firstOrNull { it.userId == me } ?: return
    setMyTargetRatio(mine.targetRatio, foyerIdFlow.first())
}

internal fun TaskStatePair.isRedToday() = today == TaskUrgencyCalculator.State.RED
