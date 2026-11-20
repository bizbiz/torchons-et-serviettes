package com.torchons.app.data.repository

import com.torchons.app.data.local.entities.CompletionEntity
import com.torchons.app.data.model.CollectiveCompleteRequest
import com.torchons.app.data.model.CollectiveCompletionResponse
import com.torchons.app.data.model.Completion
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * « Fait ensemble » must reach the end of its sequence (optimistic rows, call, then reconcile or
 * rollback) even when the screen that tapped it is left: a ViewModel scope cancelled between the
 * optimistic insert and the answer used to leave phantom rows the server never got.
 *
 * PATHS by which the cache can end up wrong after a collective tap:
 *   (1) caller cancelled while the call is in flight, then the server accepts ...... COVERED
 *   (2) caller cancelled while the call is in flight, then 409 ..................... COVERED
 *   (3) caller cancelled while the call is in flight, then the network fails ........ COVERED
 *   (4) nothing cancelled: success reconciles, failure rolls back, result is right .. COVERED
 *   (5) the reconcile is a settling: a refresh begun earlier must not be applied ..... COVERED
 *   (6) the real Room transaction and the Retrofit call: no Room/network in JVM tests;
 *       the fakes below stand for them (the contract: submit is a suspend call, the store
 *       transaction is all-or-nothing).
 *   (7) process killed mid-call: not covered; the phantom rows stay until the next
 *       authoritative refresh (replaceAll / replaceForTask) removes them.
 */
class CollectiveCompletionLedgerTest {

    /**
     * Like Room's suspend DAOs, whose calls refuse to start in a cancelled coroutine: a rollback
     * attempted by a cancelled caller does not happen (this is what left phantom rows).
     */
    private class FakeStore : CollectiveStore {
        val rows = java.util.concurrent.ConcurrentHashMap<String, CompletionEntity>()
        private val lock = Mutex()
        val inserted = CompletableDeferred<Unit>()

        override suspend fun <T> atomically(block: suspend () -> T): T {
            currentCoroutineContext().ensureActive()
            return lock.withLock { block() }
        }
        override suspend fun insert(rows: List<CompletionEntity>) {
            currentCoroutineContext().ensureActive()
            rows.forEach { this.rows[it.id] = it }
            inserted.complete(Unit)
        }
        override suspend fun delete(ids: List<String>) {
            currentCoroutineContext().ensureActive()
            ids.forEach { rows.remove(it) }
        }
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ids = AtomicInteger()
    private val participants = listOf("me", "alice")

    private fun serverRows(): List<Completion> = participants.map {
        Completion(
            id = "server-$it", taskId = "towels", completedBy = it, completedAt = "2026-11-20T10:00:00Z",
            pointsEarned = 3, collectiveId = "server-group",
        )
    }

    private fun ok() = Response.success(CollectiveCompletionResponse("server-group", serverRows()))

    private fun refused(code: Int) =
        Response.error<CollectiveCompletionResponse>(code, "{\"detail\":\"x\"}".toResponseBody(null))

    private fun ledger(
        store: CollectiveStore,
        guard: CompletionRefreshGuard = CompletionRefreshGuard(),
        submit: CollectiveSubmit,
    ) = CollectiveCompletionLedger(
        store, submit, appScope, guard,
        now = { Instant.parse("2026-11-20T10:00:00Z") }, newId = { "local-${ids.incrementAndGet()}" },
    )

    /** Taps from a scope that is cancelled once the optimistic rows are in and the call is in flight. */
    private suspend fun tapThenLeave(
        ledger: CollectiveCompletionLedger,
        store: FakeStore,
        calling: CompletableDeferred<Unit>,
    ) {
        val caller = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = caller.launch { ledger.complete("towels", participants, null, null) }
        withTimeout(5_000) { store.inserted.await(); calling.await() }
        assertEquals(participants.size, store.rows.size) // optimistic rows are there
        caller.coroutineContext[Job]!!.cancelAndJoin() // the screen is left
        assertTrue(job.isCancelled)
    }

    private suspend fun waitUntil(condition: () -> Boolean) =
        withTimeoutOrNull(2_000) { while (!condition()) delay(10) } // then the assertion names what is left

    @Test
    fun `the call is reconciled even when the caller leaves right after the tap`() = runBlocking {
        val store = FakeStore()
        val calling = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Response<CollectiveCompletionResponse>>()
        val ledger = ledger(store) { _, _ -> calling.complete(Unit); answer.await() }

        tapThenLeave(ledger, store, calling)
        answer.complete(ok())

        waitUntil { store.rows.keys == setOf("server-me", "server-alice") }
        assertEquals(setOf("server-me", "server-alice"), store.rows.keys) // no optimistic row left
    }

    @Test
    fun `a 409 after the caller left rolls the optimistic rows back`() = runBlocking {
        val store = FakeStore()
        val calling = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Response<CollectiveCompletionResponse>>()
        val ledger = ledger(store) { _, _ -> calling.complete(Unit); answer.await() }

        tapThenLeave(ledger, store, calling)
        answer.complete(refused(409))

        waitUntil { store.rows.isEmpty() }
        assertEquals(emptySet<String>(), store.rows.keys)
    }

    @Test
    fun `a network failure after the caller left rolls the optimistic rows back`() = runBlocking {
        val store = FakeStore()
        val calling = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val ledger = ledger(store) { _, _ -> calling.complete(Unit); gate.await(); throw IOException("offline") }

        tapThenLeave(ledger, store, calling)
        gate.complete(Unit)

        waitUntil { store.rows.isEmpty() }
        assertEquals(emptySet<String>(), store.rows.keys)
    }

    @Test
    fun `without cancellation success returns the body and failure carries the http code`() = runBlocking {
        val okStore = FakeStore()
        val sent = mutableListOf<CollectiveCompleteRequest>()
        val done = ledger(okStore) { _, request -> sent += request; ok() }.complete("towels", participants, null, "n")
        assertEquals("server-group", done.getOrThrow().collectiveId)
        assertEquals(setOf("server-me", "server-alice"), okStore.rows.keys)
        assertEquals(listOf(CollectiveCompleteRequest(participants, null, "n")), sent)

        val failStore = FakeStore()
        val failed = ledger(failStore) { _, _ -> refused(409) }.complete("towels", participants, null, null)
        val error = failed.exceptionOrNull() as CollectiveCompletionException
        assertEquals(409, error.httpCode)
        assertTrue(failStore.rows.isEmpty())
    }

    @Test
    fun `no participant is refused without touching the cache or the network`() = runBlocking {
        val store = FakeStore()
        var calls = 0
        val result = ledger(store) { _, _ -> calls++; ok() }.complete("towels", emptyList(), null, null)
        assertTrue(result.isFailure)
        assertEquals(0, calls)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `the reconcile is a settling that voids a refresh begun before it`() = runBlocking {
        val guard = CompletionRefreshGuard()
        val before = guard.currentEpoch()
        ledger(FakeStore(), guard) { _, _ -> ok() }.complete("towels", participants, null, null).getOrThrow()
        assertTrue(guard.currentEpoch() != before)

        val rolledBack = guard.currentEpoch()
        ledger(FakeStore(), guard) { _, _ -> refused(409) }.complete("towels", participants, null, null)
        assertEquals(rolledBack, guard.currentEpoch()) // a rollback adds nothing a stale refresh could erase
    }
}
