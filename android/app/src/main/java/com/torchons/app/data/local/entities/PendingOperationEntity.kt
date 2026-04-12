package com.torchons.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Operation en attente de synchronisation avec le backend.
 * Persiste meme si l'app est tuee — le SyncWorker la reprend au redemarrage.
 */
@Entity(tableName = "pending_operations")
data class PendingOperationEntity(
    @PrimaryKey val clientOpId: String,
    val tableName: String,
    val operation: String,
    val rowId: String,
    val payloadJson: String,
    val createdAt: Long,
    val attemptCount: Int = 0,
    val lastError: String? = null,
)
