package com.torchons.app.ui.taskedit

import com.torchons.app.data.model.CatalogSuggestion
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Perimeter: the pure function [filterCatalogSuggestions] only (context + title narrowing).
 *
 * Paths by which "checked contexts filter the proposals" can be violated:
 * - no context checked -> must not filter (covered)
 * - one context / two contexts (OR semantics) -> covered
 * - context + title together (AND) -> covered
 * - context with no matching proposal -> empty list (covered)
 * - non couvert : ViewModel wiring (matchingSuggestions reading state.contextIds), needs a Hilt VM
 * - non couvert : empty-card hiding and screen order, Compose rendering, no instrumented tests here
 * - non couvert : applySuggestion/clearSuggestion state transitions, VM construction needs repositories
 */
class CatalogSuggestionFilterTest {

    private fun sugg(key: String, title: String, vararg contexts: String) =
        CatalogSuggestion(key = key, title = title, contextIds = contexts.toList())

    private val litter = sugg("litter", "Nettoyer la litière", "cat")
    private val dishes = sugg("dishes", "Faire la vaisselle", "kitchen")
    private val oven = sugg("oven", "Nettoyer le four", "kitchen", "appliances")
    private val all = listOf(litter, dishes, oven)

    private fun keys(l: List<CatalogSuggestion>) = l.map { it.key }

    @Test
    fun noContextAndNoQueryKeepsEverything() {
        assertEquals(listOf("litter", "dishes", "oven"), keys(filterCatalogSuggestions(all, "", emptyList())))
    }

    @Test
    fun oneContextKeepsOnlyItsProposals() {
        assertEquals(listOf("dishes", "oven"), keys(filterCatalogSuggestions(all, "", listOf("kitchen"))))
    }

    @Test
    fun twoContextsKeepAProposalCarryingEitherOne() {
        val r = filterCatalogSuggestions(all, "", listOf("cat", "appliances"))
        assertEquals(listOf("litter", "oven"), keys(r))
    }

    @Test
    fun contextAndTitleBothApply() {
        val r = filterCatalogSuggestions(all, "nettoyer", listOf("kitchen"))
        assertEquals(listOf("oven"), keys(r))
    }

    @Test
    fun titleIsAccentInsensitiveWithoutContext() {
        assertEquals(listOf("litter"), keys(filterCatalogSuggestions(all, "litiere", emptyList())))
    }

    @Test
    fun contextWithoutAnyProposalGivesEmptyList() {
        assertEquals(emptyList<String>(), keys(filterCatalogSuggestions(all, "", listOf("garden"))))
    }
}
