package com.torchons.app

import android.app.Application
import android.util.Log
import com.torchons.app.core.sync.SyncTrigger
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.local.dao.ViewSnapshotDao
import com.torchons.app.worker.DailyNotificationWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.Dispatchers
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

        DailyNotificationWorker.schedule(this)
        syncTrigger.schedulePeriodic()
    }
}
