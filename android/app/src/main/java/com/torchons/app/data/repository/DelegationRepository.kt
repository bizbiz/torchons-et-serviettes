package com.torchons.app.data.repository

import com.torchons.app.data.api.DelegationApi
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.local.dao.DelegationDao
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
 * and queues a /sync op on `task_delegations` (INSERT / DELETE), both in one [LocalMutation];
 * a refresh REPLACES the cache.
 * A completion needs no op of its own here: the server settles the delegation, and the cache
 * read already hides it (DelegationDao.observeActive).
 */
@Singleton
class DelegationRepository @Inject constructor(
    private val api: DelegationApi,
    private val dao: DelegationDao,
    offline: OfflineWriter,
    private val tokenManager: TokenManager,
) {
    private val ledger = DelegationLedger(RoomDelegationMutationStore(dao), offline.mutation)

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
        return ledger.hand(taskId, recipientIds, me)
    }

    /** Take a delegation back; only its author may (the server ignores anyone else's). */
    suspend fun cancel(delegation: Delegation) = ledger.cancel(delegation)

    companion object {
        const val TABLE = "task_delegations"
    }
}

/** What [DelegationLedger] needs from the cache; Room in the app, a fake in JVM tests. */
internal interface DelegationMutationStore {
    suspend fun save(delegation: Delegation)
    suspend fun delete(id: String)
}

/** Room-backed [DelegationMutationStore]. */
internal class RoomDelegationMutationStore(private val dao: DelegationDao) : DelegationMutationStore {
    override suspend fun save(delegation: Delegation) = dao.upsertAll(listOf(delegation.toEntity()))
    override suspend fun delete(id: String) = dao.deleteById(id)
}

/** Handing and cancelling a delegation: one [LocalMutation] each (cache change + queued op, atomic). */
internal class DelegationLedger(
    private val store: DelegationMutationStore,
    private val mutation: LocalMutation,
    private val now: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun hand(taskId: String, recipientIds: List<String>, me: String): Delegation? {
        if (recipientIds.isEmpty()) return null
        val delegation = Delegation(
            id = newId(),
            taskId = taskId,
            authorId = me,
            recipientIds = recipientIds.sorted(),
            createdAt = now().toString(),
        )
        mutation.run {
            store.save(delegation)
            enqueue(
                DelegationRepository.TABLE, "INSERT", delegation.id,
                mapOf("task_id" to taskId, "recipient_ids" to delegation.recipientIds),
            )
        }
        return delegation
    }

    suspend fun cancel(delegation: Delegation) {
        mutation.run {
            store.delete(delegation.id)
            // task_id rides along for the sync dialog and opsToDiscard; the server reads row_id only.
            enqueue(DelegationRepository.TABLE, "DELETE", delegation.id, mapOf("task_id" to delegation.taskId))
        }
    }
}
