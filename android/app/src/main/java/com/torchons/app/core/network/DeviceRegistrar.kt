package com.torchons.app.core.network

import android.os.Build
import android.util.Log
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.DeviceRegisterRequest
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells the server which identity THIS installation actually holds.
 *
 * Called once at startup. Two things come out of it:
 *
 *  - the device shows up in Settings with a readable name (`Build.MODEL`), a first-seen
 *    and a last-seen date -- the three fields the list leans on, since two handsets of
 *    the same model are otherwise indistinguishable;
 *  - the SILENT TRANSITION. After a restore onto a new phone, the token has been copied
 *    over but the identity has not (its DataStore is excluded from Auto Backup). The
 *    server sees a valid token presenting an unknown identity, registers the new handset
 *    and hands back a token bound to it. Nothing is shown, nothing is asked -- and the
 *    old phone keeps its own row, so either can be revoked without the other.
 *
 * Failing silently is deliberate: this must never block startup. If the call does not go
 * through, the device is adopted on the next authenticated request anyway.
 *
 * Rationale: docs/appareils.fr.md
 */
@Singleton
class DeviceRegistrar @Inject constructor(
    private val api: ApiService,
    private val tokenManager: TokenManager,
) {

    suspend fun registerIfSignedIn() {
        val token = tokenManager.tokenFlow.first()
        if (token.isNullOrBlank()) return

        val deviceId = tokenManager.getOrCreateDeviceId()
        try {
            val response = api.registerDevice(
                DeviceRegisterRequest(deviceId = deviceId, deviceName = Build.MODEL)
            )
            val newToken = response.body()?.newToken
            if (response.isSuccessful && !newToken.isNullOrBlank()) {
                tokenManager.updateToken(newToken)
                Log.i(TAG, "Session rebound to this handset's own identity")
            }
        } catch (e: Exception) {
            // Offline, server down, anything: the next authenticated call adopts
            // the device server-side. Never let this stop the app from starting.
            Log.d(TAG, "Device registration deferred: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "DeviceRegistrar"
    }
}
