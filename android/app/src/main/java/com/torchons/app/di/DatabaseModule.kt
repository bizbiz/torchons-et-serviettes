package com.torchons.app.di

import android.content.Context
import androidx.room.Room
import com.torchons.app.data.local.AppDatabase
import com.torchons.app.data.local.dao.CategoryDao
import com.torchons.app.data.local.dao.CompletionDao
import com.torchons.app.data.local.dao.TaskDao
import com.torchons.app.data.local.dao.UserDao
import com.torchons.app.data.local.dao.ViewSnapshotDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Module Hilt pour la base de donnees Room (cache local stale-while-revalidate).
 */
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
            // Cache lecture seule en P1 — on peut tout reconstruire depuis l'API.
            // Sera remplace par de vraies migrations quand on ajoutera la queue
            // d'ecritures offline (P3).
            .fallbackToDestructiveMigration()
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
}
