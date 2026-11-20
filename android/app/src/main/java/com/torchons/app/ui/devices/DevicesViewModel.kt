package com.torchons.app.ui.devices

import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import com.torchons.app.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.core.network.DeviceRevocationMonitor
import com.torchons.app.core.sync.SyncTrigger
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.dao.PendingOperationDao
import com.torchons.app.data.model.DeviceDto
import com.torchons.app.ui.common.UserError
import com.torchons.app.ui.common.UserErrors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DevicesUiState(
    val loading: Boolean = true,
    val devices: List<DeviceDto> = emptyList(),
    val error: UserError? = null,
    /** Passe a true quand CET appareil s'est deconnecte : l'UI doit sortir. */
    val signedOut: Boolean = false,
)

/**
 * Ecran « Mes appareils » : voir et couper les sessions de ce compte.
 *
 * Chacun ne voit que SES appareils — le champ `role` (admin/member) existe en
 * base mais n'est consulte nulle part, et la revocation d'appareil n'est pas la
 * fonctionnalite qui doit introduire le premier pouvoir d'un membre du foyer
 * sur un autre.
 *
 * Detail et arbitrages : docs/appareils.fr.md
 */
@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val api: ApiService,
    private val revocationMonitor: DeviceRevocationMonitor,
    private val pendingDao: PendingOperationDao,
    private val syncTrigger: SyncTrigger,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DevicesUiState())
    val uiState: StateFlow<DevicesUiState> = _uiState.asStateFlow()

    /**
     * Changes made on this phone and not yet sent. The sign-out wipes them, so its dialog shows
     * the count and offers to sync first (D9, 2026-11-20 audit).
     */
    val pendingOps: StateFlow<Int> = pendingDao.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _signOutSync = MutableStateFlow(SignOutSyncState.IDLE)
    val signOutSync: StateFlow<SignOutSyncState> = _signOutSync.asStateFlow()

    /** "Sync first": a pass of the worker, waited for until the queue is empty or the wait runs out. */
    fun syncFirst() {
        if (_signOutSync.value == SignOutSyncState.SYNCING) return
        _signOutSync.value = SignOutSyncState.SYNCING
        viewModelScope.launch {
            val emptied = syncBeforeSignOut(pendingDao.observeCount(), syncTrigger::requestSync)
            _signOutSync.value = if (emptied) SignOutSyncState.IDLE else SignOutSyncState.GAVE_UP
        }
    }

    /** The sign-out dialog was closed: a later one starts clean. */
    fun resetSignOutSync() {
        _signOutSync.value = SignOutSyncState.IDLE
    }

    init {
        refresh()
        // The queue emptied by itself after a failed "sync first": forget the failure, or it comes
        // back the next time a change is queued (the dialog already hides it at a count of zero).
        viewModelScope.launch {
            pendingDao.observeCount().collect { count ->
                _signOutSync.value = shownSignOutSyncState(_signOutSync.value, count)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            try {
                val response = api.listDevices()
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    _uiState.value = DevicesUiState(loading = false, devices = body)
                } else {
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        error = UserErrors.fromResponse(context, response),
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = UserErrors.from(context, e),
                )
            }
        }
    }

    /** Coupe un AUTRE appareil. Celui-ci restera connecte. */
    fun revoke(deviceId: String) {
        viewModelScope.launch {
            try {
                val response = api.revokeDevice(deviceId)
                if (response.isSuccessful) refresh()
                else _uiState.value = _uiState.value.copy(
                    error = UserErrors.fromResponse(context, response),
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = UserErrors.from(context, e))
            }
        }
    }

    /**
     * Se deconnecter ET revoquer cet appareil.
     *
     * L'effacement local passe par [DeviceRevocationMonitor] : couper l'acces
     * serveur laisserait les sept tables Room lisibles hors ligne, et
     * `clearAuthData()` seul ne les touche pas.
     */
    fun signOutThisDevice() {
        viewModelScope.launch {
            try {
                api.signOutThisDevice()
            } catch (_: Exception) {
                // Meme hors ligne, on efface : l'utilisateur a demande a sortir.
            }
            revocationMonitor.wipeNow()
            _uiState.value = _uiState.value.copy(signedOut = true)
        }
    }
}
