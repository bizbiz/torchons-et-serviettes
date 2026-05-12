package com.torchons.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.torchons.app.data.local.converters.Converters
import com.torchons.app.data.local.dao.CategoryDao
import com.torchons.app.data.local.dao.CompletionDao
import com.torchons.app.data.local.dao.PendingOperationDao
import com.torchons.app.data.local.dao.RoomDao
import com.torchons.app.data.local.dao.TaskDao
import com.torchons.app.data.local.dao.TaskHistoryDao
import com.torchons.app.data.local.dao.UserDao
import com.torchons.app.data.local.dao.ViewSnapshotDao
import com.torchons.app.data.local.entities.CategoryEntity
import com.torchons.app.data.local.entities.CompletionEntity
import com.torchons.app.data.local.entities.PendingOperationEntity
import com.torchons.app.data.local.entities.RoomEntity
import com.torchons.app.data.local.entities.TaskEntity
import com.torchons.app.data.local.entities.TaskHistoryEntity
import com.torchons.app.data.local.entities.UserEntity
import com.torchons.app.data.local.entities.ViewSnapshotEntity

@Database(
    entities = [
        TaskEntity::class,
        CategoryEntity::class,
        UserEntity::class,
        CompletionEntity::class,
        ViewSnapshotEntity::class,
        PendingOperationEntity::class,
        TaskHistoryEntity::class,
        RoomEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun categoryDao(): CategoryDao
    abstract fun userDao(): UserDao
    abstract fun completionDao(): CompletionDao
    abstract fun viewSnapshotDao(): ViewSnapshotDao
    abstract fun pendingOperationDao(): PendingOperationDao
    abstract fun taskHistoryDao(): TaskHistoryDao
    abstract fun roomDao(): RoomDao
}
