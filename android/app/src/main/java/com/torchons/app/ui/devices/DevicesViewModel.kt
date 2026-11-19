package com.torchons.app.ui.devices

import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import com.torchons.app.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.torchons.app.core.network.DeviceRevocationMonitor
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.model.DeviceDto
import com.torchons.app.ui.common.UserError
import com.torchons.app.ui.common.UserErrors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DevicesUiState())
    val uiState: StateFlow<DevicesUiState> = _uiState.asStateFlow()

    init {
        refresh()
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
