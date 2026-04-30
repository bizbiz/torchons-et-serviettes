package com.torchons.app.data.model

import com.google.gson.annotations.SerializedName

/** Suggestion de tache. */
data class Suggestion(
    @SerializedName("task_id") val taskId: String,
    @SerializedName("title") val title: String,
    @SerializedName("category_id") val categoryId: String? = null,
    @SerializedName("weight") val weight: Int = 1,
    /** Etat d'urgence : "GREEN" / "ORANGE" / "RED". */
    @SerializedName("state") val state: String = "GREEN",
    /** Jours d'ici l'echeance ; negatif si en retard, 0 si aujourd'hui. */
    @SerializedName("days_until_due") val daysUntilDue: Int = 0,
    @SerializedName("affinity_score") val affinityScore: Int = 5,
    @SerializedName("reason") val reason: String = "urgent",
    /** True si la prochaine completion de cette tache touchera le bonus retard. */
    @SerializedName("bonus_eligible") val bonusEligible: Boolean = false,
    /** Pct de bonus (ex 0.5 = +50%). */
    @SerializedName("bonus_pct") val bonusPct: Float = 0.0f,
)

/**
 * Enveloppe de reponse /suggestions (v0.16+).
 *
 * remainingByTier : nombre de candidats restants par tier (urgent /
 * affinity / uncertain) APRES application du filtre ?exclude=. Le front
 * l'utilise pour detecter qu'un tier est epuise (valeur = 0) et
 * declencher le recycle de la dismiss list locale.
 */
data class SuggestionsResponse(
    @SerializedName("suggestions") val suggestions: List<Suggestion>,
    @SerializedName("remaining_by_tier") val remainingByTier: Map<String, Int> = emptyMap(),
)

/** Corps du POST /suggestions/dismiss (log analytics fire-and-forget). */
data class DismissRequest(
    @SerializedName("task_id") val taskId: String,
    @SerializedName("tier") val tier: String,
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
