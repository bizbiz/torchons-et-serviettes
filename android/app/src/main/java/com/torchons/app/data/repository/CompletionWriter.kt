package com.torchons.app.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import com.torchons.app.core.sync.SyncTrigger
import com.torchons.app.data.local.AppDatabase
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.local.dao.CompletionDao
import com.torchons.app.data.local.dao.PendingOperationDao
import com.torchons.app.data.local.dao.TaskDao
import com.torchons.app.data.local.entities.CompletionEntity
import com.torchons.app.data.local.entities.PendingOperationEntity
import com.torchons.app.data.local.entities.toEntity
import com.torchons.app.data.model.Completion
import com.torchons.app.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** The write was refused because [twin], a completion by the same member, is inside the duplicate window. */
class DuplicateCompletionException(val twin: CompletionEntity) : Exception("duplicate completion ${twin.id}")

/** What [CompletionLedger] needs from the database; Room in the app, a fake in JVM tests. */
internal interface CompletionStore {
    /** Runs [block] as ONE transaction: all of its writes land, or none. */
    suspend fun <T> atomically(block: suspend () -> T): T

    /** The duplicate window of the task in seconds, null when the task is not in the cache. */
    suspend fun duplicateGapSeconds(taskId: String): Long?

    suspend fun completionsOf(taskId: String): List<CompletionEntity>
    suspend fun insertCompletion(entity: CompletionEntity)
    suspend fun enqueue(op: PendingOperationEntity)
}

/**
 * The offline-first write of a solo completion: duplicate check, optimistic Room row and queued
 * `/sync` op in ONE transaction, run in a scope that outlives the screen.
 *
 * Why both (2026-11-19, measured in prod): a user tapped « Fait par moi aujourd'hui » three or
 * four times and only one op reached the server.
 *  - The screens used to write AFTER their 3 s celebration, in the ViewModel scope: leaving the
 *    screen cancelled the write, so nothing reached Room or the queue. The write now runs in
 *    [scope] (the application's) and cannot be cancelled by the caller: the caller only waits
 *    for the result.
 *  - Check, upsert and enqueue were three separate steps, so two quick taps both passed the
 *    check. Inside one transaction the second one sees the first one's row and is refused.
 *
 * What is read from DataStore (user id, household) is read BEFORE the transaction: a suspend
 * call that waits on another dispatcher has no business holding the database's write lock.
 */
internal class CompletionLedger(
    private val store: CompletionStore,
    private val scope: CoroutineScope,
    private val requestSync: () -> Unit,
    private val now: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val gson = Gson()

    suspend fun complete(
        taskId: String,
        completedAt: String?,
        completedBy: String?,
        notes: String?,
        currentUserId: String?,
        foyerId: String?,
    ): Result<Completion> {
        // The optimistic row MIMICS the server: without an explicit completedBy the backend credits
        // the token's user (sync.py _handle_completion), so the local row must too, or the history
        // shows "?" until the next refresh.
        val by = completedBy ?: currentUserId
        val at = completedAt ?: now().toString()
        val rowId = newId()
        val clientOpId = newId()
        val optimistic = Completion(
            id = rowId, taskId = taskId, completedBy = by ?: "", completedAt = at,
            pointsEarned = 1, notes = notes,
        )
        val payload = mapOf<String, Any?>(
            "task_id" to taskId,
            "completed_by" to by,
            "completed_at" to at, // the tap's time, not the sync's: an offline tap keeps its date
            "notes" to notes,
        )

        // async + await, not withContext: cancelling the caller stops its wait, never the write.
        val refusedBy = scope.async {
            store.atomically {
                val twin = by?.let { duplicateIn(taskId, it, at) }
                if (twin == null) {
                    store.insertCompletion(optimistic.toEntity())
                    store.enqueue(
                        PendingOperationEntity(
                            clientOpId = clientOpId,
                            tableName = "task_completions",
                            operation = "INSERT",
                            rowId = rowId,
                            payloadJson = gson.toJson(payload),
                            createdAt = System.currentTimeMillis(),
                            foyerId = foyerId,
                        ),
                    )
                }
                twin
            }.also { if (it == null) requestSync() } // after the commit: the worker must find the op
        }.await()

        return if (refusedBy == null) {
            Result.success(optimistic)
        } else {
            Result.failure(DuplicateCompletionException(refusedBy))
        }
    }

    private suspend fun duplicateIn(taskId: String, member: String, at: String): CompletionEntity? {
        val gap = store.duplicateGapSeconds(taskId) ?: return null
        val instant = parseInstant(at) ?: return null
        return findDuplicate(store.completionsOf(taskId), setOf(member), instant, gap)
    }
}

/** Room-backed [CompletionStore]. */
internal class RoomCompletionStore(
    private val db: AppDatabase,
    private val taskDao: TaskDao,
    private val completionDao: CompletionDao,
    private val pendingDao: PendingOperationDao,
) : CompletionStore {
    override suspend fun <T> atomically(block: suspend () -> T): T = db.withTransaction { block() }

    override suspend fun duplicateGapSeconds(taskId: String): Long? =
        taskDao.getById(taskId)?.let { minCompletionGapSeconds(it.recurrenceRule, it.inferredIntervalDays) }

    override suspend fun completionsOf(taskId: String) = completionDao.getByTask(taskId)

    override suspend fun insertCompletion(entity: CompletionEntity) = completionDao.upsertAll(listOf(entity))

    override suspend fun enqueue(op: PendingOperationEntity) = pendingDao.insert(op)
}

/** The Hilt-facing entry point of [CompletionLedger]; [TaskRepository.completeTask] delegates here. */
@Singleton
class CompletionWriter @Inject constructor(
    db: AppDatabase,
    taskDao: TaskDao,
    completionDao: CompletionDao,
    pendingDao: PendingOperationDao,
    private val tokenManager: TokenManager,
    syncTrigger: SyncTrigger,
    @ApplicationScope scope: CoroutineScope,
) {
    private val ledger = CompletionLedger(
        RoomCompletionStore(db, taskDao, completionDao, pendingDao), scope, syncTrigger::requestSync,
    )

    suspend fun complete(
        taskId: String,
        completedAt: String? = null,
        completedBy: String? = null,
        notes: String? = null,
    ): Result<Completion> = ledger.complete(
        taskId, completedAt, completedBy, notes,
        currentUserId = tokenManager.userIdFlow.first(),
        foyerId = tokenManager.foyerIdFlow.first(),
    )
}
