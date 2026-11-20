package com.torchons.app.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.torchons.app.core.sync.settleApplied
import com.torchons.app.data.local.AppDatabase
import com.torchons.app.data.local.completionRow
import com.torchons.app.data.local.newInMemoryDb
import com.torchons.app.data.local.pendingOp
import com.torchons.app.data.local.taskRow
import com.torchons.app.data.model.SyncAppliedOp
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The offline-first write path and the worker's settling on a REAL Room transaction (the "no Room
 * in JVM tests" gap noted in LocalMutationTest, CompletionLedgerTest, CompletionRefreshGuardTest).
 *
 * Paths by which "row and op land together, once, and a settled queue is never half-settled" can
 * break:
 *   (1) the block throws after writing the row and the op: both must roll back ......... COVERED
 *   (2) a block that succeeds: both land, one sync after the commit ..................... COVERED
 *   (3) concurrent completions of one task: check + row + op in one real transaction ... COVERED
 *   (4) discarding a task INSERT: its row, its dependents' rows and ops go together ..... COVERED
 *   (5) settleApplied: ops deleted, dropped completion removed, both epochs bumped ...... COVERED
 *   (6) settleApplied on a batch with no task/completion op: epochs untouched ............ COVERED
 * Not covered: a crash between statements (Room's rollback on a process kill is SQLite's, not
 * ours); the SyncWorker loop (Hilt, Retrofit).
 */
@RunWith(AndroidJUnit4::class)
class RoomTransactionsTest {
    private lateinit var db: AppDatabase
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun setUp() { db = newInMemoryDb() }

    @After fun tearDown() { db.close() }

    private fun mutation(syncs: AtomicInteger = AtomicInteger()) = LocalMutation(
        RoomOpStore(db, db.pendingOperationDao()), appScope, { syncs.incrementAndGet() }, foyerId = { "home" },
    )

    @Test
    fun `an exception in the block rolls back the row AND the op`() = runBlocking {
        val syncs = AtomicInteger()

        val outcome = runCatching {
            mutation(syncs).run {
                db.taskDao().upsert(taskRow("t1"))
                enqueue("tasks", "INSERT", "t1", mapOf("title" to "x"))
                error("boom after the op")
            }
        }

        assertTrue(outcome.exceptionOrNull() is IllegalStateException)
        assertNull(db.taskDao().getById("t1"))
        assertEquals(emptyList<Any>(), db.pendingOperationDao().getAll())
        assertEquals(0, syncs.get())
    }

    @Test
    fun `a successful block lands the row and the op together and requests one sync`() = runBlocking {
        val syncs = AtomicInteger()

        mutation(syncs).run {
            db.taskDao().upsert(taskRow("t1"))
            enqueue("tasks", "INSERT", "t1", mapOf("title" to "x"))
        }

        assertEquals("task t1", db.taskDao().getById("t1")?.title)
        val ops = db.pendingOperationDao().getAll()
        assertEquals(listOf("t1"), ops.map { it.rowId })
        assertEquals("home", ops.single().foyerId)
        assertEquals(1, syncs.get())
    }

    @Test
    fun `simultaneous completions of one task leave one row and one op`() = runBlocking {
        db.taskDao().upsert(taskRow("towels"))
        val syncs = AtomicInteger()
        val ledger = CompletionLedger(
            RoomCompletionStore(db, db.taskDao(), db.completionDao(), db.pendingOperationDao()),
            appScope,
            requestSync = { syncs.incrementAndGet() },
        )

        val results = (1..6).map {
            async(Dispatchers.Default) {
                ledger.complete("towels", null, null, null, currentUserId = "me", foyerId = "home")
            }
        }.awaitAll()

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, db.completionDao().observeRecent().first().size)
        assertEquals(listOf("task_completions"), db.pendingOperationDao().getAll().map { it.tableName })
    }

    @Test
    fun `discarding a task INSERT removes its row, its completions' rows and their ops`() = runBlocking {
        db.taskDao().upsertAll(listOf(taskRow("new"), taskRow("other")))
        db.completionDao().upsertAll(listOf(completionRow("c-new", taskId = "new"), completionRow("c-other", "other")))
        val dao = db.pendingOperationDao()
        dao.insert(pendingOp("op-task", "tasks", "INSERT", "new", createdAt = 1))
        dao.insert(
            pendingOp(
                "op-comp", "task_completions", "INSERT", "c-new",
                payloadJson = """{"task_id":"new"}""", createdAt = 2,
            ),
        )
        dao.insert(
            pendingOp(
                "op-keep", "task_completions", "INSERT", "c-other",
                payloadJson = """{"task_id":"other"}""", createdAt = 3,
            ),
        )
        val ledger = PendingDiscardLedger(
            RoomPendingDiscardStore(dao, db.taskDao(), db.completionDao()),
            mutation(),
        )

        val dropped = ledger.discard("op-task")

        assertEquals(setOf("op-task", "op-comp"), dropped.map { it.clientOpId }.toSet())
        assertEquals(listOf("op-keep"), dao.getAll().map { it.clientOpId })
        assertNull(db.taskDao().getById("new"))
        assertEquals(setOf("c-other"), db.completionDao().observeRecent().first().map { it.id }.toSet())
    }

    @Test
    fun `settleApplied deletes the ops, removes a dropped completion and bumps both epochs`() = runBlocking {
        val guard = CompletionRefreshGuard()
        db.completionDao().upsertAll(listOf(completionRow("dup")))
        val batch = listOf(
            pendingOp("op-task", "tasks", "UPDATE", "t1"),
            pendingOp("op-dup", "task_completions", "INSERT", "dup"),
            pendingOp("op-later", "tasks", "UPDATE", "t2"),
        )
        batch.forEach { db.pendingOperationDao().insert(it) }
        val tasksBefore = guard.tasks.current()
        val completionsBefore = guard.currentEpoch()

        settleApplied(
            guard,
            listOf(SyncAppliedOp("op-task", "t1", 1), SyncAppliedOp("op-dup", "dup", 0, dropped = true)),
            batch,
            deleteOp = db.pendingOperationDao()::delete,
            deleteCompletionRow = db.completionDao()::deleteById,
        )

        assertEquals(listOf("op-later"), db.pendingOperationDao().getAll().map { it.clientOpId })
        assertNull(db.completionDao().getById("dup"))
        assertNotEquals(tasksBefore, guard.tasks.current())
        assertNotEquals(completionsBefore, guard.currentEpoch())
    }

    @Test
    fun `settleApplied bumps only the epoch of the table it settled`() = runBlocking {
        val guard = CompletionRefreshGuard()
        val batch = listOf(pendingOp("op-del", "task_delegations", "INSERT", "d1"))
        db.pendingOperationDao().insert(batch.single())
        val (tasksBefore, completionsBefore) = guard.tasks.current() to guard.currentEpoch()

        settleApplied(
            guard, listOf(SyncAppliedOp("op-del", "d1", 1)), batch,
            deleteOp = db.pendingOperationDao()::delete, deleteCompletionRow = db.completionDao()::deleteById,
        )

        assertEquals(emptyList<Any>(), db.pendingOperationDao().getAll())
        assertEquals(tasksBefore, guard.tasks.current())
        assertEquals(completionsBefore, guard.currentEpoch())
    }
}
