package com.torchons.app.data.repository

import com.google.gson.Gson
import com.torchons.app.core.sync.SyncTrigger
import com.torchons.app.data.api.DelegationApi
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.local.dao.DelegationDao
import com.torchons.app.data.local.dao.PendingOperationDao
import com.torchons.app.data.local.entities.PendingOperationEntity
import com.torchons.app.data.local.entities.toDomain
import com.torchons.app.data.local.entities.toEntity
import com.torchons.app.data.model.Delegation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * « À faire aujourd'hui » (docs/plans/a-faire-aujourdhui.md), offline-first like the tasks
 * (docs/cache-conventions.md): handing a task or cancelling it writes the local cache at once
 * and queues a /sync op on `task_delegations` (INSERT / DELETE); a refresh REPLACES the cache.
 * A completion needs no op of its own here: the server settles the delegation, and the cache
 * read already hides it (DelegationDao.observeActive).
 */
@Singleton
class DelegationRepository @Inject constructor(
    private val api: DelegationApi,
    private val dao: DelegationDao,
    private val pendingDao: PendingOperationDao,
    private val syncTrigger: SyncTrigger,
    private val tokenManager: TokenManager,
) {
    private val gson = Gson()

    fun observeActive(): Flow<List<Delegation>> = dao.observeActive().map { rows -> rows.map { it.toDomain() } }

    /** REPLACE of the cache from the server (best effort: false offline or on error). */
    suspend fun refresh(): Boolean = try {
        val response = api.getDelegations()
        if (response.isSuccessful) {
            dao.replaceAll(response.body().orEmpty().map { it.toEntity() })
            true
        } else {
            false
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    /** Hand [taskId] to [recipientIds] (never empty, never me: the caller offers only others). */
    suspend fun handTask(taskId: String, recipientIds: List<String>): Delegation? {
        val me = tokenManager.userIdFlow.first() ?: return null
        if (recipientIds.isEmpty()) return null
        val delegation = Delegation(
            id = UUID.randomUUID().toString(),
            taskId = taskId,
            authorId = me,
            recipientIds = recipientIds.sorted(),
            createdAt = Instant.now().toString(),
        )
        dao.upsertAll(listOf(delegation.toEntity()))
        enqueue("INSERT", delegation.id, mapOf("task_id" to taskId, "recipient_ids" to delegation.recipientIds))
        return delegation
    }

    /** Take a delegation back; only its author may (the server ignores anyone else's). */
    suspend fun cancel(delegation: Delegation) {
        dao.deleteById(delegation.id)
        // task_id rides along for the sync dialog and opsToDiscard; the server reads row_id only.
        enqueue("DELETE", delegation.id, mapOf("task_id" to delegation.taskId))
    }

    private suspend fun enqueue(operation: String, rowId: String, payload: Map<String, Any?>) {
        pendingDao.insert(
            PendingOperationEntity(
                clientOpId = UUID.randomUUID().toString(),
                tableName = TABLE,
                operation = operation,
                rowId = rowId,
                payloadJson = gson.toJson(payload),
                createdAt = System.currentTimeMillis(),
                foyerId = tokenManager.foyerIdFlow.first(),
            )
        )
        syncTrigger.requestSync()
    }

    companion object {
        const val TABLE = "task_delegations"
    }
}
