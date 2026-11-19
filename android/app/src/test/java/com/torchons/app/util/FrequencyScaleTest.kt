package com.torchons.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Scope: the pure conversion of [FrequencyScale]. Paths by which the slider could break the
 * rule « 1 to 15 in the unit, a unit switch keeps the nearest duration, never above 365 days »:
 * - unit switch: [FrequencyScale.countFor] then [FrequencyScale.daysFor] — covered;
 * - slider drag: [FrequencyScale.daysFor] on a count within 1..maxCount — covered;
 * - dialog opening: [FrequencyScale.initialUnit] — covered.
 * Not covered: the composable wiring (FrequencyScaleSlider), seen in the dogfood; the two
 * callers (FrequencyVoteDialog, RatingDialog) only pass days in and out.
 */
class FrequencyScaleTest {

    @Test
    fun `switching unit keeps the nearest duration`() {
        // 10 days -> 1.43 weeks -> 1 week (nearest, decided 2026-11-19)
        assertEquals(1, FrequencyScale.countFor(DurationUnit.SEMAINES, 10))
        // 11 days -> 1.57 weeks -> 2 weeks
        assertEquals(2, FrequencyScale.countFor(DurationUnit.SEMAINES, 11))
        // 1 week -> 7 days
        assertEquals(7, FrequencyScale.countFor(DurationUnit.JOURS, 7))
        // 2 months -> 60 days -> 9 weeks (8.57 rounded)
        assertEquals(9, FrequencyScale.countFor(DurationUnit.SEMAINES, 60))
    }

    @Test
    fun `count stays inside the slider range`() {
        assertEquals(15, FrequencyScale.countFor(DurationUnit.JOURS, 40))
        assertEquals(1, FrequencyScale.countFor(DurationUnit.MOIS, 3))
        assertEquals(12, FrequencyScale.countFor(DurationUnit.MOIS, 365))
    }

    @Test
    fun `months stop at 12 because the server refuses more than 365 days`() {
        assertEquals(15, FrequencyScale.maxCount(DurationUnit.JOURS))
        assertEquals(15, FrequencyScale.maxCount(DurationUnit.SEMAINES))
        assertEquals(12, FrequencyScale.maxCount(DurationUnit.MOIS))
        assertEquals(360, FrequencyScale.daysFor(DurationUnit.MOIS, 12))
    }

    @Test
    fun `opening unit is the natural one, moved up when out of range`() {
        assertEquals(DurationUnit.JOURS, FrequencyScale.initialUnit(10))
        assertEquals(DurationUnit.SEMAINES, FrequencyScale.initialUnit(14))
        assertEquals(DurationUnit.MOIS, FrequencyScale.initialUnit(90))
        // 20 days: not a multiple of 7, too many days -> weeks (3)
        assertEquals(DurationUnit.SEMAINES, FrequencyScale.initialUnit(20))
        // 200 days: 29 weeks is too many -> months
        assertEquals(DurationUnit.MOIS, FrequencyScale.initialUnit(200))
        // 112 days = 16 weeks -> months
        assertEquals(DurationUnit.MOIS, FrequencyScale.initialUnit(112))
    }
}
