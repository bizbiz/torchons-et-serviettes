package com.torchons.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.torchons.app.data.model.RecurrenceRule
import com.torchons.app.data.model.Task

/**
 * Entite Room miroir du DTO Task.
 * Les champs complexes (assignedToIds, recurrenceRule) sont serializes
 * en JSON via les TypeConverters.
 */
@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String?,
    val categoryId: String?,
    val roomId: String?,
    val assignedToIds: List<String>,
    val recurrenceRule: RecurrenceRule?,
    // v0.14 : cadence inferee pour les taches 'uncertain'.
    val inferredIntervalDays: Double? = null,
    val weight: Int,
    val tolerance: String,
    val isActive: Boolean,
    // v0.20 : tache archivee par le job serveur (ponctuelle completee >7j).
    // Filtree des listes UI. Propagee via change_log en sync.
    val isArchived: Boolean = false,
    val sortOrder: Int,
    val createdAt: String,
    val updatedAt: String,
)

/** Conversion DTO -> Entite (pour upsert depuis l'API). */
fun Task.toEntity(): TaskEntity = TaskEntity(
    id = id,
    title = title,
    description = description,
    categoryId = categoryId,
    roomId = roomId,
    assignedToIds = assignedToIds,
    recurrenceRule = recurrenceRule,
    inferredIntervalDays = inferredIntervalDays,
    weight = weight,
    tolerance = tolerance,
    isActive = isActive,
    isArchived = isArchived,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/** Conversion Entite -> DTO (pour exposer aux ViewModels). */
fun TaskEntity.toDomain(): Task = Task(
    id = id,
    title = title,
    description = description,
    categoryId = categoryId,
    roomId = roomId,
    assignedToIds = assignedToIds,
    recurrenceRule = recurrenceRule,
    inferredIntervalDays = inferredIntervalDays,
    weight = weight,
    tolerance = tolerance,
    isActive = isActive,
    isArchived = isArchived,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
