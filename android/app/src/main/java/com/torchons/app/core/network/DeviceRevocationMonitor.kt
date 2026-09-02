package com.torchons.app.core.network

import com.torchons.app.data.local.AppDatabase
import com.torchons.app.data.local.DeviceIdentityStore
import com.torchons.app.data.local.TokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reacts to "this device has been revoked" by wiping what stays readable offline.
 *
 * Why a wipe and not just a sign-out: the app is offline-first. Cutting the server
 * access leaves SEVEN Room tables on the handset -- tasks, completions, task history,
 * contexts, household members, view snapshots, pending operations. Someone holding a
 * revoked phone would keep reading all of it, and the owner would believe access was
 * cut. `clearAuthData()` alone does NOT do this: it only removes the auth keys.
 *
 * Why it triggers on a machine code and never on a message: the decision is keyed on
 * `code == "DEVICE_REVOKED"` in the error body. A plain 401 -- expired token, auth
 * outage, clock skew -- must erase nothing, and a localized string is not something
 * to hinge a data wipe on.
 *
 * Known limit, stated rather than hidden: a handset that never reconnects never
 * learns it was revoked, and keeps its cache. No app can wipe an offline phone.
 *
 * Rationale: docs/appareils.fr.md
 */
@Singleton
class DeviceRevocationMonitor @Inject constructor(
    private val database: AppDatabase,
    private val tokenManager: TokenManager,
    private val deviceIdentity: DeviceIdentityStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _revoked = MutableStateFlow(false)

    /** True once this device learned it was cut off. Sends the UI back to onboarding. */
    val revoked: StateFlow<Boolean> = _revoked.asStateFlow()

    /** Called by [DeviceRevocationInterceptor] on a DEVICE_REVOKED answer. */
    fun onRevoked() {
        if (_revoked.value) return
        _revoked.value = true
        scope.launch { wipe() }
    }

    /** Same wipe, awaited -- used by the "sign out this device" button. */
    suspend fun wipeNow() {
        _revoked.value = true
        wipe()
    }

    private suspend fun wipe() {
        // Order matters: identity last. If the process dies mid-wipe, having
        // dropped the token already means the app cannot talk to the server
        // with a revoked session anyway.
        database.clearAllTables()
        tokenManager.clearAuthData()
        deviceIdentity.clear()
    }
}
