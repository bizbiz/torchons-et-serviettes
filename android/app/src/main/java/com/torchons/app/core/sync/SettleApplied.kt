package com.torchons.app.core.sync

import com.torchons.app.data.local.entities.PendingOperationEntity
import com.torchons.app.data.model.ChangeLogEntry
import com.torchons.app.data.model.SyncAppliedOp
import com.torchons.app.data.repository.CompletionRefreshGuard
import kotlinx.coroutines.CancellationException

/**
 * Removes the ops the server acknowledged from the queue, and the local rows of the ones it
 * dropped. When one of them is a `task_completions` op this is a settling in the sense of
 * [CompletionRefreshGuard]: the queue delete and the epoch bump are ONE step, so a completions
 * refresh whose request started before this push cannot be applied after it (it would no longer
 * find the row pending and would delete it, S2). Batches with no completion op skip the guard.
 * Kept out of [SyncWorker] so the ordering is testable without Hilt.
 */
internal suspend fun settleApplied(
    guard: CompletionRefreshGuard,
    applied: List<SyncAppliedOp>,
    batch: List<PendingOperationEntity>,
    deleteOp: suspend (clientOpId: String) -> Unit,
    deleteCompletionRow: suspend (rowId: String) -> Unit,
) {
    val appliedIds = applied.map { it.clientOpId }.toSet()
    val touchesCompletions = batch.any { it.clientOpId in appliedIds && it.tableName == "task_completions" }
    // Same reasoning for a tasks refresh (D1): a created/edited/deleted task whose op is settled
    // must not be erased by a GET /tasks answered before the push.
    val touchesTasks = batch.any { it.clientOpId in appliedIds && it.tableName == "tasks" }
    guard.tasks.settlingIf(touchesTasks) {
        guard.settlingIf(touchesCompletions) {
            applied.forEach { deleteOp(it.clientOpId) }
            // After the queue row is gone: a duplicate the server dropped leaves no local trace.
            droppedCompletionRowIds(applied, batch).forEach { deleteCompletionRow(it) }
        }
    }
}

/**
 * Applies the change_log entries a /sync answer carries. Completion entries from another device
 * change the cache like a push does: a refresh that started earlier must not delete them.
 */
internal suspend fun applyServerChanges(
    guard: CompletionRefreshGuard,
    changes: List<ChangeLogEntry>,
    onError: (ChangeLogEntry, Exception) -> Unit = { _, _ -> },
    apply: suspend (ChangeLogEntry) -> Unit,
) {
    guard.tasks.settlingIf(changes.any { it.table == "tasks" }) {
        guard.settlingIf(changes.any { it.table == "task_completions" }) {
            for (change in changes) {
                try {
                    apply(change)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // D8: such an error used to vanish. The rest of the batch still applies and the
                    // next authoritative refresh puts the cache right, but it is reported.
                    onError(change, e)
                }
            }
        }
    }
}
