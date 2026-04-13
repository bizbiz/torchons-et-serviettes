package com.torchons.app.data.model

import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName

/**
 * Modele representant une categorie de taches.
 * Chaque categorie a une couleur hexadecimale et un icone optionnel.
 *
 * @Immutable : tous les champs sont des primitifs/String stables, mais on
 * marque explicitement pour que Compose smart-skip dans les listes.
 */
@Immutable
data class Category(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("color") val color: String,
    @SerializedName("icon") val icon: String? = null,
    @SerializedName("sort_order") val sortOrder: Int = 0,
    @SerializedName("requires_home") val requiresHome: Boolean = false,
    @SerializedName("created_at") val createdAt: String = ""
)

data class CategoryCreateRequest(
    @SerializedName("name") val name: String,
    @SerializedName("color") val color: String = "#6B7280",
    @SerializedName("icon") val icon: String? = null,
    @SerializedName("requires_home") val requiresHome: Boolean = false
)

data class CategoryUpdateRequest(
    @SerializedName("name") val name: String? = null,
    @SerializedName("color") val color: String? = null,
    @SerializedName("icon") val icon: String? = null,
    @SerializedName("requires_home") val requiresHome: Boolean? = null
)
