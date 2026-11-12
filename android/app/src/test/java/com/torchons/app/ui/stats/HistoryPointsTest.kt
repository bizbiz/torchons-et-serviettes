package com.torchons.app.ui.stats

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The points suffix the server writes in history lines (api/stats.py, completion_solo /
 * completion_collective, FR and EN) goes when gamification is hidden.
 * Not covered: a template changed on the server — the line then keeps its suffix, visible, not
 * broken.
 */
class HistoryPointsTest {

    @Test
    fun `the four server templates lose their points`() {
        assertEquals("Camille a complété : Aspirateur", stripPointsSuffix("Camille a complété : Aspirateur (+1 pts)"))
        assertEquals("A & B ont fait : Four", stripPointsSuffix("A & B ont fait : Four (+2 pts chacun)"))
        assertEquals("Camille completed: Oven", stripPointsSuffix("Camille completed: Oven (+12 pts)"))
        assertEquals("A & B did: Oven", stripPointsSuffix("A & B did: Oven (+2 pts each)"))
    }

    @Test
    fun `other lines and visible gamification are left alone`() {
        val vote = mapOf<String, Any>("description" to "Camille a voté (3 jours)")
        assertEquals(vote, withoutPoints(listOf(vote), gamificationVisible = false).single())
        val done = mapOf<String, Any>("description" to "Camille a complété : Four (+1 pts)")
        assertEquals(done, withoutPoints(listOf(done), gamificationVisible = true).single())
    }
}
