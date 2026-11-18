package com.torchons.app.core.today

import com.torchons.app.data.model.Delegation

/*
 * « À faire aujourd'hui » rules the app applies without network (docs/plans/a-faire-aujourdhui.md).
 * The server enforces the same ones (backend/app/services/delegations.py).
 */

/**
 * Members a task may be handed to: those who SEE it, me excepted. A task with assignees is seen by
 * its assignees only (backend/app/utils/scoping.py); one without any is seen by every member.
 * Empty = no « ! » at all (a solo task of mine).
 */
fun handOffRecipients(assignedToIds: List<String>, memberIds: List<String>, me: String): List<String> {
    val seeing = if (assignedToIds.isEmpty()) memberIds else memberIds.filter { it in assignedToIds }
    return seeing.filter { it != me }
}

/** My red stack: tasks someone else handed to me, oldest first. */
fun todayStackFor(delegations: List<Delegation>, me: String): List<Delegation> =
    delegations.filter { me in it.recipientIds && it.authorId != me }.sortedBy { it.createdAt }

/**
 * Tasks out of my suggestions: those handed to me (they are in my red stack, and in no other) and
 * those I handed (I chose not to do them myself).
 */
fun excludedFromSuggestions(delegations: List<Delegation>, me: String): Set<String> =
    delegations.filter { it.authorId == me || me in it.recipientIds }.map { it.taskId }.toSet()

/** What a tap on the « ! » of a task does. */
sealed interface TodayTap {
    /** Hand it at once: one possible recipient. */
    data class HandTo(val recipientIds: List<String>) : TodayTap
    /** Ask whom, every candidate checked. */
    data class Pick(val candidateIds: List<String>) : TodayTap
    /** Its author takes it back. */
    data class Cancel(val delegation: Delegation) : TodayTap
    /** Someone else's: say who handed it to whom, change nothing. */
    data class Info(val delegation: Delegation) : TodayTap
}

fun todayTap(delegation: Delegation?, candidates: List<String>, me: String): TodayTap? = when {
    delegation != null && delegation.authorId == me -> TodayTap.Cancel(delegation)
    delegation != null -> TodayTap.Info(delegation)
    candidates.isEmpty() -> null
    candidates.size == 1 -> TodayTap.HandTo(candidates)
    else -> TodayTap.Pick(candidates)
}

/** « Alice », « Alice & Bob », « Alice, Bob & Carol ». */
fun joinNames(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    else -> names.dropLast(1).joinToString(", ") + " & " + names.last()
}
