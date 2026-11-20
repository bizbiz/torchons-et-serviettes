package com.torchons.app.core.sync

import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.entities.PendingOperationEntity
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response

/**
 * D4 (2026-11-20 audit), the worker side: votes were pushed BEFORE the /sync batch and a 4xx
 * dropped them silently, so a vote on a task created offline got a 404 and vanished. D3 and D6
 * add the effort vote, the affinity vote and the prompt answer to the same queue.
 *
 * Paths by which "a queued REST op is sent after its task, and a refusal is never silent":
 *   (1) the pass order: ops only AFTER every /sync batch went (`syncThenVotes`) .......... COVERED
 *   (2) a /sync batch that failed: ops are NOT pushed (their task may be stuck) ......... COVERED
 *   (3) 2xx: out of the queue ........................................................... COVERED
 *   (4) permanent 4xx: out of the queue AND reported as refused (notice) ................ COVERED
 *   (5) 5xx / timeout / network: stays queued with the cause ........................... COVERED
 *   (6) unreadable payload: dropped, nothing can be sent ................................ COVERED
 *   (7) payload shapes: every_n_days, days_per_month, points, score, a prompt answer ..... COVERED
 *   (8) cancellation: propagates (a pass replaced by requestSync is not a failure) ....... COVERED
 *   (9) each queue table reaches ITS route with ITS values (`sendRestOp`) ................ COVERED
 * Not covered: the notice reaching DataStore and the dialog (`SyncWorker.pushVotes` builds it from
 * `refused`: TokenManager + Compose); the real /sync loop (Hilt, Retrofit).
 */
class RestOpsPushTest {
    private class Recorder : VotePushOutcomes {
        val events = mutableListOf<String>()
        override suspend fun sent(op: PendingOperationEntity) { events += "sent:${op.clientOpId}" }
        override suspend fun retry(op: PendingOperationEntity, reason: String) {
            events += "retry:${op.clientOpId}:$reason"
        }
        override suspend fun refused(op: PendingOperationEntity, reason: String) {
            events += "refused:${op.clientOpId}:$reason"
        }
    }

    private fun op(id: String, table: String, payload: String, rowId: String = "task-$id") =
        PendingOperationEntity(
            clientOpId = id, tableName = table, operation = "VOTE", rowId = rowId,
            payloadJson = payload, createdAt = 1L,
        )

    private fun vote(id: String, payload: String = """{"every_n_days":7}""") =
        op(id, FREQUENCY_VOTES_TABLE, payload)

    private fun reply(code: Int): Response<Any> =
        if (code in 200..299) Response.success(Any()) else Response.error(code, "{}".toResponseBody())

    private suspend fun push(
        ops: List<PendingOperationEntity>,
        rec: Recorder,
        send: suspend (String) -> Response<*>,
    ) = pushRestOps(
        ops,
        // What `sendRestOp` does for an unreadable frequency payload: null, nothing to send.
        send = { o ->
            if (o.tableName == FREQUENCY_VOTES_TABLE && frequencyRequestOf(o) == null) null else send(o.rowId)
        },
        outcomes = rec,
        reasonOf = { "http ${it.code()}" },
        reasonOfError = { "error ${it.message}" },
    )

    @Test
    fun `ops go after the sync batches`() = runBlocking {
        val order = mutableListOf<String>()

        val allSent = syncThenVotes(sendOps = { order += "sync"; true }, pushVotes = { order += "votes" })

        assertTrue(allSent)
        assertEquals(listOf("sync", "votes"), order)
    }

    @Test
    fun `ops are not pushed when a sync batch failed`() = runBlocking {
        val order = mutableListOf<String>()

        val allSent = syncThenVotes(sendOps = { order += "sync"; false }, pushVotes = { order += "votes" })

        assertEquals(false, allSent)
        assertEquals(listOf("sync"), order)
    }

    @Test
    fun `each outcome is routed to its handler`() = runBlocking {
        val rec = Recorder()
        val replies = mapOf("task-ok" to 200, "task-gone" to 404, "task-busy" to 503, "task-slow" to 408)

        push(listOf(vote("ok"), vote("gone"), vote("busy"), vote("slow")), rec) { reply(replies.getValue(it)) }

        assertEquals(
            listOf("sent:ok", "refused:gone:http 404", "retry:busy:http 503", "retry:slow:http 408"),
            rec.events,
        )
    }

    @Test
    fun `a network error keeps the op queued with its cause`() = runBlocking {
        val rec = Recorder()
        push(listOf(vote("a")), rec) { throw IOException("no route") }
        assertEquals(listOf("retry:a:error no route"), rec.events)
    }

    @Test
    fun `an unreadable payload is dropped without a request`() = runBlocking {
        val rec = Recorder()
        var requests = 0
        push(listOf(vote("bad", payload = "{not json"), vote("empty", payload = "{}")), rec) { requests++; reply(200) }
        assertEquals(listOf("sent:bad", "sent:empty"), rec.events)
        assertEquals(0, requests)
    }

    @Test
    fun `both frequency payload shapes become a request`() {
        assertEquals(7, frequencyRequestOf(vote("a"))?.everyNDays)
        val monthly = frequencyRequestOf(vote("b", payload = """{"days_per_month":{"1":10,"2":20}}"""))
        assertEquals(mapOf("1" to 10, "2" to 20), monthly?.daysPerMonth)
    }

    @Test
    fun `cancellation propagates and leaves the op queued`() = runBlocking {
        val rec = Recorder()
        try {
            push(listOf(vote("a")), rec) { throw CancellationException("replaced") }
            fail("expected the cancellation to propagate")
        } catch (_: CancellationException) {
            assertTrue(rec.events.isEmpty())
        }
    }

    // ---- sendRestOp: the queue table decides the route, the payload the values ----

    /** An ApiService whose calls are recorded as "name(args)" and answered with 200. */
    private fun recordingApi(calls: MutableList<String>): ApiService =
        Proxy.newProxyInstance(ApiService::class.java.classLoader, arrayOf(ApiService::class.java)) { _, method, args ->
            // Suspend functions get their Continuation as the last argument: not part of the call.
            val real = args?.dropLast(1).orEmpty()
            calls += "${method.name}(${real.joinToString()})"
            Response.success(Any())
        } as ApiService

    @Test
    fun `an effort op reaches setEffort with its points`() = runBlocking {
        val calls = mutableListOf<String>()
        val resp = sendRestOp(recordingApi(calls), op("e", EFFORT_VOTES_TABLE, """{"points":7}""", rowId = "t1"))
        assertNotNull("an effort op is replayed by the worker", resp)
        assertTrue(resp!!.isSuccessful)
        assertEquals(listOf("setEffort(t1, {points=7})"), calls)
    }

    @Test
    fun `an affinity op reaches setAffinity with its score`() = runBlocking {
        val calls = mutableListOf<String>()
        sendRestOp(recordingApi(calls), op("a", AFFINITY_VOTES_TABLE, """{"score":4}""", rowId = "t1"))
        assertEquals(listOf("setAffinity(t1, AffinityRequest(score=4))"), calls)
    }

    @Test
    fun `a prompt answer keeps its whole numbers`() = runBlocking {
        val calls = mutableListOf<String>()
        val payload = """{"resolution":"ANSWERED","payload":{"days_per_month":{"1":7,"2":7}}}"""
        sendRestOp(recordingApi(calls), op("p", PROMPT_RESOLUTIONS_TABLE, payload, rowId = "prompt-1"))
        // 7, not 7.0: the server reads the per-month map as integers.
        val request = "PromptResolveRequest(resolution=ANSWERED, payload={days_per_month={1=7, 2=7}})"
        assertEquals(listOf("resolvePrompt(prompt-1, $request)"), calls)
    }

    @Test
    fun `an unreadable payload sends nothing, for every table`() = runBlocking {
        val calls = mutableListOf<String>()
        for (table in REST_OP_TABLES) {
            assertNull(table, sendRestOp(recordingApi(calls), op("x", table, "{not json")))
        }
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `a table that is not a REST op is never sent`() = runBlocking {
        val calls = mutableListOf<String>()
        assertNull(sendRestOp(recordingApi(calls), op("x", "tasks", """{"points":1}""")))
        assertTrue(calls.isEmpty())
    }
}
