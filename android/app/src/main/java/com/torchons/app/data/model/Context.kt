package com.torchons.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * A household context — the single concept replacing categories and rooms.
 *
 * Naming: `Context` collides with `android.content.Context`. Every file using
 * both must qualify one of them; the Android one is the rarer here, so this
 * type keeps the short name and screens import `android.content.Context` with
 * an alias when needed.
 *
 * The two flags are deliberately separate. `requiresPresence` answers "must
 * you be on site?" and drives masking off home WiFi; `accruesWhenAway` answers
 * "does the need grow without us?" and drives absence freezing. A garden needs
 * the first and not the second — see `docs/contexts.md`.
 */
data class ContextView(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("color") val color: String = "#6B7280",
    @SerializedName("icon") val icon: String? = null,
    @SerializedName("emoji") val emoji: String? = null,
    @SerializedName("requires_presence") val requiresPresence: Boolean = true,
    @SerializedName("accrues_when_away") val accruesWhenAway: Boolean = false,
    @SerializedName("sort_order") val sortOrder: Int = 0,
    @SerializedName("template_key") val templateKey: String? = null,
    @SerializedName("version") val version: Int = 1,
    @SerializedName("created_at") val createdAt: String? = null,
) {
    /** Label shown on chips and headers: emoji first when there is one. */
    val label: String get() = if (emoji != null) "$emoji $name" else name
}

/** A context as carried by a task. `position` 0 is the primary one. */
data class TaskContextRef(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("color") val color: String = "#6B7280",
    @SerializedName("emoji") val emoji: String? = null,
    @SerializedName("position") val position: Int = 0,
    /** Faut-il etre sur place ? Pilote le masquage hors WiFi maison. */
    @SerializedName("requires_presence") val requiresPresence: Boolean = false,
    @SerializedName("accrues_when_away") val accruesWhenAway: Boolean = false,
) {
    val label: String get() = if (emoji != null) "$emoji $name" else name
}

data class ContextCreateRequest(
    @SerializedName("name") val name: String,
    @SerializedName("color") val color: String = "#6B7280",
    @SerializedName("emoji") val emoji: String? = null,
    @SerializedName("requires_presence") val requiresPresence: Boolean = true,
    @SerializedName("accrues_when_away") val accruesWhenAway: Boolean = false,
)

data class ContextUpdateRequest(
    @SerializedName("name") val name: String? = null,
    @SerializedName("color") val color: String? = null,
    @SerializedName("emoji") val emoji: String? = null,
    @SerializedName("requires_presence") val requiresPresence: Boolean? = null,
    @SerializedName("accrues_when_away") val accruesWhenAway: Boolean? = null,
)
