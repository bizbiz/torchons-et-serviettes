package com.torchons.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * Prompt de la queue unifiee user_prompts. Retourne par GET /api/prompts/next.
 * Le front affiche 1 prompt a la fois a l'ouverture de l'app (tolerance 2h).
 *
 * Types : BUMP_EFFORT (tache en retard systematique), VOTE_RATING (notation
 * affinite+effort combinee).
 */
data class UserPrompt(
    @SerializedName("id") val id: String,
    @SerializedName("type") val type: String,
    @SerializedName("priority") val priority: Int,
    @SerializedName("context") val context: Map<String, Any?>,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("shown_at") val shownAt: String? = null,
)

/**
 * Corps de la requete POST /api/prompts/{id}/resolve.
 */
data class PromptResolveRequest(
    @SerializedName("resolution") val resolution: String,
    @SerializedName("payload") val payload: Map<String, Any?>? = null,
) {
    companion object {
        /** An answered re-vote carries the new vote: the server tells the requester from it
         *  (a frequency vote may still sit in the offline queue when the prompt resolves). */
        fun effortAnswer(points: Int) = PromptResolveRequest("ANSWERED", mapOf("points" to points))

        fun frequencyAnswer(request: FrequencyRequest): PromptResolveRequest {
            val days = request.daysPerMonth
                ?: request.everyNDays?.let { n -> (1..12).associate { it.toString() to n } }
            return PromptResolveRequest("ANSWERED", mapOf("days_per_month" to days))
        }
    }
}
