package com.torchons.app.data.repository

import com.torchons.app.data.api.ApiHttpException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response

/**
 * D4 (2026-11-20 audit): a refused frequency vote was queued and reported as a success, then
 * dropped silently by the worker (a vote on a task created offline got a 404 and vanished).
 *
 * Paths by which "a vote is either sent, or queued for a retry that can succeed, or refused LOUDLY":
 *   (1) 2xx: sent, history refreshed, nothing queued ......................................... COVERED
 *   (2) permanent 4xx (404 task gone, 409 locked, 422 invalid): failure the caller shows,
 *       nothing queued ......................................................................... COVERED
 *   (3) 5xx, 408, 429: transient, queued, success ........................................... COVERED
 *   (4) network exception: queued, success ................................................... COVERED
 *   (5) the task still has an op queued (created offline): not sent at all, queued .......... COVERED
 *   (6) the caller is cancelled during the request: propagates, nothing queued ............. COVERED
 * Not covered: `TaskRepository.voteFrequency` wiring (Hilt, Retrofit: four lambdas into this
 * function); that the callers display the failure (Compose/ViewModels, checked by reading:
 * TaskDetailViewModel, TaskEditViewModel, HomeViewModel/HomePromptActions, saveRatingData).
 */
class SendOrQueueTest {
    private class Spy(var queued: Int = 0, var sent: Int = 0, var onSent: Int = 0)

    private fun reply(code: Int): Response<Any> =
        if (code in 200..299) Response.success(Any()) else Response.error(code, "{}".toResponseBody())

    private suspend fun vote(spy: Spy, queuedOp: Boolean = false, send: suspend () -> Response<*>) =
        sendOrQueue(
            taskHasQueuedOp = { queuedOp },
            send = { spy.sent++; send() },
            queue = { spy.queued++ },
            onSent = { spy.onSent++ },
        )

    @Test
    fun `an accepted vote is sent and the history refreshed`() = runBlocking {
        val spy = Spy()
        assertTrue(vote(spy) { reply(200) }.isSuccess)
        assertEquals(listOf(1, 0, 1), listOf(spy.sent, spy.queued, spy.onSent))
    }

    @Test
    fun `a permanent refusal is a failure and is not queued`() = runBlocking {
        for (code in listOf(400, 403, 404, 409, 422)) {
            val spy = Spy()
            val result = vote(spy) { reply(code) }
            assertTrue("$code", result.isFailure)
            assertEquals(code, (result.exceptionOrNull() as ApiHttpException).httpCode)
            assertEquals("$code queued", 0, spy.queued)
            assertEquals("$code refreshed", 0, spy.onSent)
        }
    }

    @Test
    fun `server errors, timeouts and rate limits are queued as a success`() = runBlocking {
        for (code in listOf(500, 502, 503, 408, 429)) {
            val spy = Spy()
            assertTrue("$code", vote(spy) { reply(code) }.isSuccess)
            assertEquals("$code", 1, spy.queued)
        }
    }

    @Test
    fun `a network failure is queued as a success`() = runBlocking {
        val spy = Spy()
        assertTrue(vote(spy) { throw IOException("offline") }.isSuccess)
        assertEquals(1, spy.queued)
    }

    @Test
    fun `a task whose own op is still queued gets its vote queued without a request`() = runBlocking {
        val spy = Spy()
        assertTrue(vote(spy, queuedOp = true) { reply(404) }.isSuccess)
        assertEquals(listOf(0, 1), listOf(spy.sent, spy.queued))
    }

    @Test
    fun `cancellation during the request propagates and queues nothing`() = runBlocking {
        val spy = Spy()
        try {
            vote(spy) { throw CancellationException("screen left") }
            fail("expected the cancellation to propagate")
        } catch (_: CancellationException) {
            assertEquals(0, spy.queued)
        }
    }

    @Test
    fun `only 4xx other than 408 and 429 are permanent`() {
        assertTrue(isPermanentRefusal(404))
        assertTrue(isPermanentRefusal(400))
        assertTrue(isPermanentRefusal(499))
        assertFalse(isPermanentRefusal(408))
        assertFalse(isPermanentRefusal(429))
        assertFalse(isPermanentRefusal(500))
        assertFalse(isPermanentRefusal(200))
    }
}
