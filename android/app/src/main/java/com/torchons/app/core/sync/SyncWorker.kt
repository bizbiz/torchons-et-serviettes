package com.torchons.app.core.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.local.dao.CompletionDao
import com.torchons.app.data.local.dao.DelegationDao
import com.torchons.app.data.local.dao.PendingOperationDao
import com.torchons.app.data.local.dao.TaskDao
import com.torchons.app.data.local.entities.DelegationEntity
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
import com.torchons.app.data.model.SyncConflict
import com.torchons.app.data.repository.TaskRepository
import com.torchons.app.widget.TaskWidget
import com.torchons.app.worker.cacheMyTargetRatio
import androidx.glance.appwidget.updateAll
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncWorkerEntryPoint {
    fun apiService(): ApiService
    fun pendingOperationDao(): PendingOperationDao
    fun taskDao(): TaskDao
    fun completionDao(): CompletionDao
    fun delegationDao(): DelegationDao
    fun tokenManager(): TokenManager
    fun taskRepository(): TaskRepository
}

/**
 * Ops per /sync request. Far below the server's bound on `changes`
 * (backend/app/schemas/sync.py), which exists to refuse abusive payloads.
 */
internal const val SYNC_BATCH_SIZE = 100

/**
 * Sends [items] in order, [batchSize] at a time, and stops at the first batch
 * [sendBatch] reports as failed: the batches after it are not sent, so the
 * next pass replays only what is left. Returns true when every batch went.
 * Kept out of [SyncWorker] so the batching is testable without Hilt.
 */
internal suspend fun <T> sendInBatches(
    items: List<T>,
    batchSize: Int,
    sendBatch: suspend (List<T>) -> Boolean,
): Boolean {
    for (batch in items.chunked(batchSize)) {
        if (!sendBatch(batch)) return false
    }
    return true
}

/**
 * After a successful sync, refreshes my cached target share (best effort: the
 * daily reminder reads it offline). Cancellation still propagates.
 */
private suspend fun refreshMyTargetRatio(apiService: ApiService, tokenManager: TokenManager) {
    try {
        apiService.getFairShareStatus().body()?.let { tokenManager.cacheMyTargetRatio(it) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Offline or refused: the previous cached share stays.
    }
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
        val allSent = sendInBatches(rest, SYNC_BATCH_SIZE) { batch ->
            val request = SyncRequest(
                deviceId,
                tokenManager.getSyncCursor(),
                batch.map { it.toSyncOperation() },
            )

            // A failed request is recorded on every op of the batch, like a
            // conflict: the sync icon turns to "problem" after two failures and
            // its dialog names the cause. It used to return silently, and the
            // icon kept spinning as an ordinary sync while the queue waited.
            suspend fun failed(reason: String): Boolean {
                batch.forEach { pendingDao.incrementAttempt(it.clientOpId, reason) }
                return false
            }

            val resp = try {
                apiService.sync(request)
            } catch (e: CancellationException) {
                // requestSync REPLACEs a running pass: not a failure of the ops.
                throw e
            } catch (e: Exception) {
                return@sendInBatches failed(e.javaClass.simpleName)
            }

            if (!resp.isSuccessful) return@sendInBatches failed("HTTP ${resp.code()}")
            val body = resp.body() ?: return@sendInBatches failed("HTTP ${resp.code()}: empty body")

            body.applied.forEach { pendingDao.delete(it.clientOpId) }
            if (body.applied.isNotEmpty()) tokenManager.setLastSyncPushAt(System.currentTimeMillis())

            settleConflicts(body.conflicts, batch, ep)

            body.serverChanges.forEach { applyServerChange(it, taskDao, completionDao, ep.delegationDao()) }

            tokenManager.setSyncCursor(body.serverVersion)
            true
        }

        // The widget skips its server refresh while ops are queued (it would wipe
        // their optimistic rows): once they are sent, it redraws from fresh data.
        if (allSent) runCatching { TaskWidget().updateAll(applicationContext) }
        if (allSent) refreshMyTargetRatio(apiService, tokenManager)
        return if (allSent) Result.success() else Result.retry()
    }

    private suspend fun settleConflicts(
        conflicts: List<SyncConflict>,
        batch: List<PendingOperationEntity>,
        ep: SyncWorkerEntryPoint,
    ) {
        val pendingDao = ep.pendingOperationDao()
        conflicts.forEach { conflict ->
            // `out_of_scope`: the op can never apply (household left, row gone).
            // `rejected`: refused for good, dropped below and told to the user.
            // Every other conflict stays queued and is replayed on the next pass.
            when (conflict.reason) {
                "out_of_scope" -> pendingDao.delete(conflict.clientOpId)
                "rejected" -> Unit
                else -> pendingDao.incrementAttempt(conflict.clientOpId, conflict.detail ?: conflict.reason)
            }
        }
        dropRejected(conflicts.filter { it.reason == "rejected" }, batch, ep)
    }

    /**
     * Drops the ops the server refused for good, the way a manual discard does
     * (their local effect undone, an INSERT's dependents with it), and records a
     * notice the sync dialog shows until acknowledged.
     */
    private suspend fun dropRejected(
        rejected: List<SyncConflict>,
        batch: List<PendingOperationEntity>,
        ep: SyncWorkerEntryPoint,
    ) {
        if (rejected.isEmpty()) return
        val notices = rejected.mapNotNull { conflict ->
            val op = batch.firstOrNull { it.clientOpId == conflict.clientOpId } ?: return@mapNotNull null
            RejectedSyncOp(
                kind = pendingOpKind(op.tableName, op.operation),
                taskTitle = taskIdOf(op)?.let { ep.taskDao().getById(it)?.title },
                reason = readableSyncError(conflict.detail),
                rejectedAtMs = System.currentTimeMillis(),
            )
        }
        ep.tokenManager().updateRejectedSyncOpsJson { appendRejectedOps(it, notices) }
        for (conflict in rejected) {
            try {
                ep.taskRepository().discardPendingOp(conflict.clientOpId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The refresh that undoes a local edit needs the network; the op itself
                // is already out of the queue, the next refresh puts the cache right.
                ep.pendingOperationDao().delete(conflict.clientOpId)
            }
        }
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
        delegationDao: DelegationDao,
    ) {
        try {
            when (change.table) {
                "tasks" -> applyTaskChange(change, taskDao)
                "task_completions" -> applyCompletionChange(change, completionDao)
                "task_delegations" -> delegationEntityFromChange(change)
                    ?.let { delegationDao.upsertAll(listOf(it)) }
                    ?: delegationDao.deleteById(change.rowId)
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

/**
 * A `task_delegations` change_log entry: an INSERT carries the full row (the server replaces
 * the op's data, api/sync_delegations.py) and is cached; a DELETE (cancelled) or an UPDATE
 * (settled by a completion) ends it, so null = drop the cached row.
 */
internal fun delegationEntityFromChange(change: ChangeLogEntry): DelegationEntity? {
    val data = change.data ?: return null
    if (change.operation != "INSERT") return null
    return DelegationEntity(
        id = change.rowId,
        taskId = data["task_id"] as? String ?: return null,
        authorId = data["author_id"] as? String ?: return null,
        recipientIds = data.stringList("recipient_ids") ?: return null,
        createdAt = data["created_at"] as? String ?: change.changedAt,
    )
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
