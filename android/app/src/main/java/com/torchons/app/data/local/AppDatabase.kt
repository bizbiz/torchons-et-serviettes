package com.torchons.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.torchons.app.data.local.converters.Converters
import com.torchons.app.data.local.dao.CompletionDao
import com.torchons.app.data.local.dao.PendingOperationDao
import com.torchons.app.data.local.dao.TaskDao
import com.torchons.app.data.local.dao.TaskHistoryDao
import com.torchons.app.data.local.dao.UserDao
import com.torchons.app.data.local.dao.ViewSnapshotDao
import com.torchons.app.data.local.entities.CompletionEntity
import com.torchons.app.data.local.entities.PendingOperationEntity
import com.torchons.app.data.local.entities.TaskEntity
import com.torchons.app.data.local.entities.TaskHistoryEntity
import com.torchons.app.data.local.dao.ContextDao
import com.torchons.app.data.local.entities.ContextEntity
import com.torchons.app.data.local.entities.UserEntity
import com.torchons.app.data.local.entities.ViewSnapshotEntity

@Database(
    entities = [
        TaskEntity::class,
        UserEntity::class,
        CompletionEntity::class,
        ViewSnapshotEntity::class,
        PendingOperationEntity::class,
        TaskHistoryEntity::class,
        ContextEntity::class,
    ],
    // Raising this ships a Room migration inside a MINIFIED APK (R8 is on since
    // 2026-09-03). No migration has run under R8 yet -- v95 to v96 both carried
    // version 12 -- so the first bump must be watched on a phone whose database
    // predates it, never only on a fresh install.
    // 13 (2026-10-01): tasks.firstCompletedAt, the anniversary anchor.
    // 14 (2026-10-05): pending_operations.foyerId, the household an op was queued in.
    version = 14,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun userDao(): UserDao
    abstract fun completionDao(): CompletionDao
    abstract fun viewSnapshotDao(): ViewSnapshotDao
    abstract fun pendingOperationDao(): PendingOperationDao
    abstract fun taskHistoryDao(): TaskHistoryDao
    abstract fun contextDao(): ContextDao
}
