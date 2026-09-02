package com.torchons.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * Un appareil qui detient une session pour ce compte.
 *
 * `isCurrent` vient du serveur plutot que d'une comparaison locale : c'est
 * le seul discriminant fiable quand deux telephones portent le meme modele,
 * et mesure le 2026-09-02, `Build.MODEL`, `device_name` et `bluetooth_name`
 * rendent tous les trois « Pixel 7 » sur le meme appareil.
 *
 * Detail : docs/appareils.fr.md
 */
data class DeviceDto(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("device_name") val deviceName: String?,
    @SerializedName("platform") val platform: String,
    @SerializedName("first_seen") val firstSeen: String,
    @SerializedName("last_seen") val lastSeen: String,
    @SerializedName("revoked_at") val revokedAt: String?,
    @SerializedName("is_current") val isCurrent: Boolean,
)

data class DeviceRegisterRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("device_name") val deviceName: String?,
)

/**
 * Reponse de l'enregistrement au demarrage.
 *
 * [newToken] n'est non-null que lorsque le serveur a constate une identite
 * NEUVE et rebascule la session dessus : c'est la transition silencieuse
 * apres une restauration sur un nouveau telephone. Le client stocke le
 * jeton et continue — rien n'est montre a l'utilisateur.
 */
data class DeviceRegisterResponse(
    @SerializedName("new_token") val newToken: String?,
)
