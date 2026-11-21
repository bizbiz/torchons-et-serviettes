package com.torchons.app.ui.common

/** What the rating dialog says under the title about the user's previous rating. */
internal enum class PreviousRatingLine {
    /** The date of the last rating, relative ("il y a 3 jours"). */
    RELATIVE_DATE,

    /** The server answered and there is none: "Jamais noté". */
    NEVER_RATED,

    /** Offline and nothing cached: the previous rating exists perhaps, nobody knows. */
    PREVIOUS_UNKNOWN,

    /** Offline with cached values but no date: the sliders show the rating, its age is unknown. */
    DATE_UNKNOWN,
}

/**
 * "Jamais noté" is a claim about the server. When the server could not be asked
 * ([previousUnknown], set by the screen when the rating-context request failed) and there is no
 * date, the dialog must not make it: the user could overwrite a vote they cannot see (emulator
 * re-test of 2026-11-20: affinity 9/10 on the server, "Jamais noté" and defaults 5/10 offline).
 */
internal fun previousRatingLine(
    lastRatedAt: String?,
    previousUnknown: Boolean,
    hasCachedValues: Boolean,
): PreviousRatingLine = when {
    lastRatedAt != null -> PreviousRatingLine.RELATIVE_DATE
    !previousUnknown -> PreviousRatingLine.NEVER_RATED
    hasCachedValues -> PreviousRatingLine.DATE_UNKNOWN
    else -> PreviousRatingLine.PREVIOUS_UNKNOWN
}
