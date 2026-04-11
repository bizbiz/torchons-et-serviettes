package com.torchons.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * Modele representant une tache menagere.
 * Le champ recurrenceRule est un objet JSON libre (JSONB cote backend).
 */
data class Task(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("category_id") val categoryId: String? = null,
    @SerializedName("assigned_to_ids") val assignedToIds: List<String> = emptyList(),
    @SerializedName("recurrence_rule") val recurrenceRule: RecurrenceRule? = null,
    @SerializedName("weight") val weight: Int = 1,
    @SerializedName("tolerance") val tolerance: String = "NORMAL",
    @SerializedName("is_active") val isActive: Boolean = true,
    @SerializedName("sort_order") val sortOrder: Int = 0,
    @SerializedName("created_at") val createdAt: String = "",
    @SerializedName("updated_at") val updatedAt: String = ""
)

/**
 * Corps de la requete pour creer une tache.
 */
data class CreateTaskRequest(
    @SerializedName("title") val title: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("category_id") val categoryId: String? = null,
    @SerializedName("assigned_to_ids") val assignedToIds: List<String>,
    @SerializedName("recurrence_rule") val recurrenceRule: RecurrenceRule? = null,
    @SerializedName("weight") val weight: Int = 1,
    @SerializedName("tolerance") val tolerance: String = "NORMAL"
)

/**
 * Corps de la requete pour modifier une tache.
 */
data class UpdateTaskRequest(
    @SerializedName("title") val title: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("category_id") val categoryId: String? = null,
    @SerializedName("assigned_to_ids") val assignedToIds: List<String>? = null,
    @SerializedName("recurrence_rule") val recurrenceRule: RecurrenceRule? = null,
    @SerializedName("weight") val weight: Int? = null,
    @SerializedName("tolerance") val tolerance: String? = null,
    @SerializedName("is_active") val isActive: Boolean? = null
)

/**
 * Regle de recurrence pour une tache.
 * Types supportes : interval, weekdays, monthly, custom.
 */
data class RecurrenceRule(
    @SerializedName("type") val type: String,
    @SerializedName("every_n_days") val everyNDays: Int? = null,
    @SerializedName("days") val days: List<Int>? = null,
    @SerializedName("day_of_month") val dayOfMonth: Int? = null,
    @SerializedName("every_n_weeks") val everyNWeeks: Int? = null,
    @SerializedName("day") val day: Int? = null,
    @SerializedName("days_per_month") val daysPerMonth: Map<String, Int>? = null
)
