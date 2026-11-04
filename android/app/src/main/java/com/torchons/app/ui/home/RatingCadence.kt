package com.torchons.app.ui.home

import com.torchons.app.data.api.RatingPromptApi
import com.torchons.app.data.api.reportOutcomeSafe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cadence of the AUTOMATIC rating pop-up (the server keeps one gap in days per member).
 *
 * The server decides when the pop-up is due (`rating_prompt_due` in the settings). This
 * class keeps what the server cannot know: whether the pop-up now on screen was opened
 * automatically, so that exactly ONE outcome is reported per automatic pop-up, at the
 * first gesture (a rating, or a dismissal). Manual ratings and the ratings chained after
 * the first one never report anything.
 *
 * After an outcome the pop-up is also marked "not due" locally for [MIN_GAP_MS] (the
 * smallest gap the server can set), so coming back to the app before the next refresh
 * cannot show it again, even if the report failed offline.
 */
@Singleton
class RatingCadence internal constructor(
    private val api: RatingPromptApi,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) {
    @Inject
    constructor(api: RatingPromptApi) : this(
        api, CoroutineScope(SupervisorJob() + Dispatchers.IO), System::currentTimeMillis,
    )

    private var serverDue = true
    private var suppressedUntil = 0L
    private var autoOpen = false

    /** Read the server flag from the cached settings. Absent (older server) means due. */
    @Synchronized
    fun syncDue(settings: Map<String, Any?>?) {
        serverDue = (settings?.get("rating_prompt_due") as? Boolean) ?: true
    }

    /**
     * Whether the pop-up must be on screen after a refresh. [eligible] is everything
     * the app already required (pending task, gate armed, setting on, no other prompt).
     * A pop-up already on screen stays, whatever its origin; a new one opens only when due.
     */
    @Synchronized
    fun resolveAuto(eligible: Boolean, wasShown: Boolean): Boolean {
        val due = serverDue && now() >= suppressedUntil
        val show = eligible && (wasShown || due)
        autoOpen = show && (autoOpen || !wasShown)
        return show
    }

    /** The user opened the pop-up by hand: whatever happens in it reports nothing. */
    @Synchronized
    fun onManualOpened() {
        autoOpen = false
    }

    /** First rating given in the pop-up (the next ones of a chain are not automatic). */
    fun onRated() = report(answered = true)

    /** The pop-up was closed. */
    fun onDismissed() = report(answered = false)

    private fun report(answered: Boolean) {
        synchronized(this) {
            if (!autoOpen) return
            autoOpen = false
            suppressedUntil = now() + MIN_GAP_MS
        }
        scope.launch { api.reportOutcomeSafe(answered) }
    }

    private companion object {
        const val MIN_GAP_MS = 24L * 60 * 60 * 1000
    }
}
