package com.torchons.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.torchons.app.data.local.converters.Converters
import com.torchons.app.data.local.dao.CategoryDao
import com.torchons.app.data.local.dao.CompletionDao
import com.torchons.app.data.local.dao.PendingOperationDao
import com.torchons.app.data.local.dao.TaskDao
import com.torchons.app.data.local.dao.UserDao
import com.torchons.app.data.local.dao.ViewSnapshotDao
import com.torchons.app.data.local.entities.CategoryEntity
import com.torchons.app.data.local.entities.CompletionEntity
import com.torchons.app.data.local.entities.PendingOperationEntity
import com.torchons.app.data.local.entities.TaskEntity
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
    ],
    version = 2,
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
}
