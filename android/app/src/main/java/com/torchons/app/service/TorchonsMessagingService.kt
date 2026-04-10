package com.torchons.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.torchons.app.MainActivity
import com.torchons.app.R

/**
 * Service Firebase Cloud Messaging.
 * Recoit les notifications push et les affiche.
 */
class TorchonsMessagingService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL_ID = "torchons_push"
        private var notifId = 2000
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Le token sera enregistre au prochain lancement de l'app
        // via le TokenRegistrar
        getSharedPreferences("fcm", Context.MODE_PRIVATE)
            .edit()
            .putString("fcm_token", token)
            .putBoolean("token_needs_sync", true)
            .apply()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        // Verifier si l'utilisateur veut recevoir les notifs de taches completees
        val prefs = getSharedPreferences("fcm", Context.MODE_PRIVATE)
        val notifCompleted = prefs.getBoolean("notif_completed", false)
        if (!notifCompleted) return

        val title = message.notification?.title ?: message.data["title"] ?: "Torchons & Serviettes"
        val body = message.notification?.body ?: message.data["body"] ?: return

        createNotificationChannel()

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(this).notify(notifId++, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Notifications",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications Torchons & Serviettes"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}
