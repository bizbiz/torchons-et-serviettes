package com.torchons.app.data.repository

import com.torchons.app.core.sync.AFFINITY_VOTES_TABLE
import com.torchons.app.core.sync.EFFORT_VOTES_TABLE
import com.torchons.app.core.sync.PROMPT_RESOLUTIONS_TABLE
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.dao.PendingOperationDao
import com.torchons.app.data.local.dao.TaskDao
import com.torchons.app.data.model.AffinityRequest
import com.torchons.app.data.model.PromptResolveRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Queue-only mutations of the REST ops that have no local row (the value lives on the server):
 * one transaction, in the application scope, sync requested after the commit ([LocalMutation]).
 */
internal class VoteLedger(private val mutation: LocalMutation) {
    suspend fun enqueueEffort(taskId: String, points: Int) {
        mutation.run { enqueue(EFFORT_VOTES_TABLE, "VOTE", taskId, mapOf("points" to points)) }
    }

    suspend fun enqueueAffinity(taskId: String, score: Int) {
        mutation.run { enqueue(AFFINITY_VOTES_TABLE, "VOTE", taskId, mapOf("score" to score)) }
    }

    suspend fun enqueueResolution(promptId: String, request: PromptResolveRequest) {
        val payload = mapOf("resolution" to request.resolution, "payload" to request.payload)
        mutation.run { enqueue(PROMPT_RESOLUTIONS_TABLE, "RESOLVE", promptId, payload) }
    }
}

/**
 * Effort votes, affinity votes and the answer to a prompt, online-first with the offline queue
 * (D3 and D6, 2026-11-20 audit). They were REST calls whose failure was swallowed: offline the
 * dialog closed as if the vote had gone through, and the answered prompt came back.
 *
 * All three are idempotent on the server (`PUT /tasks/{id}/effort` and `/affinity` upsert the
 * caller's own row; `POST /prompts/{id}/resolve` says `already_resolved` the second time), so a
 * failed send is queued and replayed by the SyncWorker ([sendOrQueue] has the rules): the caller
 * sees a failure only for a permanent refusal, which no retry can fix.
 */
@Singleton
class VoteRepository @Inject constructor(
    private val apiService: ApiService,
    private val taskDao: TaskDao,
    private val pendingDao: PendingOperationDao,
    offline: OfflineWriter,
) {
    private val ledger = VoteLedger(offline.mutation)

    suspend fun voteEffort(taskId: String, points: Int): Result<Unit> = sendOrQueue(
        taskHasQueuedOp = { taskId in taskDao.pendingRowIds() },
        send = { apiService.setEffort(taskId, mapOf("points" to points)) },
        queue = { ledger.enqueueEffort(taskId, points) },
        onSent = {},
    )

    suspend fun voteAffinity(taskId: String, score: Int): Result<Unit> = sendOrQueue(
        taskHasQueuedOp = { taskId in taskDao.pendingRowIds() },
        send = { apiService.setAffinity(taskId, AffinityRequest(score)) },
        queue = { ledger.enqueueAffinity(taskId, score) },
        onSent = {},
    )

    /**
     * Answers a prompt, or queues the answer. A failure here is a permanent refusal (the prompt is
     * gone): there is nothing to retry and nothing the user can do, so callers move on.
     */
    suspend fun resolvePrompt(promptId: String, request: PromptResolveRequest): Result<Unit> = sendOrQueue(
        taskHasQueuedOp = { false },
        send = { apiService.resolvePrompt(promptId, request) },
        queue = { ledger.enqueueResolution(promptId, request) },
        onSent = {},
    )

    /** My effort and affinity votes on [taskId] still waiting in the queue, kept up to date. */
    fun observeQueuedVotes(taskId: String): Flow<QueuedVotes> =
        pendingDao.observeForRow(QUEUED_VOTE_TABLES, taskId).map(::queuedVotesOf)

    /** Prompts whose answer is queued: the server still lists them, the screen must not. */
    suspend fun promptsAnsweredOffline(): Set<String> = pendingDao.rowIdsOf(PROMPT_RESOLUTIONS_TABLE).toSet()
}
