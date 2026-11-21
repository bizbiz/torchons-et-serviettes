package com.torchons.app.ui.taskdetail

import com.google.gson.Gson

private val gson = Gson()

/** The JSON a task's votes are cached as (data/repository/TaskVotesCache). */
internal fun ServerVotes.toJson(): String = gson.toJson(this)

/** A cached [ServerVotes], or null when the text is missing or from a schema that no longer parses. */
internal fun serverVotesFromJson(json: String?): ServerVotes? =
    if (json == null) null else runCatching { gson.fromJson(json, ServerVotes::class.java) }.getOrNull()

/**
 * What to show for the votes of a task before the network has answered (or when it never will,
 * offline): the votes cached the last time the task was fetched, else, for a task never opened
 * online, the user's own affinity from the bulk snapshot. Nothing known = empty, and the rating
 * dialog then says the previous value is unknown instead of "Jamais noté".
 *
 * Only ever used while no fetch has succeeded: online, the server's answer replaces it.
 */
internal fun votesBeforeFetch(
    cachedJson: String?,
    myAffinity: Int?,
    me: String?,
    myName: String?,
): ServerVotes {
    serverVotesFromJson(cachedJson)?.let { return it }
    if (me == null || myAffinity == null) return ServerVotes()
    return ServerVotes(
        ratings = listOf(
            TaskRating(userId = me, displayName = myName ?: "?", affinity = myAffinity, effortTimeMin = null),
        ),
    )
}
