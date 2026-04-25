package com.torchons.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.torchons.app.data.local.AppDatabase
import com.torchons.app.data.local.dao.CategoryDao
import com.torchons.app.data.local.dao.CompletionDao
import com.torchons.app.data.local.dao.PendingOperationDao
import com.torchons.app.data.local.dao.RoomDao
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

private val migration3to4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Cache local des pieces (rooms). Rempli depuis GET /api/rooms.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS rooms (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                color TEXT NOT NULL,
                icon TEXT,
                emoji TEXT,
                createdAt TEXT NOT NULL
            )
        """.trimIndent())
        // Ajout de la colonne roomId sur tasks. Defaut NULL pour les
        // taches existantes — le prochain refresh API les remplira.
        db.execSQL("ALTER TABLE tasks ADD COLUMN roomId TEXT DEFAULT NULL")
    }
}

private val migration4to5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Feature "fait ensemble" : collectiveId groupe les N rows d'une
        // meme completion collective (null = completion solo).
        db.execSQL("ALTER TABLE completions ADD COLUMN collectiveId TEXT DEFAULT NULL")
    }
}

private val migration5to6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Systeme bonus/malus points : tracabilite du multiplicateur et de la
        // raison (bonus retard, malus avance, 1ere completion, etc.) pour
        // chaque ligne de completion. Immuable (on ne reecrit pas le passe
        // si les seuils changent).
        db.execSQL("ALTER TABLE completions ADD COLUMN multiplier REAL NOT NULL DEFAULT 1.0")
        db.execSQL("ALTER TABLE completions ADD COLUMN reason TEXT NOT NULL DEFAULT 'NEUTRAL'")
    }
}

private val migration6to7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // v0.14 : cadence inferee pour les recurrences 'uncertain'.
        // NULL par defaut (ni uncertain, ni recompute cote backend encore).
        db.execSQL("ALTER TABLE tasks ADD COLUMN inferredIntervalDays REAL DEFAULT NULL")
    }
}

private val migration7to8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // v0.20 : drapeau d'archivage, toggle par un job backend quotidien
        // pour les taches ponctuelles completees depuis >7j. Defaut 0 : le
        // prochain /sync propage la vraie valeur depuis change_log si besoin.
        db.execSQL("ALTER TABLE tasks ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
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
            .addMigrations(migration1to2, migration2to3, migration3to4, migration4to5, migration5to6, migration6to7, migration7to8)
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

    @Provides
    fun provideRoomDao(db: AppDatabase): RoomDao = db.roomDao()
}
