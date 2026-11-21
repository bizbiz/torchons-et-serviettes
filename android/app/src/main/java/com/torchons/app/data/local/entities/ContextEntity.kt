package com.torchons.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.torchons.app.data.model.ContextView
import com.torchons.app.data.model.TaskContextRef

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

/**
 * Context ids as the references a task row carries, in the order given, from the CACHED contexts.
 * An id the cache does not know is left out (the next refresh brings it). Shared by the offline
 * edit (TaskLedger) and the sync worker applying a change made on another device.
 */
fun contextRefsOf(ids: List<String>, known: List<ContextEntity>): List<TaskContextRef> {
    val byId = known.associateBy { it.id }
    return ids.mapIndexedNotNull { position, id ->
        byId[id]?.let {
            TaskContextRef(
                id = it.id, name = it.name, color = it.color, emoji = it.emoji, position = position,
                requiresPresence = it.requiresPresence, accruesWhenAway = it.accruesWhenAway,
            )
        }
    }
}

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
