package com.torchons.app.data.repository

import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.local.dao.ViewSnapshotDao
import com.torchons.app.data.local.entities.ViewSnapshotEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** Minimum age of the bulk "my affinities" snapshot before the tasks refresh asks again. */
internal const val MY_AFFINITIES_MAX_AGE_MS = 10 * 60 * 1000L

/**
 * What the app last learned about the votes on tasks, kept in Room (`view_snapshots`, no schema
 * change) so they can be shown OFFLINE (emulator re-test of 2026-11-20: a vote made offline on a
 * task never opened online showed "Jamais noté" and defaults, although the user had rated it 9/10
 * on the server, and could overwrite it unseen).
 *
 * Two entries:
 *  - per task, the JSON of what the task detail fetched (every member's ratings, the effort
 *    votes): REPLACED at each successful fetch (docs/cache-conventions.md, rule 3);
 *  - the caller's affinity on EVERY task, one cheap request (`GET /my-affinities`) made with the
 *    tasks refresh, so a task never opened online still has the user's own affinity. Keyed by the
 *    user id: a different user signing in on this phone never reads it.
 *
 * Both go away with the rest of the snapshots (household switch, app update).
 * Plain Kotlin on purpose (DAO, API and user id passed in): tested on a real Room, no Hilt.
 */
internal class TaskVotesStore(
    private val snapshots: ViewSnapshotDao,
    private val api: ApiService,
    private val currentUserId: suspend () -> String?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun saveTaskVotes(taskId: String, json: String) {
        snapshots.upsert(ViewSnapshotEntity(taskKey(taskId), json, now()))
    }

    suspend fun taskVotes(taskId: String): String? = snapshots.get(taskKey(taskId))?.jsonContent

    /** The user's own affinity on [taskId] as last fetched in bulk; null = unknown (not "none"). */
    suspend fun myAffinity(userId: String, taskId: String): Int? =
        snapshots.get(affinitiesKey(userId))?.jsonContent?.let(::parseAffinities)?.get(taskId)

    /** Best effort and silent: a failure keeps the previous snapshot. */
    suspend fun refreshMyAffinities() {
        val me = currentUserId() ?: return
        val key = affinitiesKey(me)
        val previous = snapshots.get(key)
        if (previous != null && now() - previous.updatedAt < MY_AFFINITIES_MAX_AGE_MS) return
        try {
            val response = api.getMyAffinities()
            val rows = response.body()?.takeIf { response.isSuccessful } ?: return
            snapshots.upsert(ViewSnapshotEntity(key, formatAffinities(rows), now()))
        } catch (_: Exception) {
            // Offline: the snapshot stays as it was.
        }
    }

    private fun taskKey(taskId: String) = "task_votes:$taskId"

    private fun affinitiesKey(userId: String) = "my_affinities:$userId"
}

/** `[{task_id, score}]` as the server sends it, to a compact `taskId=score` text. */
internal fun formatAffinities(rows: List<Map<String, Any>>): String =
    rows.mapNotNull { row ->
        val id = row["task_id"] as? String
        val score = (row["score"] as? Number)?.toInt()
        if (id == null || score == null) null else "$id=$score"
    }.joinToString("\n")

internal fun parseAffinities(text: String): Map<String, Int> =
    text.lineSequence().mapNotNull { line ->
        val parts = line.split("=")
        val score = parts.getOrNull(1)?.toIntOrNull()
        if (parts.size == 2 && score != null) parts[0] to score else null
    }.toMap()

/** The Hilt-facing entry point of [TaskVotesStore]. */
@Singleton
class TaskVotesCache @Inject constructor(
    snapshots: ViewSnapshotDao,
    api: ApiService,
    tokenManager: TokenManager,
) {
    private val store = TaskVotesStore(snapshots, api, { tokenManager.userIdFlow.first() })

    suspend fun saveTaskVotes(taskId: String, json: String) = store.saveTaskVotes(taskId, json)

    suspend fun taskVotes(taskId: String): String? = store.taskVotes(taskId)

    suspend fun myAffinity(userId: String, taskId: String): Int? = store.myAffinity(userId, taskId)

    suspend fun refreshMyAffinities() = store.refreshMyAffinities()
}
