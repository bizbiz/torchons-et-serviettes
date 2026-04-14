package com.torchons.app.data.model

import com.google.gson.annotations.SerializedName

/** Payload envoye a POST /api/presence. */
data class PresenceRequest(
    @SerializedName("is_at_home") val isAtHome: Boolean,
)

/** Sous-objet de PresenceResponse : infos pour afficher le message "bon retour". */
data class WelcomeBack(
    @SerializedName("absence_days") val absenceDays: Int,
    @SerializedName("from_date") val fromDate: String,
    @SerializedName("to_date") val toDate: String,
)

/** Reponse du backend apres POST /presence. */
data class PresenceResponse(
    @SerializedName("ok") val ok: Boolean,
    @SerializedName("foyer_occupied") val foyerOccupied: Boolean,
    @SerializedName("welcome_back") val welcomeBack: WelcomeBack? = null,
)

/**
 * Statut du home freeze pour le foyer + user courants. Utilise pour le
 * tooltip adaptatif sur le flag requires_home.
 */
data class HomeFreezeStatus(
    @SerializedName("foyer_freeze_enabled") val foyerFreezeEnabled: Boolean,
    @SerializedName("my_wifi_optin") val myWifiOptin: Boolean,
    @SerializedName("my_seen_at_home") val mySeenAtHome: Boolean,
    @SerializedName("missing_users") val missingUsers: List<String> = emptyList(),
)
