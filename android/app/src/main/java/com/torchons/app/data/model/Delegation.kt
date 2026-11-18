package com.torchons.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * « À faire aujourd'hui »: a task its author handed to other members (GET /api/delegations).
 * Only active ones are served and cached; spec in docs/plans/a-faire-aujourdhui.md.
 */
data class Delegation(
    @SerializedName("id") val id: String,
    @SerializedName("task_id") val taskId: String,
    @SerializedName("author_id") val authorId: String,
    @SerializedName("recipient_ids") val recipientIds: List<String> = emptyList(),
    /** ISO-8601, UTC. A completion dated on or after this day ends the delegation. */
    @SerializedName("created_at") val createdAt: String,
)
