package com.torchons.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.torchons.app.data.model.Completion

/**
 * Entite Room miroir du DTO Completion.
 */
@Entity(tableName = "completions")
data class CompletionEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val completedBy: String,
    val completedAt: String,
    val pointsEarned: Int,
    val multiplier: Float = 1.0f,
    val reason: String = "NEUTRAL",
    val notes: String?,
    val collectiveId: String? = null,
)

fun Completion.toEntity(): CompletionEntity = CompletionEntity(
    id = id,
    taskId = taskId,
    completedBy = completedBy,
    completedAt = completedAt,
    pointsEarned = pointsEarned,
    multiplier = multiplier,
    reason = reason,
    notes = notes,
    collectiveId = collectiveId,
)

fun CompletionEntity.toDomain(): Completion = Completion(
    id = id,
    taskId = taskId,
    completedBy = completedBy,
    completedAt = completedAt,
    pointsEarned = pointsEarned,
    multiplier = multiplier,
    reason = reason,
    notes = notes,
    collectiveId = collectiveId,
)
