package com.torchons.app.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** [ConflictStore] over the real DAOs, repository and DataStore the worker reaches through Hilt. */
internal class EntryPointConflictStore(private val ep: SyncWorkerEntryPoint) : ConflictStore {
    override suspend fun deleteOp(clientOpId: String) = ep.pendingOperationDao().delete(clientOpId)

    override suspend fun incrementAttempt(clientOpId: String, error: String) =
        ep.pendingOperationDao().incrementAttempt(clientOpId, error)

    override suspend fun taskTitle(taskId: String): String? = ep.taskDao().getById(taskId)?.title

    override suspend fun addNotices(notices: List<RejectedSyncOp>) =
        ep.tokenManager().updateRejectedSyncOpsJson { appendRejectedOps(it, notices) }

    override suspend fun discard(clientOpId: String) {
        try {
            ep.taskRepository().discardPendingOp(clientOpId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The refresh that undoes a local edit needs the network; the op itself is already out
            // of the queue, the next refresh puts the cache right.
            ep.pendingOperationDao().delete(clientOpId)
        }
    }

    override suspend fun currentFoyerId(): String? = ep.tokenManager().foyerIdFlow.first()
}
