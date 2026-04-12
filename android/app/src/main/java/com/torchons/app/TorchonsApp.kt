package com.torchons.app

import android.app.Application
import com.torchons.app.core.sync.SyncTrigger
import com.torchons.app.worker.DailyNotificationWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class TorchonsApp : Application() {

    @Inject
    lateinit var syncTrigger: SyncTrigger

    override fun onCreate() {
        super.onCreate()
        DailyNotificationWorker.schedule(this)
        syncTrigger.schedulePeriodic()
    }
}
