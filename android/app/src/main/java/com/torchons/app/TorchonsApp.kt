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
        runBlocking(Dispatchers.IO) {
            val current = BuildConfig.VERSION_CODE
            val stored = tokenManager.getLastSeenVersionCode()
            if (stored != current) {
                viewSnapshotDao.clearAll()
                tokenManager.setLastSeenVersionCode(current)
                Log.i(
                    "TorchonsApp",
                    "View snapshots cache wiped: $stored -> $current",
                )
            }
        }

        // Lit l'heure de notif + le toggle daily depuis DataStore (meme
        // runBlocking que le wipe snapshots ci-dessus pour rester avant
        // tout setContent). KEEP : on ne touche pas une planification
        // existante a chaque cold start, seuls les changements de reglages
        // via SettingsViewModel reschedulent (replace=true).
        val (notifDaily, notifHour) = runBlocking(Dispatchers.IO) {
            tokenManager.notifDailyFlow.first() to tokenManager.notifHourFlow.first()
        }
        DailyNotificationWorker.schedule(this, hourOfDay = notifHour, enabled = notifDaily)
        syncTrigger.schedulePeriodic()
    }
}
