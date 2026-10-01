package com.torchons.app.util

import com.torchons.app.data.model.RecurrenceRule
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * `calendar` done early, inside its orange window: same cases as the end of the
 * backend's tests/test_calendar_recurrence.py (defect measured 2026-10-01: done
 * on 10 May for the 15th, the task turned red on the 16th).
 *
 * Paths by which the client can show a wrong `calendar` due date:
 *  - nextCalendarDue (which occurrence a completion honours) ...... covered
 *  - computeDueDate wiring (string parse, zone, today) ............ covered
 *  - orangeWindowDays / 7-day cap feeding the honour window ....... covered
 *    indirectly (the 8 May / 7 May boundary only holds with cap 7)
 *  - never done (lastCompletedAt null -> createdAt) ... not covered: path
 *    untouched by this fix, same rule as compute_due_date on the backend.
 *  - the ViewModels passing lastCompletedAt ... not covered: no JVM harness.
 */
class CalendarDueTest {

    private val may15 = RecurrenceRule(type = "calendar", months = listOf(5), day = 15, graceDays = 0)
    private val utc = ZoneOffset.UTC

    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    private fun due(rule: RecurrenceRule, done: String, today: LocalDate) =
        TaskUrgencyCalculator.computeDueDate(done, rule, "2025-01-01T00:00:00Z", utc, today)

    @Test
    fun doneInTheWindow_beforeTheDeadline_aimsAtNextYear() {
        assertEquals(d(2027, 5, 15), due(may15, "2026-05-10T12:00:00Z", d(2026, 5, 12)))
    }

    @Test
    fun doneInTheWindow_theDayAfterTheDeadline_isNotRed() {
        assertEquals(d(2027, 5, 15), due(may15, "2026-05-10T12:00:00Z", d(2026, 5, 16)))
    }

    @Test
    fun firstDayOfTheWindow_honoursIt() {
        assertEquals(d(2027, 5, 15), due(may15, "2026-05-08T12:00:00Z", d(2026, 5, 16)))
    }

    @Test
    fun doneBeforeTheWindow_doesNotCount() {
        assertEquals(d(2026, 5, 15), due(may15, "2026-05-07T12:00:00Z", d(2026, 5, 16)))
        assertEquals(d(2026, 5, 15), due(may15, "2026-05-07T12:00:00Z", d(2026, 5, 12)))
    }

    @Test
    fun doneLate_staysAnchoredToMay() {
        assertEquals(d(2027, 5, 15), due(may15, "2026-06-30T12:00:00Z", d(2026, 7, 1)))
    }

    @Test
    fun monthly_doneEarly_movesToTheMonthAfter() {
        val monthly = RecurrenceRule(type = "calendar", months = (1..12).toList(), day = 1, graceDays = 0)
        assertEquals(d(2026, 5, 1), due(monthly, "2026-03-29T12:00:00Z", d(2026, 4, 2)))
    }

    @Test
    fun yearly_doneOnDec30ForJan1_aimsAtTheYearAfterNext() {
        val jan1 = RecurrenceRule(type = "calendar", months = listOf(1), day = 1, graceDays = 0)
        assertEquals(d(2028, 1, 1), due(jan1, "2026-12-30T12:00:00Z", d(2026, 12, 31)))
    }
}
