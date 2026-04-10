package com.torchons.app

import android.app.Application
import com.torchons.app.worker.DailyNotificationWorker
import dagger.hilt.android.HiltAndroidApp

/**
 * Classe Application principale avec Hilt.
 * Point d'entree pour l'injection de dependances.
 */
@HiltAndroidApp
class TorchonsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Planifier la notification quotidienne
        DailyNotificationWorker.schedule(this)
    }
}
