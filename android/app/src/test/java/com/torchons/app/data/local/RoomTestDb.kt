package com.torchons.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.torchons.app.data.local.entities.CompletionEntity
import com.torchons.app.data.local.entities.DelegationEntity
import com.torchons.app.data.local.entities.PendingOperationEntity
import com.torchons.app.data.local.entities.TaskEntity

/*
 * Fixtures for the real-Room tests. Paths by which a fixture can mislead a test: a row shaped
 * differently from what the app writes (these mirror the entities' required fields only), a
 * queue op whose payload lacks `task_id` (pass [pendingOp]'s payloadJson when the code under test
 * reads it). Not covered: migrations (exportSchema = false), TypeConverter round trips beyond
 * the list/rule defaults used here.
 */

/** A REAL Room database in memory (Robolectric), for tests that need real SQL and transactions. */
internal fun newInMemoryDb(): AppDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
        .allowMainThreadQueries()
        .build()

internal fun taskRow(id: String, title: String = "task $id", updatedAt: String = "2026-11-01T00:00:00Z") = TaskEntity(
    id = id, title = title, description = null, assignedToIds = emptyList(), recurrenceRule = null,
    weight = 1, tolerance = "NORMAL", isActive = true, sortOrder = 0,
    createdAt = "2026-11-01T00:00:00Z", updatedAt = updatedAt,
)

internal fun completionRow(id: String, taskId: String = "t1", notes: String? = null) = CompletionEntity(
    id = id, taskId = taskId, completedBy = "u1", completedAt = "2026-11-02T10:00:00Z",
    pointsEarned = 1, notes = notes,
)

internal fun delegationRow(id: String, taskId: String = "t1") = DelegationEntity(
    id = id, taskId = taskId, authorId = "u1", recipientIds = listOf("u2"), createdAt = "2026-11-02T10:00:00Z",
)

internal fun pendingOp(
    id: String,
    table: String,
    operation: String,
    rowId: String,
    foyerId: String? = null,
    payloadJson: String = "null",
    createdAt: Long = 1L,
) = PendingOperationEntity(
    clientOpId = id, tableName = table, operation = operation, rowId = rowId,
    payloadJson = payloadJson, createdAt = createdAt, foyerId = foyerId,
)
