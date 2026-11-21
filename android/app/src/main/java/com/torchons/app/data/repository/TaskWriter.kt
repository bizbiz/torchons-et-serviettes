package com.torchons.app.data.repository

import com.torchons.app.core.sync.FREQUENCY_VOTES_TABLE
import com.torchons.app.data.local.dao.ContextDao
import com.torchons.app.data.local.dao.TaskDao
import com.torchons.app.data.local.entities.ContextEntity
import com.torchons.app.data.local.entities.TaskEntity
import com.torchons.app.data.local.entities.contextRefsOf
import com.torchons.app.data.local.entities.toDomain
import com.torchons.app.data.local.entities.toEntity
import com.torchons.app.data.model.CreateTaskRequest
import com.torchons.app.data.model.FrequencyRequest
import com.torchons.app.data.model.Task
import com.torchons.app.data.model.TaskContextRef
import com.torchons.app.data.model.UpdateTaskRequest
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What [TaskLedger] needs from the cache; Room in the app, a fake in JVM tests. */
internal interface TaskMutationStore {
    suspend fun task(id: String): TaskEntity?
    suspend fun saveTask(task: TaskEntity)
    suspend fun deleteTask(id: String)
    suspend fun contexts(): List<ContextEntity>
}

/** Room-backed [TaskMutationStore]. */
internal class RoomTaskMutationStore(
    private val taskDao: TaskDao,
    private val contextDao: ContextDao,
) : TaskMutationStore {
    override suspend fun task(id: String) = taskDao.getById(id)
    override suspend fun saveTask(task: TaskEntity) = taskDao.upsert(task)
    override suspend fun deleteTask(id: String) = taskDao.deleteById(id)
    override suspend fun contexts() = contextDao.getAll()
}

/**
 * The offline-first writes of a task: create, edit, delete, and the frequency vote that could not
 * be sent. Each is ONE [LocalMutation]: the optimistic Room change and its queued op land together
 * in the application's scope, so leaving the screen cannot show a change that is never queued
 * (see LocalMutation). Derived state is recomputed from the cache with no network
 * (docs/cache-conventions.md), hence the context references resolved here from Room.
 */
internal class TaskLedger(
    private val store: TaskMutationStore,
    private val mutation: LocalMutation,
    private val now: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun create(request: CreateTaskRequest): Result<Task> {
        val rowId = newId()
        val at = now().toString()
        val optimistic = mutation.run {
            val task = Task(
                id = rowId,
                title = request.title,
                description = request.description,
                assignedToIds = request.assignedToIds,
                recurrenceRule = request.recurrenceRule,
                weight = request.weight,
                tolerance = request.tolerance,
                isActive = true,
                sortOrder = 0,
                createdAt = at,
                updatedAt = at,
                contexts = resolveContextRefs(request.contextIds),
            )
            store.saveTask(task.toEntity())
            enqueue(
                "tasks", "INSERT", rowId,
                mapOf(
                    "title" to request.title,
                    "description" to request.description,
                    "assigned_to_ids" to request.assignedToIds,
                    "recurrence_rule" to request.recurrenceRule,
                    "weight" to request.weight,
                    "tolerance" to request.tolerance,
                    "context_ids" to request.contextIds,
                ),
            )
            task
        }
        return Result.success(optimistic)
    }

    suspend fun update(taskId: String, request: UpdateTaskRequest): Result<Task> {
        val at = now().toString()
        val shown = mutation.run {
            val existing = store.task(taskId)?.toDomain()
            val contexts = request.contextIds?.let { resolveContextRefs(it) }
            val updated = existing?.let { applyUpdate(it, request, at, contexts) }
            if (updated != null) store.saveTask(updated.toEntity())
            enqueue("tasks", "UPDATE", taskId, updatePayload(request))
            updated ?: existing ?: Task(id = taskId, title = "")
        }
        return Result.success(shown)
    }

    suspend fun delete(taskId: String): Result<Unit> {
        mutation.run {
            store.deleteTask(taskId)
            enqueue("tasks", "DELETE", taskId, null)
        }
        return Result.success(Unit)
    }

    /**
     * Queues a frequency vote the server did not take (offline or error). There is no local row: the
     * vote lives on the server only (`task_frequency_votes` is not handled by /sync, the SyncWorker
     * calls setFrequency), so this is a queue-only mutation, still run in the application's scope.
     */
    suspend fun enqueueFrequencyVote(taskId: String, request: FrequencyRequest) {
        val payload: MutableMap<String, Any> = mutableMapOf()
        request.everyNDays?.let { payload["every_n_days"] = it }
        request.daysPerMonth?.let { payload["days_per_month"] = it }
        mutation.run { enqueue(FREQUENCY_VOTES_TABLE, "VOTE", taskId, payload) }
    }

    /** [contexts] null keeps the task's own: only a present `contextIds` changes them. */
    private fun applyUpdate(
        existing: Task,
        request: UpdateTaskRequest,
        at: String,
        contexts: List<TaskContextRef>?,
    ) =
        existing.copy(
            title = request.title ?: existing.title,
            description = request.description ?: existing.description,
            assignedToIds = request.assignedToIds ?: existing.assignedToIds,
            recurrenceRule = request.recurrenceRule ?: existing.recurrenceRule,
            weight = request.weight ?: existing.weight,
            tolerance = request.tolerance ?: existing.tolerance,
            isActive = request.isActive ?: existing.isActive,
            updatedAt = at,
            contexts = contexts ?: existing.contexts,
        )

    private fun updatePayload(request: UpdateTaskRequest): Map<String, Any?> {
        val payload = mutableMapOf<String, Any?>()
        request.title?.let { payload["title"] = it }
        request.description?.let { payload["description"] = it }
        request.assignedToIds?.let { payload["assigned_to_ids"] = it }
        request.recurrenceRule?.let { payload["recurrence_rule"] = it }
        request.weight?.let { payload["weight"] = it }
        request.tolerance?.let { payload["tolerance"] = it }
        request.isActive?.let { payload["is_active"] = it }
        // `?.let`, not `takeIf { it.isNotEmpty() }`: an EMPTY list is an instruction ("detach every
        // context") and must go out as is. Only `null` means "leave it".
        request.contextIds?.let { payload["context_ids"] = it }
        return payload
    }

    /** Context ids as displayable references, from the CACHE: a task made offline shows its contexts. */
    private suspend fun resolveContextRefs(ids: List<String>?): List<TaskContextRef> {
        if (ids.isNullOrEmpty()) return emptyList()
        return contextRefsOf(ids, store.contexts())
    }
}

/** The Hilt-facing entry point of [TaskLedger]; [TaskRepository] delegates here. */
@Singleton
class TaskWriter @Inject constructor(
    taskDao: TaskDao,
    contextDao: ContextDao,
    offline: OfflineWriter,
) {
    private val ledger = TaskLedger(RoomTaskMutationStore(taskDao, contextDao), offline.mutation)

    suspend fun create(request: CreateTaskRequest): Result<Task> = ledger.create(request)

    suspend fun update(taskId: String, request: UpdateTaskRequest): Result<Task> = ledger.update(taskId, request)

    suspend fun delete(taskId: String): Result<Unit> = ledger.delete(taskId)

    suspend fun enqueueFrequencyVote(taskId: String, request: FrequencyRequest) =
        ledger.enqueueFrequencyVote(taskId, request)
}
