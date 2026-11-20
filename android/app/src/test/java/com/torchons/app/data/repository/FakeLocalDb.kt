package com.torchons.app.data.repository

import com.torchons.app.data.local.entities.CompletionEntity
import com.torchons.app.data.local.entities.ContextEntity
import com.torchons.app.data.local.entities.PendingOperationEntity
import com.torchons.app.data.local.entities.TaskEntity
import com.torchons.app.data.model.Delegation
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An in-memory stand-in for the Room database of the mutation tests (no Room in JVM tests, see
 * app/build.gradle.kts). It keeps the two properties the mutation code relies on:
 *  - transactions exclude each other (SQLite's single writer) and ROLL BACK when the block throws:
 *    the tables and the queue are restored as they were when it began;
 *  - it records `begin`, `commit`, `rollback` in [events], so a test can place a call (the sync
 *    request) before or after the commit.
 * One class for every narrow store the ledgers ask for: they all read and write the same tables.
 */
internal class FakeLocalDb :
    OpStore, TaskMutationStore, CompletionEditStore, DelegationMutationStore, PendingDiscardStore {

    val tasks = linkedMapOf<String, TaskEntity>()
    val completions = linkedMapOf<String, CompletionEntity>()
    val delegations = linkedMapOf<String, Delegation>()
    val contextRows = mutableListOf<ContextEntity>()
    val ops = mutableListOf<PendingOperationEntity>()
    val events = CopyOnWriteArrayList<String>()

    /** When set, [saveTask] waits for it AFTER writing the row: the transaction is open and half done. */
    var gate: CompletableDeferred<Unit>? = null

    /** Completed when a write has reached [gate]. */
    val entered = CompletableDeferred<Unit>()

    /** When set, the next [enqueue] throws it: a failure in the middle of the transaction. */
    var failOnEnqueue: Throwable? = null

    private val lock = Mutex()

    private data class Snapshot(
        val tasks: Map<String, TaskEntity>,
        val completions: Map<String, CompletionEntity>,
        val delegations: Map<String, Delegation>,
        val ops: List<PendingOperationEntity>,
    )

    private fun snapshot() = Snapshot(
        LinkedHashMap(tasks), LinkedHashMap(completions), LinkedHashMap(delegations), ops.toList(),
    )

    private fun restore(s: Snapshot) {
        tasks.clear(); tasks.putAll(s.tasks)
        completions.clear(); completions.putAll(s.completions)
        delegations.clear(); delegations.putAll(s.delegations)
        ops.clear(); ops.addAll(s.ops)
    }

    override suspend fun <T> atomically(block: suspend () -> T): T = lock.withLock {
        val before = snapshot()
        events += "begin"
        try {
            block().also { events += "commit" }
        } catch (e: Throwable) {
            restore(before)
            events += "rollback"
            throw e
        }
    }

    override suspend fun enqueue(op: PendingOperationEntity) {
        failOnEnqueue?.let { throw it }
        ops += op
    }

    override suspend fun dequeue(clientOpId: String) {
        ops.removeAll { it.clientOpId == clientOpId }
    }

    override suspend fun queue(): List<PendingOperationEntity> = ops.toList()

    // TaskMutationStore
    override suspend fun task(id: String): TaskEntity? = tasks[id]
    override suspend fun saveTask(task: TaskEntity) {
        tasks[task.id] = task
        entered.complete(Unit)
        gate?.await()
    }
    override suspend fun deleteTask(id: String) { tasks.remove(id) }
    override suspend fun contexts(): List<ContextEntity> = contextRows.toList()

    // CompletionEditStore
    override suspend fun completion(id: String): CompletionEntity? = completions[id]
    override suspend fun saveCompletion(entity: CompletionEntity) { completions[entity.id] = entity }
    override suspend fun deleteCompletion(id: String) { completions.remove(id) }

    // DelegationMutationStore
    override suspend fun save(delegation: Delegation) { delegations[delegation.id] = delegation }
    override suspend fun delete(id: String) { delegations.remove(id) }
}
