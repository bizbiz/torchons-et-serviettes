package com.torchons.app.ui.home

/**
 * When the home screen may show a prompt fetched from the server queue.
 *
 * Pure functions, so the rules can be tested on the JVM.
 */
object PromptPolicy {
    /** Client tolerance between two machine-generated prompts. */
    const val TOLERANCE_MS: Long = 2 * 3600 * 1000L

    /**
     * Prompts that skip the 2 h tolerance: the presence question (frozen tasks) and the
     * re-votes another member asked for by hand. A direct request must not wait behind a
     * prompt shown earlier; machine prompts (BUMP_EFFORT, VOTE_RATING...) keep the delay.
     */
    fun bypassesTolerance(type: String): Boolean =
        type == "RESOLVE_PRESENCE" || type == "VOTE_FREQUENCY" || type == "VOTE_EFFORT"

    /** True when a prompt (queue modal or vote dialog) is already on screen: never stack a second. */
    fun isPromptOpen(queueModal: Boolean, frequencyVote: Boolean, effortVote: Boolean): Boolean =
        queueModal || frequencyVote || effortVote

    /** True when the tolerance forbids showing a prompt of [type] now. */
    fun isHeldBackByTolerance(type: String, lastShownAtMs: Long, nowMs: Long): Boolean =
        !bypassesTolerance(type) && lastShownAtMs > 0 && nowMs - lastShownAtMs < TOLERANCE_MS
}
