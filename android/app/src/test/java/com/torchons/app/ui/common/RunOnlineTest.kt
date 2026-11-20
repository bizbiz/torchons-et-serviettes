package com.torchons.app.ui.common

import com.torchons.app.data.api.ApiHttpException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response

/**
 * `runOnline` (D7, 2026-11-20 audit): about a dozen online-only taps did their request inside a
 * `try { ... } catch (_: Exception) {}`, or read no `isSuccessful` at all, so a 4xx / 5xx / network
 * failure left the screen showing a change the server never took, and told nobody.
 *
 * Paths by which "an online-only tap that fails is rolled back AND told":
 *   (1) 2xx: Done with the body, nothing rolled back ........................................ COVERED
 *   (2) an exception (network, timeout): Failed, rolled back, error translated ............. COVERED
 *   (3) a non-2xx answer that Retrofit hands back WITHOUT throwing (4xx, 5xx): Failed, rolled
 *       back, error built from the status ................................................... COVERED
 *   (4) the raw exception message never reaches the error ................................... COVERED
 *   (5) cancellation: propagates, no rollback (the screen is gone) .......................... COVERED
 *   (6) rollback runs BEFORE the error is returned (the caller shows the error on rolled-back state) COVERED
 *   (7) network failure told apart from an answer (`isNetworkFailure`) ..................... COVERED
 * Not covered: the Android translator (`UserErrors.from`, Context + string resources); each call
 * site's use of the result (Compose, ViewModels: read in the audit).
 */
class RunOnlineTest {
    /** The translator of the app, on plain texts: what `UserErrors.from(context, it)` does, minus Android. */
    private val texts = object : UserErrorTexts {
        override fun unreachable() = "server unreachable"
        override fun serverWithCode(errorId: String) = "server error $errorId"
        override fun serverWithStatus(status: Int) = "server error status $status"
        override fun unexpected() = "unexpected"
    }
    private val errorOf: (Throwable) -> UserError = { UserErrors.translate(it, texts) }

    private fun answer(code: Int): Response<String> =
        if (code in 200..299) Response.success("body") else Response.error(code, "{}".toResponseBody())

    @Test
    fun `a 2xx answer is done with its body and rolls nothing back`() = runBlocking {
        var rollbacks = 0
        val result = runOnline(errorOf, rollback = { rollbacks++ }) { answer(200) }
        assertEquals(OnlineResult.Done("body"), result)
        assertNull(result.errorOrNull)
        assertEquals(0, rollbacks)
    }

    @Test
    fun `a network exception rolls back and is told as unreachable`() = runBlocking {
        var rollbacks = 0
        val result = runOnline<String>(errorOf, rollback = { rollbacks++ }) { throw IOException("socket closed") }
        assertEquals(1, rollbacks)
        assertEquals("server unreachable", result.errorOrNull?.message)
    }

    @Test
    fun `a non-2xx answer that was not thrown still rolls back and is told`() = runBlocking {
        for (code in listOf(400, 403, 404, 409, 422, 500, 503)) {
            var rollbacks = 0
            val seen = mutableListOf<Throwable>()
            val result = runOnline(
                errorOf = { seen += it; errorOf(it) },
                rollback = { rollbacks++ },
            ) { answer(code) }
            assertEquals("$code rollbacks", 1, rollbacks)
            assertEquals("$code error", "server error status $code", result.errorOrNull?.message)
            assertEquals("$code reportable", true, result.errorOrNull?.reportable)
            assertEquals("$code carried as", code, (seen.single() as ApiHttpException).httpCode)
        }
    }

    @Test
    fun `the raw exception message never becomes the error`() = runBlocking {
        val result = runOnline<String>(errorOf) { throw IllegalStateException("NullPointer at Foo.kt:12") }
        assertEquals("unexpected", result.errorOrNull?.message)
        assertFalse(result.errorOrNull!!.message.contains("Foo.kt"))
    }

    @Test
    fun `the rollback has run when the error is returned`() = runBlocking {
        var shown = "optimistic"
        val result = runOnline<String>(errorOf, rollback = { shown = "as before" }) { throw IOException() }
        // What a screen does with the result: show the error over the state the rollback restored.
        assertTrue(result is OnlineResult.Failed)
        assertEquals("as before", shown)
    }

    @Test
    fun `cancellation propagates and rolls nothing back`() = runBlocking {
        var rollbacks = 0
        try {
            runOnline<String>(errorOf, rollback = { rollbacks++ }) { throw CancellationException("screen left") }
            fail("expected the cancellation to propagate")
        } catch (_: CancellationException) {
            assertEquals(0, rollbacks)
        }
    }

    @Test
    fun `a network failure is told apart from a server answer`() {
        assertTrue(IOException("x").isNetworkFailure())
        assertTrue(RuntimeException("wrapped", IOException("x")).isNetworkFailure())
        assertFalse(ApiHttpException(500).isNetworkFailure())
        assertFalse(IllegalStateException("x").isNetworkFailure())
    }
}
