package com.torchons.app.data.repository

import com.torchons.app.data.local.entities.CompletionEntity
import com.torchons.app.data.model.RecurrenceRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * The app's twin of the server's anti-duplicate rule (`completion_guard.py` and
 * `min_completion_gap_seconds`): if the two disagree, the app either celebrates a completion the
 * server drops (the 2026-11-11 defect) or refuses one the server would have kept.
 *
 * PATHS by which « refused here ⇔ dropped by the server » can break:
 *   (1) the window per recurrence type ........................................ COVERED
 *   (2) same member, inside the window, on both sides of the date ............. COVERED
 *   (3) another member, or just outside the window (strict bounds) ............ COVERED
 *   (4) several members (« Fait ensemble »): one duplicate is enough ........... COVERED
 *   (5) the cache missing the earlier completion (not yet refreshed) ... not covered: the server
 *       still drops it, the celebration then plays — the old behaviour, rare.
 */
class CompletionChecksTest {

    private fun done(by: String, at: String) = CompletionEntity(
        id = "$by-$at", taskId = "t", completedBy = by, completedAt = at, pointsEarned = 1, notes = null,
    )

    @Test
    fun `the window follows the server, per recurrence type`() {
        assertEquals(3600L, minCompletionGapSeconds(null, null))
        assertEquals(3600L, minCompletionGapSeconds(RecurrenceRule("interval", everyNDays = 6), null))
        assertEquals(86400L, minCompletionGapSeconds(RecurrenceRule("interval", everyNDays = 7), null))
        assertEquals(86400L, minCompletionGapSeconds(RecurrenceRule("monthly_variable"), null))
        assertEquals(86400L, minCompletionGapSeconds(RecurrenceRule("anniversary", everyNMonths = 12), null))
        assertEquals(3600L, minCompletionGapSeconds(RecurrenceRule("uncertain"), null))
        assertEquals(86400L, minCompletionGapSeconds(RecurrenceRule("uncertain"), 7.0))
        assertEquals(3600L, minCompletionGapSeconds(RecurrenceRule("calendar", months = listOf(5), day = 15), null))
    }

    @Test
    fun `same member inside the window is a duplicate, on either side of the date`() {
        val cache = listOf(done("alice", "2026-11-11T17:21:49Z"))
        val at = Instant.parse("2026-11-11T17:21:54Z")
        assertEquals("alice-2026-11-11T17:21:49Z", findDuplicate(cache, setOf("alice"), at, 3600)?.id)
        val before = Instant.parse("2026-11-11T16:30:00Z")
        assertEquals("alice-2026-11-11T17:21:49Z", findDuplicate(cache, setOf("alice"), before, 3600)?.id)
    }

    @Test
    fun `another member, or exactly one window away, is not a duplicate`() {
        val cache = listOf(done("alice", "2026-11-11T17:00:00+00:00"))
        assertNull(findDuplicate(cache, setOf("bob"), Instant.parse("2026-11-11T17:05:00Z"), 3600))
        assertNull(findDuplicate(cache, setOf("alice"), Instant.parse("2026-11-11T18:00:00Z"), 3600))
    }

    @Test
    fun `done together is refused when one participant already did it`() {
        val cache = listOf(done("bob", "2026-11-11T09:00:00Z"))
        val at = Instant.parse("2026-11-11T12:00:00Z")
        assertEquals("bob-2026-11-11T09:00:00Z", findDuplicate(cache, setOf("alice", "bob"), at, 86400)?.id)
    }
}
