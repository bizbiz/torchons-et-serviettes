package com.torchons.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * Reponse du serveur apres connexion reussie.
 * Contient le token JWT et les informations utilisateur.
 */
data class LoginResponse(
    @SerializedName("token") val token: String,
    @SerializedName("user") val user: User
)
