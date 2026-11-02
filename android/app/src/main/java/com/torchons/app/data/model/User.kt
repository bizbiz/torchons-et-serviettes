package com.torchons.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * Modele representant un utilisateur.
 */
data class User(
    @SerializedName("id") val id: String,
    @SerializedName("username") val username: String,
    @SerializedName("display_name") val displayName: String,
    /** Only present for former members (`?include_former=true`). */
    @SerializedName("left_at") val leftAt: String? = null,
    @SerializedName("anonymized") val anonymized: Boolean? = null,
)
