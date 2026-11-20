package com.torchons.app.data.repository

import com.torchons.app.core.sync.opsToDiscard
import com.torchons.app.data.local.dao.CompletionDao
import com.torchons.app.data.local.dao.PendingOperationDao
import com.torchons.app.data.local.dao.TaskDao
import com.torchons.app.data.local.entities.PendingOperationEntity
import javax.inject.Inject
import javax.inject.Singleton

/** What [PendingDiscardLedger] needs from the cache; Room in the app, a fake in JVM tests. */
internal interface PendingDiscardStore {
    suspend fun queue(): List<PendingOperationEntity>
    suspend fun deleteTask(id: String)
    suspend fun deleteCompletion(id: String)
}

/** Room-backed [PendingDiscardStore]. */
internal class RoomPendingDiscardStore(
    private val pendingDao: PendingOperationDao,
    private val taskDao: TaskDao,
    private val completionDao: CompletionDao,
) : PendingDiscardStore {
    override suspend fun queue() = pendingDao.getAll()
    override suspend fun deleteTask(id: String) = taskDao.deleteById(id)
    override suspend fun deleteCompletion(id: String) = completionDao.deleteById(id)
}

/**
 * Drops a queued op the user gave up on (sync dialog), with the ops that depend on it
 * ([opsToDiscard]), and undoes the optimistic local write of a dropped INSERT: the row is one the
 * server will never have, so it is deleted. A dropped UPDATE or DELETE left the server row as it
 * was; the refresh the caller runs afterwards brings it back (the row is no longer pending, the
 * replace takes it).
 *
 * One [LocalMutation]: the queue is read, the ops deleted and the rows removed in ONE transaction.
 * Done as separate steps, leaving the screen between them dropped the op and kept the local row,
 * a ghost the server would never know. It queues nothing, so it requests no sync.
 */
internal class PendingDiscardLedger(
    private val store: PendingDiscardStore,
    private val mutation: LocalMutation,
) {
    /** The ops dropped (empty when [clientOpId] is no longer queued); the caller refreshes after. */
    suspend fun discard(clientOpId: String): List<PendingOperationEntity> = mutation.run {
        val queue = store.queue()
        val target = queue.firstOrNull { it.clientOpId == clientOpId } ?: return@run emptyList()
        val dropped = opsToDiscard(queue, target)
        dropped.forEach { dequeue(it.clientOpId) }
        for (op in dropped.filter { it.operation == "INSERT" }) {
            when (op.tableName) {
                "tasks" -> store.deleteTask(op.rowId)
                "task_completions" -> store.deleteCompletion(op.rowId)
            }
        }
        dropped
    }
}

/** The Hilt-facing entry point of [PendingDiscardLedger]; [TaskRepository] delegates here. */
@Singleton
class PendingDiscardWriter @Inject constructor(
    pendingDao: PendingOperationDao,
    taskDao: TaskDao,
    completionDao: CompletionDao,
    offline: OfflineWriter,
) {
    private val ledger = PendingDiscardLedger(
        RoomPendingDiscardStore(pendingDao, taskDao, completionDao), offline.mutation,
    )

    internal suspend fun discard(clientOpId: String) = ledger.discard(clientOpId)
}
