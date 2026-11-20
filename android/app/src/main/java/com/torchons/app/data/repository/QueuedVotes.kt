package com.torchons.app.data.repository

import com.torchons.app.core.sync.AFFINITY_VOTES_TABLE
import com.torchons.app.core.sync.EFFORT_VOTES_TABLE
import com.torchons.app.core.sync.opPayload
import com.torchons.app.data.local.entities.PendingOperationEntity

/** The queue tables a task's detail screen reads to show a vote that was saved offline. */
internal val QUEUED_VOTE_TABLES = listOf(AFFINITY_VOTES_TABLE, EFFORT_VOTES_TABLE)

/**
 * The caller's own effort and affinity votes on one task that are still waiting in the offline
 * queue. The server does not know them yet, so the screen shows them over what the server last
 * said (cache-conventions: derived state is right without the network). Null = nothing queued.
 */
data class QueuedVotes(val affinity: Int? = null, val effortPoints: Int? = null) {
    val isEmpty: Boolean get() = affinity == null && effortPoints == null
}

/** The latest queued value of each vote kind: [ops] are in queue order, the last one wins. */
internal fun queuedVotesOf(ops: List<PendingOperationEntity>): QueuedVotes {
    var affinity: Int? = null
    var effort: Int? = null
    for (op in ops) {
        when (op.tableName) {
            AFFINITY_VOTES_TABLE -> (opPayload(op)?.get("score") as? Number)?.let { affinity = it.toInt() }
            EFFORT_VOTES_TABLE -> (opPayload(op)?.get("points") as? Number)?.let { effort = it.toInt() }
        }
    }
    return QueuedVotes(affinity, effort)
}
