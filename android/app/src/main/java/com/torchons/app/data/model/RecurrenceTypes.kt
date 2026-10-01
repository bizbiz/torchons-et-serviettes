package com.torchons.app.data.model

/**
 * Recurrence types the app knows, in one place.
 *
 * Mirrors of the server sets in `backend/app/services/recurrence.py`. The
 * backend test `tests/test_recurrence_type_lists.py` reads THIS file and fails
 * when either set drifts from its Python twin, and `RecurrenceTypesTest`
 * proves on the JVM that every living type gets a due date from
 * [com.torchons.app.util.TaskUrgencyCalculator]. Keep both literals as
 * `setOf("...")`: the backend test parses them.
 */
object RecurrenceTypes {
    const val INTERVAL = "interval"
    const val MONTHLY_VARIABLE = "monthly_variable"
    const val UNCERTAIN = "uncertain"
    const val CALENDAR = "calendar"
    const val ANNIVERSARY = "anniversary"

    /** Types a rule may carry. Mirror of `LIVING_RULE_TYPES`. */
    val LIVING: Set<String> = setOf("interval", "monthly_variable", "uncertain", "calendar", "anniversary")

    /**
     * Types a frequency vote may adjust. Mirror of `VOTABLE_RULE_TYPES`: an
     * allowlist, the server answers 409 on any other type.
     */
    val VOTABLE: Set<String> = setOf("interval", "monthly_variable")

    fun isVotable(rule: RecurrenceRule?): Boolean = rule?.type in VOTABLE
}
