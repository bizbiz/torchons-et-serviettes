package com.torchons.app.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Perimeter: which prompt types skip the 2 h client tolerance, and when a prompt is open.
 * Not covered: the fetch itself (ViewModel, emulator run).
 */
class PromptPolicyTest {
    private val now = 10_000_000_000L
    private val oneHourAgo = now - 3_600_000L
    private val threeHoursAgo = now - 3 * 3_600_000L

    @Test
    fun requestedRevotesBypassTheTolerance() {
        assertTrue(PromptPolicy.bypassesTolerance("VOTE_FREQUENCY"))
        assertTrue(PromptPolicy.bypassesTolerance("VOTE_EFFORT"))
        assertTrue(PromptPolicy.bypassesTolerance("RESOLVE_PRESENCE"))
        assertFalse(PromptPolicy.isHeldBackByTolerance("VOTE_EFFORT", oneHourAgo, now))
        assertFalse(PromptPolicy.isHeldBackByTolerance("VOTE_FREQUENCY", oneHourAgo, now))
    }

    @Test
    fun machinePromptsKeepTheTwoHourDelay() {
        for (type in listOf("BUMP_EFFORT", "VOTE_RATING", "DISCOVER_MODULE")) {
            assertFalse(PromptPolicy.bypassesTolerance(type))
            assertTrue(PromptPolicy.isHeldBackByTolerance(type, oneHourAgo, now))
            assertFalse(PromptPolicy.isHeldBackByTolerance(type, threeHoursAgo, now))
            assertFalse(PromptPolicy.isHeldBackByTolerance(type, 0L, now))
        }
    }

    @Test
    fun anyOpenPromptBlocksAnotherOne() {
        assertFalse(PromptPolicy.isPromptOpen(false, false, false))
        assertTrue(PromptPolicy.isPromptOpen(true, false, false))
        assertTrue(PromptPolicy.isPromptOpen(false, true, false))
        assertTrue(PromptPolicy.isPromptOpen(false, false, true))
    }
}
