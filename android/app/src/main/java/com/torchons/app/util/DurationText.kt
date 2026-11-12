package com.torchons.app.util

import android.content.res.Resources
import com.torchons.app.R
import java.time.LocalDate
import java.time.Period
import java.time.temporal.ChronoUnit

/**
 * A number of days written the way people say it (Quentin, 2026-11-11: « À jour dans 171 j »
 * should read « dans moins de 6 mois »). Thresholds decided with him the same day:
 * - under a week: days (« 3 jours ») ;
 * - under a calendar month: weeks, to the nearest half (« 1 semaine et demie ») ;
 * - under 3 months: months and days (« 2 mois et 10 jours ») ;
 * - under a year: « plus de N mois » or « moins de N+1 mois », whichever is closer ;
 * - beyond: the same in years (« plus d'un an »).
 * Months are calendar months, counted from a real date: from 11/11 to 01/05 is 5 months and
 * 20 days, so « moins de 6 mois ».
 */
sealed interface DurationSpan {
    data class Days(val n: Int) : DurationSpan
    data class Weeks(val n: Int, val half: Boolean) : DurationSpan
    data class MonthsDays(val months: Int, val days: Int) : DurationSpan
    data class MoreThanMonths(val n: Int) : DurationSpan
    data class LessThanMonths(val n: Int) : DurationSpan
    data class Years(val n: Int) : DurationSpan
    data class MoreThanYears(val n: Int) : DurationSpan
    data class LessThanYears(val n: Int) : DurationSpan
}

/** The span between two dates, [from] not after [to]. */
fun durationSpan(from: LocalDate, to: LocalDate): DurationSpan {
    val days = ChronoUnit.DAYS.between(from, to).toInt().coerceAtLeast(0)
    val p = Period.between(from, to)
    val months = p.years * 12 + p.months
    return when {
        days < 7 -> DurationSpan.Days(days)
        months < 1 -> {
            val halves = Math.round(days / 3.5).toInt()
            DurationSpan.Weeks(halves / 2, halves % 2 == 1)
        }
        months < 3 || (months < 12 && p.days == 0) -> DurationSpan.MonthsDays(months, p.days)
        months < 12 ->
            if (p.days <= 15) DurationSpan.MoreThanMonths(months) else DurationSpan.LessThanMonths(months + 1)
        p.months == 0 && p.days == 0 -> DurationSpan.Years(p.years)
        p.months < 6 -> DurationSpan.MoreThanYears(p.years)
        else -> DurationSpan.LessThanYears(p.years + 1)
    }
}

/** [days] (its absolute value) written out, counted on the calendar from [today]. */
fun durationText(res: Resources, days: Int, today: LocalDate = LocalDate.now()): String =
    durationText(res, durationSpan(today, today.plusDays(kotlin.math.abs(days).toLong())))

fun durationText(res: Resources, span: DurationSpan): String {
    fun q(id: Int, n: Int) = res.getQuantityString(id, n, n)
    fun months(n: Int) = q(R.plurals.duration_months, n)
    return when (span) {
        is DurationSpan.Days -> q(R.plurals.duration_days, span.n)
        is DurationSpan.Weeks ->
            q(if (span.half) R.plurals.duration_weeks_half else R.plurals.duration_weeks, span.n)
        is DurationSpan.MonthsDays ->
            if (span.days == 0) months(span.months)
            else res.getString(R.string.duration_and_format, months(span.months), q(R.plurals.duration_days, span.days))
        is DurationSpan.MoreThanMonths -> res.getString(R.string.duration_more_than_format, months(span.n))
        is DurationSpan.LessThanMonths ->
            if (span.n == 12) res.getString(R.string.duration_less_than_one_year)
            else res.getString(R.string.duration_less_than_format, months(span.n))
        is DurationSpan.Years -> q(R.plurals.duration_years, span.n)
        is DurationSpan.MoreThanYears ->
            if (span.n == 1) res.getString(R.string.duration_more_than_one_year)
            else res.getString(R.string.duration_more_than_format, q(R.plurals.duration_years, span.n))
        is DurationSpan.LessThanYears ->
            res.getString(R.string.duration_less_than_format, q(R.plurals.duration_years, span.n))
    }
}
