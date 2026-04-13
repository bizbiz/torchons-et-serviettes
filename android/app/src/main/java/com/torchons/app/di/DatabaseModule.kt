package com.torchons.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.torchons.app.data.local.AppDatabase
import com.torchons.app.data.local.dao.CategoryDao
import com.torchons.app.data.local.dao.CompletionDao
import com.torchons.app.data.local.dao.PendingOperationDao
import com.torchons.app.data.local.dao.TaskDao
import com.torchons.app.data.local.dao.TaskHistoryDao
import com.torchons.app.data.local.dao.UserDao
import com.torchons.app.data.local.dao.ViewSnapshotDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val migration1to2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS pending_operations (
                clientOpId TEXT NOT NULL PRIMARY KEY,
                tableName TEXT NOT NULL,
                operation TEXT NOT NULL,
                rowId TEXT NOT NULL,
                payloadJson TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                attemptCount INTEGER NOT NULL DEFAULT 0,
                lastError TEXT
            )
        """.trimIndent())
    }
}

private val migration2to3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Cache local de l'historique de modification d'une tache.
        // Rempli via GET /tasks/{id}/history, clef primaire = change_log.id serveur.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS task_history (
                id INTEGER NOT NULL PRIMARY KEY,
                taskId TEXT NOT NULL,
                eventType TEXT NOT NULL,
                actorUserId TEXT,
                actorName TEXT NOT NULL,
                at TEXT,
                voteValue INTEGER,
                consensusBefore INTEGER,
                consensusAfter INTEGER,
                fieldsChangedJson TEXT,
                oldJson TEXT,
                newJson TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS ix_task_history_taskId ON task_history(taskId)")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "torchons.db",
        )
            .addMigrations(migration1to2, migration2to3)
            .build()
    }

    @Provides
    fun provideTaskDao(db: AppDatabase): TaskDao = db.taskDao()

    @Provides
    fun provideCategoryDao(db: AppDatabase): CategoryDao = db.categoryDao()

    @Provides
    fun provideUserDao(db: AppDatabase): UserDao = db.userDao()

    @Provides
    fun provideCompletionDao(db: AppDatabase): CompletionDao = db.completionDao()

    @Provides
    fun provideViewSnapshotDao(db: AppDatabase): ViewSnapshotDao = db.viewSnapshotDao()

    @Provides
    fun providePendingOperationDao(db: AppDatabase): PendingOperationDao = db.pendingOperationDao()

    @Provides
    fun provideTaskHistoryDao(db: AppDatabase): TaskHistoryDao = db.taskHistoryDao()
}
