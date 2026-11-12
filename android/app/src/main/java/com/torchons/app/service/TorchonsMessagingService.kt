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
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.torchons.app.MainActivity
import com.torchons.app.R
import com.torchons.app.core.proposition.PropositionStateHolder
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TorchonsMessagingEntryPoint {
    fun propositionStateHolder(): PropositionStateHolder
}

/**
 * Service Firebase Cloud Messaging.
 * Recoit les notifications push et les affiche, ou dispatche les data
 * messages vers le PropositionStateHolder (v0.8.1 Feature 3).
 */
class TorchonsMessagingService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL_ID = "torchons_push"
        private var notifId = 2000
    }

    /**
     * The Firebase Installation ID registered with FCM, new or changed (restore, reinstall, data
     * cleared). Replaces the deprecated onNewToken (firebase-messaging 25.1). Remembered here and
     * sent by TokenRegistrar at the next start, as onNewToken did: a service is no place for a
     * network call that must survive the process.
     */
    override fun onRegistered(installationId: String) {
        super.onRegistered(installationId)
        getSharedPreferences("fcm", Context.MODE_PRIVATE)
            .edit()
            .putString("fid", installationId)
            .putBoolean("token_needs_sync", true)
            .apply()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        // Data-only message : dispatch vers PropositionStateHolder (v0.8.1)
        val dataType = message.data["type"]
        if (dataType != null && dataType.startsWith("objective_proposition_")) {
            val ep = EntryPointAccessors.fromApplication(
                applicationContext, TorchonsMessagingEntryPoint::class.java
            )
            ep.propositionStateHolder().handleFcmEvent(
                type = dataType,
                propositionId = message.data["proposition_id"],
            )
            return
        }

        // v0.22+ : le filtrage par categorie (solo / collective_with_me /
        // collective_without_me) est entierement cote backend. Si un push
        // arrive jusqu'ici, c'est qu'il a deja passe le filtre serveur.
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
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.primary))
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
