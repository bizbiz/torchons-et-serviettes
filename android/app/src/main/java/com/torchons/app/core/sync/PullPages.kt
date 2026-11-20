package com.torchons.app.core.sync

/**
 * Most extra pull pages one worker run asks for. The server cuts an answer at 500 change_log rows
 * (`has_more`); 20 pages is 10 000 rows, far beyond a household's backlog. Past it the run stops
 * and the next one resumes from the cursor, which only ever moves past rows that were delivered.
 */
internal const val MAX_PULL_PAGES = 20

/** One extra pull: whether the request went, and whether the server says more changes wait. */
internal class PullPage(val ok: Boolean, val hasMore: Boolean)

/**
 * Pulls again while the last /sync answer said `has_more`, at most [maxPages] times (the worker
 * used to take the first 500 rows and stop: a device that was away for long never caught up
 * unless something else refreshed it). [startsWithMore] is the `has_more` of the last batch the
 * pass sent. Returns false when a pull failed (the pass is then a retry), true otherwise, also
 * when [maxPages] cut the loop short: the cursor is persisted, the next run goes on.
 */
internal suspend fun pullWhileMore(startsWithMore: Boolean, maxPages: Int, pull: suspend () -> PullPage): Boolean {
    var more = startsWithMore
    var pages = 0
    while (more && pages < maxPages) {
        val page = pull()
        if (!page.ok) return false
        more = page.hasMore
        pages++
    }
    return true
}
