package com.torchons.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * DTOs des endpoints onboarding sans compte (v0.30).
 *
 * Voir docs/design-onboarding-public.md cote backend pour les decisions
 * de fond. Cote backend, les routes sont :
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
)

data class PairingJoinRequest(
    @SerializedName("invite_token") val inviteToken: String,
    @SerializedName("display_name") val displayName: String,
)

data class PairingJoinResponse(
    @SerializedName("token") val token: String,
    @SerializedName("user_id") val userId: String,
    @SerializedName("foyer_id") val foyerId: String,
    @SerializedName("foyer_name") val foyerName: String,
    @SerializedName("recovery_code") val recoveryCode: String,
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
)

// --- Lifecycle ---

data class LeaveFoyerResponse(
    @SerializedName("left_foyer_id") val leftFoyerId: String,
    @SerializedName("new_active_foyer_id") val newActiveFoyerId: String?,
    /** JWT regenere si le foyer quitte etait l'actif. Null si l'user n'a plus aucun foyer. */
    @SerializedName("new_token") val newToken: String?,
)

// --- Billing (v0.30.x — plomberie premium) ---

data class BillingVerifyRequest(
    @SerializedName("purchase_token") val purchaseToken: String,
    @SerializedName("product_id") val productId: String,
    @SerializedName("device_id") val deviceId: String? = null,
)

data class BillingVerifyResponse(
    @SerializedName("is_premium") val isPremium: Boolean,
    /** ISO 8601 ou null si pas premium. */
    @SerializedName("premium_until") val premiumUntil: String? = null,
)
