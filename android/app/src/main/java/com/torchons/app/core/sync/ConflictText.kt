package com.torchons.app.core.sync

import android.content.Context
import androidx.annotation.StringRes
import com.torchons.app.R

/**
 * The translated sentence for a /sync conflict `reason` (D8, 2026-11-20 audit: the dialog showed
 * `stale_update`, `unique_violation`, `ValueError: ...` as the server wrote them). Unknown reasons
 * (a server newer than the app) fall back to a generic sentence, never to the raw code.
 */
@StringRes
internal fun conflictTextRes(reason: String): Int = when (reason) {
    "stale_update" -> R.string.sync_reason_stale_update
    "unique_violation" -> R.string.sync_reason_unique_violation
    "deleted" -> R.string.sync_reason_deleted
    "not_found" -> R.string.sync_reason_not_found
    "op_error" -> R.string.sync_reason_op_error
    "rejected" -> R.string.sync_reason_rejected
    "out_of_scope" -> R.string.sync_reason_out_of_scope
    else -> R.string.sync_reason_unknown
}

private val SERVER_WORDED = Regex("""^\w+: \d{3}: (.+)""", RegexOption.DOT_MATCHES_ALL)

/**
 * The message of a server `HTTPException` detail (`<Class>: <status>: <message>`, worded for the
 * user by the server), or null for anything else: `ValueError: ...` and bare class names such as
 * `OperationalError` are developer text and never shown.
 */
internal fun serverWordedMessage(detail: String?): String? =
    detail?.let { SERVER_WORDED.find(it)?.groupValues?.get(1)?.trim() }?.takeIf { it.isNotEmpty() }

/** The sentence stored under an op and shown in the sync dialog. */
internal fun conflictText(context: Context, reason: String, detail: String?): String =
    (if (reason == "rejected" || reason == "op_error") serverWordedMessage(detail) else null)
        ?: context.getString(conflictTextRes(reason))
