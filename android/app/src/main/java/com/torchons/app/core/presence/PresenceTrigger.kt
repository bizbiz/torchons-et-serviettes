package com.torchons.app.core.presence

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
 * Gestionnaire du PresenceWorker. Expose :
 * - `schedulePeriodic()` : lance un worker periodique 6h en arriere-plan.
 *   A appeler au demarrage de l'app (MainActivity.onCreate).
 * - `reportNow()` : declenche un one-shot pour rafraichir immediatement
 *   l'etat de presence. A appeler au LifecycleResumeEffect du HomeScreen.
 */
@Singleton
class PresenceTrigger @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val PRESENCE_ONESHOT_NAME = "presence_oneshot"
        private const val PRESENCE_PERIODIC_NAME = "presence_periodic"
        // 6h : fenetre decidee dans la grille v0.7.4 (compromis batterie / precision
        // avec un seuil de freeze de 24h — 4 reports / jour suffisent).
        private const val PERIODIC_HOURS = 6L
    }

    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun reportNow() {
        val request = OneTimeWorkRequestBuilder<PresenceWorker>()
            .setConstraints(networkConstraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            PRESENCE_ONESHOT_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<PresenceWorker>(
            PERIODIC_HOURS, TimeUnit.HOURS,
        )
            .setConstraints(networkConstraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PRESENCE_PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
