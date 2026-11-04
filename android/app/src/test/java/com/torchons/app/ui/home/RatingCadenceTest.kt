package com.torchons.app.ui.home

import com.torchons.app.data.api.RatingPromptApi
import com.torchons.app.data.api.reportOutcomeSafe
import com.torchons.app.data.model.RatingOutcomeRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * Perimeter: WHICH gesture reports WHICH outcome, and when the pop-up may open
 * (RatingCadence). Paths by which a wrong outcome or a wrong pop-up could occur:
 * - dismissal of an automatic pop-up -> answered=false, once (dismissalReports...)
 * - first rating in an automatic pop-up -> answered=true, once (firstRatingReports...)
 * - ratings chained after the first one -> nothing (chainedRatings...)
 * - pop-up opened by hand -> nothing, even after an automatic one (manualPopup...)
 * - a refresh that hides the pop-up without a gesture -> nothing (hiddenByRefresh...)
 * - not due (server flag false, or just reported locally) -> no new pop-up
 *   (notDueServer..., justReportedStaysClosedForOneDay)
 * - older server without the flag -> due (absentFlagMeansDue)
 * - a pop-up already on screen is not closed by a refresh (popupOnScreenStays...)
 * - failed report: no throw, cancellation propagates (failedReport..., cancellation...)
 * Not covered: the Retrofit call itself and the server rule (backend test_rating_cadence.py),
 * HomeViewModel wiring and Compose, which need Android/Hilt or an emulator.
 * The clock is a variable: no test depends on the time of day.
 */
class RatingCadenceTest {

    private class FakeApi(private val failure: Exception? = null) : RatingPromptApi {
        val sent = mutableListOf<Boolean>()
        override suspend fun reportOutcome(body: RatingOutcomeRequest): Response<Unit> {
            sent += body.answered
            failure?.let { throw it }
            return Response.success(Unit)
        }
    }

    private var clock = 1_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    // Unconfined: the report runs inline, so `sent` is complete when the call returns.
    private fun cadence(api: RatingPromptApi) =
        RatingCadence(api, CoroutineScope(Dispatchers.Unconfined)) { clock }

    /** An automatic pop-up opened by a refresh; returns true if it opened. */
    private fun RatingCadence.openAuto() = resolveAuto(eligible = true, wasShown = false)

    @Test
    fun dismissalReportsNotAnsweredOnce() {
        val api = FakeApi()
        val c = cadence(api)
        assertTrue(c.openAuto())
        c.onDismissed()
        c.onDismissed()
        assertEquals(listOf(false), api.sent)
    }

    @Test
    fun firstRatingReportsAnsweredOnce() {
        val api = FakeApi()
        val c = cadence(api)
        assertTrue(c.openAuto())
        c.onRated()
        c.onRated()
        assertEquals(listOf(true), api.sent)
    }

    @Test
    fun chainedRatingsAndTheirDismissalReportNothing() {
        val api = FakeApi()
        val c = cadence(api)
        c.openAuto()
        c.onRated()            // first rating of the automatic pop-up
        c.onRated()            // "rate the next one" x2
        c.onRated()
        c.onDismissed()        // closing the chain
        assertEquals(listOf(true), api.sent)
    }

    @Test
    fun manualPopupReportsNothing() {
        val api = FakeApi()
        val c = cadence(api)
        c.onManualOpened()
        c.onRated()
        c.onDismissed()
        assertTrue(api.sent.isEmpty())
    }

    @Test
    fun manualPopupAfterAnAutomaticOneReportsNothing() {
        val api = FakeApi()
        val c = cadence(api)
        c.openAuto()
        c.onDismissed()        // outcome 1
        clock += 2 * day
        c.onManualOpened()
        c.onRated()
        c.onDismissed()
        assertEquals(listOf(false), api.sent)
    }

    @Test
    fun popupHiddenByRefreshWithoutGestureReportsNothing() {
        val api = FakeApi()
        val c = cadence(api)
        assertTrue(c.openAuto())
        // a unified prompt took priority: the refresh hides the pop-up
        assertFalse(c.resolveAuto(eligible = false, wasShown = true))
        c.onDismissed()
        assertTrue(api.sent.isEmpty())
    }

    @Test
    fun notDueServerKeepsThePopupClosed() {
        val c = cadence(FakeApi())
        c.syncDue(mapOf("rating_prompt_due" to false))
        assertFalse(c.openAuto())
        c.syncDue(mapOf("rating_prompt_due" to true))
        assertTrue(c.openAuto())
    }

    @Test
    fun absentFlagMeansDue() {
        val c = cadence(FakeApi())
        c.syncDue(mapOf("affinity_popup_enabled" to true))
        assertTrue(c.openAuto())
        c.onDismissed()
        clock += day + 1
        c.syncDue(null)
        assertTrue(c.openAuto())
    }

    @Test
    fun justReportedStaysClosedForOneDay() {
        val c = cadence(FakeApi())
        assertTrue(c.openAuto())
        c.onDismissed()
        // the cached settings still say "due": the local mark wins
        c.syncDue(mapOf("rating_prompt_due" to true))
        assertFalse(c.openAuto())
        clock += day - 1
        assertFalse(c.openAuto())
        clock += 1
        assertTrue(c.openAuto())
    }

    @Test
    fun popupOnScreenStaysEvenIfNoLongerDue() {
        val c = cadence(FakeApi())
        assertTrue(c.openAuto())
        c.syncDue(mapOf("rating_prompt_due" to false))
        assertTrue(c.resolveAuto(eligible = true, wasShown = true))
    }

    @Test
    fun ineligibleNeverOpens() {
        val c = cadence(FakeApi())
        assertFalse(c.resolveAuto(eligible = false, wasShown = false))
    }

    @Test
    fun failedReportDoesNotThrowAndStillClosesTheOutcome() {
        val api = FakeApi(failure = IOException("offline"))
        val c = cadence(api)
        c.openAuto()
        c.onDismissed()
        c.onDismissed()
        assertEquals(listOf(false), api.sent)
        assertFalse(c.openAuto())  // the local mark holds although the report failed
    }

    @Test
    fun safeCallReturnsFalseOnFailure() = runBlocking {
        assertFalse(FakeApi(failure = IOException("offline")).reportOutcomeSafe(true))
        assertTrue(FakeApi().reportOutcomeSafe(true))
    }

    @Test
    fun cancellationPropagatesFromTheSafeCall() = runBlocking {
        try {
            FakeApi(failure = CancellationException("cancelled")).reportOutcomeSafe(true)
            fail("CancellationException must propagate")
        } catch (e: CancellationException) {
            // expected
        }
    }
}
