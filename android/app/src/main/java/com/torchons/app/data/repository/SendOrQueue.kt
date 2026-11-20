package com.torchons.app.data.repository

import com.torchons.app.data.api.ApiHttpException
import kotlinx.coroutines.CancellationException
import retrofit2.Response

/**
 * A 4xx the server will answer the same way every time: payload refused, task gone, vote locked.
 * Not 408 (timeout) and 429 (rate limit): those pass, so they are queued like a network error.
 */
internal fun isPermanentRefusal(httpCode: Int): Boolean =
    httpCode in 400..499 && httpCode != 408 && httpCode != 429

/**
 * Sends a "set my value" write that has a REST route and no `/sync` table (frequency, effort and
 * affinity votes, the answer to a prompt), or queues it. The answer is what the caller shows:
 *  - success, or queued (offline, 5xx, timeout, rate limit, or the task is not on the server yet):
 *    `Result.success`;
 *  - a permanent refusal (D4, 2026-11-20 audit): `Result.failure(ApiHttpException)`. It used to be
 *    queued and reported as a success, then dropped silently by the worker (a vote on a task
 *    created offline got 404 and vanished).
 *
 * Only for a write that is IDEMPOTENT on the server (it sets the caller's own value, or resolves
 * a row once): a queued op is replayed after a send that timed out may have gone through.
 *
 * A task with an op still queued ([taskHasQueuedOp]: created offline, edited, deleted) is not
 * sent: the server may not know it yet and would answer 404. The worker pushes the write after
 * the /sync batch that carries the task.
 */
internal suspend fun sendOrQueue(
    taskHasQueuedOp: suspend () -> Boolean,
    send: suspend () -> Response<*>,
    queue: suspend () -> Unit,
    onSent: suspend () -> Unit,
): Result<Unit> {
    if (taskHasQueuedOp()) {
        queue()
        return Result.success(Unit)
    }
    val response = try {
        send()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        queue()
        return Result.success(Unit)
    }
    return when {
        response.isSuccessful -> {
            onSent()
            Result.success(Unit)
        }
        isPermanentRefusal(response.code()) -> Result.failure(ApiHttpException.from(response))
        else -> {
            queue()
            Result.success(Unit)
        }
    }
}
