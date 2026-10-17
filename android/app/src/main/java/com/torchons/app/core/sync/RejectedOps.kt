package com.torchons.app.core.sync

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * An op the server refused for good (`reason = "rejected"` in /sync): it was
 * dropped from the queue, and the sync dialog says so until the user
 * acknowledges it. Until 2026-10-17 such an op was replayed forever and only
 * a manual discard got it out.
 */
data class RejectedSyncOp(
    val kind: PendingOpKind,
    /** Title of the task the op was about; null when the cache no longer had it. */
    val taskTitle: String?,
    /** Readable refusal, as the server worded it. */
    val reason: String?,
    val rejectedAtMs: Long,
)

/** Bound on the notices kept: the oldest go first. */
internal const val MAX_REJECTED_NOTICES = 20

private val gson = Gson()
private val listType = object : TypeToken<List<RejectedSyncOp>>() {}.type

/** Reads the stored notices; anything unreadable counts as none. */
internal fun decodeRejectedOps(json: String?): List<RejectedSyncOp> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching { gson.fromJson<List<RejectedSyncOp>>(json, listType) }.getOrNull()
        // Gson ignores Kotlin nullability: a stored enum name no longer known becomes null.
        ?.filter { @Suppress("SENSELESS_COMPARISON") (it.kind != null) }
        .orEmpty()
}

/** The stored notices with [added] appended, bounded to [MAX_REJECTED_NOTICES]. */
internal fun appendRejectedOps(json: String?, added: List<RejectedSyncOp>): String =
    gson.toJson((decodeRejectedOps(json) + added).takeLast(MAX_REJECTED_NOTICES))
