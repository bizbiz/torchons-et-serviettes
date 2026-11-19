package com.torchons.app.util

/**
 * Frequency slider counted in the chosen unit: 1 to 15 days, weeks or months.
 *
 * Decided with Quentin on 2026-11-19 (todo « Le curseur de fréquence change d'échelle sans
 * garder la durée »): switching unit converts the value to keep roughly the same duration,
 * rounded to the nearest ([DurationUnit.fromDays], half up). Months stop at 12, because the
 * backend refuses more than 365 days (FrequencyUpdate.every_n_days, le=365).
 */
object FrequencyScale {
    const val MAX_COUNT = 15
    const val MAX_DAYS = 365

    /** Highest slider position in [unit]: 15, or less when 15 units exceed 365 days. */
    fun maxCount(unit: DurationUnit): Int = minOf(MAX_COUNT, MAX_DAYS / unit.days)

    /** Slider position in [unit] closest to [days], kept inside 1..[maxCount]. */
    fun countFor(unit: DurationUnit, days: Int): Int =
        unit.fromDays(days).coerceIn(1, maxCount(unit))

    /**
     * Unit shown when the dialog opens: the most natural one for [days] (a multiple of 30 gives
     * months, of 7 weeks), moved up while [days] does not fit in its 1..15 range.
     */
    fun initialUnit(days: Int): DurationUnit {
        val units = DurationUnit.values()
        var unit = DurationUnit.bestFor(days)
        while (unit.fromDays(days) > maxCount(unit) && unit.ordinal < units.size - 1) {
            unit = units[unit.ordinal + 1]
        }
        return unit
    }

    /** Days for [count] units of [unit], within 1..365. */
    fun daysFor(unit: DurationUnit, count: Int): Int =
        unit.toDays(count).coerceIn(1, MAX_DAYS)
}
