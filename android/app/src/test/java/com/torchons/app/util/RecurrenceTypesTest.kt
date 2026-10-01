package com.torchons.app.util

import com.torchons.app.data.model.RecurrenceRule
import com.torchons.app.data.model.RecurrenceTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Every living type gets a due date from the calculator.
 *
 * The backend test tests/test_recurrence_type_lists.py proves that
 * [RecurrenceTypes.LIVING] equals the server's `LIVING_RULE_TYPES`; this one
 * proves the calculator handles each of them. Together: a type the server
 * accepts cannot be silently inert on the phone (`else -> null`).
 *
 * Paths by which a living type can be inert on the phone:
 *  - computeDueDate has no branch for it ......................... covered
 *  - RecurrenceTypes.LIVING misses it ... covered by the backend test above
 *  - non couvert : nominalIntervalDays / screen labels missing a branch,
 *    motif : they degrade (no orange, raw type name), the due date survives.
 */
class RecurrenceTypesTest {

    /** One sample per living type. A new type without a sample fails below. */
    private val samples = mapOf(
        "interval" to RecurrenceRule(type = "interval", everyNDays = 7),
        "monthly_variable" to RecurrenceRule(type = "monthly_variable", daysPerMonth = (1..12).associate { it.toString() to 30 }),
        "uncertain" to RecurrenceRule(type = "uncertain"),
        "calendar" to RecurrenceRule(type = "calendar", months = listOf(5), day = 15, graceDays = 0),
        "anniversary" to RecurrenceRule(type = "anniversary", everyNMonths = 12),
    )

    @Test
    fun everyLivingTypeHasASample() {
        assertEquals(RecurrenceTypes.LIVING, samples.keys)
    }

    @Test
    fun everyLivingTypeExceptUncertainGetsADueDate() {
        val today = LocalDate.of(2026, 10, 1)
        for ((type, rule) in samples) {
            if (type == RecurrenceTypes.UNCERTAIN) continue // deliberate: no due date
            val due = TaskUrgencyCalculator.computeDueDate(
                lastCompletedAt = "2026-06-01T09:00:00Z",
                rule = rule,
                createdAt = "2026-01-01T09:00:00Z",
                zone = ZoneOffset.UTC,
                today = today,
                firstCompletedAt = "2026-01-15T09:00:00Z",
            )
            assertNotNull("no due date for living type $type", due)
        }
    }

    @Test
    fun votableTypesAreLivingTypes() {
        assertTrue(RecurrenceTypes.LIVING.containsAll(RecurrenceTypes.VOTABLE))
    }
}
