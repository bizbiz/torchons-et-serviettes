package com.torchons.app.ui.home

import com.torchons.app.data.model.Completion
import com.torchons.app.data.model.Task
import com.torchons.app.data.repository.locallyCompletedTaskIds
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/**
 * The tasks the current user completed inside their duplicate window, read from the Room
 * completions, so the Home suggestions (a server snapshot) never offer one again, whatever the
 * ViewModel remembers: a completion not pushed yet survives a new ViewModel and a process death
 * in Room, not in `justCompletedIds`.
 */
internal fun locallyCompletedFlow(
    tasks: Flow<List<Task>>,
    completions: Flow<List<Completion>>,
    me: Flow<String?>,
    now: () -> Instant = Instant::now,
): Flow<Set<String>> =
    combine(tasks, completions, me) { t, c, user -> locallyCompletedTaskIds(t, c, user, now()) }
        .distinctUntilChanged()

/**
 * Emits each time the sync worker finishes a push (`lastSyncPushAt` moves), not for the value
 * already stored when the collection starts: that is the screen's own first refresh. Home
 * refreshes on it instead of right after a tap, when the server does not have the completion yet.
 */
internal fun pushFinished(lastSyncPushAt: Flow<Long?>): Flow<Long?> =
    lastSyncPushAt.distinctUntilChanged().drop(1)
