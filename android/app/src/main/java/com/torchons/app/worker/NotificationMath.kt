package com.torchons.app.worker

import com.torchons.app.util.TaskUrgencyCalculator.State
import kotlin.math.floor

/**
 * Pure computations behind the two on-phone reminders (daily and overdue).
 * No Android, no clock: the workers feed them states already computed by
 * TaskUrgencyCalculator, so the numbers match what the Tasks tab shows.
 */

/** A task's urgency state on two consecutive days, for the "turned red today" rule. */
data class TaskStatePair(val title: String, val today: State, val yesterday: State)

/**
 * round_half_up(redCount * ratio). A tiny epsilon absorbs binary noise such as
 * 5 * 0.3 = 1.5000000000000002 vs 3 * 0.5 = 1.5, so every exact half rounds up.
 * A negative or non-finite ratio counts as 0.
 */
fun dailyReminderCount(redCount: Int, ratio: Double): Int {
    if (redCount <= 0 || !ratio.isFinite() || ratio <= 0.0) return 0
    return floor(redCount * ratio + 0.5 + 1e-9).toInt()
}

/** Share used when no target is known for me: an equal split among the active members. */
fun defaultTargetRatio(activeMembers: Int): Double = 1.0 / activeMembers.coerceAtLeast(1)

/** Titles of tasks RED today and not RED yesterday, in input order. */
fun newlyRedTitles(states: List<TaskStatePair>): List<String> =
    states.filter { it.today == State.RED && it.yesterday != State.RED }.map { it.title }

/**
 * Overdue notification body: titles joined by ", ", at most [maxTitles] of them,
 * then " +N" for the rest. A title alone longer than [maxTitleChars] is cut with "…".
 */
fun overdueBody(titles: List<String>, maxTitles: Int = 5, maxTitleChars: Int = 40): String {
    val shown = titles.take(maxTitles).map {
        if (it.length > maxTitleChars) it.take(maxTitleChars - 1).trimEnd() + "…" else it
    }
    val rest = titles.size - shown.size
    val joined = shown.joinToString(", ")
    return if (rest > 0) "$joined +$rest" else joined
}
