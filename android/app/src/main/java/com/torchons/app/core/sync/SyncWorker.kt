package com.torchons.app.core.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.local.dao.CompletionDao
import com.torchons.app.data.local.dao.PendingOperationDao
import com.torchons.app.data.local.dao.TaskDao
import com.torchons.app.data.local.entities.PendingOperationEntity
import com.torchons.app.data.local.entities.TaskEntity
import com.torchons.app.data.local.entities.toEntity
import com.torchons.app.data.model.ChangeLogEntry
import com.torchons.app.data.model.Completion
import com.torchons.app.data.model.FrequencyRequest
import com.torchons.app.data.model.RecurrenceRule
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
    fun completionDao(): CompletionDao
    fun tokenManager(): TokenManager
}

/**
 * Ops per /sync request. Far below the server's bound on `changes`
 * (backend/app/schemas/sync.py), which exists to refuse abusive payloads.
 */
private const val SYNC_BATCH_SIZE = 100

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
                @Suppress("UNCHECKED_CAST")
                val daysPerMonth = (payload?.get("days_per_month") as? Map<String, Any?>)
                    ?.mapNotNull { (k, v) -> (v as? Number)?.toInt()?.let { k to it } }
                    ?.toMap()
                val request = when {
                    daysPerMonth != null -> FrequencyRequest(daysPerMonth = daysPerMonth)
                    days != null -> FrequencyRequest(everyNDays = days)
                    else -> null
                }
                if (request != null) {
                    val resp = apiService.setFrequency(op.rowId, request)
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

        // Sent in batches, in queue order (an INSERT before its UPDATE). The whole
        // queue used to go in one request: the server could not bound `changes`
        // without refusing a long offline queue forever. Each batch is settled --
        // applied ops dropped, cursor advanced -- before the next one, so a failure
        // midway retries only what is left.
        for (batch in rest.chunked(SYNC_BATCH_SIZE)) {
            val request = SyncRequest(
                deviceId,
                tokenManager.getSyncCursor(),
                batch.map { it.toSyncOperation() },
            )

            val resp = try {
                apiService.sync(request)
            } catch (_: Exception) {
                return Result.retry()
            }

            if (!resp.isSuccessful) return Result.retry()
            val body = resp.body() ?: return Result.retry()

            body.applied.forEach { pendingDao.delete(it.clientOpId) }

            body.conflicts.forEach { conflict ->
                // `out_of_scope`: the op can never apply (household left, row gone).
                // Every other conflict stays queued and is replayed on the next pass.
                if (conflict.reason == "out_of_scope") {
                    pendingDao.delete(conflict.clientOpId)
                } else {
                    pendingDao.incrementAttempt(conflict.clientOpId, conflict.reason)
                }
            }

            body.serverChanges.forEach { applyServerChange(it, taskDao, completionDao) }

            tokenManager.setSyncCursor(body.serverVersion)
        }

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
            foyerId = foyerId,
        )
    }

    private suspend fun applyServerChange(
        change: ChangeLogEntry,
        taskDao: TaskDao,
        completionDao: CompletionDao,
    ) {
        try {
            when (change.table) {
                "tasks" -> applyTaskChange(change, taskDao)
                "task_completions" -> applyCompletionChange(change, completionDao)
            }
        } catch (_: Exception) {}
    }

    private suspend fun applyTaskChange(change: ChangeLogEntry, taskDao: TaskDao) {
        if (change.operation == "DELETE") {
            taskDao.deleteById(change.rowId)
            return
        }
        if (change.data == null) return
        val merged = taskEntityFromChange(taskDao.getById(change.rowId), change) ?: return
        taskDao.upsert(merged)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun applyCompletionChange(change: ChangeLogEntry, completionDao: CompletionDao) {
        if (change.operation == "DELETE") {
            completionDao.deleteById(change.rowId)
            return
        }
        val data = change.data ?: return

        // UPDATE : le new_data du change_log est PARTIEL (completed_by /
        // completed_at, cf stats.py update_completion + sync.py
        // _handle_completion UPDATE). Reconstruire la Completion entiere ici
        // ecraserait taskId / points / collectiveId avec des defauts (la row
        // disparaitrait de l'historique de la tache). On patche la row
        // existante a la place — meme strategie que taskEntityFromChange
        // pour les taches. Si la row est absente, on laisse le prochain
        // refreshCompletions (replaceForTask autoritaire) la reconcilier.
        if (change.operation == "UPDATE") {
            val existing = completionDao.getById(change.rowId) ?: return
            completionDao.upsertAll(
                listOf(
                    existing.copy(
                        completedBy = (data["completed_by"] as? String) ?: existing.completedBy,
                        completedAt = (data["completed_at"] as? String) ?: existing.completedAt,
                        notes = if (data.containsKey("notes")) data["notes"] as? String
                                else existing.notes,
                    )
                )
            )
            return
        }

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

private val changeGson = Gson()

/**
 * Turns a `tasks` change_log entry into the row to cache, or null to leave the
 * cache alone.
 *
 * The server's task UPDATEs are PARTIAL: a frequency vote and the d4ec241658ca
 * migration carry only `recurrence_rule`, PUT /tasks dumps with
 * `exclude_unset=True`, a relayed /sync op carries only the edited keys, the
 * auto-archive job carries only `is_archived`. So an UPDATE (and an INSERT for
 * a row already cached, e.g. fetched by a refresh before this sync) patches
 * the existing row key by key: a key present wins, even when null for a
 * nullable field; an absent key keeps the cached value. An UPDATE for a row
 * not cached invents nothing: the next authoritative refresh (replaceAll)
 * reconciles it. Same strategy as applyCompletionChange.
 *
 * `context_ids` cannot be resolved into TaskContextRef here (no context
 * cache in the worker): cached contexts are kept until the next refresh.
 * Fields the change_log never carries (inferred_interval_days,
 * last_completed_at, absent days) always keep their cached value.
 */
internal fun taskEntityFromChange(existing: TaskEntity?, change: ChangeLogEntry): TaskEntity? {
    val data = change.data ?: return null

    if (existing != null && change.operation in setOf("UPDATE", "INSERT")) {
        return existing.copy(
            title = (data["title"] as? String) ?: existing.title,
            description = if (data.containsKey("description")) data["description"] as? String
                          else existing.description,
            assignedToIds = data.stringList("assigned_to_ids") ?: existing.assignedToIds,
            recurrenceRule = if (data.containsKey("recurrence_rule"))
                                 parseRecurrenceRule(data["recurrence_rule"])
                             else existing.recurrenceRule,
            weight = (data["weight"] as? Number)?.toInt() ?: existing.weight,
            tolerance = (data["tolerance"] as? String) ?: existing.tolerance,
            isActive = (data["is_active"] as? Boolean) ?: existing.isActive,
            isArchived = (data["is_archived"] as? Boolean) ?: existing.isArchived,
            sortOrder = (data["sort_order"] as? Number)?.toInt() ?: existing.sortOrder,
            updatedAt = (data["updated_at"] as? String) ?: change.changedAt,
        )
    }
    if (change.operation != "INSERT") return null

    val task = Task(
        id = change.rowId,
        title = data["title"] as? String ?: "",
        description = data["description"] as? String,
        assignedToIds = data.stringList("assigned_to_ids") ?: emptyList(),
        recurrenceRule = parseRecurrenceRule(data["recurrence_rule"]),
        weight = (data["weight"] as? Number)?.toInt() ?: 1,
        tolerance = data["tolerance"] as? String ?: "NORMAL",
        isActive = data["is_active"] as? Boolean ?: true,
        isArchived = data["is_archived"] as? Boolean ?: false,
        sortOrder = (data["sort_order"] as? Number)?.toInt() ?: 0,
        // The INSERT payload is the create request: no timestamps. The
        // server's change time stands in, an empty string would break the
        // due-date math (created_at anchors a never-done task).
        createdAt = data["created_at"] as? String ?: change.changedAt,
        updatedAt = data["updated_at"] as? String ?: change.changedAt,
    )
    return task.toEntity()
}

private fun Map<String, Any?>.stringList(key: String): List<String>? =
    (this[key] as? List<*>)?.filterIsInstance<String>()

/**
 * Gson hands nested JSON as LinkedTreeMap with Double numbers; going through
 * a JSON tree lets Gson map it onto RecurrenceRule with its real Int fields,
 * the same way Room's converter reads it back. Unknown keys are ignored.
 */
private fun parseRecurrenceRule(raw: Any?): RecurrenceRule? {
    if (raw !is Map<*, *>) return null
    // Gson bypasses Kotlin null-safety: a rule without "type" would carry a
    // null in a non-null field, hence the check on the raw map.
    if (raw["type"] !is String) return null
    return runCatching {
        changeGson.fromJson(changeGson.toJsonTree(raw), RecurrenceRule::class.java)
    }.getOrNull()
}
