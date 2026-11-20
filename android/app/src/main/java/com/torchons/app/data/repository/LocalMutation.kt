package com.torchons.app.data.repository

import androidx.room.withTransaction
import com.google.gson.Gson
import com.torchons.app.core.sync.SyncTrigger
import com.torchons.app.data.local.AppDatabase
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.local.dao.PendingOperationDao
import com.torchons.app.data.local.entities.PendingOperationEntity
import com.torchons.app.di.ApplicationScope
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first

/** What [LocalMutation] needs from the database; Room in the app, a fake in JVM tests. */
internal interface OpStore {
    /** Runs [block] as ONE transaction: all of its writes land, or none. */
    suspend fun <T> atomically(block: suspend () -> T): T

    suspend fun enqueue(op: PendingOperationEntity)

    suspend fun dequeue(clientOpId: String)
}

/** Room-backed [OpStore]. The DAOs a block uses join the transaction through the coroutine context. */
internal class RoomOpStore(
    private val db: AppDatabase,
    private val pendingDao: PendingOperationDao,
) : OpStore {
    override suspend fun <T> atomically(block: suspend () -> T): T = db.withTransaction { block() }

    override suspend fun enqueue(op: PendingOperationEntity) = pendingDao.insert(op)

    override suspend fun dequeue(clientOpId: String) = pendingDao.delete(clientOpId)
}

/** What a mutation block can do besides writing the local cache: queue ops for the server. */
internal class MutationScope(
    private val store: OpStore,
    private val foyerId: String?,
    private val newId: () -> String,
    private val nowMillis: () -> Long,
) {
    private val gson = Gson()

    /** How many ops the block has queued; the sync is requested only when this is above zero. */
    var enqueued: Int = 0
        private set

    /**
     * Queues a `/sync` op inside the transaction of the block. [payload] null is a DELETE's
     * (stored as "null", as it always was).
     */
    suspend fun enqueue(
        table: String,
        operation: String,
        rowId: String,
        payload: Any?,
        clientOpId: String = newId(),
    ) {
        store.enqueue(
            PendingOperationEntity(
                clientOpId = clientOpId,
                tableName = table,
                operation = operation,
                rowId = rowId,
                payloadJson = gson.toJson(payload),
                createdAt = nowMillis(),
                foyerId = foyerId,
            ),
        )
        enqueued++
    }

    /** Drops a queued op the user gave up on; requests no sync (nothing new to push). */
    suspend fun dequeue(clientOpId: String) = store.dequeue(clientOpId)
}

/**
 * The ONE way an offline-first mutation writes: local Room change and queued op(s) in a single
 * transaction, run in a scope that outlives the screen, sync requested after the commit.
 *
 * Why (2026-11-20, found while auditing 72ae485): every mutation but the completion used to do
 * "write Room", then "enqueue", as two suspend steps in the ViewModel scope. Leaving the screen
 * between them cancelled the coroutine: the change was shown but never queued, so it never
 * reached the server and the next authoritative refresh erased it (or, the other way round, an op
 * was queued for a change the screen never made). Two guarantees close that:
 *  - atomicity: [OpStore.atomically] makes row and op land together or not at all, and a refresh
 *    (which reads the queue to spare pending rows) never sees one without the other;
 *  - survival: the transaction runs through `scope.async { ... }.await()`. Cancelling the caller
 *    stops its wait, never the write (a `withContext` would be cancelled with it).
 *
 * What the block cannot do: wait on anything but local work. Reads of DataStore (user id,
 * household) happen BEFORE: the household through [foyerId], read by [run] before the transaction
 * opens, anything else by the caller before it calls [run]. A suspend call that waits on another
 * dispatcher has no business holding the database's write lock. A network call does not belong in
 * a block at all.
 *
 * [requestSync] runs after the commit, only if the block queued an op: the worker must find it,
 * and a refused or failed block has nothing to push.
 */
internal class LocalMutation(
    private val store: OpStore,
    private val scope: CoroutineScope,
    private val requestSync: () -> Unit,
    /** The household stamped on every op; a suspend read (DataStore), done before the transaction. */
    private val foyerId: suspend () -> String? = { null },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** [run] with the household read from [foyerId]. */
    suspend fun <T> run(block: suspend MutationScope.() -> T): T = run(foyerId(), block)

    /**
     * Runs [block] atomically. Its result is returned; an exception thrown by it rolls everything
     * back, requests no sync and reaches the caller.
     */
    suspend fun <T> run(foyerId: String?, block: suspend MutationScope.() -> T): T =
        scope.async {
            val mutation = MutationScope(store, foyerId, newId, nowMillis)
            store.atomically { mutation.block() }.also {
                if (mutation.enqueued > 0) requestSync() // after the commit
            }
        }.await()
}

/** The Hilt-facing entry point of [LocalMutation]; the mutation writers of each family share it. */
@Singleton
class OfflineWriter @Inject constructor(
    db: AppDatabase,
    pendingDao: PendingOperationDao,
    tokenManager: TokenManager,
    syncTrigger: SyncTrigger,
    @ApplicationScope scope: CoroutineScope,
) {
    internal val mutation = LocalMutation(
        RoomOpStore(db, pendingDao), scope, syncTrigger::requestSync,
        foyerId = { tokenManager.foyerIdFlow.first() },
    )
}
