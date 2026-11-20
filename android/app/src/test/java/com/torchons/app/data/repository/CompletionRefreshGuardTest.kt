package com.torchons.app.data.repository

import com.torchons.app.core.sync.applyServerChanges
import com.torchons.app.core.sync.settleApplied
import com.torchons.app.data.local.entities.PendingOperationEntity
import com.torchons.app.data.model.ChangeLogEntry
import com.torchons.app.data.model.SyncAppliedOp
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S2: a completions refresh whose request started before a push, applied after it, erased the
 * completion just pushed (the guard's KDoc tells the sequence). Pinned through the real
 * [CompletionRefreshGuard] and the real [settleApplied] / [applyServerChanges] the worker calls;
 * the cache, the queue and the server are the small model of [Phone].
 *
 * PATHS by which a refresh can delete (or resurrect) a row it should not:
 *   (1) request before the commit, applied after the op was settled ............... COVERED
 *   (2) no push in between: REPLACE semantics, server-deleted rows still go ........ COVERED
 *   (3) the retry is stale too: nothing is applied, the cache is left alone ........ COVERED
 *   (4) the worker must not wait for a request in flight ............................ COVERED
 *   (5) a batch without a completion op must not void refreshes ..................... COVERED
 *   (6) completions the worker receives from another device .......................... COVERED
 *   (7) the DAO's SQL (`replaceAll`, `replaceForTask`) and the Room transaction: no Room in
 *       JVM tests; [Phone.replaceAll] mirrors them (non-pending rows deleted, server rows that are
 *       not pending upserted), as `serverRowsToApply` does.
 *   (8) `TaskRepository.fetchAndApplyCompletions` and the real worker loop: not covered
 *       (Hilt, Retrofit); each is one call into the code tested here.
 */
class CompletionRefreshGuardTest {

    /** The Room cache and its queue, and the server's rows. */
    private class Phone {
        val cache = linkedSetOf<String>()
        val pending = linkedSetOf<String>()
        val server = linkedSetOf<String>()

        @Synchronized
        fun replaceAll(fromServer: List<String>) {
            cache.removeAll { it !in pending }
            cache += fromServer.filter { it !in pending }
        }

        @Synchronized
        fun serverSnapshot(): List<String> = server.toList()
    }

    private fun op(id: String, table: String, row: String) = PendingOperationEntity(
        clientOpId = id, tableName = table, operation = "INSERT", rowId = row,
        payloadJson = "{}", createdAt = 0L,
    )

    private suspend fun CompletionRefreshGuard.refresh(phone: Phone, fetch: suspend () -> List<String>) =
        fetchThenApply(fetch = fetch, apply = { phone.replaceAll(it) })

    private suspend fun CompletionRefreshGuard.pushSettled(phone: Phone, row: String) =
        settleApplied(
            this, listOf(SyncAppliedOp("op-$row", row, 1)), listOf(op("op-$row", "task_completions", row)),
            deleteOp = { phone.pending.remove(row) }, deleteCompletionRow = {},
        )

    @Test
    fun `a refresh answered before a push and applied after it keeps the pushed row`() = runBlocking {
        val guard = CompletionRefreshGuard()
        val phone = Phone().apply { cache += "mine"; pending += "mine" }
        val requestSent = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fetches = AtomicInteger()

        val refresh = async(Dispatchers.Default) {
            guard.refresh(phone) {
                val answer = phone.serverSnapshot() // what the server has when it answers
                if (fetches.incrementAndGet() == 1) { requestSent.complete(Unit); release.await() }
                answer
            }
        }
        withTimeout(5_000) { requestSent.await() }
        // The server answered without the row; now /sync commits it and the worker settles the op.
        phone.server += "mine"
        guard.pushSettled(phone, "mine")
        release.complete(Unit) // the stale answer reaches the phone

        withTimeout(5_000) { refresh.await() }
        assertEquals(setOf("mine"), phone.cache)
        assertEquals(2, fetches.get()) // dropped, then asked again
    }

    @Test
    fun `a refresh with no push in between still deletes what the server deleted`() = runBlocking {
        val guard = CompletionRefreshGuard()
        val phone = Phone().apply { cache += listOf("kept", "deleted-elsewhere"); server += "kept" }
        val fetches = AtomicInteger()

        val result = guard.refresh(phone) { fetches.incrementAndGet(); phone.serverSnapshot() }

        assertEquals(listOf("kept"), result)
        assertEquals(setOf("kept"), phone.cache)
        assertEquals(1, fetches.get())
    }

    @Test
    fun `a row whose op is still pending survives a refresh`() = runBlocking {
        val guard = CompletionRefreshGuard()
        val phone = Phone().apply { cache += "queued"; pending += "queued" }
        guard.refresh(phone) { phone.serverSnapshot() }
        assertEquals(setOf("queued"), phone.cache)
    }

    @Test
    fun `when the retry is stale too nothing is applied and the cache is left alone`() = runBlocking {
        val guard = CompletionRefreshGuard()
        val phone = Phone().apply { cache += "mine" }
        var applied = 0
        val fetches = AtomicInteger()

        val result = guard.fetchThenApply(
            fetch = { fetches.incrementAndGet(); guard.settling { }; phone.serverSnapshot() },
            apply = { applied++ },
        )

        assertNull(result)
        assertEquals(2, fetches.get())
        assertEquals(0, applied)
        assertEquals(setOf("mine"), phone.cache)
    }

    @Test
    fun `the worker settles an op while a request is still in flight`() = runBlocking {
        val guard = CompletionRefreshGuard()
        val phone = Phone().apply { cache += "mine"; pending += "mine"; server += "mine" }
        val requestSent = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>() // the network: does not answer during this test

        val refresh = async(Dispatchers.Default) {
            guard.refresh(phone) { requestSent.complete(Unit); never.await(); phone.serverSnapshot() }
        }
        withTimeout(5_000) { requestSent.await() }

        withTimeout(2_000) { guard.pushSettled(phone, "mine") } // would time out if it queued behind the GET

        assertTrue(phone.pending.isEmpty())
        never.complete(Unit)
        withTimeout(5_000) { refresh.await() }
        Unit
    }

    @Test
    fun `a batch without a completion op does not void refreshes`() = runBlocking {
        val guard = CompletionRefreshGuard()
        val before = guard.currentEpoch()
        val deleted = mutableListOf<String>()

        settleApplied(
            guard, listOf(SyncAppliedOp("op-t", "t1", 1)), listOf(op("op-t", "tasks", "t1")),
            deleteOp = { deleted += it }, deleteCompletionRow = {},
        )

        assertEquals(listOf("op-t"), deleted)
        assertEquals(before, guard.currentEpoch())
    }

    @Test
    fun `a dropped duplicate loses its row after its op, inside the settling`() = runBlocking {
        val guard = CompletionRefreshGuard()
        val order = CopyOnWriteArrayList<String>()
        val before = guard.currentEpoch()

        settleApplied(
            guard, listOf(SyncAppliedOp("op-1", "row-1", 0, dropped = true)),
            listOf(op("op-1", "task_completions", "row-1")),
            deleteOp = { order += "op:$it" }, deleteCompletionRow = { order += "row:$it" },
        )

        assertEquals(listOf("op:op-1", "row:row-1"), order)
        assertTrue(guard.currentEpoch() != before)
    }

    @Test
    fun `completions received from another device void refreshes, other changes do not`() = runBlocking {
        val guard = CompletionRefreshGuard()
        val applied = mutableListOf<String>()
        fun change(table: String, row: String) = ChangeLogEntry(1, table, row, "INSERT", null, "2026-11-20T10:00:00Z")

        val epoch0 = guard.currentEpoch()
        applyServerChanges(guard, listOf(change("tasks", "t1"))) { applied += it.rowId }
        assertEquals(epoch0, guard.currentEpoch())

        val mixed = listOf(change("tasks", "t1"), change("task_completions", "c1"))
        applyServerChanges(guard, mixed) { applied += it.rowId }
        assertTrue(guard.currentEpoch() != epoch0)
        assertEquals(listOf("t1", "t1", "c1"), applied)
    }

    @Test
    fun `an apply that fails still counts as a change`() = runBlocking {
        val guard = CompletionRefreshGuard()
        val before = guard.currentEpoch()
        runCatching { guard.settling { error("disk full") } }
        assertTrue(guard.currentEpoch() != before)
        assertFalse(guard.applyIfUnchanged(before) { })
    }
}
