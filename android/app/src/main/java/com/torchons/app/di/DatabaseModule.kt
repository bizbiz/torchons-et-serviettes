package com.torchons.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.torchons.app.data.local.AppDatabase
import com.torchons.app.data.local.dao.CompletionDao
import com.torchons.app.data.local.dao.PendingOperationDao
import com.torchons.app.data.local.dao.ContextDao
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

private val migration8to9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Home freeze v2 : nb de jours absents du foyer depuis la derniere
        // completion. Decalage applique cote client par TaskUrgencyCalculator
        // pour ne pas afficher en retard les taches "requires_home" pendant
        // les absences confirmees. Defaut 0 : un GET /api/tasks rempliera la
        // vraie valeur au prochain refresh.
        db.execSQL("ALTER TABLE tasks ADD COLUMN absentDaysSinceLastCompletion INTEGER NOT NULL DEFAULT 0")
    }
}

private val migration9to10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // last_completed_at calcule serveur (max completed_at non supprimee).
        // Consomme par TaskUrgencyCalculator (reconcilie max() avec le cache
        // local des completions) pour que l'urgence soit juste meme quand le
        // cache local de completions est vide pour la tache. NULL par defaut :
        // le prochain GET /api/tasks rempliera la vraie valeur.
        db.execSQL("ALTER TABLE tasks ADD COLUMN lastCompletedAt TEXT DEFAULT NULL")
    }
}

private val migration10to11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Contextes : le concept unique qui remplace pieces et categories.
        // Les deux anciennes tables restent en place tant que le serveur
        // ecrit en double — les retirer ici casserait les ecrans qui n'ont
        // pas encore bascule, et la bascule se fait ecran par ecran.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS contexts (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                color TEXT NOT NULL,
                icon TEXT,
                emoji TEXT,
                requiresPresence INTEGER NOT NULL DEFAULT 1,
                accruesWhenAway INTEGER NOT NULL DEFAULT 0,
                sortOrder INTEGER NOT NULL DEFAULT 0,
                templateKey TEXT,
                version INTEGER NOT NULL DEFAULT 1,
                createdAt TEXT
            )
            """.trimIndent()
        )
        // Les rattachements d'une tache voyagent AVEC elle plutot que dans une
        // table locale : le serveur les renvoie a chaque lecture, une seconde
        // source locale n'ajouterait qu'un risque de divergence.
        db.execSQL("ALTER TABLE tasks ADD COLUMN contexts TEXT NOT NULL DEFAULT '[]'")
    }
}

private val migration11to12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Les contextes sont desormais la seule source : les deux tables
        // heritees disparaissent du cache local, comme cote serveur. Leurs
        // colonnes sur `tasks` partent avec — Room ne sait pas retirer une
        // colonne, il faut recreer la table.
        db.execSQL("DROP TABLE IF EXISTS categories")
        db.execSQL("DROP TABLE IF EXISTS rooms")
        db.execSQL("ALTER TABLE tasks RENAME TO tasks_old")
        db.execSQL(
            """
            CREATE TABLE tasks (
                id TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                description TEXT,
                assignedToIds TEXT NOT NULL,
                recurrenceRule TEXT,
                inferredIntervalDays REAL,
                weight INTEGER NOT NULL,
                tolerance TEXT NOT NULL,
                isActive INTEGER NOT NULL,
                isArchived INTEGER NOT NULL DEFAULT 0,
                sortOrder INTEGER NOT NULL,
                createdAt TEXT NOT NULL,
                updatedAt TEXT NOT NULL,
                absentDaysSinceLastCompletion INTEGER NOT NULL DEFAULT 0,
                lastCompletedAt TEXT,
                contexts TEXT NOT NULL DEFAULT '[]'
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO tasks (id, title, description, assignedToIds, recurrenceRule,
                inferredIntervalDays, weight, tolerance, isActive, isArchived,
                sortOrder, createdAt, updatedAt, absentDaysSinceLastCompletion,
                lastCompletedAt, contexts)
            SELECT id, title, description, assignedToIds, recurrenceRule,
                inferredIntervalDays, weight, tolerance, isActive, isArchived,
                sortOrder, createdAt, updatedAt, absentDaysSinceLastCompletion,
                lastCompletedAt, contexts
            FROM tasks_old
            """.trimIndent()
        )
        db.execSQL("DROP TABLE tasks_old")
    }
}

private val migration12to13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Earliest live completion, computed server side: the anchor of an
        // `anniversary` rule. NULL until the next GET /api/tasks fills it;
        // meanwhile the calculator falls back on the local completions cache.
        db.execSQL("ALTER TABLE tasks ADD COLUMN firstCompletedAt TEXT DEFAULT NULL")
    }
}

private val migration13to14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Household each queued op belongs to. NULL for the ops already queued:
        // the server applies those in the token's household, as before.
        db.execSQL("ALTER TABLE pending_operations ADD COLUMN foyerId TEXT DEFAULT NULL")
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
            .addMigrations(migration1to2, migration2to3, migration3to4, migration4to5, migration5to6, migration6to7, migration7to8, migration8to9, migration9to10, migration10to11, migration11to12, migration12to13, migration13to14)
            .build()
    }

    @Provides
    fun provideTaskDao(db: AppDatabase): TaskDao = db.taskDao()

    @Provides
    fun provideContextDao(db: AppDatabase): ContextDao = db.contextDao()


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
