package com.torchons.app.core.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton qui gere le declenchement du SyncWorker.
 * Trois modes : one-shot (apres ecriture), periodique (background),
 * et reactif (au retour online).
 */
@Singleton
class SyncTrigger @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val SYNC_WORK_NAME = "sync_one_shot"
        private const val SYNC_PERIODIC_NAME = "sync_periodic"
    }

    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun requestSync() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConstraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            SYNC_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SYNC_PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
