package com.torchons.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * Modele representant un utilisateur.
 */
data class User(
    @SerializedName("id") val id: String,
    @SerializedName("username") val username: String,
    @SerializedName("display_name") val displayName: String
)
