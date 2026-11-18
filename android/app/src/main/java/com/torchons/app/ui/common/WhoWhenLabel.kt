package com.torchons.app.ui.common

import android.content.res.Resources
import com.torchons.app.R
import com.torchons.app.core.today.joinNames
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/*
 * The confirm button of « Date + Qui » (CompletionDialog), worded as in
 * docs/plans/a-faire-aujourdhui.md: « Toi aujourd'hui », « Bob hier », « Bob & Toi mardi »,
 * « Bob, Alice & Toi hier », « 4 personnes mardi ». Its own strings: the home cards keep theirs.
 */

/** People shown by name: up to three; past that the label counts them. */
internal const val MAX_NAMED_PEOPLE = 3

/**
 * The « who » half: others in the order given, me always last. Null past [MAX_NAMED_PEOPLE]
 * (the caller says « N personnes »), and for nobody.
 */
internal fun whoNames(
    chosen: List<String>,
    me: String,
    names: Map<String, String>,
    youAlone: String,
    youLast: String,
): String? {
    if (chosen.isEmpty() || chosen.size > MAX_NAMED_PEOPLE) return null
    val others = chosen.filter { it != me }.map { names[it] ?: "?" }
    return when {
        me !in chosen -> joinNames(others)
        others.isEmpty() -> youAlone
        else -> joinNames(others + youLast)
    }
}

/** « aujourd'hui », « hier », « mardi » (within the week), else « le 3 novembre ». */
internal fun whenLabel(res: Resources, day: LocalDate, today: LocalDate): String {
    val locale = res.configuration.locales.get(0) ?: Locale.getDefault()
    return when (ChronoUnit.DAYS.between(day, today)) {
        0L -> res.getString(R.string.completion_day_today)
        1L -> res.getString(R.string.completion_day_yesterday)
        in 2L..6L -> res.getString(
            R.string.completion_day_weekday_format, day.dayOfWeek.getDisplayName(TextStyle.FULL, locale),
        )
        else -> res.getString(
            R.string.completion_day_date_format,
            day.format(DateTimeFormatter.ofPattern(res.getString(R.string.completion_day_date_pattern), locale)),
        )
    }
}

/**
 * The points line of « Date + Qui »: whose share it is. [points] is the per-person share the
 * server computes (completion-preview, split rounded up). Me alone or nobody: « Tu gagneras
 * 5 pts »; one other: « Bob gagnera 5 pts »; several: « Bob & Toi : 3 pts chacun ».
 */
internal fun pointsEarnLabel(
    res: Resources,
    chosen: List<String>,
    me: String,
    names: Map<String, String>,
    points: Int,
): String {
    if (chosen.isEmpty() || chosen == listOf(me)) {
        return res.getQuantityString(R.plurals.completion_preview_earn, points, points)
    }
    val who = whoNames(
        chosen, me, names,
        res.getString(R.string.completion_who_you_alone), res.getString(R.string.completion_who_you_last),
    ) ?: res.getQuantityString(R.plurals.completion_people_count, chosen.size, chosen.size)
    return if (chosen.size == 1) {
        res.getQuantityString(R.plurals.completion_preview_earn_other, points, who, points)
    } else {
        res.getQuantityString(R.plurals.completion_preview_earn_each, points, who, points)
    }
}

/** The whole label; « Valider » when nobody is checked (the button is then disabled). */
internal fun whoWhenLabel(
    res: Resources,
    chosen: List<String>,
    me: String,
    names: Map<String, String>,
    day: LocalDate,
    today: LocalDate,
): String {
    if (chosen.isEmpty()) return res.getString(R.string.common_validate)
    val dayText = whenLabel(res, day, today)
    val who = whoNames(
        chosen, me, names,
        res.getString(R.string.completion_who_you_alone), res.getString(R.string.completion_who_you_last),
    ) ?: return res.getQuantityString(R.plurals.completion_people_day, chosen.size, chosen.size, dayText)
    return res.getString(R.string.completion_who_day_format, who, dayText)
}
