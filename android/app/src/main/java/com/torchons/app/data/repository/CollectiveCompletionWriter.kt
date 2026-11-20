package com.torchons.app.data.repository

import androidx.room.withTransaction
import com.torchons.app.data.api.ApiHttpException
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.AppDatabase
import com.torchons.app.data.local.dao.CompletionDao
import com.torchons.app.data.local.entities.CompletionEntity
import com.torchons.app.data.local.entities.toEntity
import com.torchons.app.data.model.CollectiveCompleteRequest
import com.torchons.app.data.model.CollectiveCompletionResponse
import com.torchons.app.data.model.Completion
import com.torchons.app.di.ApplicationScope
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import retrofit2.Response

class CollectiveCompletionException(httpCode: Int, errorBody: String, errorId: String? = null) :
    ApiHttpException(httpCode, errorBody, errorId)

/** The dedicated « complete together » endpoint: the Retrofit call in the app, a fake in JVM tests. */
internal typealias CollectiveSubmit =
    suspend (String, CollectiveCompleteRequest) -> Response<CollectiveCompletionResponse>

/** What [CollectiveCompletionLedger] needs from the database; Room in the app, a fake in JVM tests. */
internal interface CollectiveStore {
    /** Runs [block] as ONE transaction: all of its writes land, or none. */
    suspend fun <T> atomically(block: suspend () -> T): T

    suspend fun insert(rows: List<CompletionEntity>)
    suspend fun delete(ids: List<String>)
}

/**
 * « Fait ensemble »: optimistic with reconciliation, ONLINE by design.
 *
 * 1. Inserts N local rows at once (client collective id, estimated points): the history shows
 *    them without waiting for the network.
 * 2. Calls the dedicated endpoint. It cannot go through `/sync`: the server splits the points
 *    (ceil) and checks duplicates atomically, N INSERTs would each price the full points.
 * 3. Success: the optimistic rows are replaced by the server's (real ids, points, reason).
 * 4. Failure (409 duplicate, network): the optimistic rows are removed. Nothing was persisted,
 *    so the UI must not lie.
 *
 * The whole sequence runs in [scope], the application's, and the caller only waits for the
 * result: leaving the screen cancels the wait, never the sequence. Cancelled between step 1 and
 * step 3 or 4 it would leave rows on the phone that the server never got (or one it did, shown
 * twice with the optimistic ones). Same reasoning as [CompletionLedger]; the call is started at
 * tap time by all three screens.
 *
 * Step 3 is a [CompletionRefreshGuard.settling]: a refresh that started before the answer and is
 * applied after it would delete the server rows just written (see the guard's KDoc).
 */
internal class CollectiveCompletionLedger(
    private val store: CollectiveStore,
    private val submit: CollectiveSubmit,
    private val scope: CoroutineScope,
    private val guard: CompletionRefreshGuard,
    private val now: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun complete(
        taskId: String,
        participantUserIds: List<String>,
        completedAt: String?,
        notes: String?,
    ): Result<CollectiveCompletionResponse> {
        if (participantUserIds.isEmpty()) {
            return Result.failure(IllegalArgumentException("Aucun participant"))
        }
        val clientCollectiveId = newId()
        val at = completedAt ?: now().toString()
        val optimisticRows = participantUserIds.map { uid ->
            Completion(
                id = newId(), taskId = taskId, completedBy = uid, completedAt = at,
                pointsEarned = 1, notes = notes, collectiveId = clientCollectiveId,
            )
        }
        val request = CollectiveCompleteRequest(participantUserIds, completedAt, notes)

        // async + await, not withContext: cancelling the caller stops its wait, never the write.
        return scope.async { run(taskId, request, optimisticRows) }.await()
    }

    private suspend fun run(
        taskId: String,
        request: CollectiveCompleteRequest,
        optimisticRows: List<Completion>,
    ): Result<CollectiveCompletionResponse> {
        val optimisticIds = optimisticRows.map { it.id }
        store.insert(optimisticRows.map { it.toEntity() })
        return try {
            val resp = submit(taskId, request)
            val body = resp.body()
            when {
                !resp.isSuccessful -> {
                    store.delete(optimisticIds)
                    val http = ApiHttpException.from(resp)
                    Result.failure(CollectiveCompletionException(http.httpCode, http.errorBody, http.errorId))
                }
                body == null -> {
                    store.delete(optimisticIds)
                    Result.failure(IllegalStateException("Reponse vide"))
                }
                else -> {
                    // One transaction: observers never see the rows gone and not yet replaced.
                    guard.settling {
                        store.atomically {
                            store.delete(optimisticIds)
                            store.insert(body.completions.map { it.toEntity() })
                        }
                    }
                    Result.success(body)
                }
            }
        } catch (e: Exception) {
            store.delete(optimisticIds)
            Result.failure(e)
        }
    }
}

/** Room-backed [CollectiveStore]. */
internal class RoomCollectiveStore(
    private val db: AppDatabase,
    private val completionDao: CompletionDao,
) : CollectiveStore {
    override suspend fun <T> atomically(block: suspend () -> T): T = db.withTransaction { block() }

    override suspend fun insert(rows: List<CompletionEntity>) = completionDao.upsertAll(rows)

    override suspend fun delete(ids: List<String>) = ids.forEach { completionDao.deleteById(it) }
}

/** The Hilt-facing entry point of [CollectiveCompletionLedger]; [TaskRepository] delegates here. */
@Singleton
class CollectiveCompletionWriter @Inject constructor(
    db: AppDatabase,
    completionDao: CompletionDao,
    apiService: ApiService,
    guard: CompletionRefreshGuard,
    @ApplicationScope scope: CoroutineScope,
) {
    private val ledger = CollectiveCompletionLedger(
        RoomCollectiveStore(db, completionDao), apiService::completeTaskCollective, scope, guard,
    )

    suspend fun complete(
        taskId: String,
        participantUserIds: List<String>,
        completedAt: String? = null,
        notes: String? = null,
    ): Result<CollectiveCompletionResponse> = ledger.complete(taskId, participantUserIds, completedAt, notes)
}
