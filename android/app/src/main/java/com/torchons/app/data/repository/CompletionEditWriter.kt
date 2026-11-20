package com.torchons.app.data.repository

import com.torchons.app.data.local.dao.CompletionDao
import com.torchons.app.data.local.entities.CompletionEntity
import javax.inject.Inject
import javax.inject.Singleton

/** What [CompletionEditLedger] needs from the cache; Room in the app, a fake in JVM tests. */
internal interface CompletionEditStore {
    suspend fun completion(id: String): CompletionEntity?
    suspend fun saveCompletion(entity: CompletionEntity)
    suspend fun deleteCompletion(id: String)
}

/** Room-backed [CompletionEditStore]. */
internal class RoomCompletionEditStore(private val completionDao: CompletionDao) : CompletionEditStore {
    override suspend fun completion(id: String) = completionDao.getById(id)
    override suspend fun saveCompletion(entity: CompletionEntity) = completionDao.upsertAll(listOf(entity))
    override suspend fun deleteCompletion(id: String) = completionDao.deleteById(id)
}

/**
 * The offline-first delete and edit of completions that already exist (the write of a NEW solo
 * completion is [CompletionLedger]). Each is one [LocalMutation]: the optimistic Room change and
 * its op(s) in one transaction, in the application's scope. The urgency of the task is recomputed
 * from the cache at once (docs/cache-conventions.md); no refresh follows, the server still has the
 * old state until the op is pushed.
 *
 * Why no [CompletionRefreshGuard] bump here: the guard protects a refresh from answers OLDER than
 * a push (settling). A local change is protected differently, by its pending op: a completions
 * refresh applies the server's rows except those with an op still in the queue
 * (`serverRowsToApply`), reading the queue inside its own Room transaction. Row change and op
 * being ONE transaction, that read sees both or neither: the window in which a refresh found the
 * row changed but no op, and brought the server's copy back, is closed by the atomicity itself.
 * When the op is pushed the worker's settling bumps the epoch, as for any completion op.
 */
internal class CompletionEditLedger(
    private val store: CompletionEditStore,
    private val mutation: LocalMutation,
) {
    suspend fun delete(completionId: String): Result<Unit> {
        mutation.run {
            store.deleteCompletion(completionId)
            enqueue("task_completions", "DELETE", completionId, null)
        }
        return Result.success(Unit)
    }

    /** A collective event: every row deleted, one DELETE op per row, all or nothing. */
    suspend fun deleteCollective(rowIds: List<String>): Result<Unit> {
        mutation.run {
            for (id in rowIds) {
                store.deleteCompletion(id)
                enqueue("task_completions", "DELETE", id, null)
            }
        }
        return Result.success(Unit)
    }

    suspend fun update(completionId: String, completedBy: String, completedAt: String): Result<Unit> =
        mutation.run {
            val existing = store.completion(completionId)
                ?: return@run Result.failure(IllegalStateException("Completion introuvable en cache"))
            store.saveCompletion(existing.copy(completedBy = completedBy, completedAt = completedAt))
            enqueue(
                "task_completions", "UPDATE", completionId,
                mapOf("completed_by" to completedBy, "completed_at" to completedAt),
            )
            Result.success(Unit)
        }
}

/** The Hilt-facing entry point of [CompletionEditLedger]; [TaskRepository] delegates here. */
@Singleton
class CompletionEditWriter @Inject constructor(
    completionDao: CompletionDao,
    offline: OfflineWriter,
) {
    private val ledger = CompletionEditLedger(RoomCompletionEditStore(completionDao), offline.mutation)

    suspend fun delete(completionId: String): Result<Unit> = ledger.delete(completionId)

    suspend fun deleteCollective(rowIds: List<String>): Result<Unit> = ledger.deleteCollective(rowIds)

    suspend fun update(completionId: String, completedBy: String, completedAt: String): Result<Unit> =
        ledger.update(completionId, completedBy, completedAt)
}
