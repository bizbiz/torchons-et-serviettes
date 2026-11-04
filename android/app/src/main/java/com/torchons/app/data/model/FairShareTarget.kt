package com.torchons.app.data.model

import com.google.gson.annotations.SerializedName

/** One member's target share, as sent to PUT /fair-share/config (0.6 = 60 %). */
data class FairShareTargetItem(
    @SerializedName("user_id") val userId: String,
    @SerializedName("target_ratio") val targetRatio: Double,
)

/** A member's share in one history line; `before` is null for a member who was not there yet. */
data class FairShareHistoryShare(
    @SerializedName("user_id") val userId: String,
    @SerializedName("display_name") val displayName: String,
    @SerializedName("before") val before: Double?,
    @SerializedName("after") val after: Double?,
)

/** One accepted change of the household's target shares (GET /fair-share/history). */
data class FairShareHistoryEntry(
    @SerializedName("id") val id: String,
    @SerializedName("changed_at") val changedAt: String,
    @SerializedName("changed_by") val changedBy: String,
    @SerializedName("changed_by_name") val changedByName: String,
    @SerializedName("shares") val shares: List<FairShareHistoryShare>,
)
