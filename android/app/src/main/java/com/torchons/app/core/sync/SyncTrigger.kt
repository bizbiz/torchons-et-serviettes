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

    /**
     * Asks for a push pass now. What happens to the work already there is [syncRequestAction]'s
     * rule: a running pass is never cancelled, a pass in WorkManager's backoff (30 s, 1 min...
     * up to 5 h) is replaced, so a new write never waits for the end of a backoff.
     *
     * Cancelling a pass mid-flight used to be called safe because /sync is idempotent on
     * client_op_id. It is not for the REST ops (votes): the second pass replays a request the
     * server may still be handling. The lookup is asynchronous, this never blocks its caller.
     */
    fun requestSync() {
        val workManager = WorkManager.getInstance(context)
        val lookup = workManager.getWorkInfosForUniqueWork(SYNC_WORK_NAME)
        lookup.addListener({
            val states = runCatching { lookup.get().map { it.state } }.getOrDefault(emptyList())
            val policy = when (syncRequestAction(states)) {
                SyncRequestAction.START_NOW -> ExistingWorkPolicy.REPLACE
                SyncRequestAction.CHAIN_AFTER_RUNNING -> ExistingWorkPolicy.APPEND_OR_REPLACE
                SyncRequestAction.ALREADY_CHAINED -> return@addListener
            }
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(networkConstraints)
                .build()
            workManager.enqueueUniqueWork(SYNC_WORK_NAME, policy, request)
        }, Runnable::run)
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
