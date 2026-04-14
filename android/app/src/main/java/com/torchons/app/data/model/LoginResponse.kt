package com.torchons.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * Reponse du serveur apres connexion reussie.
 * Contient le token JWT, les informations utilisateur, et depuis v0.7.0
 * le foyer_id actif (last_active_foyer_id du user).
 */
data class LoginResponse(
    @SerializedName("token") val token: String,
    @SerializedName("user") val user: User,
    @SerializedName("foyer_id") val foyerId: String? = null,
)
