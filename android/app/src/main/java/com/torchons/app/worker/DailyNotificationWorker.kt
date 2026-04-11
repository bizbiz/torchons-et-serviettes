package com.torchons.app.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.torchons.app.MainActivity
import com.torchons.app.R
import com.torchons.app.data.model.Suggestion
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Worker pour envoyer une notification locale quotidienne
 * avec le resume des taches a faire.
 */
class DailyNotificationWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val CHANNEL_ID = "torchons_daily"
        const val NOTIFICATION_ID = 1001
        const val WORK_NAME = "daily_notification"

        /** Planifie la notification quotidienne. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DailyNotificationWorker>(
                1, TimeUnit.DAYS
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /** Annule la notification quotidienne. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        createNotificationChannel()

        // Recuperer les suggestions
        val suggestions = try {
            val url = URL("${com.torchons.app.BuildConfig.API_BASE_URL}/suggestions")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val json = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            Gson().fromJson<List<Suggestion>>(json, object : TypeToken<List<Suggestion>>() {}.type)
        } catch (e: Exception) {
            emptyList()
        }

        if (suggestions.isEmpty()) return Result.success()

        val urgentCount = suggestions.count { it.reason == "urgent" }
        val title = "Torchons & Serviettes"
        val text = if (urgentCount > 0) {
            "Tu as $urgentCount tache(s) urgente(s) a faire !"
        } else {
            "Des taches t'attendent aujourd'hui."
        }

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        if (ActivityCompat.checkSelfPermission(
                applicationContext, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
        }

        return Result.success()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Resume quotidien",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Resume quotidien des taches a faire"
        }
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}
