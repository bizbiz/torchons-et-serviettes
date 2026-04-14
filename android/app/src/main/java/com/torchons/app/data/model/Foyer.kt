package com.torchons.app.data.model

import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName

/**
 * Modele d'un foyer (container multi-tenant). Un user peut appartenir
 * a plusieurs foyers via la relation N:N. Le foyer actuellement actif
 * est celui dont `isCurrent = true`.
 */
@Immutable
data class Foyer(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("home_freeze_enabled") val homeFreezeEnabled: Boolean = false,
    @SerializedName("last_occupied_at") val lastOccupiedAt: String? = null,
    @SerializedName("created_at") val createdAt: String = "",
    @SerializedName("role") val role: String = "member",
    @SerializedName("is_current") val isCurrent: Boolean = false,
)

/** Corps de la requete pour creer un foyer. */
data class FoyerCreateRequest(
    @SerializedName("name") val name: String,
)

/** Reponse du endpoint /switch : nouveau JWT + foyer_id. */
data class SwitchFoyerResponse(
    @SerializedName("token") val token: String,
    @SerializedName("foyer_id") val foyerId: String,
)
