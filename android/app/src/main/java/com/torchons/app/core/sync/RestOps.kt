package com.torchons.app.core.sync

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.entities.PendingOperationEntity
import com.torchons.app.data.model.AffinityRequest
import com.torchons.app.data.model.FrequencyRequest
import com.torchons.app.data.model.PromptResolveRequest
import com.torchons.app.data.repository.isPermanentRefusal
import kotlinx.coroutines.CancellationException
import retrofit2.Response

/*
 * Queued ops that are NOT part of the /sync contract: each one is an idempotent "set my value" (or
 * "resolve once") write the server exposes as a REST route, and the worker replays it over REST
 * AFTER the /sync batches (a vote on a task created offline needs the task to exist, D4). Verified
 * on the server (backend/app/api/gamification.py, prompts.py, 2026-11-20): `PUT /tasks/{id}/frequency`,
 * `/effort` and `/affinity` upsert the caller's own row, so a replay changes nothing; `POST
 * /prompts/{id}/resolve` answers `already_resolved` once it is resolved.
 */

/** Queue table names of the REST ops; they only exist in `pending_operations`. */
internal const val FREQUENCY_VOTES_TABLE = "task_frequency_votes"
internal const val EFFORT_VOTES_TABLE = "task_effort_votes"
internal const val AFFINITY_VOTES_TABLE = "task_affinity_votes"
internal const val PROMPT_RESOLUTIONS_TABLE = "prompt_resolutions"

internal val REST_OP_TABLES = setOf(
    FREQUENCY_VOTES_TABLE, EFFORT_VOTES_TABLE, AFFINITY_VOTES_TABLE, PROMPT_RESOLUTIONS_TABLE,
)

/** What happens to a queued REST op once the worker has tried to send it. */
internal interface VotePushOutcomes {
    /** The server took it: out of the queue. */
    suspend fun sent(op: PendingOperationEntity)

    /** Network error, 5xx, 408, 429: stays queued, [reason] is shown under it in the sync dialog. */
    suspend fun retry(op: PendingOperationEntity, reason: String)

    /** Refused for good (4xx): out of the queue, and told to the user (a notice, never silent). */
    suspend fun refused(op: PendingOperationEntity, reason: String)
}

private val gson = Gson()

/** Whole numbers stay whole: `7` is read back as 7, not 7.0 (the server's per-month maps are ints). */
private val exactGson: Gson = GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()

/**
 * One sync pass: [sendOps] (the /sync batches), then [pushVotes] ONLY if every batch went. A
 * vote whose task is created by an op still stuck in the queue would get a 404 now (D4). True when
 * every batch went. Kept out of the worker so the order is testable without Hilt.
 */
internal suspend fun syncThenVotes(sendOps: suspend () -> Boolean, pushVotes: suspend () -> Unit): Boolean {
    val allSent = sendOps()
    if (allSent) pushVotes()
    return allSent
}

/**
 * The payload of a queued op as a map whose whole numbers stay whole (`9`, not `9.0`). Gson reads
 * every number of an untyped map as a Double, and `/sync` writes `user_settings` with a raw
 * `setattr`, bypassing Pydantic's int coercion: a float into an integer column fails there.
 */
@Suppress("UNCHECKED_CAST")
internal fun opPayload(op: PendingOperationEntity): Map<String, Any?>? =
    runCatching { exactGson.fromJson(op.payloadJson, Map::class.java) as? Map<String, Any?> }.getOrNull()

/** The request a queued frequency vote carries, or null when its payload is unreadable or empty. */
internal fun frequencyRequestOf(op: PendingOperationEntity): FrequencyRequest? {
    val payload = opPayload(op)
    val days = (payload?.get("every_n_days") as? Number)?.toInt()
    @Suppress("UNCHECKED_CAST")
    val daysPerMonth = (payload?.get("days_per_month") as? Map<String, Any?>)
        ?.mapNotNull { (k, v) -> (v as? Number)?.toInt()?.let { k to it } }
        ?.toMap()
    return when {
        daysPerMonth != null -> FrequencyRequest(daysPerMonth = daysPerMonth)
        days != null -> FrequencyRequest(everyNDays = days)
        else -> null
    }
}

/** The answer a queued prompt resolution carries (`resolution` + optional `payload`), or null. */
internal fun promptResolutionOf(op: PendingOperationEntity): PromptResolveRequest? {
    val map = opPayload(op) ?: return null
    val resolution = map["resolution"] as? String ?: return null
    @Suppress("UNCHECKED_CAST")
    return PromptResolveRequest(resolution, map["payload"] as? Map<String, Any?>)
}

private fun intField(op: PendingOperationEntity, key: String): Int? =
    (opPayload(op)?.get(key) as? Number)?.toInt()

/**
 * The request of a queued REST op, sent; null when its payload is unreadable (nothing can ever be
 * sent: the caller drops it). The only place that maps a queue table to a route.
 */
internal suspend fun sendRestOp(api: ApiService, op: PendingOperationEntity): Response<*>? = when (op.tableName) {
    FREQUENCY_VOTES_TABLE -> frequencyRequestOf(op)?.let { api.setFrequency(op.rowId, it) }
    EFFORT_VOTES_TABLE -> intField(op, "points")?.let { api.setEffort(op.rowId, mapOf("points" to it)) }
    AFFINITY_VOTES_TABLE -> intField(op, "score")?.let { api.setAffinity(op.rowId, AffinityRequest(it)) }
    PROMPT_RESOLUTIONS_TABLE -> promptResolutionOf(op)?.let { api.resolvePrompt(op.rowId, it) }
    else -> null
}

/**
 * Pushes the queued REST ops (votes, prompt answers) in queue order. The worker runs this AFTER the
 * /sync batch (D4, 2026-11-20 audit): a vote on a task created offline used to go first, got a 404
 * and was dropped without a word. Now the task exists on the server by then, and a 4xx that still
 * comes back is [VotePushOutcomes.refused].
 *
 * [send] returns null for an unreadable payload: dropped (nothing can ever be sent). Cancellation
 * propagates: a pass stopped by the system is not a failure of the op.
 */
internal suspend fun pushRestOps(
    ops: List<PendingOperationEntity>,
    send: suspend (PendingOperationEntity) -> Response<*>?,
    outcomes: VotePushOutcomes,
    reasonOf: (Response<*>) -> String,
    reasonOfError: (Throwable) -> String,
) {
    for (op in ops) {
        try {
            val resp = send(op)
            when {
                resp == null -> outcomes.sent(op)
                resp.isSuccessful -> outcomes.sent(op)
                isPermanentRefusal(resp.code()) -> outcomes.refused(op, reasonOf(resp))
                else -> outcomes.retry(op, reasonOf(resp))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            outcomes.retry(op, reasonOfError(e))
        }
    }
}
