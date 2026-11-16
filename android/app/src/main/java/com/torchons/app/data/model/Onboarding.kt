package com.torchons.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * DTOs des endpoints onboarding sans compte (v0.30).
 *
 * Cote backend, les routes sont :
 *   POST /api/onboarding/create-foyer  (unauth)
 *   POST /api/pairing/init             (auth)
 *   POST /api/pairing/join             (unauth)
 *   POST /api/recovery/restore         (unauth)
 *   POST /api/recovery/restore/confirm (unauth)
 *   POST /api/recovery/regenerate      (auth)
 *   POST /api/foyers/{id}/leave        (auth)
 *   DELETE /api/me                     (auth)
 */

// --- Onboarding (creation de foyer wizard) ---

data class OnboardingCreateFoyerRequest(
    @SerializedName("display_name") val displayName: String,
    @SerializedName("foyer_name") val foyerName: String,
    @SerializedName("device_id") val deviceId: String,
)

data class OnboardingCreateFoyerResponse(
    @SerializedName("token") val token: String,
    @SerializedName("user_id") val userId: String,
    @SerializedName("foyer_id") val foyerId: String,
    @SerializedName("foyer_name") val foyerName: String,
    /** Affiche au user UNE SEULE FOIS. Sera ensuite seulement stocke en hash. */
    @SerializedName("recovery_code") val recoveryCode: String,
)

// --- Pairing QR ---

data class PairingInitResponse(
    @SerializedName("invite_token") val inviteToken: String,
    /** ISO 8601, UTC. */
    @SerializedName("expires_at") val expiresAt: String,
    @SerializedName("foyer_name") val foyerName: String,
    /** v0.31 : "foyer_join" (defaut) ou "add_device". */
    @SerializedName("kind") val kind: String = "foyer_join",
)

data class PairingJoinRequest(
    @SerializedName("invite_token") val inviteToken: String,
    @SerializedName("display_name") val displayName: String = "",
    /** v0.31 : device_id du joiner pour le binding JWT. */
    @SerializedName("device_id") val deviceId: String? = null,
)

/** v0.31 — probe sans consommation, pour que le joiner adapte son UI. */
data class PairingInfoResponse(
    @SerializedName("kind") val kind: String,
    @SerializedName("foyer_name") val foyerName: String,
    @SerializedName("expires_at") val expiresAt: String,
)

data class PairingJoinResponse(
    @SerializedName("token") val token: String,
    @SerializedName("user_id") val userId: String,
    @SerializedName("foyer_id") val foyerId: String,
    @SerializedName("foyer_name") val foyerName: String,
    @SerializedName("kind") val kind: String = "foyer_join",
    /** Null pour kind=add_device (l'user existant garde son code). */
    @SerializedName("recovery_code") val recoveryCode: String? = null,
    /** v0.31 : display_name du user (existant pour add_device, fourni pour foyer_join). */
    @SerializedName("display_name") val displayName: String = "",
)

// --- Recovery ---

data class RecoveryRestoreRequest(
    @SerializedName("recovery_code") val recoveryCode: String,
)

data class RecoveryRestoreResponse(
    @SerializedName("display_name") val displayName: String,
    @SerializedName("foyer_name") val foyerName: String?,
    @SerializedName("role") val role: String?,
    @SerializedName("ratings_count") val ratingsCount: Int,
    @SerializedName("completions_count") val completionsCount: Int,
    /** A renvoyer dans /restore/confirm pour valider l'identite. TTL 5min. */
    @SerializedName("restore_token") val restoreToken: String,
)

data class RecoveryRestoreConfirmRequest(
    @SerializedName("restore_token") val restoreToken: String,
    @SerializedName("device_id") val deviceId: String,
)

data class RecoveryRestoreConfirmResponse(
    @SerializedName("token") val token: String,
    @SerializedName("user_id") val userId: String,
    @SerializedName("foyer_id") val foyerId: String?,
)

data class RecoveryRegenerateResponse(
    @SerializedName("recovery_code") val recoveryCode: String,
    /**
     * Nombre d'AUTRES appareils coupes par cette regeneration. Un code
     * regenere signifie « l'ancien a pu fuiter » : fermer la porte du code
     * sans fermer celles deja ouvertes ne protegerait de rien, puisque le
     * jeton d'une session existante n'expire pas. Voir docs/appareils.fr.md.
     */
    @SerializedName("revoked_devices") val revokedDevices: Int = 0,
)

// --- Lifecycle ---

data class LeaveFoyerResponse(
    @SerializedName("left_foyer_id") val leftFoyerId: String,
    @SerializedName("new_active_foyer_id") val newActiveFoyerId: String?,
    /** JWT regenere si le foyer quitte etait l'actif. Null si l'user n'a plus aucun foyer. */
    @SerializedName("new_token") val newToken: String?,
)
