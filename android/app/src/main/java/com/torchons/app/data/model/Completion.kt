package com.torchons.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * Modele representant une completion de tache.
 * Enregistre qui a complete la tache, quand, et les points gagnes.
 */
data class Completion(
    @SerializedName("id") val id: String,
    @SerializedName("task_id") val taskId: String,
    @SerializedName("completed_by") val completedBy: String,
    @SerializedName("completed_at") val completedAt: String,
    @SerializedName("points_earned") val pointsEarned: Int = 1,
    @SerializedName("notes") val notes: String? = null,
    @SerializedName("collective_id") val collectiveId: String? = null,
)

/**
 * Corps de la requete pour completer une tache.
 */
data class CompleteTaskRequest(
    @SerializedName("notes") val notes: String? = null,
    @SerializedName("completed_at") val completedAt: String? = null,
    @SerializedName("completed_by") val completedBy: String? = null
)

/**
 * Corps de la requete pour un "fait ensemble" (N participants).
 */
data class CollectiveCompleteRequest(
    @SerializedName("participant_user_ids") val participantUserIds: List<String>,
    @SerializedName("completed_at") val completedAt: String? = null,
    @SerializedName("notes") val notes: String? = null,
)

/**
 * Reponse d'un "fait ensemble" : un UUID de groupe + N Completions individuelles.
 */
data class CollectiveCompletionResponse(
    @SerializedName("collective_id") val collectiveId: String,
    @SerializedName("completions") val completions: List<Completion>,
)
