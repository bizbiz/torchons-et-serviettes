package com.torchons.app.ui.taskedit

import com.google.gson.Gson
import com.torchons.app.data.model.CatalogContextsResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Perimeter: [catalogTasksAsSuggestions] (full-catalog fallback for a household without context)
 * combined with [filterCatalogSuggestions]. The payload goes through Gson like the real
 * `GET /catalog/contexts` answer, so numbers arrive as Double inside the recurrence map.
 *
 * Not covered: TaskEditLoader wiring (needs repositories) and Compose rendering.
 */
class CatalogFullFallbackTest {

    private val json = """
        {"contexts": [], "tasks": [
          {"key": "oven", "title": "Nettoyer le four", "contexts": ["kitchen"],
           "default_tolerance": "NORMAL", "default_weight": 3,
           "default_recurrence": {"type": "interval", "every_n_days": 30}},
          {"key": "litter", "title": "Nettoyer la litière", "contexts": ["cat"],
           "default_tolerance": "NORMAL", "default_weight": 1},
          {"key": "dishes", "title": "Faire la vaisselle", "contexts": ["kitchen"],
           "default_tolerance": "NORMAL", "default_weight": 1}
        ]}
    """.trimIndent()

    private val suggestions = catalogTasksAsSuggestions(
        Gson().fromJson(json, CatalogContextsResponse::class.java).tasks,
    )

    @Test
    fun keepsWholeCatalogSortedLightestFirst() {
        assertEquals(listOf("dishes", "litter", "oven"), suggestions.map { it.key })
    }

    @Test
    fun noHouseholdContextMeansNoContextIdsAndNoLabel() {
        assertTrue(suggestions.all { it.contextIds.isEmpty() && it.contextLabel == null })
        assertEquals(listOf("kitchen"), suggestions.first { it.key == "oven" }.contextKeys)
    }

    @Test
    fun recurrenceSurvivesTheDoubleRoundTrip() {
        val rule = suggestions.first { it.key == "oven" }.defaultRecurrence
        assertNotNull(rule)
        assertEquals("interval", rule!!.type)
        assertEquals(30, rule.everyNDays)
        assertNull(suggestions.first { it.key == "litter" }.defaultRecurrence)
    }

    @Test
    fun titleOnlyFilterWorksWithoutAnyContext() {
        val hits = filterCatalogSuggestions(suggestions, "litiere", emptyList())
        assertEquals(listOf("litter"), hits.map { it.key })
    }
}
