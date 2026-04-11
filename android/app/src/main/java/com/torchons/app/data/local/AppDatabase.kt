package com.torchons.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.torchons.app.data.local.converters.Converters
import com.torchons.app.data.local.dao.CategoryDao
import com.torchons.app.data.local.dao.CompletionDao
import com.torchons.app.data.local.dao.TaskDao
import com.torchons.app.data.local.dao.UserDao
import com.torchons.app.data.local.dao.ViewSnapshotDao
import com.torchons.app.data.local.entities.CategoryEntity
import com.torchons.app.data.local.entities.CompletionEntity
import com.torchons.app.data.local.entities.TaskEntity
import com.torchons.app.data.local.entities.UserEntity
import com.torchons.app.data.local.entities.ViewSnapshotEntity

/**
 * Base de donnees Room pour le cache local "stale-while-revalidate".
 *
 * Strategie de migration : pour la phase 1 (cache lecture seule), pas de
 * donnees critiques en local. fallbackToDestructiveMigration() est ok :
 * en cas de bump de version on repart d'un cache vide et le repository
 * re-fetche tout au prochain demarrage.
 *
 * Quand on ajoutera la queue d'ecritures offline (P3), il faudra des
 * vraies migrations pour ne pas perdre les operations en attente.
 */
@Database(
    entities = [
        TaskEntity::class,
        CategoryEntity::class,
        UserEntity::class,
        CompletionEntity::class,
        ViewSnapshotEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun categoryDao(): CategoryDao
    abstract fun userDao(): UserDao
    abstract fun completionDao(): CompletionDao
    abstract fun viewSnapshotDao(): ViewSnapshotDao
}
