package com.torchons.app.data.model

import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName

/**
 * Modele d'une piece (room) de la maison.
 *
 * @Immutable pour permettre a Compose de smart-skip dans les listes
 * (filtres pieces, picker dans TaskEditScreen).
 *
 * icon (Material name) et emoji sont optionnels — l'UI affiche d'abord
 * l'icone Material si presente, sinon tombe sur l'emoji, sinon le nom.
 */
@Immutable
data class Room(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("color") val color: String = "#6B7280",
    @SerializedName("icon") val icon: String? = null,
    @SerializedName("emoji") val emoji: String? = null,
    @SerializedName("created_at") val createdAt: String = ""
)

/** Corps de la requete pour creer une piece. */
data class RoomCreateRequest(
    @SerializedName("name") val name: String,
    @SerializedName("color") val color: String = "#6B7280",
    @SerializedName("icon") val icon: String? = null,
    @SerializedName("emoji") val emoji: String? = null,
)

/** Corps de la requete pour modifier une piece. */
data class RoomUpdateRequest(
    @SerializedName("name") val name: String? = null,
    @SerializedName("color") val color: String? = null,
    @SerializedName("icon") val icon: String? = null,
    @SerializedName("emoji") val emoji: String? = null,
)
