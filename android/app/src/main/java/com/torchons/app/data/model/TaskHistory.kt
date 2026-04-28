package com.torchons.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * Corps pour definir le vote de frequence (v0.27).
 *
 * Exactement un des deux champs doit etre fourni :
 * - `everyNDays` : raccourci interval (la valeur est expansee 12 mois cote
 *   serveur).
 * - `daysPerMonth` : forme canonique 12 mois (cle = "1".."12", valeur = int).
 */
data class FrequencyRequest(
    @SerializedName("every_n_days") val everyNDays: Int? = null,
    @SerializedName("days_per_month") val daysPerMonth: Map<String, Int>? = null,
)

/** Reponse du serveur apres un vote de frequence. */
data class FrequencyVoteResponse(
    @SerializedName("task_id") val taskId: String,
    @SerializedName("user_id") val userId: String,
    @SerializedName("days_per_month") val daysPerMonth: Map<String, Int> = emptyMap(),
    @SerializedName("consensus_rule") val consensusRule: ConsensusRule? = null,
)

/**
 * Forme du consensus telle que stockee/retournee par le serveur. Forme
 * collapse-on-write : type=interval si les 12 mois sont egaux, sinon
 * monthly_variable.
 */
data class ConsensusRule(
    @SerializedName("type") val type: String,
    @SerializedName("every_n_days") val everyNDays: Int? = null,
    @SerializedName("days_per_month") val daysPerMonth: Map<String, Int>? = null,
)

/** Vote individuel d'un autre membre du foyer (sans le user courant). */
data class OtherFrequencyVote(
    @SerializedName("user_id") val userId: String,
    @SerializedName("display_name") val displayName: String,
    @SerializedName("days_per_month") val daysPerMonth: Map<String, Int> = emptyMap(),
    @SerializedName("updated_at") val updatedAt: String? = null,
)

/**
 * Etat des votes de frequence pour une tache, retourne par
 * GET /tasks/{id}/frequency-votes (v0.27). Alimente le mini-dialog
 * "Modifier mon vote" et le futur "+ d'info" modal sur le detail.
 */
data class FrequencyVotesState(
    @SerializedName("task_id") val taskId: String,
    @SerializedName("consensus_rule") val consensusRule: ConsensusRule? = null,
    @SerializedName("votes_count") val votesCount: Int = 0,
    @SerializedName("my_days_per_month") val myDaysPerMonth: Map<String, Int>? = null,
    @SerializedName("my_updated_at") val myUpdatedAt: String? = null,
    @SerializedName("can_vote") val canVote: Boolean = false,
    @SerializedName("others") val others: List<OtherFrequencyVote> = emptyList(),
) {
    /**
     * Helper : valeur "interval" du consensus si la regle est uniforme,
     * null sinon (cas monthly_variable).
     */
    val consensusEveryNDays: Int?
        get() = consensusRule?.takeIf { it.type == "interval" }?.everyNDays

    /** Valeur moyenne du consensus, qu'il soit interval ou variable. Sert
     *  de fallback pour les composants qui ne raisonnent qu'en "1 nombre". */
    val consensusAverageDays: Int?
        get() {
            val rule = consensusRule ?: return null
            return when (rule.type) {
                "interval" -> rule.everyNDays
                "monthly_variable" -> rule.daysPerMonth?.values?.takeIf { it.isNotEmpty() }
                    ?.let { (it.sum() / it.size) }
                else -> null
            }
        }
}

/**
 * Ligne brute d'un evenement d'historique de modification de tache
 * telle que retournee par GET /tasks/{id}/history.
 */
data class TaskHistoryDto(
    @SerializedName("id") val id: Long,
    @SerializedName("event_type") val eventType: String,
    @SerializedName("actor_user_id") val actorUserId: String? = null,
    @SerializedName("actor_name") val actorName: String = "",
    @SerializedName("at") val at: String? = null,
    @SerializedName("details") val details: Map<String, @JvmSuppressWildcards Any?> = emptyMap(),
)
