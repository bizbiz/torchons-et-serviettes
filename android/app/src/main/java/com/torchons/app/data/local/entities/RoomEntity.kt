package com.torchons.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.torchons.app.data.model.Room

/**
 * Entite Room miroir du DTO Room (piece de la maison).
 *
 * Note de naming : le mot "Room" entre en collision avec androidx.room.
 * On garde Room comme nom de domaine (cohérent avec le backend) et
 * RoomEntity pour l'entite Room (cache local). Le contexte des imports
 * differencie les deux sans ambiguite.
 */
@Entity(tableName = "rooms")
data class RoomEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: String,
    val icon: String?,
    val emoji: String?,
    val createdAt: String,
)

fun Room.toEntity(): RoomEntity = RoomEntity(
    id = id,
    name = name,
    color = color,
    icon = icon,
    emoji = emoji,
    createdAt = createdAt,
)

fun RoomEntity.toDomain(): Room = Room(
    id = id,
    name = name,
    color = color,
    icon = icon,
    emoji = emoji,
    createdAt = createdAt,
)
