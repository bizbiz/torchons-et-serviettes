package com.torchons.app.core.revote

import com.torchons.app.data.model.FrequencyRequest
import com.torchons.app.data.model.PromptResolveRequest
import com.torchons.app.ui.common.wireUniformDays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Paths by which a re-vote answer reaches the dialog or the notification tray:
 * - no registration: push not consumed (notification shows) - covered;
 * - registration of another task or kind - covered;
 * - matching registration: consumed, then unregistered - covered;
 * - push without task id - covered;
 * - wire formatting of a frequency vote (uniform or not) and the resolve payload - covered.
 * non couvert : emission reaching RevoteFeedbackViewModel, needs a coroutine test harness
 * non couvert : strings rendered by revoteNoticeText, needs Android Resources
 * non couvert : real FCM delivery, not reachable from a unit test
 */
class RevoteAnswerRegistryTest {
    private val push = mapOf(
        "type" to "revote_answered", "task_id" to "t1", "kind" to "effort",
        "resolution" to "answered", "responder_name" to "Lo", "old_value" to "3", "new_value" to "2",
    )

    @Test
    fun `a push nobody waits for is not consumed, so the notification shows`() {
        assertFalse(RevoteAnswerRegistry().consume(push))
    }

    @Test
    fun `a registration for another task or kind does not match`() {
        val registry = RevoteAnswerRegistry()
        registry.register("t2", "effort")
        registry.register("t1", "frequency")
        assertFalse(registry.consume(push))
    }

    @Test
    fun `a matching registration consumes the push and the registration ends with unregister`() {
        val registry = RevoteAnswerRegistry()
        registry.register("t1", "effort")
        assertTrue(registry.consume(push))
        registry.unregister("t1", "effort")
        assertFalse(registry.consume(push))
    }

    @Test
    fun `a push without a task is never consumed`() {
        val registry = RevoteAnswerRegistry()
        registry.register("t1", "effort")
        assertFalse(registry.consume(push - "task_id"))
    }

    @Test
    fun `the answer keeps the raw values`() {
        val answer = RevoteAnswerRegistry.parse(push)!!
        assertEquals("3", answer.oldValue)
        assertEquals("2", answer.newValue)
        assertEquals("Lo", answer.responderName)
    }

    @Test
    fun `a frequency wire value is uniform only when the twelve months agree`() {
        assertEquals(7, wireUniformDays(List(12) { "7" }.joinToString(",")))
        assertNull(wireUniformDays("7,7,7,7,7,7,7,7,7,7,7,30"))
        assertNull(wireUniformDays(""))
    }

    @Test
    fun `the resolve payload carries the new vote`() {
        assertEquals(mapOf("points" to 2), PromptResolveRequest.effortAnswer(2).payload)
        val days = PromptResolveRequest.frequencyAnswer(FrequencyRequest(everyNDays = 14)).payload!!["days_per_month"]
        assertEquals((1..12).associate { it.toString() to 14 }, days)
    }
}
