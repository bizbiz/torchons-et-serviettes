package com.torchons.app.data.repository

import com.torchons.app.data.local.entities.CompletionEntity
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * LocalMutation: the one write path of every offline-first mutation (2026-11-20 audit of 72ae485:
 * "write Room, then enqueue" as two suspend steps in the ViewModel scope lost the op, or the
 * change, when the user left the screen between them).
 *
 * PATHS by which « the local change and its op both land, or neither » can break, from the
 * callers of the old `pendingDao.insert` (TaskRepository.enqueue x7, DelegationRepository.enqueue
 * x2, CompletionLedger x1):
 *   (1) the caller is cancelled between the local write and the enqueue ........... COVERED
 *   (2) the enqueue (or anything later in the block) throws after the local write .. COVERED
 *   (3) the block itself throws after enqueuing ..................................... COVERED
 *   (4) the sync requested before the commit, or for a mutation that queued nothing . COVERED
 *   (5) the household read inside the transaction instead of before it ............. COVERED
 *   (6) a block that queues several ops: all land, ONE sync ......................... COVERED
 *   (7) the real Room transaction (`withTransaction`) and a refresh reading the queue meanwhile:
 *       not covered, no Room in JVM tests (app/build.gradle.kts). FakeLocalDb serialises its
 *       transactions and rolls back like SQLite does; the contract is that one block is one
 *       transaction, which is a reading of RoomOpStore (one line), not something a JVM test sees.
 */
class LocalMutationTest {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class Counter {
        val syncs = AtomicInteger()
    }

    private fun mutation(db: FakeLocalDb, counter: Counter = Counter(), foyer: suspend () -> String? = { "home" }) =
        LocalMutation(
            store = db,
            scope = appScope,
            requestSync = { db.events += "sync"; counter.syncs.incrementAndGet() },
            foyerId = foyer,
        )

    private val row = "row-1"

    /** The shape of every mutation: a local write, then the op that carries it. */
    private suspend fun MutationScope.localThenOp(db: FakeLocalDb) {
        db.saveCompletion(completionRow())
        enqueue("task_completions", "INSERT", row, mapOf("task_id" to "t"))
    }

    private fun completionRow() = CompletionEntity(
        id = row, taskId = "t", completedBy = "me", completedAt = "2026-11-20T10:00:00Z",
        pointsEarned = 1, notes = null,
    )

    @Test
    fun `the local change and its op both land when the caller is cancelled between them`() = runBlocking {
        val db = FakeLocalDb()
        val counter = Counter()
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val caller = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val job = caller.launch {
            mutation(db, counter).run {
                db.saveCompletion(completionRow())
                entered.complete(Unit)
                gate.await() // the screen is left HERE: local row written, op not yet queued
                enqueue("task_completions", "INSERT", row, mapOf("task_id" to "t"))
            }
        }
        withTimeout(5_000) { entered.await() }
        caller.coroutineContext[Job]!!.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals("the op is not queued yet", 0, db.ops.size)

        gate.complete(Unit)
        val arrived = withTimeoutOrNull(3_000) { while (counter.syncs.get() == 0) delay(10); true }
        assertEquals("the op and its sync must still arrive", true, arrived)

        assertEquals(listOf(row), db.completions.keys.toList())
        assertEquals(1, db.ops.size)
        assertEquals(row, db.ops[0].rowId)
        assertEquals(1, counter.syncs.get())
    }

    @Test
    fun `a failure when queuing leaves neither the local change nor an op`() = runBlocking {
        val db = FakeLocalDb()
        val counter = Counter()
        db.failOnEnqueue = IllegalStateException("queue write failed")

        try {
            mutation(db, counter).run { localThenOp(db) }
            fail("the failure must reach the caller")
        } catch (e: IllegalStateException) {
            assertEquals("queue write failed", e.message)
        }

        assertEquals(0, db.completions.size)
        assertEquals(0, db.ops.size)
        assertEquals(0, counter.syncs.get())
        assertEquals(listOf("begin", "rollback"), db.events.toList())
    }

    @Test
    fun `a block that throws after queuing rolls the op back with the local change`() = runBlocking {
        val db = FakeLocalDb()
        val counter = Counter()

        try {
            mutation(db, counter).run {
                localThenOp(db)
                error("refused after the fact")
            }
            fail("the failure must reach the caller")
        } catch (e: IllegalStateException) {
            assertEquals("refused after the fact", e.message)
        }

        assertEquals(0, db.completions.size)
        assertEquals(0, db.ops.size)
        assertEquals(0, counter.syncs.get())
    }

    @Test
    fun `the sync is requested after the commit and only then`() = runBlocking {
        val db = FakeLocalDb()
        mutation(db).run { localThenOp(db) }
        assertEquals(listOf("begin", "commit", "sync"), db.events.toList())
    }

    @Test
    fun `a block that queues nothing requests no sync`() = runBlocking {
        val db = FakeLocalDb()
        val counter = Counter()
        val result = mutation(db, counter).run { 42 }
        assertEquals(42, result)
        assertEquals(0, counter.syncs.get())
        assertEquals(listOf("begin", "commit"), db.events.toList())
    }

    @Test
    fun `the household is read before the transaction and stamped on the op`() = runBlocking {
        val db = FakeLocalDb()
        val mutation = mutation(db, foyer = { db.events += "foyer"; "home-7" })
        mutation.run { localThenOp(db) }
        assertEquals(listOf("foyer", "begin", "commit", "sync"), db.events.toList())
        assertEquals("home-7", db.ops.single().foyerId)
    }

    @Test
    fun `several ops in one block all land with one sync`() = runBlocking {
        val db = FakeLocalDb()
        val counter = Counter()
        mutation(db, counter).run {
            enqueue("task_completions", "DELETE", "a", null)
            enqueue("task_completions", "DELETE", "b", null)
            enqueue("task_completions", "DELETE", "c", null)
        }
        assertEquals(listOf("a", "b", "c"), db.ops.map { it.rowId })
        assertEquals(3, db.ops.map { it.clientOpId }.toSet().size)
        assertEquals("null", db.ops[0].payloadJson) // a DELETE carries no payload, stored as before
        assertEquals(1, counter.syncs.get())
    }
}
