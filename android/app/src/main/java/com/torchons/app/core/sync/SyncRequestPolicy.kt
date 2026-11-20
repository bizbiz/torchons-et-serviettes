package com.torchons.app.core.sync

import androidx.work.WorkInfo
import kotlinx.coroutines.sync.Mutex

/** What [SyncTrigger.requestSync] does with the one-shot sync work, given the work already there. */
internal enum class SyncRequestAction {
    /** Nothing is running: start a pass now, replacing a pass that waits (idle, or in backoff). */
    START_NOW,

    /** A pass is running: queue a new one right behind it, never cancel the running one. */
    CHAIN_AFTER_RUNNING,

    /** A pass is running and one already waits behind it: it will read the queue after this write. */
    ALREADY_CHAINED,
}

/**
 * The rule that replaced a blanket REPLACE (2026-11-20 emulator run: each write or reconnect
 * cancelled the running pass and started again, so a vote request already on the wire was sent a
 * second time, concurrently, and the server answered 500).
 *
 * - A RUNNING pass is never cancelled: its request may already be at the server, and a restart
 *   would replay it. The write is pushed by a pass chained after it (WorkManager's
 *   APPEND_OR_REPLACE starts it when the running one ends, whatever its result), so it is still
 *   pushed promptly. One waiting pass is enough: a second write adds nothing, the waiting pass
 *   reads the whole queue when it starts.
 * - Otherwise REPLACE, as before: a pass sitting in WorkManager's exponential backoff (30 s, 1 min,
 *   2 min... up to 5 h) must not hold a new write back, and nothing in flight can be lost.
 *
 * Deduced, not measured: if the running pass ends with `retry` it goes back to the backoff and the
 * pass chained behind it waits with it, until the next request (a write, a reconnect) replaces both.
 */
internal fun syncRequestAction(states: List<WorkInfo.State>): SyncRequestAction = when {
    WorkInfo.State.RUNNING !in states -> SyncRequestAction.START_NOW
    WorkInfo.State.BLOCKED in states -> SyncRequestAction.ALREADY_CHAINED
    else -> SyncRequestAction.CHAIN_AFTER_RUNNING
}

/**
 * One sync pass at a time in this process. The periodic work and the one-shot work are two unique
 * works that WorkManager may run together: both would read the same queue and send the same ops.
 * The second one waits, then finds the queue emptied by the first.
 */
internal val syncPassLock = Mutex()
