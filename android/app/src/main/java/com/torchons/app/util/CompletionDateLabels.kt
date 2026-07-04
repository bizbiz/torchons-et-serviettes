package com.torchons.app.util

import android.content.res.Resources
import com.torchons.app.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Shared date labels and ISO conversions for completion flows.
 *
 * Used by the home segmented completion buttons (day navigation) and by
 * [com.torchons.app.ui.common.CompletionDialog] for the ISO conversion.
 */
object CompletionDateLabels {

    /**
     * Relative day label with a three-tier rule:
     *  - offset 0 -> "aujourd'hui" / "today"
     *  - offset 1 -> "hier" / "yesterday"
     *  - offset 2 -> "avant-hier" / "2 days ago"
     *  - offset 3..6 -> "mercredi dernier" / "last Wednesday"
     *  - offset >= 7 -> absolute date "jeudi 26 juin" / "Thu, Jun 26"
     *
     * A (defensive) future date falls back to the absolute format.
     */
    fun relativeDayLabel(
        res: Resources,
        date: LocalDate,
        today: LocalDate = LocalDate.now(),
    ): String {
        val locale = res.configuration.locales.get(0) ?: Locale.getDefault()
        return when (ChronoUnit.DAYS.between(date, today).toInt()) {
            0 -> res.getString(R.string.date_label_today)
            1 -> res.getString(R.string.date_label_yesterday)
            2 -> res.getString(R.string.date_label_two_days_ago)
            3, 4, 5, 6 -> res.getString(
                R.string.date_label_last_weekday_format,
                date.dayOfWeek.getDisplayName(TextStyle.FULL, locale),
            )
            else -> date.format(
                DateTimeFormatter.ofPattern(
                    res.getString(R.string.date_label_absolute_pattern),
                    locale,
                )
            )
        }
    }

    /** LocalDate -> ISO 8601 at noon UTC ("2026-06-26T12:00:00Z"), TZ-safe. */
    fun toIsoNoonUtc(date: LocalDate): String = "${date}T12:00:00Z"

    /** Epoch millis (interpreted as a UTC calendar day) -> ISO noon UTC. */
    fun millisToIsoNoonUtc(millis: Long): String {
        val date = Instant.ofEpochMilli(millis)
            .atOffset(ZoneOffset.UTC)
            .toLocalDate()
        return toIsoNoonUtc(date)
    }
}
