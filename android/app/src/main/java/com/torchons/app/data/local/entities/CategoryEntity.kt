package com.torchons.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.torchons.app.data.model.Category

/**
 * Entite Room miroir du DTO Category.
 */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val color: String,
    val icon: String?,
    val sortOrder: Int,
    val requiresHome: Boolean,
    val createdAt: String,
)

fun Category.toEntity(): CategoryEntity = CategoryEntity(
    id = id,
    name = name,
    color = color,
    icon = icon,
    sortOrder = sortOrder,
    requiresHome = requiresHome,
    createdAt = createdAt,
)

fun CategoryEntity.toDomain(): Category = Category(
    id = id,
    name = name,
    color = color,
    icon = icon,
    sortOrder = sortOrder,
    requiresHome = requiresHome,
    createdAt = createdAt,
)
