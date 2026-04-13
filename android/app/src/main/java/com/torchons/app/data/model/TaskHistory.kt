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
