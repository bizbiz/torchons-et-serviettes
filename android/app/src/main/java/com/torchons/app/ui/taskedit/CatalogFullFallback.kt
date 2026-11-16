package com.torchons.app.ui.taskedit

import com.google.gson.Gson
import com.torchons.app.data.model.CatalogSuggestion
import com.torchons.app.data.model.CatalogTaskContextView
import com.torchons.app.data.model.RecurrenceRule

private val gson = Gson()

/**
 * Whole-catalog proposals for a household that has no context yet.
 *
 * `GET /catalog/suggestions` answers an empty list when the household owns no context
 * (server side, nothing to match against), so the form falls back to the public
 * `GET /catalog/contexts` and maps each template to a proposal. `contextIds` stays empty on
 * purpose: the household has no context to point at, so applying a proposal sets none, and
 * `contextLabel` (built from the primary context name) is absent. Sorted lightest first,
 * like the server does.
 */
internal fun catalogTasksAsSuggestions(tasks: List<CatalogTaskContextView>): List<CatalogSuggestion> =
    tasks.map { t ->
        CatalogSuggestion(
            key = t.key,
            title = t.title,
            description = t.description,
            contextKeys = t.contexts,
            defaultWeight = t.defaultWeight,
            defaultRecurrence = t.defaultRecurrence?.let { toRecurrenceRule(it) },
            defaultTolerance = t.defaultTolerance,
        )
    }.sortedWith(compareBy({ it.defaultWeight }, { it.title }))

/** Gson turns JSON numbers into Double inside Map<String, Any>; a round trip re-types them. */
private fun toRecurrenceRule(raw: Map<String, Any>): RecurrenceRule? =
    runCatching { gson.fromJson(gson.toJson(raw), RecurrenceRule::class.java) }.getOrNull()
