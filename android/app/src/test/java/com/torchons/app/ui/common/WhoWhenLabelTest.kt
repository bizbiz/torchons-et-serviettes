package com.torchons.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The « who » half of the « Date + Qui » confirm button (docs/plans/a-faire-aujourdhui.md).
 *
 * PATHS by which the button could name the wrong people, listed before the cases:
 *   (1) me alone: « Toi » ........................................................ COVERED
 *   (2) someone else alone: their name ........................................... COVERED
 *   (3) me with others: others in list order, « Toi » always last ................ COVERED
 *   (4) four and more: counted, not named (null here) ............................ COVERED
 *   (5) nobody checked: null (the dialog shows « Valider », disabled) ............ COVERED
 *   non couvert : the « when » half and the final format (whenLabel, whoWhenLabel): they read
 *                 Resources, Android only.
 */
class WhoWhenLabelTest {
    private val me = "m"
    private val names = mapOf("m" to "Moi", "b" to "Bob", "a" to "Alice", "c" to "Carol")

    private fun who(vararg ids: String) = whoNames(ids.toList(), me, names, "Toi", "toi-last")

    @Test
    fun `me alone is You`() = assertEquals("Toi", who("m"))

    @Test
    fun `someone else alone is named`() = assertEquals("Bob", who("b"))

    @Test
    fun `me with others comes last`() {
        assertEquals("Bob & toi-last", who("m", "b"))
        assertEquals("Bob, Alice & toi-last", who("b", "m", "a"))
        assertEquals("Bob, Alice & Carol", who("b", "a", "c"))
    }

    @Test
    fun `four and more are counted, nobody is nothing`() {
        assertNull(who("m", "b", "a", "c"))
        assertNull(who())
    }
}
