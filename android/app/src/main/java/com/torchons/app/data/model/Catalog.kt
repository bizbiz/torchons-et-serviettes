package com.torchons.app.data.model

import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName

/**
 * Modeles pour le catalogue de templates (v0.9.0 Feature 4).
 *
 * Les champs label (name, title, description) arrivent deja resolus en locale
 * fr par le backend (pas de dict locale ici — c'est decide cote serveur).
 */

@Immutable
data class CatalogRoomView(
    @SerializedName("key") val key: String,
    @SerializedName("name") val name: String,
    @SerializedName("emoji") val emoji: String? = null,
)

@Immutable
data class CatalogCategoryView(
    @SerializedName("key") val key: String,
    @SerializedName("name") val name: String,
    @SerializedName("color") val color: String,
    @SerializedName("requires_home") val requiresHome: Boolean,
)

@Immutable
data class CatalogTaskView(
    @SerializedName("key") val key: String,
    @SerializedName("title") val title: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("default_category_key") val defaultCategoryKey: String? = null,
    @SerializedName("default_room_key") val defaultRoomKey: String? = null,
    @SerializedName("default_weight") val defaultWeight: Int = 1,
    @SerializedName("default_recurrence") val defaultRecurrence: Map<String, Any>? = null,
    @SerializedName("default_tolerance") val defaultTolerance: String = "NORMAL",
)

@Immutable
data class CatalogResponse(
    @SerializedName("rooms") val rooms: List<CatalogRoomView> = emptyList(),
    @SerializedName("categories") val categories: List<CatalogCategoryView> = emptyList(),
    @SerializedName("tasks") val tasks: List<CatalogTaskView> = emptyList(),
)

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
    @SerializedName("room_decisions") val roomDecisions: List<ImportDecision> = emptyList(),
    @SerializedName("category_decisions") val categoryDecisions: List<ImportDecision> = emptyList(),
    @SerializedName("room_keys") val roomKeys: List<String> = emptyList(),
    @SerializedName("category_keys") val categoryKeys: List<String> = emptyList(),
)

@Immutable
data class ImportApplyResponse(
    @SerializedName("created_rooms") val createdRooms: Int,
    @SerializedName("created_categories") val createdCategories: Int,
    @SerializedName("created_tasks") val createdTasks: Int,
    @SerializedName("skipped") val skipped: Int,
)
