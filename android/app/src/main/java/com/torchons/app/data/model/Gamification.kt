package com.torchons.app.data.model

import com.google.gson.annotations.SerializedName

/** Suggestion de tache. */
data class Suggestion(
    @SerializedName("task_id") val taskId: String,
    @SerializedName("title") val title: String,
    @SerializedName("category_id") val categoryId: String? = null,
    @SerializedName("weight") val weight: Int = 1,
    /**
     * Etat d'urgence : "GREEN" / "ORANGE" / "RED".
     *
     * Nullable parce que Gson n'applique pas les defaults Kotlin quand il
     * lit un snapshot cache par une version precedente de l'app (sans ce
     * champ). Le rendu fallback sur VERT tant que le refresh n'a pas eu
     * lieu, evitant un crash NPE sur le cache stale.
     */
    @SerializedName("state") val state: String? = null,
    /** Jours d'ici l'echeance ; negatif si en retard, 0 si aujourd'hui. */
    @SerializedName("days_until_due") val daysUntilDue: Int = 0,
    @SerializedName("affinity_score") val affinityScore: Int = 5,
    @SerializedName("reason") val reason: String = "urgent"
)

/** Tache en cours. */
data class TaskInProgress(
    @SerializedName("task_id") val taskId: String,
    @SerializedName("title") val title: String,
    @SerializedName("user_id") val userId: String,
    @SerializedName("started_at") val startedAt: String
)

/** Tache en attente de notation d'affinite/effort. */
data class PendingAffinity(
    @SerializedName("task_id") val taskId: String,
    @SerializedName("title") val title: String,
    @SerializedName("current_affinity") val currentAffinity: Int? = null,
    @SerializedName("current_effort_points") val currentEffortPoints: Int? = null,
    @SerializedName("recurrence_rule") val recurrenceRule: RecurrenceRule? = null,
    @SerializedName("pending_total") val pendingTotal: Int? = null,
    /** Date ISO de la derniere notation (max affinity/effort updated_at) du user courant. */
    @SerializedName("last_rated_at") val lastRatedAt: String? = null,
)

/** Statut fair-share. */
data class FairShareStatus(
    @SerializedName("user_id") val userId: String,
    @SerializedName("display_name") val displayName: String,
    @SerializedName("target_ratio") val targetRatio: Double,
    @SerializedName("actual_ratio") val actualRatio: Double,
    @SerializedName("above_target") val aboveTarget: Boolean
)

/** Corps pour definir l'affinite. */
data class AffinityRequest(
    @SerializedName("score") val score: Int
)
