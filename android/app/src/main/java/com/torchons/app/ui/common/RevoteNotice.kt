package com.torchons.app.ui.common

import android.content.res.Resources
import com.torchons.app.R
import com.torchons.app.core.revote.RevoteAnswer
import com.torchons.app.util.DurationFormat

/** What the info dialog tells the requester about a re-vote request, under the button. */
sealed interface RevoteNotice {
    /** The member the request was made to. */
    val userId: String
    val userName: String

    /** The request is pending. [notified]: the server says FCM accepted the push for a device. */
    data class Waiting(
        override val userId: String,
        override val userName: String,
        val kind: String,
        val notified: Boolean,
    ) : RevoteNotice

    data class Failed(override val userId: String, override val userName: String) : RevoteNotice

    data class Answered(
        override val userId: String,
        override val userName: String,
        val answer: RevoteAnswer,
    ) : RevoteNotice
}

/** The common day count of a wire frequency vote ("7,7,...,7"), null when it varies or is empty. */
internal fun wireUniformDays(wire: String): Int? {
    val days = wire.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
    return days.singleOrNull()
}

/** A wire vote for a sentence, null when there is no vote. [bare]: "7 j" rather than "tous les 7 j". */
internal fun wireVoteText(res: Resources, kind: String, wire: String, bare: Boolean): String? {
    if (wire.isBlank()) return null
    if (kind == "effort") return wire.toIntOrNull()?.let { effortPointsToTime(it) }
    val days = wireUniformDays(wire) ?: return res.getString(R.string.frequency_info_variable_label)
    val human = DurationFormat.formatDaysHuman(res, days)
    return if (bare) human else res.getString(R.string.frequency_info_interval_format, human)
}

/** True when the answer left the vote as it was (both votes known and equal). */
internal fun RevoteAnswer.isConfirmation(): Boolean =
    oldValue.isNotBlank() && oldValue == newValue

/** The status line for a notice, in the app's language. */
fun revoteNoticeText(res: Resources, notice: RevoteNotice): String = when (notice) {
    is RevoteNotice.Waiting -> res.getString(
        if (notice.notified) R.string.revote_status_notified else R.string.revote_status_not_notified,
        notice.userName,
    )
    is RevoteNotice.Failed -> res.getString(R.string.revote_status_failed)
    is RevoteNotice.Answered -> answeredText(res, notice)
}

private fun answeredText(res: Resources, notice: RevoteNotice.Answered): String {
    val a = notice.answer
    val name = a.responderName.ifBlank { notice.userName }
    if (a.resolution == "dismissed") return res.getString(R.string.revote_answer_dismissed, name)
    val kindLabel = res.getString(
        if (a.kind == "effort") R.string.revote_kind_effort else R.string.revote_kind_frequency
    )
    val new = wireVoteText(res, a.kind, a.newValue, bare = a.oldValue != a.newValue)
        ?: return res.getString(R.string.revote_answer_nothing, name)
    if (a.isConfirmation()) return res.getString(R.string.revote_answer_confirmed, name, new)
    val old = wireVoteText(res, a.kind, a.oldValue, bare = true)
        ?: return res.getString(R.string.revote_answer_first, name, kindLabel, new)
    return res.getString(R.string.revote_answer_changed, name, kindLabel, old, new)
}
