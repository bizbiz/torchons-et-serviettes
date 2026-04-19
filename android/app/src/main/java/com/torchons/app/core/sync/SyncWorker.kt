package com.torchons.app.core.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.local.dao.CategoryDao
import com.torchons.app.data.local.dao.CompletionDao
import com.torchons.app.data.local.dao.PendingOperationDao
import com.torchons.app.data.local.dao.TaskDao
import com.torchons.app.data.local.entities.PendingOperationEntity
import com.torchons.app.data.local.entities.toEntity
import com.torchons.app.data.model.Category
import com.torchons.app.data.model.ChangeLogEntry
import com.torchons.app.data.model.Completion
import com.torchons.app.data.model.FrequencyRequest
import com.torchons.app.data.model.SyncOperation
import com.torchons.app.data.model.SyncRequest
import com.torchons.app.data.model.Task
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncWorkerEntryPoint {
    fun apiService(): ApiService
    fun pendingOperationDao(): PendingOperationDao
    fun taskDao(): TaskDao
    fun categoryDao(): CategoryDao
    fun completionDao(): CompletionDao
    fun tokenManager(): TokenManager
}

class SyncWorker(
    ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {

    private val gson = Gson()

    override suspend fun doWork(): Result {
        val ep = EntryPointAccessors.fromApplication(
            applicationContext, SyncWorkerEntryPoint::class.java
        )
        val pendingDao = ep.pendingOperationDao()
        val apiService = ep.apiService()
        val taskDao = ep.taskDao()
        val categoryDao = ep.categoryDao()
        val completionDao = ep.completionDao()
        val tokenManager = ep.tokenManager()

        val pending = pendingDao.getAll()
        if (pending.isEmpty()) return Result.success()

        // Les votes de frequence ne transitent pas par /sync (table absente
        // du contrat serveur). On les pousse directement via setFrequency.
        val (freqVotes, rest) = pending.partition { it.tableName == "task_frequency_votes" }
        freqVotes.forEach { op ->
            runCatching {
                @Suppress("UNCHECKED_CAST")
                val payload = gson.fromJson(op.payloadJson, Map::class.java) as? Map<String, Any?>
                val days = (payload?.get("every_n_days") as? Number)?.toInt()
                if (days != null) {
                    val resp = apiService.setFrequency(op.rowId, FrequencyRequest(days))
                    when {
                        resp.isSuccessful -> pendingDao.delete(op.clientOpId)
                        // 4xx = erreur permanente (payload invalide / tache supprimee /
                        // foyer different...). Retry indefini pollue le sync et laisse
                        // le bandeau "Synchronisation en cours" affiche a vie. On drop.
                        resp.code() in 400..499 -> pendingDao.delete(op.clientOpId)
                        // 5xx / reseau via onFailure : on retry normalement.
                        else -> pendingDao.incrementAttempt(op.clientOpId, "HTTP ${resp.code()}")
                    }
                } else {
                    pendingDao.delete(op.clientOpId)
                }
            }.onFailure {
                pendingDao.incrementAttempt(op.clientOpId, it.message ?: "error")
            }
        }

        if (rest.isEmpty()) return Result.success()

        val deviceId = tokenManager.getOrCreateDeviceId()
        val cursor = tokenManager.getSyncCursor()

        val ops = rest.map { it.toSyncOperation() }
        val request = SyncRequest(deviceId, cursor, ops)

        val resp = try {
            apiService.sync(request)
        } catch (_: Exception) {
            return Result.retry()
        }

        if (!resp.isSuccessful) return Result.retry()
        val body = resp.body() ?: return Result.retry()

        body.applied.forEach { pendingDao.delete(it.clientOpId) }

        body.conflicts.forEach { conflict ->
            pendingDao.incrementAttempt(conflict.clientOpId, conflict.reason)
        }

        body.serverChanges.forEach { applyServerChange(it, taskDao, categoryDao, completionDao) }

        tokenManager.setSyncCursor(body.serverVersion)

        return Result.success()
    }

    private fun PendingOperationEntity.toSyncOperation(): SyncOperation {
        @Suppress("UNCHECKED_CAST")
        val data = try {
            gson.fromJson(payloadJson, Map::class.java) as? Map<String, Any?>
        } catch (_: Exception) { null }

        return SyncOperation(
            clientOpId = clientOpId,
            table = tableName,
            operation = operation,
            rowId = rowId,
            data = data,
            clientTimestamp = DateTimeFormatter.ISO_INSTANT.format(
                Instant.ofEpochMilli(createdAt).atOffset(ZoneOffset.UTC)
            ),
        )
    }

    private suspend fun applyServerChange(
        change: ChangeLogEntry,
        taskDao: TaskDao,
        categoryDao: CategoryDao,
        completionDao: CompletionDao,
    ) {
        try {
            when (change.table) {
                "tasks" -> applyTaskChange(change, taskDao)
                "categories" -> applyCategoryChange(change, categoryDao)
                "task_completions" -> applyCompletionChange(change, completionDao)
            }
        } catch (_: Exception) {}
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun applyTaskChange(change: ChangeLogEntry, taskDao: TaskDao) {
        if (change.operation == "DELETE") {
            taskDao.deleteById(change.rowId)
            return
        }
        val data = change.data ?: return
        val task = Task(
            id = change.rowId,
            title = data["title"] as? String ?: "",
            description = data["description"] as? String,
            categoryId = data["category_id"] as? String,
            assignedToIds = (data["assigned_to_ids"] as? List<String>) ?: emptyList(),
            weight = (data["weight"] as? Double)?.toInt() ?: 1,
            tolerance = data["tolerance"] as? String ?: "NORMAL",
            isActive = data["is_active"] as? Boolean ?: true,
            sortOrder = (data["sort_order"] as? Double)?.toInt() ?: 0,
            createdAt = data["created_at"] as? String ?: "",
            updatedAt = data["updated_at"] as? String ?: "",
        )
        taskDao.upsert(task.toEntity())
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun applyCategoryChange(change: ChangeLogEntry, categoryDao: CategoryDao) {
        if (change.operation == "DELETE") {
            categoryDao.deleteById(change.rowId)
            return
        }
        val data = change.data ?: return
        val cat = Category(
            id = change.rowId,
            name = data["name"] as? String ?: "",
            color = data["color"] as? String ?: "#6B7280",
            icon = data["icon"] as? String,
            sortOrder = (data["sort_order"] as? Double)?.toInt() ?: 0,
            requiresHome = data["requires_home"] as? Boolean ?: false,
            createdAt = data["created_at"] as? String ?: "",
        )
        categoryDao.upsert(cat.toEntity())
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun applyCompletionChange(change: ChangeLogEntry, completionDao: CompletionDao) {
        if (change.operation == "DELETE") {
            completionDao.deleteById(change.rowId)
            return
        }
        val data = change.data ?: return
        val comp = Completion(
            id = change.rowId,
            taskId = data["task_id"] as? String ?: "",
            completedBy = data["completed_by"] as? String ?: "",
            completedAt = data["completed_at"] as? String ?: "",
            pointsEarned = (data["points_earned"] as? Double)?.toInt() ?: 1,
            notes = data["notes"] as? String,
        )
        completionDao.upsertAll(listOf(comp.toEntity()))
    }
}
