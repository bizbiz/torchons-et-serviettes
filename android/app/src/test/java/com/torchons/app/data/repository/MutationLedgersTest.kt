package com.torchons.app.data.repository

import com.google.gson.Gson
import com.torchons.app.data.local.entities.CompletionEntity
import com.torchons.app.data.local.entities.ContextEntity
import com.torchons.app.data.local.entities.PendingOperationEntity
import com.torchons.app.data.local.entities.toEntity
import com.torchons.app.data.model.CreateTaskRequest
import com.torchons.app.data.model.Delegation
import com.torchons.app.data.model.FrequencyRequest
import com.torchons.app.data.model.Task
import com.torchons.app.data.model.UpdateTaskRequest
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One family at a time, the ledgers that moved their writes onto LocalMutation (2026-11-20): a
 * mutation leaves its local change AND its queued op, in one transaction, and asks for one sync.
 * The mechanism itself (cancellation, rollback, ordering) is LocalMutationTest; here each family
 * proves its own contract.
 *
 * PATHS, from the call sites of the old `enqueue(` in TaskRepository (7) and DelegationRepository (2):
 *   (1) task create / update / delete: row + op in `tasks` ......................... COVERED
 *   (2) task update of a task that is not in the cache: op still queued ............ COVERED
 *   (3) context references resolved from the cache, with no network ................ COVERED
 *   (4) frequency vote queued when the server did not take it (queue only) ......... COVERED
 *   (5) completion delete / edit / collective delete, and the edit of a missing row . COVERED
 *   (6) delegation hand / cancel, and handing to nobody ............................ COVERED
 *   (7) discarding a queued op: queue and local row removed together ................ COVERED
 *   (8) the caller leaving mid-write, through a ledger (not only the helper) ........ COVERED (task create)
 *   (9) a completions refresh racing a delete/edit (CompletionRefreshGuard): not covered, it needs
 *       the Room DAO `replaceAll` (no Room in JVM tests); its rule, « rows with a pending op are
 *       spared », is serverRowsToApply, already tested in PendingRowsRefreshTest. What this change
 *       adds is that the row change and its op are ONE transaction, asserted in LocalMutationTest.
 */
class MutationLedgersTest {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gson = Gson()
    private val syncs = AtomicInteger()
    private val ids = AtomicInteger()

    private fun mutation(db: FakeLocalDb) = LocalMutation(
        store = db,
        scope = appScope,
        requestSync = { db.events += "sync"; syncs.incrementAndGet() },
        foyerId = { "home" },
    )

    private val clock = Instant.parse("2026-11-20T10:00:00Z")
    private fun newId() = "id-${ids.incrementAndGet()}"

    private fun tasks(db: FakeLocalDb) = TaskLedger(db, mutation(db), now = { clock }, newId = ::newId)
    private fun completionEdits(db: FakeLocalDb) = CompletionEditLedger(db, mutation(db))
    private fun delegations(db: FakeLocalDb) = DelegationLedger(db, mutation(db), now = { clock }, newId = ::newId)
    private fun discards(db: FakeLocalDb) = PendingDiscardLedger(db, mutation(db))

    @Suppress("UNCHECKED_CAST")
    private fun payload(op: PendingOperationEntity) =
        gson.fromJson(op.payloadJson, Map::class.java) as Map<String, Any?>

    private fun assertOp(op: PendingOperationEntity, table: String, operation: String, row: String) =
        assertEquals(listOf(table, operation, row), listOf(op.tableName, op.operation, op.rowId))

    private fun context(id: String) = ContextEntity(
        id = id, name = "Kitchen", color = "#112233", icon = null, emoji = "K", requiresPresence = false,
        accruesWhenAway = false, sortOrder = 0, templateKey = null, version = 1, createdAt = null,
    )

    private fun completion(id: String, by: String = "me") = CompletionEntity(
        id = id, taskId = "t1", completedBy = by, completedAt = "2026-11-19T10:00:00Z", pointsEarned = 1, notes = null,
    )

    private fun op(id: String, table: String, operation: String, row: String, at: Long) = PendingOperationEntity(
        clientOpId = id, tableName = table, operation = operation, rowId = row, payloadJson = "null", createdAt = at,
    )

    // --- tasks -----------------------------------------------------------------------------

    @Test
    fun `creating a task leaves the optimistic row and an INSERT op, contexts resolved from the cache`() = runBlocking {
        val db = FakeLocalDb().also { it.contextRows += context("ctx1") }
        val created = tasks(db).create(
            CreateTaskRequest(title = "Towels", assignedToIds = listOf("me"), contextIds = listOf("ctx1", "gone")),
        ).getOrThrow()

        assertEquals(listOf(created.id), db.tasks.keys.toList())
        assertEquals(listOf("ctx1"), db.tasks.getValue(created.id).contexts.map { it.id }) // unknown id dropped
        val queued = db.ops.single()
        assertOp(queued, "tasks", "INSERT", created.id)
        assertEquals("Towels", payload(queued)["title"])
        assertEquals(listOf("ctx1", "gone"), payload(queued)["context_ids"])
        assertEquals("home", queued.foyerId)
        assertEquals(1, syncs.get())
    }

    @Test
    fun `editing a task updates the cache and queues an UPDATE with only the changed fields`() = runBlocking {
        val db = FakeLocalDb()
        db.tasks["t1"] = Task(id = "t1", title = "Old", weight = 2).toEntity()

        val shown = tasks(db).update("t1", UpdateTaskRequest(title = "New", contextIds = emptyList())).getOrThrow()

        assertEquals("New", shown.title)
        assertEquals("New", db.tasks.getValue("t1").title)
        assertEquals(2, db.tasks.getValue("t1").weight) // untouched field kept
        val queued = db.ops.single()
        assertEquals(setOf("title", "context_ids"), payload(queued).keys) // an EMPTY list is an instruction
        assertEquals("UPDATE", queued.operation)
        assertEquals(1, syncs.get())
    }

    @Test
    fun `editing a task missing from the cache still queues the op and returns a placeholder`() = runBlocking {
        val db = FakeLocalDb()
        val shown = tasks(db).update("ghost", UpdateTaskRequest(title = "X")).getOrThrow()
        assertEquals("ghost", shown.id)
        assertEquals(0, db.tasks.size)
        assertEquals("UPDATE", db.ops.single().operation)
    }

    @Test
    fun `deleting a task removes the row and queues a DELETE`() = runBlocking {
        val db = FakeLocalDb()
        db.tasks["t1"] = Task(id = "t1", title = "Old").toEntity()
        tasks(db).delete("t1").getOrThrow()
        assertEquals(0, db.tasks.size)
        val queued = db.ops.single()
        assertOp(queued, "tasks", "DELETE", "t1")
        assertEquals(1, syncs.get())
    }

    @Test
    fun `a frequency vote the server did not take is queued, with no local row`() = runBlocking {
        val db = FakeLocalDb()
        tasks(db).enqueueFrequencyVote("t1", FrequencyRequest(everyNDays = 14))
        val queued = db.ops.single()
        assertOp(queued, "task_frequency_votes", "VOTE", "t1")
        assertEquals(14.0, payload(queued)["every_n_days"])
        assertEquals(1, syncs.get())
    }

    @Test
    fun `leaving the screen while a task is being created does not lose the op`() = runBlocking {
        val db = FakeLocalDb()
        val gate = CompletableDeferred<Unit>()
        db.gate = gate // the transaction is open, the row written, the op not yet queued
        val caller = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val job = caller.launch {
            tasks(db).create(CreateTaskRequest(title = "Towels", assignedToIds = listOf("me")))
        }
        withTimeout(5_000) { db.entered.await() }
        caller.coroutineContext[Job]!!.cancelAndJoin()
        assertTrue(job.isCancelled)

        gate.complete(Unit)
        val arrived = withTimeoutOrNull(3_000) { while (syncs.get() == 0) delay(10); true }
        assertEquals("the op and its sync must still arrive", true, arrived)
        assertEquals(1, db.tasks.size)
        assertEquals(1, db.ops.size)
        assertEquals(db.tasks.keys.single(), db.ops.single().rowId)
    }

    // --- completions: delete / edit ---------------------------------------------------------

    @Test
    fun `deleting a completion removes the row and queues a DELETE`() = runBlocking {
        val db = FakeLocalDb()
        db.completions["c1"] = completion("c1")
        completionEdits(db).delete("c1").getOrThrow()
        assertEquals(0, db.completions.size)
        val queued = db.ops.single()
        assertOp(queued, "task_completions", "DELETE", "c1")
        assertEquals(1, syncs.get())
    }

    @Test
    fun `deleting a collective event removes every row, one DELETE each, one sync`() = runBlocking {
        val db = FakeLocalDb()
        listOf("c1", "c2", "c3").forEach { db.completions[it] = completion(it) }
        completionEdits(db).deleteCollective(listOf("c1", "c2", "c3")).getOrThrow()
        assertEquals(0, db.completions.size)
        assertEquals(listOf("c1", "c2", "c3"), db.ops.map { it.rowId })
        assertEquals(1, syncs.get())
        assertEquals(listOf("begin", "commit", "sync"), db.events.toList()) // one transaction for the three
    }

    @Test
    fun `editing a completion upserts the new who and when and queues an UPDATE`() = runBlocking {
        val db = FakeLocalDb()
        db.completions["c1"] = completion("c1", by = "me")
        completionEdits(db).update("c1", "alice", "2026-11-18T08:00:00Z").getOrThrow()
        assertEquals("alice", db.completions.getValue("c1").completedBy)
        assertEquals("2026-11-18T08:00:00Z", db.completions.getValue("c1").completedAt)
        val queued = db.ops.single()
        assertEquals("UPDATE", queued.operation)
        assertEquals("alice", payload(queued)["completed_by"])
        assertEquals(1, syncs.get())
    }

    @Test
    fun `editing a completion missing from the cache fails and queues nothing`() = runBlocking {
        val db = FakeLocalDb()
        val result = completionEdits(db).update("ghost", "alice", "2026-11-18T08:00:00Z")
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(0, db.ops.size)
        assertEquals(0, syncs.get())
    }

    // --- delegations -------------------------------------------------------------------------

    @Test
    fun `handing a task saves the delegation and queues an INSERT with the sorted recipients`() = runBlocking {
        val db = FakeLocalDb()
        val handed = delegations(db).hand("t1", listOf("zoe", "alice"), me = "me")!!
        assertEquals(listOf("alice", "zoe"), db.delegations.getValue(handed.id).recipientIds)
        val queued = db.ops.single()
        assertOp(queued, "task_delegations", "INSERT", handed.id)
        assertEquals("t1", payload(queued)["task_id"])
        assertEquals(1, syncs.get())
    }

    @Test
    fun `handing a task to nobody writes and queues nothing`() = runBlocking {
        val db = FakeLocalDb()
        assertNull(delegations(db).hand("t1", emptyList(), me = "me"))
        assertEquals(0, db.ops.size)
        assertEquals(0, syncs.get())
    }

    @Test
    fun `cancelling a delegation removes it and queues a DELETE carrying the task id`() = runBlocking {
        val db = FakeLocalDb()
        val d = Delegation(id = "d1", taskId = "t1", authorId = "me", recipientIds = listOf("alice"), createdAt = "x")
        db.delegations["d1"] = d
        delegations(db).cancel(d)
        assertEquals(0, db.delegations.size)
        val queued = db.ops.single()
        assertOp(queued, "task_delegations", "DELETE", "d1")
        assertEquals("t1", payload(queued)["task_id"])
    }

    // --- discarding a queued op ------------------------------------------------------------

    @Test
    fun `discarding a queued INSERT removes its dependents and the local row, with no sync`() = runBlocking {
        val db = FakeLocalDb()
        db.tasks["t9"] = Task(id = "t9", title = "Never synced").toEntity()
        db.ops += op("op1", "tasks", "INSERT", "t9", at = 1)
        db.ops += op("op2", "tasks", "UPDATE", "t9", at = 2) // dependent: edit of a row the server never had
        db.ops += op("op3", "tasks", "INSERT", "other", at = 3)

        val dropped = discards(db).discard("op1")

        assertEquals(listOf("op1", "op2"), dropped.map { it.clientOpId })
        assertEquals(listOf("op3"), db.ops.map { it.clientOpId })
        assertNull(db.tasks["t9"])
        assertEquals(0, syncs.get())
    }

    @Test
    fun `discarding a queued DELETE keeps the local state and only drops the op`() = runBlocking {
        val db = FakeLocalDb()
        db.ops += op("op1", "task_completions", "DELETE", "c1", at = 1)
        val dropped = discards(db).discard("op1")
        assertEquals(1, dropped.size)
        assertEquals(0, db.ops.size)
    }

    @Test
    fun `discarding an op that is no longer queued does nothing`() = runBlocking {
        val db = FakeLocalDb()
        db.ops += op("op1", "tasks", "INSERT", "t1", at = 1)
        assertTrue(discards(db).discard("unknown").isEmpty())
        assertNotNull(db.ops.singleOrNull())
    }
}
