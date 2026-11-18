package com.torchons.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.torchons.app.data.model.Delegation

/** Cached « À faire aujourd'hui » delegation; mirror of [Delegation]. */
@Entity(tableName = "delegations")
data class DelegationEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val authorId: String,
    val recipientIds: List<String>,
    val createdAt: String,
)

fun Delegation.toEntity(): DelegationEntity =
    DelegationEntity(id = id, taskId = taskId, authorId = authorId, recipientIds = recipientIds, createdAt = createdAt)

fun DelegationEntity.toDomain(): Delegation =
    Delegation(id = id, taskId = taskId, authorId = authorId, recipientIds = recipientIds, createdAt = createdAt)
