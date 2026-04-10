package com.torchons.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * Corps de la requete de connexion.
 * Pas de mot de passe necessaire, juste le nom d'utilisateur et l'identifiant appareil.
 */
data class LoginRequest(
    @SerializedName("username") val username: String,
    @SerializedName("device_id") val deviceId: String
)
