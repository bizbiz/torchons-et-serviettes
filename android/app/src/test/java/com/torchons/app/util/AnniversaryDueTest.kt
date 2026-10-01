package com.torchons.app.util

import com.torchons.app.data.model.RecurrenceRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * `anniversary` on the client: the same cases as the backend's
 * tests/test_anniversary_recurrence.py, so both mirrors are held to one spec.
 *
 * Paths by which the client can show a wrong due date, listed first:
 *  - nextAnniversaryDue (the computation) ........................ covered
 *  - computeDueDate wiring (anchor passed / missing / never done) . covered
 *  - nominal, orange cap ........................................... covered
 *  - earliestCompletedAt (server vs local cache reconciliation) .... covered
 *  - the two ViewModels passing firstCompletedAt ... not covered: Android
 *    ViewModels, no JVM harness for them in this module.
 */
class AnniversaryDueTest {

    private val yearly = RecurrenceRule(type = "anniversary", everyNMonths = 12)
    private val utc = ZoneOffset.UTC

    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    @Test
    fun afterTheFirstCompletion_theNextOneIsAPeriodLater() {
        assertEquals(d(2026, 3, 14), TaskUrgencyCalculator.nextAnniversaryDue(yearly, d(2025, 3, 14), d(2025, 3, 14)))
    }

    @Test
    fun doneLate_doesNotDrift() {
        assertEquals(d(2027, 3, 14), TaskUrgencyCalculator.nextAnniversaryDue(yearly, d(2025, 3, 14), d(2026, 4, 2)))
    }

    @Test
    fun doneInsideTheOrangeWindow_honoursTheOccurrence() {
        assertEquals(d(2027, 3, 14), TaskUrgencyCalculator.nextAnniversaryDue(yearly, d(2025, 3, 14), d(2026, 3, 10)))
    }

    @Test
    fun doneLongBeforeTheWindow_doesNotHonourIt() {
        assertEquals(d(2026, 3, 14), TaskUrgencyCalculator.nextAnniversaryDue(yearly, d(2025, 3, 14), d(2026, 1, 10)))
    }

    @Test
    fun aSkippedYear_staysDue() {
        assertEquals(d(2024, 3, 14), TaskUrgencyCalculator.nextAnniversaryDue(yearly, d(2023, 3, 14), d(2023, 3, 14)))
    }

    @Test
    fun multiYearPeriod() {
        val rule = RecurrenceRule(type = "anniversary", everyNMonths = 24)
        assertEquals(d(2029, 3, 14), TaskUrgencyCalculator.nextAnniversaryDue(rule, d(2025, 3, 14), d(2027, 3, 20)))
    }

    @Test
    fun monthEndAnchor_isComputedFromTheAnchorNotIterated() {
        val monthly = RecurrenceRule(type = "anniversary", everyNMonths = 1)
        assertEquals(d(2024, 2, 29), TaskUrgencyCalculator.nextAnniversaryDue(monthly, d(2024, 1, 31), d(2024, 1, 31)))
        assertEquals(d(2024, 3, 31), TaskUrgencyCalculator.nextAnniversaryDue(monthly, d(2024, 1, 31), d(2024, 2, 29)))
    }

    @Test
    fun computeDueDate_usesTheFirstCompletionAsAnchor() {
        val due = TaskUrgencyCalculator.computeDueDate(
            lastCompletedAt = "2026-04-02T09:00:00Z",
            rule = yearly,
            createdAt = "2025-01-01T09:00:00Z",
            zone = utc,
            firstCompletedAt = "2025-03-14T09:00:00Z",
        )
        assertEquals(d(2027, 3, 14), due)
    }

    @Test
    fun computeDueDate_withoutAFirst_theLastIsTheAnchor() {
        val due = TaskUrgencyCalculator.computeDueDate(
            lastCompletedAt = "2026-04-02T09:00:00Z", rule = yearly, createdAt = null, zone = utc,
        )
        assertEquals(d(2027, 4, 2), due)
    }

    @Test
    fun neverDone_isDueAtCreation() {
        val due = TaskUrgencyCalculator.computeDueDate(
            lastCompletedAt = null, rule = yearly, createdAt = "2026-09-01T09:00:00Z", zone = utc,
        )
        assertEquals(d(2026, 9, 1), due)
    }

    @Test
    fun nominalIsThePeriodInDays_andTheOrangeWindowIsCapped() {
        assertEquals(365, TaskUrgencyCalculator.nominalIntervalDays(yearly))
        assertEquals(730, TaskUrgencyCalculator.nominalIntervalDays(RecurrenceRule(type = "anniversary", everyNMonths = 24)))
        assertEquals(182, TaskUrgencyCalculator.nominalIntervalDays(RecurrenceRule(type = "anniversary", everyNMonths = 6)))
        assertEquals(30, TaskUrgencyCalculator.nominalIntervalDays(RecurrenceRule(type = "anniversary", everyNMonths = 1)))
        assertEquals(7, TaskUrgencyCalculator.orangeWindowCapOf(yearly))

        val due = d(2026, 3, 14)
        val nominal = TaskUrgencyCalculator.nominalIntervalDays(yearly)
        val cap = TaskUrgencyCalculator.orangeWindowCapOf(yearly)
        assertEquals(
            TaskUrgencyCalculator.State.ORANGE,
            TaskUrgencyCalculator.computeState(due, due.minusDays(7), nominal, windowCapDays = cap),
        )
        assertEquals(
            TaskUrgencyCalculator.State.GREEN,
            TaskUrgencyCalculator.computeState(due, due.minusDays(8), nominal, windowCapDays = cap),
        )
    }

    @Test
    fun earliestCompletedAt_keepsTheOlderOfServerAndLocal() {
        val server = "2025-03-14T09:00:00Z"
        val localOlder = "2025-01-02T09:00:00Z"
        val localNewer = "2026-04-02T09:00:00Z"
        assertEquals(localOlder, TaskUrgencyCalculator.earliestCompletedAt(server, localOlder))
        assertEquals(server, TaskUrgencyCalculator.earliestCompletedAt(server, localNewer))
        assertEquals(server, TaskUrgencyCalculator.earliestCompletedAt(server, null))
        assertEquals(localNewer, TaskUrgencyCalculator.earliestCompletedAt(null, localNewer))
        assertNull(TaskUrgencyCalculator.earliestCompletedAt(null, null))
    }
}
