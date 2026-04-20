package com.torchons.app

import android.app.Application
import android.util.Log
import com.torchons.app.core.sync.SyncTrigger
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.local.dao.ViewSnapshotDao
import com.torchons.app.worker.DailyNotificationWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltAndroidApp
class TorchonsApp : Application() {

    @Inject
    lateinit var syncTrigger: SyncTrigger

    @Inject
    lateinit var tokenManager: TokenManager

    @Inject
    lateinit var viewSnapshotDao: ViewSnapshotDao

    override fun onCreate() {
        super.onCreate()

        // Invalidation du cache view_snapshots quand le versionCode a change
        // depuis le dernier lancement (v0.10.2+). Les snapshots JSON sont
        // serialises via Gson qui ignore les defaults Kotlin et remplit null
        // sur les champs absents — ce qui a crashe v0.10.0 sur les anciennes
        // Suggestion en cache. Wipe avant toute lecture = clean slate a chaque
        // montee de version. Runs runBlocking avant tout setContent, donc les
        // observers Home ne voient jamais d'etat incoherent.
        //
        // Utilise aussi pour forcer un replace=true du DailyNotificationWorker
        // sur montee de version : sans ca, l'ancienne planif (v0.15.1 et avant,
        // sans setInitialDelay) persiste a cause du ExistingPeriodicWorkPolicy
        // .KEEP et la notif continue de sortir a un horaire arbitraire meme
        // apres que v0.15.2 ait introduit le respect de notifHour.
        val versionChanged = runBlocking(Dispatchers.IO) {
            val current = BuildConfig.VERSION_CODE
            val stored = tokenManager.getLastSeenVersionCode()
            val changed = stored != current
            if (changed) {
                viewSnapshotDao.clearAll()
                tokenManager.setLastSeenVersionCode(current)
                Log.i(
                    "TorchonsApp",
                    "View snapshots cache wiped: $stored -> $current",
                )
            }
            changed
        }

        // Lit l'heure de notif + le toggle daily depuis DataStore. Sur montee
        // de version, replace=true pour ecraser toute ancienne planif cree
        // avec une logique differente (ex : sans setInitialDelay). Sinon
        // KEEP : on ne touche pas a chaque cold start — les changements de
        // reglages reschedulent deja via SettingsViewModel.
        val (notifDaily, notifHour) = runBlocking(Dispatchers.IO) {
            tokenManager.notifDailyFlow.first() to tokenManager.notifHourFlow.first()
        }
        DailyNotificationWorker.schedule(
            context = this,
            hourOfDay = notifHour,
            enabled = notifDaily,
            replace = versionChanged,
        )
        syncTrigger.schedulePeriodic()
    }
}
