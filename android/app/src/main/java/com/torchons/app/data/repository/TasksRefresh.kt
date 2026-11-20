package com.torchons.app.data.repository

import com.torchons.app.data.api.ApiHttpException
import com.torchons.app.data.local.dao.TaskDao
import com.torchons.app.data.local.entities.toEntity
import com.torchons.app.data.model.Task
import retrofit2.Response

/**
 * REPLACE of the tasks cache from a `GET /tasks` answer, spared for rows with a queued op
 * (TaskDao.replaceAll) and guarded by the tasks epoch: an answer sent before a push the worker
 * settled meanwhile is dropped (the op is no longer queued, so the replace would no longer spare
 * its row) and the request is made once more. False when the request failed or two answers in a
 * row were outdated by pushes. Kept out of TaskRepository so it is testable on a real Room.
 */
internal suspend fun refreshTasksCache(
    guard: CompletionRefreshGuard,
    taskDao: TaskDao,
    getTasks: suspend () -> Response<List<Task>>,
): Boolean = try {
    guard.tasks.fetchThenApply(
        fetch = {
            val response = getTasks()
            if (!response.isSuccessful) throw ApiHttpException.from(response)
            response.body() ?: emptyList()
        },
        apply = { tasks -> taskDao.replaceAll(tasks.map { it.toEntity() }) },
    ) != null
} catch (_: Exception) {
    false
}
