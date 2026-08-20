package com.torchons.app.data.model

import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName

/**
 * Modeles pour le catalogue de templates (v0.9.0 Feature 4).
 *
 * Les champs label (name, title, description) arrivent deja resolus en locale
 * fr par le backend (pas de dict locale ici — c'est decide cote serveur).
 */

// --- Import flow ---

/** Body POST /api/catalog/import/resolve. */
data class ImportResolveRequest(
    @SerializedName("task_keys") val taskKeys: List<String> = emptyList(),
    @SerializedName("room_keys") val roomKeys: List<String> = emptyList(),
    @SerializedName("category_keys") val categoryKeys: List<String> = emptyList(),
)

/**
 * Une resolution proposee par le backend pour un template_key donne.
 * strategy = 'create' | 'map_existing' | 'already_same_template'
 */
@Immutable
data class ResolvedItem(
    @SerializedName("template_key") val templateKey: String,
    @SerializedName("template_name") val templateName: String,
    @SerializedName("strategy") val strategy: String,
    @SerializedName("existing_id") val existingId: String? = null,
    @SerializedName("existing_name") val existingName: String? = null,
)

@Immutable
data class ImportResolveResponse(
    @SerializedName("rooms") val rooms: List<ResolvedItem> = emptyList(),
    @SerializedName("categories") val categories: List<ResolvedItem> = emptyList(),
    @SerializedName("tasks") val tasks: List<ResolvedItem> = emptyList(),
)

/** Decision finale de l'user pour un template. decision = 'create' | 'map'. */
data class ImportDecision(
    @SerializedName("template_key") val templateKey: String,
    @SerializedName("decision") val decision: String,
    @SerializedName("map_to_id") val mapToId: String? = null,
)

/** Body POST /api/catalog/import/apply. */
data class ImportApplyRequest(
    @SerializedName("task_keys") val taskKeys: List<String> = emptyList(),
    @SerializedName("context_keys") val contextKeys: List<String> = emptyList(),
    @SerializedName("room_decisions") val roomDecisions: List<ImportDecision> = emptyList(),
    @SerializedName("category_decisions") val categoryDecisions: List<ImportDecision> = emptyList(),
    @SerializedName("room_keys") val roomKeys: List<String> = emptyList(),
    @SerializedName("category_keys") val categoryKeys: List<String> = emptyList(),
)

@Immutable
data class ImportApplyResponse(
    @SerializedName("created_tasks") val createdTasks: Int,
    @SerializedName("skipped") val skipped: Int,
)

// --- Contextes (chantier contextes) ---
//
// Remplacent `rooms` + `categories`. Une tache en declare PLUSIEURS, l'ordre
// etant significatif : le premier est le principal. Les anciens modeles
// ci-dessus restent le temps que l'APK diffuse disparaisse — le serveur sert
// encore l'ancienne forme sur GET /api/catalog.

@Immutable
data class CatalogContextView(
    @SerializedName("key") val key: String,
    @SerializedName("name") val name: String,
    @SerializedName("color") val color: String,
    @SerializedName("emoji") val emoji: String? = null,
    /**
     * False = contexte implicite que tout foyer a (menage, linge, entretien).
     * Jamais propose a l'etape 3 — « faites-vous du menage ? » n'a pas de
     * sens — mais porte le filtre « montre-moi tout le menage ».
     */
    @SerializedName("selectable") val selectable: Boolean = true,
    @SerializedName("default_selected") val defaultSelected: Boolean = false,
    @SerializedName("requires_presence") val requiresPresence: Boolean = false,
    @SerializedName("accrues_when_away") val accruesWhenAway: Boolean = false,
)

@Immutable
data class CatalogTaskContextView(
    @SerializedName("key") val key: String,
    @SerializedName("title") val title: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("contexts") val contexts: List<String> = emptyList(),
    @SerializedName("default_weight") val defaultWeight: Int = 1,
    @SerializedName("default_recurrence") val defaultRecurrence: Map<String, Any>? = null,
    @SerializedName("default_tolerance") val defaultTolerance: String = "NORMAL",
    @SerializedName("default_selected") val defaultSelected: Boolean = false,
)

@Immutable
data class CatalogContextsResponse(
    @SerializedName("contexts") val contexts: List<CatalogContextView> = emptyList(),
    @SerializedName("tasks") val tasks: List<CatalogTaskContextView> = emptyList(),
)


/**
 * Une tâche du catalogue proposée au foyer parce qu'il en a le contexte.
 *
 * Le serveur a déjà résolu `contextIds` vers les contextes DU FOYER : le
 * client n'a pas à refaire l'appariement, il n'aurait de toute façon pas la
 * table de correspondance entre clés de catalogue et contextes locaux.
 */
data class CatalogSuggestion(
    @SerializedName("key") val key: String,
    @SerializedName("title") val title: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("context_keys") val contextKeys: List<String> = emptyList(),
    @SerializedName("context_ids") val contextIds: List<String> = emptyList(),
    @SerializedName("primary_context_name") val primaryContextName: String? = null,
    @SerializedName("primary_context_emoji") val primaryContextEmoji: String? = null,
    @SerializedName("default_weight") val defaultWeight: Int = 1,
    @SerializedName("default_recurrence") val defaultRecurrence: RecurrenceRule? = null,
    @SerializedName("default_tolerance") val defaultTolerance: String = "NORMAL",
) {
    val contextLabel: String?
        get() = primaryContextName?.let {
            if (primaryContextEmoji != null) "$primaryContextEmoji $it" else it
        }
}

data class CatalogSuggestionsResponse(
    @SerializedName("suggestions") val suggestions: List<CatalogSuggestion> = emptyList(),
)
