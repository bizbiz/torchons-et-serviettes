package com.torchons.app.ui.settings

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Pure rules of the "target share" block (no Android, no network), so that they are
 * covered by plain JVM tests. The screen only displays what these functions decide.
 */

/** The shares of the household must add up to this many percent. */
const val FAIR_SHARE_TOTAL_PERCENT = 100

/** A household with fewer members than this has nobody to share with: the block is hidden. */
const val FAIR_SHARE_MIN_MEMBERS = 2

private const val PERCENT_INPUT_MAX_DIGITS = 3

/** The server accepts a total of 1 +/- 0.01; beyond that the stored shares are not whole. */
private const val SERVER_TOTAL_TOLERANCE = 0.01
private const val WHOLE_EPSILON = 1e-6

/** Whole percent the field shows for a server ratio (0.6 -> 60). */
fun ratioToPercent(ratio: Double): Int = (ratio * 100).roundToInt()

/** Ratio sent to the server for a whole percent (60 -> 0.6). */
fun percentToRatio(percent: Int): Double = percent / 100.0

/** The block shows only for a household of at least two members. */
fun isFairShareBlockVisible(memberCount: Int): Boolean = memberCount >= FAIR_SHARE_MIN_MEMBERS

/**
 * Percent to show in each field, from the server ratios (in the same order).
 *
 * Ratios that are already whole percents are shown as they are. Otherwise (typically the
 * default 1/3 each) the percents are rounded so that they ADD UP TO 100 (largest remainder):
 * plain rounding would give 33+33+33 and open the screen with a Save button that cannot be
 * pressed. Ratios that do not add up to 1 at all are only rounded, so the screen does not
 * hide that the stored shares are off.
 */
fun initialPercents(ratios: List<Double>): List<Int> {
    val exact = ratios.map { it * 100 }
    val rounded = exact.map { it.roundToInt() }
    val allWhole = exact.indices.all { abs(exact[it] - rounded[it]) < WHOLE_EPSILON }
    if (allWhole || abs(ratios.sum() - 1.0) > SERVER_TOTAL_TOLERANCE + WHOLE_EPSILON) return rounded
    val floors = exact.map { floor(it + WHOLE_EPSILON).toInt() }
    val result = floors.toMutableList()
    var missing = FAIR_SHARE_TOTAL_PERCENT - floors.sum()
    val byRemainder = exact.indices.sortedWith(
        compareByDescending<Int> { exact[it] - floors[it] }.thenBy { it }
    )
    for (i in byRemainder) {
        if (missing <= 0) break
        result[i] += 1
        missing--
    }
    return result
}

/** What a field accepts: digits only, at most 100. A cleared field stays empty. */
fun filterPercentInput(raw: String): String {
    val digits = raw.filter { it.isDigit() }.take(PERCENT_INPUT_MAX_DIGITS)
    if (digits.isEmpty()) return ""
    return digits.toInt().coerceAtMost(FAIR_SHARE_TOTAL_PERCENT).toString()
}

/** Percent a field stands for; an empty field counts as 0. */
fun percentOf(text: String): Int = (text.toIntOrNull() ?: 0).coerceIn(0, FAIR_SHARE_TOTAL_PERCENT)

/** Sum of the fields. */
fun totalPercent(texts: List<String>): Int = texts.sumOf { percentOf(it) }

/** Save is allowed only for a total of exactly 100, and not while a save is running. */
fun canSaveShares(total: Int, saving: Boolean = false): Boolean =
    total == FAIR_SHARE_TOTAL_PERCENT && !saving

/** A share in a history line: "60 %" (with a no-break space), or a dash when absent. */
fun shareText(ratio: Double?): String =
    if (ratio == null) "—" else "${ratioToPercent(ratio)} %"

/**
 * Date of a history line in the phone's zone, or the raw text when it cannot be read
 * (a line must never disappear because of its date).
 */
fun historyDateText(
    iso: String,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String =
    try {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .format(OffsetDateTime.parse(iso).atZoneSameInstant(zone))
    } catch (e: Exception) {
        iso
    }
