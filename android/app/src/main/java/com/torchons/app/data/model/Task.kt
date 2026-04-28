package com.torchons.app.data.model

import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName

/**
 * Modele representant une tache menagere.
 * Le champ recurrenceRule est un objet JSON libre (JSONB cote backend).
 *
 * @Immutable car le data class contient List<String> qui est instable
 * pour Compose par defaut. On promet que ces collections ne mutent jamais
 * (data class = nouvelle instance a chaque modification). Permet a Compose
 * de smart-skip les items LazyColumn inchanges et stabilise le scroll.
 */
@Immutable
data class Task(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("category_id") val categoryId: String? = null,
    @SerializedName("room_id") val roomId: String? = null,
    @SerializedName("assigned_to_ids") val assignedToIds: List<String> = emptyList(),
    @SerializedName("recurrence_rule") val recurrenceRule: RecurrenceRule? = null,
    // v0.14 : cadence inferee pour les recurrences de type "uncertain" (null tant
    // qu'on a moins de 2 completions ou que le type n'est pas uncertain).
    @SerializedName("inferred_interval_days") val inferredIntervalDays: Double? = null,
    @SerializedName("weight") val weight: Int = 1,
    @SerializedName("tolerance") val tolerance: String = "NORMAL",
    @SerializedName("is_active") val isActive: Boolean = true,
    // v0.20 : archivage soft cote backend. true = tache ponctuelle completee
    // depuis >7j, masquee des listes par un job quotidien serveur. Lecture
    // seule cote client : on ne l'emet jamais dans CreateTask/UpdateTask.
    @SerializedName("is_archived") val isArchived: Boolean = false,
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
    @SerializedName("room_id") val roomId: String? = null,
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
    @SerializedName("room_id") val roomId: String? = null,
    @SerializedName("assigned_to_ids") val assignedToIds: List<String>? = null,
    @SerializedName("recurrence_rule") val recurrenceRule: RecurrenceRule? = null,
    @SerializedName("weight") val weight: Int? = null,
    @SerializedName("tolerance") val tolerance: String? = null,
    @SerializedName("is_active") val isActive: Boolean? = null
)

/**
 * Regle de recurrence pour une tache (v0.27 — types simplifies).
 *
 * Types supportes :
 * - `interval` : cadence reguliere (every_n_days obligatoire).
 * - `monthly_variable` : cadence variable selon le mois (days_per_month
 *   obligatoire — 12 entrees "1".."12").
 * - `uncertain` : cadence libre inferee cote backend (v0.14).
 *
 * @Immutable pour les memes raisons que Task (champs Map).
 */
@Immutable
data class RecurrenceRule(
    @SerializedName("type") val type: String,
    @SerializedName("every_n_days") val everyNDays: Int? = null,
    @SerializedName("days_per_month") val daysPerMonth: Map<String, Int>? = null,
)
