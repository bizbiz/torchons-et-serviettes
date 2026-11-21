package com.torchons.app.ui.taskdetail

import com.torchons.app.data.model.EffortVotesState
import com.torchons.app.data.repository.QueuedVotes
import com.torchons.app.ui.common.PreviousRatingLine
import com.torchons.app.ui.common.previousRatingLine
import com.torchons.app.ui.common.shouldShowEmptyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Emulator re-test of 2026-11-20 (items 5 and 6). Offline, on a task whose votes were cached: the
 * section shows the cached votes (my queued vote still on top), the rating dialog starts from MY
 * cached values. With nothing known, the dialog says the previous value is unknown, never "Jamais
 * noté", and an empty list says it is unavailable, never "Aucun ...".
 *
 * Paths:
 *   (1) cached JSON of a fetched task: its rows and effort votes are shown ........ COVERED
 *   (2) a task never fetched but with my bulk affinity: one row, mine, no effort ... COVERED
 *   (3) nothing known: empty votes, so no invented 5/10 .............................. COVERED
 *   (4) cache text that no longer parses: treated as nothing, no crash ................ COVERED
 *   (5) a queued vote of mine laid over the cached ones ............................. COVERED
 *   (6) the previous-rating line: date / never (server answered) / unknown offline ... COVERED
 *   (7) the empty-state rule: only an answered, non-failed, non-loading load is "empty" ... COVERED
 * Not covered: the ViewModel's call order (cache first, fetch second) and the Compose texts.
 */
class CachedVotesTest {
    private val me = "u-me"

    private val fetched = ServerVotes(
        ratings = listOf(
            TaskRating(userId = me, displayName = "Robin", affinity = 9, effortTimeMin = 15),
            TaskRating(userId = "u-2", displayName = "Alice", affinity = 4, effortTimeMin = 30),
        ),
        avgEffortTimeMin = 23,
        scorePoints = 5,
        effortVotes = EffortVotesState(taskId = "t1", myPoints = 3, votesCount = 2, canVote = true),
    )

    @Test
    fun `the cached votes of a fetched task are shown as fetched`() {
        val shown = votesBeforeFetch(fetched.toJson(), myAffinity = null, me = me, myName = "Robin")

        assertEquals(fetched, shown)
        assertEquals(9, shown.ratings.first { it.userId == me }.affinity)
        assertEquals(3, shown.effortVotes?.myPoints)
    }

    @Test
    fun `a task never opened still shows my affinity from the bulk snapshot`() {
        val shown = votesBeforeFetch(cachedJson = null, myAffinity = 9, me = me, myName = "Robin")

        assertEquals(1, shown.ratings.size)
        assertEquals(9, shown.ratings.single().affinity)
        assertNull("effort is not in the bulk snapshot: unknown, not 15 minutes", shown.ratings.single().effortTimeMin)
    }

    @Test
    fun `nothing known gives empty votes, and a broken cache counts as nothing`() {
        assertEquals(ServerVotes(), votesBeforeFetch(null, null, me, "Robin"))
        assertEquals(ServerVotes(), votesBeforeFetch("{not json", null, me, "Robin"))
        assertEquals(ServerVotes(), votesBeforeFetch(null, 9, me = null, myName = null))
    }

    @Test
    fun `my queued vote still goes over the cached ones`() {
        val cached = votesBeforeFetch(fetched.toJson(), null, me, "Robin")

        val shown = overlayQueuedVotes(cached, QueuedVotes(affinity = 2), "t1", me, "Robin")

        assertEquals(2, shown.ratings.first { it.userId == me }.affinity)
        assertEquals(4, shown.ratings.first { it.userId == "u-2" }.affinity)
    }

    @Test
    fun `the previous-rating line never says Jamais note when the server could not be asked`() {
        assertEquals(PreviousRatingLine.RELATIVE_DATE, previousRatingLine("2026-11-01T10:00:00Z", false, true))
        assertEquals(
            PreviousRatingLine.NEVER_RATED,
            previousRatingLine(null, previousUnknown = false, hasCachedValues = false),
        )
        assertEquals(PreviousRatingLine.PREVIOUS_UNKNOWN, previousRatingLine(null, true, hasCachedValues = false))
        assertEquals(PreviousRatingLine.DATE_UNKNOWN, previousRatingLine(null, true, hasCachedValues = true))
    }

    @Test
    fun `an empty list is a real empty only after a load that answered`() {
        assertTrue(shouldShowEmptyState(isLoading = false, loadFailed = false, isEmpty = true))
        assertFalse("still loading", shouldShowEmptyState(isLoading = true, loadFailed = false, isEmpty = true))
        assertFalse("the load failed", shouldShowEmptyState(isLoading = false, loadFailed = true, isEmpty = true))
        assertFalse("not empty", shouldShowEmptyState(isLoading = false, loadFailed = false, isEmpty = false))
    }
}
