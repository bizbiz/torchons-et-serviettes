package com.torchons.app.ui.common

import com.google.gson.Gson
import com.torchons.app.data.model.ModuleView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Perimeter: the PURE guide decisions only (pickDueGuide, guideFromPromptContext,
 * DTO defaults). Paths by which a wrong guide could show:
 * - several due modules: first in registry order wins (picksFirstDueInRegistryOrder)
 * - due but already seen locally: skipped (skipsDueGuideAlreadySeen)
 * - prompt modal on screen: nothing (nothingWhilePromptShowing)
 * - none due: nothing (nothingWhenNoneDue)
 * - old server without the fields: defaults (oldServerJson...)
 * - old DISCOVER_MODULE prompt without guide text: blanks (promptContextWithoutGuideText...)
 * Not covered: network calls (guide-seen POST, offline guard), no live server and the
 * ViewModels need Android/Hilt; Compose rendering, needs an emulator (forbidden here).
 */
class ModuleGuideTest {

    private fun module(key: String, order: Int, due: Boolean = false, seen: Boolean = false) =
        ModuleView(
            key = key, scope = "collective", order = order, enabled = true,
            title = key, pitch = "", details = "", guideDue = due, guideSeen = seen,
        )

    @Test
    fun picksFirstDueInRegistryOrder() {
        val modules = listOf(module("c", 3, due = true), module("a", 1), module("b", 2, due = true))
        assertEquals("b", pickDueGuide(modules, promptShowing = false)?.key)
    }

    @Test
    fun skipsDueGuideAlreadySeen() {
        assertNull(pickDueGuide(listOf(module("a", 1, due = true, seen = true)), false))
    }

    @Test
    fun nothingWhilePromptShowing() {
        assertNull(pickDueGuide(listOf(module("a", 1, due = true)), promptShowing = true))
    }

    @Test
    fun nothingWhenNoneDue() {
        assertNull(pickDueGuide(listOf(module("a", 1), module("b", 2)), false))
    }

    @Test
    fun oldServerJsonWithoutGuideFieldsDecodesWithDefaults() {
        val json = """{"key":"k","scope":"collective","order":1,"enabled":true,
            "title":"t","pitch":"p","details":"d"}"""
        val m = Gson().fromJson(json, ModuleView::class.java)
        assertEquals("", m.toGuide().why)
        assertEquals("", m.toGuide().how)
        assertTrue(!m.guideSeen && !m.guideDue)
    }

    @Test
    fun promptContextWithoutGuideTextIsBlank() {
        val g = guideFromPromptContext("k", mapOf("title" to "T", "pitch" to "P"))
        assertEquals("", g.why)
        assertEquals("", g.how)
        assertNull(g.example)
    }

    @Test
    fun promptContextWithGuideText() {
        val g = guideFromPromptContext("k", mapOf("guide_why" to "W", "guide_how" to "H", "example" to "E"))
        assertEquals("W", g.why)
        assertEquals("H", g.how)
        assertEquals("E", g.example)
    }
}
