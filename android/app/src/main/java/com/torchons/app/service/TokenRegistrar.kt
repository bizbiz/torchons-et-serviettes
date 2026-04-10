package com.torchons.app.service

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging
import com.torchons.app.data.api.ApiService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enregistre le token FCM aupres du backend.
 * Appele apres chaque login reussi.
 */
@Singleton
class TokenRegistrar @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: ApiService
) {
    /** Recupere le token FCM et l'enregistre sur le backend. */
    suspend fun registerFcmToken() {
        try {
            val fcmToken = FirebaseMessaging.getInstance().token.await()

            // Sauvegarder localement
            context.getSharedPreferences("fcm", Context.MODE_PRIVATE)
                .edit()
                .putString("fcm_token", fcmToken)
                .putBoolean("token_needs_sync", false)
                .apply()

            // Envoyer au backend
            apiService.registerNotificationToken(
                mapOf(
                    "fcm_token" to fcmToken,
                    "device_name" to android.os.Build.MODEL,
                    "platform" to "android"
                )
            )
        } catch (_: Exception) {
            // Silencieux — sera reessaye au prochain lancement
        }
    }
}
