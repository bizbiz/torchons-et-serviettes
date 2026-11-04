package com.torchons.app.ui.settings

import com.torchons.app.data.api.FairShareSaveResult
import com.torchons.app.data.api.FairShareTargetApi
import com.torchons.app.data.api.loadHistorySafe
import com.torchons.app.data.api.loadTargetsSafe
import com.torchons.app.data.api.saveTargetsSafe
import com.torchons.app.data.model.FairShareHistoryEntry
import com.torchons.app.data.model.FairShareStatus
import com.torchons.app.data.model.FairShareTargetItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.time.ZoneId
import java.util.Locale

/**
 * Perimeter: the RULES of the target-share block (FairShareTargetLogic) and the guarded
 * calls under it (FairShareTargetApi). The Compose screen and the ViewModel only display
 * and forward what these decide, so they are not covered here.
 *
 * Paths by which a wrong share could be saved or shown:
 * - total of the fields -> Save enabled only at exactly 100, never while saving
 *   (saveIsOnlyPossibleAtExactly100, saveIsRefusedWhileSaving)
 * - ratio sent -> percent / 100, and back (percentAndRatioConvertBothWays)
 * - first display of the server ratios -> adds up to 100 for 2, 3, 4 members; stored whole
 *   percents untouched; shares that do not add up are not disguised (firstDisplay*)
 * - what a field accepts -> digits, 0..100, empty stays empty (fieldInput*)
 * - one member -> block hidden (blockIsHiddenForASingleMember)
 * - offline / refused / success of load, save, history -> a result, never an exception
 *   (offline*, refused*, success*), cancellation still propagates (cancellationPropagates)
 * - the body of a save carries what the caller passed (saveBodyIsPassedThrough)
 * Not covered: the screen (no Compose test here), the spinner state of the ViewModel (it
 * ends in UNAVAILABLE because the guarded calls return null, which is covered), the 422 text
 * (server side), reading a member list that changes between two screens.
 */
class FairShareTargetTest {

    // --- Total and Save button ---------------------------------------------------------

    @Test
    fun saveIsOnlyPossibleAtExactly100() {
        assertTrue(canSaveShares(100))
        assertFalse(canSaveShares(99))
        assertFalse(canSaveShares(101))
        assertFalse(canSaveShares(0))
        assertEquals(100, totalPercent(listOf("60", "40")))
        assertEquals(90, totalPercent(listOf("30", "30", "30")))
        assertEquals(60, totalPercent(listOf("60", "")))
        assertTrue(canSaveShares(totalPercent(listOf("50", "30", "20"))))
    }

    @Test
    fun saveIsRefusedWhileSaving() {
        assertFalse(canSaveShares(100, saving = true))
    }

    // --- Percent <-> ratio -------------------------------------------------------------

    @Test
    fun percentAndRatioConvertBothWays() {
        assertEquals(0.6, percentToRatio(60), 1e-12)
        assertEquals(0.05, percentToRatio(5), 1e-12)
        assertEquals(60, ratioToPercent(0.6))
        assertEquals(29, ratioToPercent(0.29)) // 0.29 * 100 is 28.999999999999996
        for (p in 0..100) assertEquals(p, ratioToPercent(percentToRatio(p)))
    }

    // --- First display of the server ratios --------------------------------------------

    @Test
    fun firstDisplayOfTheDefaultForThreeMembersAddsUpTo100() {
        val third = 1.0 / 3
        val shown = initialPercents(listOf(third, third, third))
        assertEquals(100, shown.sum())
        assertEquals(listOf(34, 33, 33), shown)
    }

    @Test
    fun firstDisplayOfTwoAndFourEqualSharesAddsUpTo100() {
        assertEquals(listOf(50, 50), initialPercents(listOf(0.5, 0.5)))
        assertEquals(listOf(25, 25, 25, 25), initialPercents(listOf(0.25, 0.25, 0.25, 0.25)))
        assertEquals(100, initialPercents(List(7) { 1.0 / 7 }).sum())
    }

    @Test
    fun firstDisplayKeepsStoredWholePercents() {
        assertEquals(listOf(60, 40), initialPercents(listOf(0.6, 0.4)))
        // Accepted by the server at the edge of its tolerance: shown as stored, not "repaired".
        assertEquals(listOf(33, 33, 33), initialPercents(listOf(0.33, 0.33, 0.33)))
        assertEquals(listOf(29, 71), initialPercents(listOf(0.29, 0.71)))
    }

    @Test
    fun firstDisplayDoesNotHideSharesThatDoNotAddUp() {
        // The old server default at three members: 50 % each. The screen shows it as is.
        assertEquals(listOf(50, 50, 50), initialPercents(listOf(0.5, 0.5, 0.5)))
    }

    // --- What a field accepts ----------------------------------------------------------

    @Test
    fun fieldInputKeepsDigitsOnlyAndCapsAt100() {
        assertEquals("12", filterPercentInput("a1b2"))
        assertEquals("100", filterPercentInput("999"))
        assertEquals("100", filterPercentInput("1000"))
        assertEquals("100", filterPercentInput("100"))
        assertEquals("7", filterPercentInput("007"))
        assertEquals("0", filterPercentInput("0"))
        assertEquals("", filterPercentInput(""))
        assertEquals("", filterPercentInput("%"))
    }

    @Test
    fun anEmptyFieldCountsAsZero() {
        assertEquals(0, percentOf(""))
        assertEquals(45, percentOf("45"))
        assertEquals(100, percentOf("250")) // never reached through filterPercentInput
    }

    // --- Who sees the block ------------------------------------------------------------

    @Test
    fun blockIsHiddenForASingleMember() {
        assertFalse(isFairShareBlockVisible(0))
        assertFalse(isFairShareBlockVisible(1))
        assertTrue(isFairShareBlockVisible(2))
        assertTrue(isFairShareBlockVisible(3))
    }

    // --- History texts -----------------------------------------------------------------

    @Test
    fun shareTextShowsAPercentOrADash() {
        assertEquals("60 %", shareText(0.6))
        assertEquals("—", shareText(null))
    }

    @Test
    fun historyDateIsReadableOrKeptRaw() {
        val readable = historyDateText(
            "2026-11-03T10:15:30.123456+00:00", ZoneId.of("UTC"), Locale.FRANCE,
        )
        assertTrue(readable, readable.contains("2026"))
        assertFalse(readable, readable.contains("T10"))
        assertEquals("not a date", historyDateText("not a date"))
    }

    // --- Guarded calls: offline, refused, success --------------------------------------

    private class FakeApi(
        val status: () -> Response<List<FairShareStatus>> = { Response.success(emptyList()) },
        val put: (List<FairShareTargetItem>) -> Response<Unit> = { Response.success(Unit) },
        val history: () -> Response<List<FairShareHistoryEntry>> = { Response.success(emptyList()) },
    ) : FairShareTargetApi {
        var sent: List<FairShareTargetItem>? = null
        override suspend fun getStatus() = status()
        override suspend fun putConfig(items: List<FairShareTargetItem>): Response<Unit> {
            sent = items
            return put(items)
        }
        override suspend fun getHistory() = history()
    }

    private fun <T> failure(code: Int): Response<T> =
        Response.error(code, "{}".toResponseBody("application/json".toMediaType()))

    private val oneStatus = FairShareStatus("u1", "Alex", 0.5, 0.4, false)

    @Test
    fun offlineLoadGivesNullInsteadOfThrowing() = runBlocking {
        val api = FakeApi(status = { throw IOException("no network") })
        assertNull(api.loadTargetsSafe())
    }

    @Test
    fun offlineSaveIsReportedAsOffline() = runBlocking {
        val api = FakeApi(put = { throw IOException("no network") })
        assertEquals(FairShareSaveResult.OFFLINE, api.saveTargetsSafe(listOf(FairShareTargetItem("u1", 1.0))))
    }

    @Test
    fun offlineHistoryGivesNull() = runBlocking {
        val api = FakeApi(history = { throw IOException("no network") })
        assertNull(api.loadHistorySafe())
    }

    @Test
    fun refusedLoadSaveAndHistoryAreNotSuccesses() = runBlocking {
        val api = FakeApi(
            status = { failure(500) },
            put = { failure(422) },
            history = { failure(404) },
        )
        assertNull(api.loadTargetsSafe())
        assertEquals(FairShareSaveResult.REJECTED, api.saveTargetsSafe(listOf(FairShareTargetItem("u1", 1.0))))
        assertNull(api.loadHistorySafe())
    }

    @Test
    fun successGivesTheServerAnswer() = runBlocking {
        val api = FakeApi(status = { Response.success(listOf(oneStatus)) })
        assertEquals(listOf(oneStatus), api.loadTargetsSafe())
        assertEquals(FairShareSaveResult.SAVED, api.saveTargetsSafe(listOf(FairShareTargetItem("u1", 1.0))))
        assertNotNull(api.loadHistorySafe())
    }

    @Test
    fun saveBodyIsPassedThrough() = runBlocking {
        val api = FakeApi()
        val items = listOf(FairShareTargetItem("u1", 0.6), FairShareTargetItem("u2", 0.4))
        api.saveTargetsSafe(items)
        assertEquals(items, api.sent)
    }

    @Test
    fun cancellationPropagates() = runBlocking {
        val api = FakeApi(status = { throw CancellationException("cancelled") })
        try {
            api.loadTargetsSafe()
            fail("a cancelled call must not be swallowed")
        } catch (e: CancellationException) {
            // expected
        }
    }
}
