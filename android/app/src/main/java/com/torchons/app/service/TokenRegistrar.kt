package com.torchons.app.service

import android.content.Context
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import com.torchons.app.data.api.ApiService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Registers this phone for notifications with the backend. Called after each successful login and
 * at every start (MainActivity).
 *
 * Since 2026-11-12 the target is the Firebase Installation ID (FID): FCM deprecated the
 * registration token (`getToken`, `onNewToken`) for it (firebase-messaging 25.1). `register()`
 * registers the installation with FCM; the FID is then read and sent as `fid`. The backend drops
 * the token this phone used to send (api/notifications.py), so it is never notified twice.
 */
@Singleton
class TokenRegistrar @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: ApiService
) {
    /** Registers with FCM, then sends the FID to the backend. Silent on failure: retried at next start. */
    suspend fun registerFcmToken() {
        try {
            FirebaseMessaging.getInstance().register().await()
            val fid = FirebaseInstallations.getInstance().id.await()
            sendFid(fid)
        } catch (_: Exception) {
            // Silencieux — sera reessaye au prochain lancement
        }
    }

    /** Sends [fid] to the backend and remembers it was sent. */
    suspend fun sendFid(fid: String) {
        context.getSharedPreferences("fcm", Context.MODE_PRIVATE)
            .edit()
            .putString("fid", fid)
            .putBoolean("token_needs_sync", false)
            .apply()
        apiService.registerNotificationToken(
            mapOf(
                "fid" to fid,
                "device_name" to android.os.Build.MODEL,
                "platform" to "android"
            )
        )
    }
}
