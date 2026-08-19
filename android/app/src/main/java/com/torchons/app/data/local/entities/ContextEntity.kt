package com.torchons.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.torchons.app.data.model.ContextView

/** Local cache of a household context. */
@Entity(tableName = "contexts")
data class ContextEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: String,
    val icon: String?,
    val emoji: String?,
    val requiresPresence: Boolean,
    val accruesWhenAway: Boolean,
    val sortOrder: Int,
    val templateKey: String?,
    val version: Int,
    val createdAt: String?,
)

fun ContextView.toEntity(): ContextEntity = ContextEntity(
    id = id,
    name = name,
    color = color,
    icon = icon,
    emoji = emoji,
    requiresPresence = requiresPresence,
    accruesWhenAway = accruesWhenAway,
    sortOrder = sortOrder,
    templateKey = templateKey,
    version = version,
    createdAt = createdAt,
)

fun ContextEntity.toDomain(): ContextView = ContextView(
    id = id,
    name = name,
    color = color,
    icon = icon,
    emoji = emoji,
    requiresPresence = requiresPresence,
    accruesWhenAway = accruesWhenAway,
    sortOrder = sortOrder,
    templateKey = templateKey,
    version = version,
    createdAt = createdAt,
)
