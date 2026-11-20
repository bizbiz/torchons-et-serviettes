package com.torchons.app.core.sync

import com.google.gson.Gson
import com.torchons.app.data.local.entities.PendingOperationEntity

/** What a queued op does, as the sync dialog names it. */
enum class PendingOpKind {
    TASK_CREATE, TASK_EDIT, TASK_DELETE,
    COMPLETION_ADD, COMPLETION_EDIT, COMPLETION_DELETE,
    FREQUENCY_VOTE, EFFORT_VOTE, AFFINITY_VOTE, PROMPT_ANSWER,
    DELEGATION_ADD, DELEGATION_CANCEL, SETTING_CHANGE, OTHER,
}

/** One line of the sync dialog: a queued op, what it is about, why it waits. */
data class PendingOpRow(
    val clientOpId: String,
    val kind: PendingOpKind,
    /** Title of the task the op is about; null when the cache no longer has it. */
    val taskTitle: String?,
    /** Member a completion is credited to; null for other ops or unknown members. */
    val memberName: String?,
    /** Key of the member setting a `user_settings` op changes (`notif_hour`...); null for other ops. */
    val settingKey: String? = null,
    val queuedAtMs: Long,
    val attemptCount: Int,
    /** Readable cause of the last failure, null when the op has not failed yet. */
    val error: String?,
)

internal fun pendingOpKind(table: String, operation: String): PendingOpKind = when (table) {
    "tasks" -> when (operation) {
        "INSERT" -> PendingOpKind.TASK_CREATE
        "UPDATE" -> PendingOpKind.TASK_EDIT
        "DELETE" -> PendingOpKind.TASK_DELETE
        else -> PendingOpKind.OTHER
    }
    "task_completions" -> when (operation) {
        "INSERT" -> PendingOpKind.COMPLETION_ADD
        "UPDATE" -> PendingOpKind.COMPLETION_EDIT
        "DELETE" -> PendingOpKind.COMPLETION_DELETE
        else -> PendingOpKind.OTHER
    }
    FREQUENCY_VOTES_TABLE -> PendingOpKind.FREQUENCY_VOTE
    EFFORT_VOTES_TABLE -> PendingOpKind.EFFORT_VOTE
    AFFINITY_VOTES_TABLE -> PendingOpKind.AFFINITY_VOTE
    PROMPT_RESOLUTIONS_TABLE -> PendingOpKind.PROMPT_ANSWER
    "user_settings" -> PendingOpKind.SETTING_CHANGE
    "task_delegations" -> if (operation == "DELETE") PendingOpKind.DELEGATION_CANCEL else PendingOpKind.DELEGATION_ADD
    else -> PendingOpKind.OTHER
}

private val gson = Gson()

private fun payloadOf(op: PendingOperationEntity): Map<*, *>? =
    runCatching { gson.fromJson(op.payloadJson, Map::class.java) }.getOrNull()

/** Task an op is about: its row for task and vote ops, `task_id` in a completion payload. */
internal fun taskIdOf(op: PendingOperationEntity): String? = when (op.tableName) {
    "tasks", FREQUENCY_VOTES_TABLE, EFFORT_VOTES_TABLE, AFFINITY_VOTES_TABLE -> op.rowId
    "task_completions", "task_delegations" -> payloadOf(op)?.get("task_id") as? String
    else -> null
}

/** The setting a queued `user_settings` op changes: its payload is `{key: value}`, one key. */
internal fun settingKeyOf(op: PendingOperationEntity): String? =
    if (op.tableName == "user_settings") payloadOf(op)?.keys?.singleOrNull() as? String else null

/**
 * The server sends a conflict `detail` as `<ExceptionClass>: <status>: <message>`
 * for its own validation errors (sync.py). The message is what the user can
 * act on; the prefix is noise. Anything else is shown as is.
 */
internal fun readableSyncError(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return Regex("""^\w+: \d{3}: """).replaceFirst(raw, "").ifBlank { raw }
}

internal fun describePendingOps(
    ops: List<PendingOperationEntity>,
    taskTitles: Map<String, String>,
    memberNames: Map<String, String>,
): List<PendingOpRow> = ops.map { op ->
    val completedBy = if (op.tableName == "task_completions") payloadOf(op)?.get("completed_by") as? String else null
    PendingOpRow(
        clientOpId = op.clientOpId,
        kind = pendingOpKind(op.tableName, op.operation),
        taskTitle = taskIdOf(op)?.let { taskTitles[it] },
        memberName = completedBy?.let { memberNames[it] },
        settingKey = settingKeyOf(op),
        queuedAtMs = op.createdAt,
        attemptCount = op.attemptCount,
        error = readableSyncError(op.lastError),
    )
}

/**
 * Ops to drop when the user discards [target] from the queue. Dropping an
 * INSERT also drops what was queued after it on the same row (an edit or a
 * delete of a row the server will never have) and, for a task, the
 * completions queued on it. Dropping an UPDATE or a DELETE drops it alone:
 * the server still has the row, the next ops on it stay valid.
 */
internal fun opsToDiscard(
    queue: List<PendingOperationEntity>,
    target: PendingOperationEntity,
): List<PendingOperationEntity> {
    if (target.operation != "INSERT") return listOf(target)
    val dependents = queue.filter { op ->
        op.clientOpId != target.clientOpId && op.createdAt >= target.createdAt && (
            (op.tableName == target.tableName && op.rowId == target.rowId) ||
                (target.tableName == "tasks" && taskIdOf(op) == target.rowId)
            )
    }
    return listOf(target) + dependents
}
