package com.torchons.app.data.repository

import com.torchons.app.data.local.entities.CompletionEntity
import com.torchons.app.data.local.entities.PendingOperationEntity
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The write of a solo completion (CompletionLedger): measured in prod on 2026-11-19, a user tapped
 * « Fait par moi aujourd'hui » 3 or 4 times and a single op ever reached the server.
 *
 * PATHS by which « each tap that should count reaches Room AND the queue, once » can break:
 *   (1) the caller leaves (its scope is cancelled) right after the tap ............ COVERED
 *   (2) two or more taps in the same instant: check, row and op not atomic ....... COVERED
 *   (3) a duplicate outside the transaction's view (second tap after the first) ... COVERED
 *   (4) another member, or outside the window: must be written ................... COVERED
 *   (5) the sync requested before the commit, or for a write that did not happen .. COVERED
 *   (6) the real Room transaction (`withTransaction`): not covered, no Room in JVM tests
 *       (app/build.gradle.kts); the fake store below serialises its transactions the way
 *       SQLite does, and the contract is that one transaction holds check + row + op.
 */
class CompletionLedgerTest {

    /** A store in memory whose transactions exclude each other, like SQLite's single writer. */
    private class FakeStore(private val gapSeconds: Long? = 3600) : CompletionStore {
        val completions = mutableListOf<CompletionEntity>()
        val ops = mutableListOf<PendingOperationEntity>()
        private val lock = Mutex()

        /** When set, a transaction waits for it after reading, before writing: the widest race window. */
        var gate: CompletableDeferred<Unit>? = null

        /** Completed when a transaction has read the completions and reached the gate. */
        val entered = CompletableDeferred<Unit>()

        override suspend fun <T> atomically(block: suspend () -> T): T = lock.withLock { block() }
        override suspend fun duplicateGapSeconds(taskId: String) = gapSeconds
        override suspend fun completionsOf(taskId: String): List<CompletionEntity> {
            val seen = completions.filter { it.taskId == taskId }
            entered.complete(Unit)
            gate?.await()
            kotlinx.coroutines.delay(20) // between the read and the write: where an unguarded second tap slips in
            return seen
        }
        override suspend fun insertCompletion(entity: CompletionEntity) { completions += entity }
        override suspend fun enqueue(op: PendingOperationEntity) { ops += op }
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val now = Instant.parse("2026-11-19T10:00:00Z")

    private fun ledger(store: CompletionStore, syncs: AtomicInteger = AtomicInteger()) =
        CompletionLedger(store, appScope, requestSync = { syncs.incrementAndGet() }, now = { now })

    private suspend fun CompletionLedger.tap(taskId: String = "towels", by: String? = null, at: String? = null) =
        complete(taskId, completedAt = at, completedBy = by, notes = null, currentUserId = "me", foyerId = "home")

    @Test
    fun `the write lands even when the caller is cancelled right after the tap`() = runBlocking {
        val store = FakeStore()
        val syncs = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        store.gate = gate // the transaction is still open when the caller goes away
        val caller = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val job = caller.launch { ledger(store, syncs).tap() }
        withTimeout(5_000) { store.entered.await() } // the write is under way...
        // ...and the screen is left: its scope is cancelled before the transaction can finish.
        caller.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(0, store.completions.size)

        gate.complete(Unit)
        withTimeout(5_000) { while (store.ops.isEmpty()) kotlinx.coroutines.delay(10) }

        assertEquals(1, store.completions.size)
        assertEquals(1, store.ops.size)
        assertEquals("task_completions", store.ops[0].tableName)
        assertEquals("INSERT", store.ops[0].operation)
        assertEquals(store.completions[0].id, store.ops[0].rowId)
        assertEquals("home", store.ops[0].foyerId)
        withTimeout(5_000) { while (syncs.get() == 0) kotlinx.coroutines.delay(10) }
        assertEquals(1, syncs.get())
    }

    @Test
    fun `simultaneous taps on one task leave one row and one op`() = runBlocking {
        val store = FakeStore()
        val syncs = AtomicInteger()
        val ledger = ledger(store, syncs)

        val results = (1..5).map { async(Dispatchers.Default) { ledger.tap() } }.awaitAll()

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(4, results.count { it.exceptionOrNull() is DuplicateCompletionException })
        assertEquals(1, store.completions.size)
        assertEquals(1, store.ops.size)
        assertEquals(1, syncs.get())
    }

    @Test
    fun `a second tap after the first is refused and names the first completion`() = runBlocking {
        val store = FakeStore()
        val ledger = ledger(store)

        val first = ledger.tap().getOrThrow()
        val second = ledger.tap()

        val refusal = second.exceptionOrNull() as DuplicateCompletionException
        assertEquals(first.id, refusal.twin.id)
        assertEquals(1, store.completions.size)
        assertEquals(1, store.ops.size)
    }

    @Test
    fun `another member, or outside the window, is written`() = runBlocking {
        val store = FakeStore(gapSeconds = 3600)
        val ledger = ledger(store)

        ledger.tap(by = "me").getOrThrow()
        ledger.tap(by = "alice").getOrThrow() // another member
        ledger.tap(by = "me", at = "2026-11-19T08:30:00Z").getOrThrow() // 1 h 30 earlier
        assertEquals(3, store.completions.size)
        assertEquals(3, store.ops.size)
    }

    @Test
    fun `the optimistic row is credited to the current user like the server does`() = runBlocking {
        val store = FakeStore()
        val done = ledger(store).tap().getOrThrow()
        assertEquals("me", done.completedBy)
        assertEquals("me", store.completions[0].completedBy)
        assertEquals(now.toString(), store.completions[0].completedAt)
    }

    @Test
    fun `a refused duplicate requests no sync`() = runBlocking {
        val store = FakeStore()
        val syncs = AtomicInteger()
        val ledger = ledger(store, syncs)
        ledger.tap().getOrThrow()
        ledger.tap()
        assertEquals(1, syncs.get())
    }
}
