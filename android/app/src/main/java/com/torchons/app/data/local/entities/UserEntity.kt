package com.torchons.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.torchons.app.data.model.User

/**
 * Entite Room miroir du DTO User.
 */
@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val username: String,
    val displayName: String,
)

fun User.toEntity(): UserEntity = UserEntity(
    id = id,
    username = username,
    displayName = displayName,
)

fun UserEntity.toDomain(): User = User(
    id = id,
    username = username,
    displayName = displayName,
)
