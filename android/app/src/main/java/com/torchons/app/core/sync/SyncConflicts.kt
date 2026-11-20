package com.torchons.app.core.sync

import com.torchons.app.data.local.entities.PendingOperationEntity
import com.torchons.app.data.model.SyncConflict

/** What [settleConflicts] needs from the app; the SyncWorker's entry point in production, a fake in tests. */
internal interface ConflictStore {
    suspend fun deleteOp(clientOpId: String)

    suspend fun incrementAttempt(clientOpId: String, error: String)

    /** Title of a task for a notice; null when the cache no longer has it. */
    suspend fun taskTitle(taskId: String): String?

    /** Appends notices to the list the sync dialog shows until acknowledged. */
    suspend fun addNotices(notices: List<RejectedSyncOp>)

    /** Drops a refused op the way a manual discard does: its local effect undone, its dependents with it. */
    suspend fun discard(clientOpId: String)

    /** The household the token is in now; null when unknown. */
    suspend fun currentFoyerId(): String?
}

/**
 * Settles the conflicts of one /sync answer.
 *
 * - `out_of_scope`: the op can never apply (row gone, household left). Dropped from the queue, and
 *   NOW TOLD (D8, 2026-11-20 audit: an offline completion on a task someone else had deleted just
 *   disappeared), except when it belongs to a household that is not the current one: that is the
 *   user's own leave/switch, which is no news.
 * - `rejected`: refused for good. Dropped like a manual discard, and told.
 * - every other reason stays queued, replayed next pass, with its translated cause under it.
 *
 * [textOf] turns `(reason, detail)` into the translated sentence (ConflictText.kt).
 */
internal suspend fun settleConflicts(
    conflicts: List<SyncConflict>,
    batch: List<PendingOperationEntity>,
    store: ConflictStore,
    textOf: (reason: String, detail: String?) -> String,
    nowMs: () -> Long = System::currentTimeMillis,
) {
    val notices = mutableListOf<RejectedSyncOp>()
    suspend fun noticeFor(conflict: SyncConflict): RejectedSyncOp? {
        val op = batch.firstOrNull { it.clientOpId == conflict.clientOpId } ?: return null
        return RejectedSyncOp(
            kind = pendingOpKind(op.tableName, op.operation),
            taskTitle = taskIdOf(op)?.let { store.taskTitle(it) },
            reason = textOf(conflict.reason, conflict.detail),
            rejectedAtMs = nowMs(),
        )
    }

    val current = if (conflicts.any { it.reason == "out_of_scope" }) store.currentFoyerId() else null
    for (conflict in conflicts) {
        when (conflict.reason) {
            "out_of_scope" -> {
                val op = batch.firstOrNull { it.clientOpId == conflict.clientOpId }
                val leftOnPurpose = op?.foyerId != null && current != null && op.foyerId != current
                if (!leftOnPurpose) noticeFor(conflict)?.let { notices += it }
                store.deleteOp(conflict.clientOpId)
            }
            "rejected" -> noticeFor(conflict)?.let { notices += it }
            else -> store.incrementAttempt(conflict.clientOpId, textOf(conflict.reason, conflict.detail))
        }
    }
    if (notices.isNotEmpty()) store.addNotices(notices)
    for (conflict in conflicts.filter { it.reason == "rejected" }) store.discard(conflict.clientOpId)
}
