package com.torchons.app.data.model

import com.google.gson.annotations.SerializedName

/** Corps pour definir le vote de frequence. */
data class FrequencyRequest(
    @SerializedName("every_n_days") val everyNDays: Int
)

/** Reponse du serveur apres un vote de frequence. */
data class FrequencyVoteResponse(
    @SerializedName("task_id") val taskId: String,
    @SerializedName("user_id") val userId: String,
    @SerializedName("every_n_days") val everyNDays: Int,
    @SerializedName("consensus") val consensus: Int
)

/** Vote individuel d'un autre membre du foyer (sans le user courant). */
data class OtherFrequencyVote(
    @SerializedName("user_id") val userId: String,
    @SerializedName("display_name") val displayName: String,
    @SerializedName("every_n_days") val everyNDays: Int
)

/**
 * Etat des votes de frequence pour une tache, retourne par
 * GET /tasks/{id}/frequency-votes. Alimente le mini-dialog de re-vote
 * et la ligne "Consensus : Xj (N votes)" sur la fiche d'edition.
 */
data class FrequencyVotesState(
    @SerializedName("task_id") val taskId: String,
    @SerializedName("consensus_every_n_days") val consensusEveryNDays: Int? = null,
    @SerializedName("votes_count") val votesCount: Int = 0,
    @SerializedName("my_vote") val myVote: Int? = null,
    @SerializedName("can_vote") val canVote: Boolean = false,
    @SerializedName("others") val others: List<OtherFrequencyVote> = emptyList()
)

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
