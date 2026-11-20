package com.torchons.app.ui.home

import com.torchons.app.data.api.ApiHttpException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D3 (2026-11-20 audit), the rating pop-up: `saveRatingData` read none of its answers, so
 * « Notes enregistrées » showed on a 4xx / 5xx. Each vote now returns a Result and the steps
 * stop at the first refusal.
 *
 * Paths by which "saved is only said when every vote was taken":
 *   (1) every step ok (sent or queued): success, steps in order affinity, effort, frequency .. COVERED
 *   (2) a step refused: that failure is the result, the later steps are NOT run ................. COVERED
 *   (3) no step at all (no frequency vote asked): still a success ................................ COVERED
 * Not covered: `saveRatingData`'s own wiring to VoteRepository / TaskRepository (Hilt).
 */
class RatingSaveTest {
    @Test
    fun `every step ok runs them in order and succeeds`() = runBlocking {
        val ran = mutableListOf<String>()
        val result = runRatingSteps(
            listOf(
                { ran += "affinity"; Result.success(Unit) },
                { ran += "effort"; Result.success(Unit) },
                { ran += "frequency"; Result.success(Unit) },
            ),
        )
        assertTrue(result.isSuccess)
        assertEquals(listOf("affinity", "effort", "frequency"), ran)
    }

    @Test
    fun `a refused step is the result and the later steps do not run`() = runBlocking {
        val ran = mutableListOf<String>()
        val refusal = ApiHttpException(422)
        val result = runRatingSteps(
            listOf(
                { ran += "affinity"; Result.success(Unit) },
                { ran += "effort"; Result.failure(refusal) },
                { ran += "frequency"; Result.success(Unit) },
            ),
        )
        assertTrue(result.isFailure)
        assertEquals(refusal, result.exceptionOrNull())
        assertEquals(listOf("affinity", "effort"), ran)
    }

    @Test
    fun `no step is a success`() = runBlocking {
        assertTrue(runRatingSteps(emptyList()).isSuccess)
    }
}
