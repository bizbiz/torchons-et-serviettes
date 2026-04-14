package com.torchons.app.data.model

import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName

/**
 * Modeles pour les propositions d'objectifs (v0.8.1 Feature 3 magic buttons).
 */

@Immutable
data class UserResponseInfo(
    @SerializedName("user_id") val userId: String,
    @SerializedName("display_name") val displayName: String,
    /** 'pending' / 'accepted' / 'declined' */
    @SerializedName("status") val status: String,
    @SerializedName("responded_at") val respondedAt: String? = null,
)

@Immutable
data class PropositionView(
    @SerializedName("id") val id: String,
    @SerializedName("foyer_id") val foyerId: String,
    @SerializedName("proposer_id") val proposerId: String,
    @SerializedName("proposer_name") val proposerName: String,
    @SerializedName("slider_values") val sliderValues: Map<String, Int>,
    /** 'pending' / 'accepted' / 'declined' / 'expired' */
    @SerializedName("status") val status: String,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("expires_at") val expiresAt: String,
    @SerializedName("responses") val responses: List<UserResponseInfo>,
)

/** Corps du POST /api/objective-propositions. */
data class PropositionCreate(
    @SerializedName("slider_values") val sliderValues: Map<String, Int>,
)

/** Corps du POST /api/objective-propositions/{id}/respond. */
data class PropositionResponseBody(
    @SerializedName("accepted") val accepted: Boolean,
)
