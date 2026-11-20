package com.torchons.app.ui.taskdetail

import com.torchons.app.data.model.EffortVotesState
import com.torchons.app.data.repository.QueuedVotes
import kotlin.math.roundToInt

/** Minutes of effort per point of an effort vote (the server stores points, shows minutes). */
private const val MINUTES_PER_EFFORT_POINT = 5

/** What the server last said about the votes of a task: the screen's "Effort & Affinity" block. */
internal data class ServerVotes(
    val ratings: List<TaskRating> = emptyList(),
    val avgEffortTimeMin: Int? = null,
    val scorePoints: Int? = null,
    val effortVotes: EffortVotesState? = null,
)

/**
 * The votes to show: the server's last answer with my own queued (not yet sent) effort and
 * affinity votes laid over it. Offline, the section and the "you have not voted" banner must
 * reflect what I just saved, not wait for the sync (docs/cache-conventions.md, rule 1). Once the
 * worker has sent the votes and the screen has refetched, [queued] is empty and the server's
 * answer is what is shown (online = authoritative).
 *
 * My row is added when the server list has none (never fetched, or fetch failed offline). The
 * average is recomputed from the rows only when a queued effort moved it.
 */
internal fun overlayQueuedVotes(
    server: ServerVotes,
    queued: QueuedVotes,
    taskId: String,
    me: String?,
    myName: String?,
): ServerVotes {
    if (queued.isEmpty || me == null) return server
    val mine = server.ratings.firstOrNull { it.userId == me }
    val myRow = TaskRating(
        userId = me,
        displayName = mine?.displayName ?: myName ?: "?",
        affinity = queued.affinity ?: mine?.affinity,
        effortTimeMin = queued.effortPoints?.let { it * MINUTES_PER_EFFORT_POINT } ?: mine?.effortTimeMin,
    )
    val ratings = if (mine != null) {
        server.ratings.map { if (it.userId == me) myRow else it }
    } else {
        server.ratings + myRow
    }
    val points = queued.effortPoints ?: return server.copy(ratings = ratings)
    val averagePoints = ratings.mapNotNull { it.effortTimeMin }.average() / MINUTES_PER_EFFORT_POINT
    val previous = server.effortVotes
    val effortVotes = previous?.copy(
        myPoints = points,
        votesCount = if (previous.myPoints == null) previous.votesCount + 1 else previous.votesCount,
    ) ?: EffortVotesState(taskId = taskId, myPoints = points, votesCount = 1, canVote = true)
    return ServerVotes(
        ratings = ratings,
        avgEffortTimeMin = (averagePoints * MINUTES_PER_EFFORT_POINT).roundToInt(),
        scorePoints = averagePoints.roundToInt(),
        effortVotes = effortVotes,
    )
}
