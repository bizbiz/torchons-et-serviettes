package com.torchons.app.util

import com.torchons.app.util.DurationSpan.Days
import com.torchons.app.util.DurationSpan.LessThanMonths
import com.torchons.app.util.DurationSpan.LessThanYears
import com.torchons.app.util.DurationSpan.MonthsDays
import com.torchons.app.util.DurationSpan.MoreThanMonths
import com.torchons.app.util.DurationSpan.MoreThanYears
import com.torchons.app.util.DurationSpan.Weeks
import com.torchons.app.util.DurationSpan.Years
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * The thresholds decided with Quentin on 2026-11-11 (DurationText.kt), from the examples he
 * approved, counted from 11/11/2026. Not covered here: the wording itself (plurals, « d'un an »),
 * which needs Resources ; it is read on the device.
 */
class DurationSpanTest {

    private val today = LocalDate.of(2026, 11, 11)
    private fun span(days: Long) = durationSpan(today, today.plusDays(days))

    @Test
    fun `under a week, days`() {
        assertEquals(Days(0), span(0))
        assertEquals(Days(3), span(3))
        assertEquals(Days(6), span(6))
    }

    @Test
    fun `under a month, weeks to the nearest half`() {
        assertEquals(Weeks(1, half = false), span(7))
        assertEquals(Weeks(1, half = true), span(10))
        assertEquals(Weeks(2, half = false), span(14))
        assertEquals(Weeks(3, half = true), span(24))
        assertEquals(Weeks(4, half = false), span(29))
    }

    @Test
    fun `under three months, calendar months and days`() {
        assertEquals(MonthsDays(1, 0), span(30))     // 11/12
        assertEquals(MonthsDays(1, 10), span(40))    // 21/12
        assertEquals(MonthsDays(2, 9), span(70))     // 20/01, December has 31 days
    }

    @Test
    fun `under a year, the closer of more than N or less than N+1 months`() {
        assertEquals(MoreThanMonths(3), span(95))    // 14/02 : 3 months 3 days
        assertEquals(LessThanMonths(6), span(171))   // 01/05 : 5 months 20 days, Quentin's tax return
        assertEquals(MonthsDays(4, 0), span(120))    // 11/03 exactly
        assertEquals(LessThanMonths(12), span(350))  // 27/10 : « moins d'un an »
    }

    @Test
    fun `beyond, years`() {
        assertEquals(Years(1), span(365))
        assertEquals(MoreThanYears(1), span(400))
        assertEquals(LessThanYears(2), span(600))
    }
}
